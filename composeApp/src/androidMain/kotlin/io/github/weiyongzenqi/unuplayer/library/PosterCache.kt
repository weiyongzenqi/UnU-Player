package io.github.weiyongzenqi.unuplayer.library

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Android 海报/缩略图缓存：按目标 single-flight、临时文件原子发布、近似 LRU 容量淘汰。 */
class PosterCache internal constructor(
    private val cacheDir: File,
    private val trimIntervalMillis: Long = TRIM_INTERVAL_MILLIS,
) {
    private data class KeyLock(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val rootFile: File by lazy {
        cacheDir.absoluteFile.apply { mkdirs() }.canonicalFile.also { root ->
            // 清理旧版直接放在 postercache 根目录中的 md5 flat 文件。
            runCatching { root.listFiles()?.filter { it.isFile }?.forEach { it.delete() } }
        }
    }
    private val rootPath: Path by lazy { rootFile.toPath().normalize() }
    private val stateMutex = Mutex()
    private val keyLocks = mutableMapOf<String, KeyLock>()
    private val maintenanceMutex = Mutex()
    private var globalGeneration = 0L
    private val showGenerations = mutableMapOf<String, Long>()
    private var lastTrimAt = 0L
    private var orphanCleanupDone = false

    @Volatile
    private var maxSizeLimit = DEFAULT_MAX_SIZE_BYTES

    suspend fun get(
        showKey: String,
        imageBasename: String,
        sourceIdentity: String,
        maxSizeBytes: Long,
        downloader: suspend (File) -> Boolean,
    ): File? = withContext(Dispatchers.IO) {
        applyMaxSize(maxSizeBytes)
        val showSegment = safeSegment(showKey)
        val target = targetPath(showSegment, imageBasename, sourceIdentity)
        val key = target.toString()
        val keyLock = acquireKeyLock(key)
        try {
            keyLock.mutex.withLock {
                val now = System.currentTimeMillis()
                val slot = maintenanceMutex.withLock {
                    cleanupOrphanPartsLocked()
                    ensureSafeShowDirectory(target.parent)
                    if (isCompleteFile(target)) {
                        touchIfStale(target, now)
                        return@withContext target.toFile()
                    }
                    DownloadSlot(
                        part = Files.createTempFile(target.parent, ".${target.fileName}.", ".part"),
                        globalGeneration = globalGeneration,
                        showGeneration = showGenerations[showSegment] ?: 0L,
                    )
                }

                try {
                    val downloaded = downloader(slot.part.toFile())
                    if (!downloaded || !isCompleteFile(slot.part)) return@withLock null

                    val published = maintenanceMutex.withLock {
                        val generationMatches = globalGeneration == slot.globalGeneration &&
                            (showGenerations[showSegment] ?: 0L) == slot.showGeneration
                        if (!generationMatches) return@withLock false
                        ensureSafeShowDirectory(target.parent)
                        moveAtomically(slot.part, target)
                        true
                    }
                    if (!published) return@withLock null

                    trimIfNeeded(force = false)
                    target.toFile().takeIf { isCompleteFile(target) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                } finally {
                    runCatching { Files.deleteIfExists(slot.part) }
                }
            }
        } finally {
            releaseKeyLock(key, keyLock)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        maintenanceMutex.withLock {
            globalGeneration++
            cleanupOrphanPartsLocked()
            deleteTreeLocked(rootPath, keepRoot = true)
            showGenerations.clear()
            lastTrimAt = 0L
        }
    }

    suspend fun clearShow(showKey: String) = withContext(Dispatchers.IO) {
        val showSegment = safeSegment(showKey)
        maintenanceMutex.withLock {
            showGenerations[showSegment] = (showGenerations[showSegment] ?: 0L) + 1L
            cleanupOrphanPartsLocked()
            deleteTreeLocked(safeShowDirectory(showSegment), keepRoot = false)
            // CR-071: 磁盘目录已删后回收 entry; remove 后 showGenerations[seg]?:0L=0,
            // 在途下载(slot.showGeneration=旧非0值)仍被 0!=旧值 拒绝, 不破坏 generation 防发布语义。
            // deleteTree 抛异常时 remove 不执行, 自增值保留兜底。
            showGenerations.remove(showSegment)
        }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        maintenanceMutex.withLock {
            cleanupOrphanPartsLocked()
            cacheFilesLocked().sumOf { path -> fileSize(path) }
        }
    }

    internal suspend fun updateMaxSizeBytes(maxSizeBytes: Long) = withContext(Dispatchers.IO) {
        applyMaxSize(maxSizeBytes)
    }

    private data class DownloadSlot(
        val part: Path,
        val globalGeneration: Long,
        val showGeneration: Long,
    )

    private suspend fun acquireKeyLock(key: String): KeyLock = stateMutex.withLock {
        keyLocks.getOrPut(key) { KeyLock() }.also { it.users++ }
    }

    private suspend fun releaseKeyLock(key: String, lock: KeyLock) = stateMutex.withLock {
        lock.users--
        if (lock.users == 0 && keyLocks[key] === lock) keyLocks.remove(key)
    }

    private suspend fun applyMaxSize(maxSizeBytes: Long) {
        if (maxSizeBytes <= 0L) return
        val lowered = maintenanceMutex.withLock {
            cleanupOrphanPartsLocked()
            val wasLowered = maxSizeBytes < maxSizeLimit
            maxSizeLimit = maxSizeBytes
            wasLowered
        }
        if (lowered) trimIfNeeded(force = true)
    }

    private suspend fun trimIfNeeded(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && trimIntervalMillis > 0L && now - lastTrimAt < trimIntervalMillis) return
        maintenanceMutex.withLock {
            cleanupOrphanPartsLocked()
            if (!force && trimIntervalMillis > 0L && now - lastTrimAt < trimIntervalMillis) return@withLock
            trimLocked()
            lastTrimAt = now
        }
    }

    private fun trimLocked() {
        val files = cacheFilesLocked().sortedBy { path ->
            runCatching {
                Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
            }.getOrDefault(0L)
        }
        var total = files.sumOf { path -> fileSize(path) }
        val limit = maxSizeLimit.coerceAtLeast(1L)
        if (total > limit) {
            val targetSize = (limit * 9L / 10L).coerceAtLeast(0L)
            for (file in files) {
                if (total <= targetSize) break
                val size = fileSize(file)
                if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) total -= size
            }
        }
        deleteEmptyDirectoriesLocked()
    }

    private fun cleanupOrphanPartsLocked() {
        if (orphanCleanupDone) return
        orphanCleanupDone = true
        runCatching {
            Files.walk(rootPath).use { paths ->
                paths.filter { candidate ->
                    isWithinRoot(candidate) &&
                        candidate != rootPath &&
                        candidate.fileName.toString().endsWith(".part") &&
                        Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                }.forEach { candidate ->
                    runCatching { Files.deleteIfExists(candidate) }
                }
            }
        }
    }

    private fun cacheFilesLocked(): List<Path> = runCatching {
        val files = mutableListOf<Path>()
        Files.walk(rootPath).use { paths ->
            paths.filter { candidate ->
                isWithinRoot(candidate) &&
                    Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) &&
                    !candidate.fileName.toString().endsWith(".part")
            }.forEach { candidate -> files.add(candidate) }
        }
        files
    }.getOrDefault(emptyList())

    private fun deleteTreeLocked(path: Path, keepRoot: Boolean) {
        val normalized = path.toAbsolutePath().normalize()
        if (!isWithinRoot(normalized) || (normalized == rootPath && !keepRoot)) return
        runCatching {
            Files.walk(normalized).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { candidate ->
                    if ((!keepRoot || candidate != normalized) && isWithinRoot(candidate)) {
                        runCatching { Files.deleteIfExists(candidate) }
                    }
                }
            }
        }
    }

    private fun deleteEmptyDirectoriesLocked() {
        runCatching {
            Files.walk(rootPath).use { paths ->
                paths.filter { candidate ->
                    candidate != rootPath &&
                        isWithinRoot(candidate) &&
                        Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                }.sorted(Comparator.reverseOrder()).forEach { directory ->
                    runCatching { Files.deleteIfExists(directory) }
                }
            }
        }
    }

    private fun targetPath(showSegment: String, imageBasename: String, sourceIdentity: String): Path {
        val showDir = safeShowDirectory(showSegment)
        val rawName = imageBasename.substringAfterLast('/').substringAfterLast('\\')
        val cleanedName = safeSegment(rawName)
        val dot = cleanedName.lastIndexOf('.')
        val rawExtension = if (dot in 1 until cleanedName.lastIndex) cleanedName.substring(dot + 1) else ""
        val extension = rawExtension.lowercase()
            .takeIf { it != "part" && it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: "img"
        val rawStem = if (dot > 0) cleanedName.substring(0, dot) else cleanedName
        val stem = rawStem.trimEnd(' ', '.').ifBlank { "image" }.take(80)
        val target = showDir.resolve("$stem-${sha256(sourceIdentity).take(12)}.$extension").normalize()
        require(isWithinRoot(target) && target != rootPath) { "缓存目标越界" }
        return target
    }

    private fun safeShowDirectory(showSegment: String): Path {
        val path = rootPath.resolve(showSegment).normalize()
        require(isWithinRoot(path) && path != rootPath) { "缓存目录越界" }
        return path
    }

    private fun ensureSafeShowDirectory(path: Path) {
        require(isWithinRoot(path) && path.parent == rootPath) { "缓存目录越界" }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                "缓存目录不是安全目录"
            }
        } else {
            Files.createDirectory(path)
        }
        val canonical = path.toFile().canonicalFile.toPath().normalize()
        require(canonical.startsWith(rootPath) && canonical != rootPath) { "缓存目录越界" }
    }

    private fun safeSegment(raw: String): String {
        val withoutControls = raw.replace(Regex("[\\x00-\\x1F\\x7F]"), "_")
        val sanitized = sanitizeFileName(withoutControls).trimEnd(' ', '.')
        val base = sanitized.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "unknown"
        val needsHash = base != raw || base.length > 112
        return if (needsHash) "${base.take(96)}-${sha256(raw).take(12)}" else base
    }

    private fun isCompleteFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && fileSize(path) > 0L

    private fun fileSize(path: Path): Long =
        runCatching { Files.size(path) }.getOrDefault(0L)

    private fun touchIfStale(path: Path, now: Long) {
        val file = path.toFile()
        if (now - file.lastModified() >= TOUCH_INTERVAL_MILLIS) file.setLastModified(now)
    }

    private fun moveAtomically(part: Path, target: Path) {
        try {
            Files.move(
                part,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isWithinRoot(path: Path): Boolean =
        path.toAbsolutePath().normalize().startsWith(rootPath)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val MIB = 1024L * 1024L
        private const val DEFAULT_MAX_SIZE_BYTES = 200L * MIB
        private const val TOUCH_INTERVAL_MILLIS = 60L * 60L * 1000L
        private const val TRIM_INTERVAL_MILLIS = 30_000L

        @Volatile
        private var instance: PosterCache? = null

        fun get(context: Context): PosterCache = instance ?: synchronized(this) {
            instance ?: PosterCache(
                File(
                    context.applicationContext.getExternalFilesDir(null)
                        ?: context.applicationContext.filesDir,
                    "postercache",
                ),
            ).also { instance = it }
        }
    }
}
