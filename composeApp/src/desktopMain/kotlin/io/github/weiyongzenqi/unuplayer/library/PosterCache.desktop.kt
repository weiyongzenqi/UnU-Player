package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.util.Crypto
import io.github.weiyongzenqi.unuplayer.platform.DesktopAppDirectories
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Windows 海报/缩略图缓存：按目标 single-flight、临时文件原子发布、近似 LRU 容量淘汰。 */
class PosterCache internal constructor(
    cacheDir: File,
    private val trimIntervalMillis: Long = 30_000L,
    private val createTempFile: (Path, String, String) -> Path = { parent, prefix, suffix ->
        Files.createTempFile(parent, prefix, suffix)
    },
) {
    private data class KeyLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private class ShowGenerationState(var generation: Long = 0L, var activeSlots: Int = 0)
    internal data class ImportedFile(val file: File, val created: Boolean)

    private val rootFile = cacheDir.absoluteFile.apply { mkdirs() }.canonicalFile
    private val rootPath: Path = rootFile.toPath().normalize()
    private val stateMutex = Mutex()
    private val keyLocks = mutableMapOf<String, KeyLock>()
    private val maintenanceMutex = Mutex()
    private var globalGeneration = 0L
    private val showGenerations = mutableMapOf<String, ShowGenerationState>()
    private var lastTrimAt = 0L
    private var orphanCleanupDone = false

    @Volatile
    private var maxSizeLimit = DEFAULT_MAX_SIZE_BYTES

    suspend fun get(
        showKey: String,
        imageBasename: String,
        sourceIdentity: String,
        maxSizeBytes: Long,
        maxFileBytes: Long = MAX_POSTER_IMAGE_BYTES,
        downloader: suspend (File) -> Boolean,
    ): File? = withContext(Dispatchers.IO) {
        require(maxFileBytes > 0L) { "单文件缓存上限必须为正数" }
        applyMaxSize(maxSizeBytes)
        val showSegment = safeSegment(showKey)
        val target = targetPath(showSegment, imageBasename, sourceIdentity)
        val key = target.toString()
        val keyLock = acquireKeyLock(key)
        try {
            keyLock.mutex.withLock keyLock@ {
                val now = System.currentTimeMillis()
                val slot = maintenanceMutex.withLock maintenance@ {
                    cleanupOrphanPartsLocked()
                    if (isCompleteFile(target, maxFileBytes)) {
                        touchIfStale(target, now)
                        return@withContext target.toFile()
                    }
                    val part = try {
                        Files.createDirectories(target.parent)
                        createTempFile(target.parent, ".${target.fileName}.", ".part")
                    } catch (_: Exception) {
                        return@maintenance null
                    }
                    try {
                        val showState = showGenerations.getOrPut(showSegment) { ShowGenerationState() }
                        showState.activeSlots++
                        DownloadSlot(
                            part = part,
                            globalGeneration = globalGeneration,
                            showState = showState,
                            showGeneration = showState.generation,
                        )
                    } catch (error: Throwable) {
                        runCatching { Files.deleteIfExists(part) }
                        throw error
                    }
                } ?: return@keyLock null

                try {
                    val downloaded = downloader(slot.part.toFile())
                    if (!downloaded || !isCompleteFile(slot.part, maxFileBytes)) return@keyLock null

                    val published = maintenanceMutex.withLock maintenance@ {
                        val generationMatches = globalGeneration == slot.globalGeneration &&
                            showGenerationMatchesLocked(showSegment, slot.showState, slot.showGeneration)
                        if (!generationMatches) return@maintenance false
                        moveAtomically(slot.part, target)
                        true
                    }
                    if (!published) return@keyLock null

                    trimIfNeeded(force = false)
                    target.toFile().takeIf { isCompleteFile(target, maxFileBytes) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                } finally {
                    runCatching { Files.deleteIfExists(slot.part) }
                    releaseShowGeneration(showSegment, slot.showState)
                }
            }
        } finally {
            releaseKeyLock(key, keyLock)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        maintenanceMutex.withLock {
            cleanupOrphanPartsLocked()
            globalGeneration++
            rootFile.listFiles()?.forEach { it.deleteRecursively() }
            showGenerations.clear()
            lastTrimAt = 0L
        }
    }

    suspend fun clearShow(showKey: String) = withContext(Dispatchers.IO) {
        val showSegment = safeSegment(showKey)
        maintenanceMutex.withLock {
            cleanupOrphanPartsLocked()
            val showState = showGenerations.getOrPut(showSegment) { ShowGenerationState() }
            showState.generation++
            safeShowDirectory(showSegment).toFile().deleteRecursively()
            // 有在途 slot 时保留 state，避免清理后重建的默认 generation 重新接纳旧任务。
            if (showState.activeSlots == 0 && showGenerations[showSegment] === showState) {
                showGenerations.remove(showSegment)
            }
        }
    }

    /**
     * 集照本地生成文件路径: <rootFile>/<showKey>/ep<episodeId>.jpg。
     * 目录自动创建; 文件在 showKey 目录内, [clearShow] 自动删除。
     * (Windows 集照生成后续实现, 接口预留。)
     */
    fun episodeThumbFile(showKey: String, episodeId: Long): File {
        val showSegment = safeSegment(showKey)
        val dir = safeShowDirectory(showSegment)
        Files.createDirectories(dir)
        return dir.resolve("ep$episodeId.jpg").toFile()
    }

    /** 导入在线图片：复用完整既有目标；新文件经同目录 part 原子发布；发布前回调仅用于并发测试。 */
    internal suspend fun importShowImage(
        showKey: String,
        imageBasename: String,
        sourceIdentity: String,
        bytes: ByteArray,
        beforePublishForTest: suspend () -> Unit = {},
    ): ImportedFile? {
        val showSegment = safeSegment(showKey)
        val target = targetPath(showSegment, imageBasename, sourceIdentity)
        return importFile(
            showSegment,
            target,
            replaceExisting = false,
            bytes = bytes,
            beforePublishForTest = beforePublishForTest,
        )
    }

    /** 导入本地集照：始终以 part 原子替换；写入或发布失败时保留既有目标。 */
    internal suspend fun importEpisodeThumb(
        showKey: String,
        episodeId: Long,
        bytes: ByteArray,
    ): ImportedFile? {
        val showSegment = safeSegment(showKey)
        val target = safeShowDirectory(showSegment).resolve("ep$episodeId.jpg").normalize()
        require(target.startsWith(rootPath) && target != rootPath) { "缓存目标越界" }
        return importFile(
            showSegment,
            target,
            replaceExisting = true,
            bytes = bytes,
            beforePublishForTest = {},
        )
    }

    /** 列某 showKey 子目录下的文件(媒体库导出图片收集用; 目录不存在返回空)。 */
    internal suspend fun listShowFiles(showKey: String): List<File> = withContext(Dispatchers.IO) {
        runCatching {
            safeShowDirectory(safeSegment(showKey)).toFile().listFiles()?.filter { it.isFile }.orEmpty()
        }.getOrDefault(emptyList())
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        maintenanceMutex.withLock {
            cleanupOrphanPartsLocked()
            cacheFilesLocked().sumOf { it.length() }
        }
    }

    internal suspend fun updateMaxSizeBytes(maxSizeBytes: Long) = withContext(Dispatchers.IO) {
        applyMaxSize(maxSizeBytes)
    }

    /** 导入结束后的强制容量整理；只消费当前配置，不改变 [maxSizeLimit]。 */
    internal suspend fun trimToCurrentLimit() = withContext(Dispatchers.IO) {
        trimIfNeeded(force = true)
    }

    internal suspend fun generationStateCountForTest(): Int = maintenanceMutex.withLock { showGenerations.size }

    private data class DownloadSlot(
        val part: Path,
        val globalGeneration: Long,
        val showState: ShowGenerationState,
        val showGeneration: Long,
    )

    private data class ImportSlot(
        val part: Path,
        val globalGeneration: Long,
        val showState: ShowGenerationState,
        val showGeneration: Long,
        val targetExisted: Boolean,
    )

    private suspend fun importFile(
        showSegment: String,
        target: Path,
        replaceExisting: Boolean,
        bytes: ByteArray,
        beforePublishForTest: suspend () -> Unit,
    ): ImportedFile? = withContext(Dispatchers.IO) {
        val key = target.toString()
        val keyLock = acquireKeyLock(key)
        try {
            keyLock.mutex.withLock {
                val (existing, slot) = maintenanceMutex.withLock {
                    cleanupOrphanPartsLocked()
                    ensureSafeShowDirectory(target.parent)
                    val targetExisted = isCompleteFile(target)
                    if (targetExisted && !replaceExisting) {
                        target.toFile() to null
                    } else {
                        val part = createTempFile(target.parent, ".${target.fileName}.", ".part")
                        try {
                            val showState = showGenerations.getOrPut(showSegment) { ShowGenerationState() }
                            showState.activeSlots++
                            null to ImportSlot(
                                part = part,
                                globalGeneration = globalGeneration,
                                showState = showState,
                                showGeneration = showState.generation,
                                targetExisted = targetExisted,
                            )
                        } catch (error: Throwable) {
                            runCatching { Files.deleteIfExists(part) }
                            throw error
                        }
                    }
                }
                if (existing != null) return@withLock ImportedFile(existing, created = false)
                val writeSlot = requireNotNull(slot)
                try {
                    Files.write(writeSlot.part, bytes)
                    if (!isCompleteFile(writeSlot.part)) return@withLock null

                    beforePublishForTest()
                    val published = maintenanceMutex.withLock {
                        val generationMatches = globalGeneration == writeSlot.globalGeneration &&
                            showGenerationMatchesLocked(
                                showSegment,
                                writeSlot.showState,
                                writeSlot.showGeneration,
                            )
                        if (!generationMatches) return@withLock false
                        ensureSafeShowDirectory(target.parent)
                        moveAtomicallyStrict(writeSlot.part, target)
                        true
                    }
                    if (!published) return@withLock null
                    ImportedFile(target.toFile(), created = !writeSlot.targetExisted)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                } finally {
                    runCatching { Files.deleteIfExists(writeSlot.part) }
                    releaseShowGeneration(showSegment, writeSlot.showState)
                }
            }
        } finally {
            releaseKeyLock(key, keyLock)
        }
    }

    private suspend fun acquireKeyLock(key: String): KeyLock = stateMutex.withLock {
        keyLocks.getOrPut(key) { KeyLock() }.also { it.users++ }
    }

    private suspend fun releaseKeyLock(key: String, lock: KeyLock) = stateMutex.withLock {
        lock.users--
        if (lock.users == 0 && keyLocks[key] === lock) keyLocks.remove(key)
    }

    private fun showGenerationMatchesLocked(
        showSegment: String,
        showState: ShowGenerationState,
        showGeneration: Long,
    ): Boolean {
        val current = showGenerations[showSegment]
        return current === showState && current.generation == showGeneration
    }

    private suspend fun releaseShowGeneration(showSegment: String, showState: ShowGenerationState) {
        withContext(NonCancellable) {
            maintenanceMutex.withLock {
                if (showState.activeSlots > 0) showState.activeSlots--
                if (showState.activeSlots == 0 && showGenerations[showSegment] === showState) {
                    showGenerations.remove(showSegment)
                }
            }
        }
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
        val files = cacheFilesLocked().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        val limit = maxSizeLimit.coerceAtLeast(1L)
        if (total > limit) {
            val target = (limit * 9L / 10L).coerceAtLeast(0L)
            for (file in files) {
                if (total <= target) break
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
        rootFile.walkBottomUp()
            .filter { it != rootFile && it.isDirectory && it.list()?.isEmpty() == true }
            .forEach { it.delete() }
    }

    private fun cleanupOrphanPartsLocked() {
        if (orphanCleanupDone) return
        orphanCleanupDone = true
        runCatching {
            Files.walk(rootPath).use { paths ->
                paths.filter { candidate ->
                    candidate != rootPath &&
                        candidate.toAbsolutePath().normalize().startsWith(rootPath) &&
                        candidate.fileName.toString().endsWith(".part") &&
                        Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                }.forEach { candidate ->
                    runCatching { Files.deleteIfExists(candidate) }
                }
            }
        }
    }

    private fun cacheFilesLocked(): List<File> =
        rootFile.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".part") }
            .toList()

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
        require(target.startsWith(rootPath) && target != rootPath) { "缓存目标越界" }
        return target
    }

    private fun safeShowDirectory(showSegment: String): Path {
        val path = rootPath.resolve(showSegment).normalize()
        require(path.startsWith(rootPath) && path != rootPath) { "缓存目录越界" }
        return path
    }

    private fun ensureSafeShowDirectory(path: Path) {
        require(path.startsWith(rootPath) && path.parent == rootPath) { "缓存目录越界" }
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

    private fun isCompleteFile(path: Path, maxBytes: Long = Long.MAX_VALUE): Boolean =
        Files.isRegularFile(path) && runCatching { Files.size(path) in 1L..maxBytes }.getOrDefault(false)

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

    private fun moveAtomicallyStrict(part: Path, target: Path) {
        Files.move(
            part,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun sha256(value: String): String = Crypto.sha256Hex(value)

    companion object {
        private const val MIB = 1024L * 1024L
        private const val DEFAULT_MAX_SIZE_BYTES = 200L * MIB
        private const val TOUCH_INTERVAL_MILLIS = 60L * 60L * 1000L

        @Volatile
        private var instance: PosterCache? = null

        fun get(): PosterCache = instance ?: synchronized(this) {
            instance ?: PosterCache(
                DesktopAppDirectories.posterCacheDirectory.toFile(),
            ).also { instance = it }
        }
    }
}
