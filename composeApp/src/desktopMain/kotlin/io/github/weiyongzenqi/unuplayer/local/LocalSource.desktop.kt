package io.github.weiyongzenqi.unuplayer.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.MediaKeys
import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile
import io.github.weiyongzenqi.unuplayer.webdav.isVideoFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Windows 桌面本地媒体来源。
 *
 * 所有来自数据库或 UI 的路径都必须重新校验，不能信任调用方传入的绝对路径。
 * 目录联接、符号链接和其他重解析点不参与浏览或扫描，避免越出媒体库根目录，
 * 也避免递归扫描形成环路。
 */
class DesktopLocalSource(
    rootPath: String,
) : MediaSource {

    override val kind: MediaSourceKind = MediaSourceKind.LOCAL

    private val rootAbsolute: Path = Paths.get(rootPath).toAbsolutePath().normalize()
    private val rootReal: Path? = runCatching { rootAbsolute.toRealPath() }.getOrNull()

    override val displayName: String = rootAbsolute.fileName?.toString().orEmpty()
        .ifBlank { rootAbsolute.toString() }

    override suspend fun listFolder(path: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        listDirectory(path, videosOnly = true)
    }

    override suspend fun resolvePlayMedia(entry: MediaEntry): PlayableMedia =
        withContext(Dispatchers.IO) {
            val mediaPath = safeExistingPath(entry.path)
                ?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                ?: throw IllegalArgumentException("媒体文件不在当前媒体库根目录内: ${entry.path}")
            val normalizedPath = mediaPath.toString()
            PlayableMedia(
                url = normalizedPath,
                title = entry.name,
                sourceKind = MediaSourceKind.LOCAL,
                contentUri = null,
                mediaKey = MediaKeys.local(normalizedPath),
            )
        }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        rootReal != null &&
            Files.isDirectory(rootAbsolute, LinkOption.NOFOLLOW_LINKS) &&
            Files.isReadable(rootAbsolute)
    }

    /** 全量列目录，不过滤 NFO、图片等非视频文件，供海报墙扫描使用。 */
    override suspend fun listFolderAll(path: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        listDirectory(path, videosOnly = false)
    }

    override suspend fun readTextFile(path: String): String? = withContext(Dispatchers.IO) {
        val source = safeExistingPath(path)
            ?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            ?: return@withContext null
        runCatching {
            val size = Files.size(source)
            if (size > MAX_TEXT_FILE_BYTES) return@runCatching null
            val bytes = Files.readAllBytes(source)
            val offset = if (
                bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
            ) 3 else 0
            String(bytes, offset, bytes.size - offset, StandardCharsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun downloadToFile(path: String, dest: PlatformFile): Boolean = withContext(Dispatchers.IO) {
        val source = safeExistingPath(path)
            ?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            ?: return@withContext false
        runCatching {
            val target = File(dest.path)
            target.parentFile?.mkdirs()
            Files.copy(source, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
    }

    /**
     * 删除库内文件或目录。禁止删除库根目录；递归遍历不跟随符号链接或目录联接。
     */
    override suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        val target = safeLexicalPath(path) ?: return@withContext false
        if (target == rootAbsolute || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return@withContext false
        }

        val targetReal = runCatching { target.toRealPath() }.getOrNull() ?: return@withContext false
        val trustedRoot = rootReal ?: return@withContext false
        if (!targetReal.startsWith(trustedRoot) || containsReparsePoint(target)) {
            return@withContext false
        }

        runCatching {
            Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
            true
        }.getOrDefault(false)
    }

    override fun close() = Unit

    private fun listDirectory(path: String, videosOnly: Boolean): List<MediaEntry> {
        val directory = safeExistingPath(path)
            ?.takeIf { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            ?: return emptyList()

        return runCatching {
            Files.newDirectoryStream(directory).use { entries ->
                entries.asSequence()
                    .filterNot(::isReparsePoint)
                    .mapNotNull { child ->
                        val safeChild = safeExistingPath(child.toString()) ?: return@mapNotNull null
                        val isDirectory = Files.isDirectory(safeChild, LinkOption.NOFOLLOW_LINKS)
                        val isRegularFile = Files.isRegularFile(safeChild, LinkOption.NOFOLLOW_LINKS)
                        if (!isDirectory && !isRegularFile) return@mapNotNull null
                        if (videosOnly && !isDirectory && !isVideoFile(child.fileName.toString())) {
                            return@mapNotNull null
                        }
                        child.toEntry(isDirectory, isRegularFile)
                    }
                    .sortedWith(compareByDescending<MediaEntry> { it.isDirectory }.thenBy { it.name })
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun safeExistingPath(path: String): Path? {
        val candidate = safeLexicalPath(path) ?: return null
        if (containsReparsePoint(candidate)) return null
        val candidateReal = runCatching { candidate.toRealPath() }.getOrNull() ?: return null
        val trustedRoot = rootReal ?: return null
        return candidate.takeIf { candidateReal.startsWith(trustedRoot) }
    }

    private fun safeLexicalPath(path: String): Path? {
        val candidate = if (path.isBlank()) {
            rootAbsolute
        } else {
            runCatching { Paths.get(path) }.getOrNull()?.let {
                if (it.isAbsolute) it else rootAbsolute.resolve(it)
            }?.toAbsolutePath()?.normalize()
        } ?: return null
        return candidate.takeIf { it.startsWith(rootAbsolute) }
    }

    private fun isReparsePoint(path: Path): Boolean {
        if (Files.isSymbolicLink(path)) return true
        return runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isOther
        }.getOrDefault(true)
    }

    /** 检查根目录以下的每一段，防止通过“链接目录/子文件”形式绕过末段检查。 */
    private fun containsReparsePoint(path: Path): Boolean {
        val relative = runCatching { rootAbsolute.relativize(path) }.getOrNull() ?: return true
        var current = rootAbsolute
        for (segment in relative) {
            current = current.resolve(segment)
            if (isReparsePoint(current)) return true
        }
        return false
    }

    private fun Path.toEntry(isDirectory: Boolean, isRegularFile: Boolean) = MediaEntry(
        name = fileName?.toString().orEmpty(),
        path = toAbsolutePath().normalize().toString(),
        isDirectory = isDirectory,
        size = if (isRegularFile) runCatching { Files.size(this) }.getOrDefault(0L) else 0L,
        lastModified = runCatching { Files.getLastModifiedTime(this).toMillis() }.getOrDefault(0L),
    )

    private companion object {
        const val MAX_TEXT_FILE_BYTES: Long = 8L * 1024L * 1024L
    }
}
