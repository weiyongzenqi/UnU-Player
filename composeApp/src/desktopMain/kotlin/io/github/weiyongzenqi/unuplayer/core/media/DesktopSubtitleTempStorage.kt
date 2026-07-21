package io.github.weiyongzenqi.unuplayer.core.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.platform.DesktopAppDirectories
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal const val DESKTOP_SUBTITLE_SESSION_PREFIX = "session-"
internal const val DESKTOP_SUBTITLE_STALE_MILLIS: Long = 24L * 60L * 60L * 1000L

internal fun defaultDesktopSubtitleTempRoot(): Path = DesktopAppDirectories.subtitleTempDirectory

/**
 * 管理 Windows 远程字幕下载留下的过期 session。
 *
 * 只接受临时根目录的直接子目录 `session-*`，且整个目录树不能包含符号链接或重解析点。
 * 扫描和删除由本类强制调度到 IO 线程，调用方不能误在 UI 线程递归遍历。
 */
internal class DesktopSubtitleTempStorage(
    root: Path = defaultDesktopSubtitleTempRoot(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val rootPath = root.toAbsolutePath().normalize()

    internal fun markActiveSession(session: Path) {
        val normalized = session.toAbsolutePath().normalize()
        if (normalized.parent == rootPath && normalized.startsWith(rootPath)) {
            synchronized(activeSessionLock) { activeSessions.add(normalized) }
        }
    }

    internal fun markInactiveSession(session: Path) {
        val normalized = session.toAbsolutePath().normalize()
        synchronized(activeSessionLock) { activeSessions.remove(normalized) }
    }

    /** 仅统计超过 24 小时、可安全清理的会话。正在使用或结构可疑的会话不计入。 */
    suspend fun staleSizeBytes(): Long = withContext(Dispatchers.IO) {
        staleSessions().sumOf { session -> safeTreeSize(session) ?: 0L }
    }

    /** 删除可安全确认的过期会话，返回实际成功删除的字节数。 */
    suspend fun clearStaleSessions(): Long = withContext(Dispatchers.IO) {
        staleSessions().sumOf { session ->
            val size = safeTreeSize(session) ?: return@sumOf 0L
            if (deleteSafeTree(session)) size else 0L
        }
    }

    internal fun isOwnedStaleSession(candidate: Path): Boolean {
        val normalized = candidate.toAbsolutePath().normalize()
        if (normalized.parent != rootPath || !normalized.startsWith(rootPath)) return false
        if (!normalized.fileName.toString().startsWith(DESKTOP_SUBTITLE_SESSION_PREFIX)) return false
        if (synchronized(activeSessionLock) { normalized in activeSessions }) return false
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) return false
        if (isLinkOrReparse(normalized)) return false

        val rootReal = runCatching { rootPath.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: return false
        val candidateReal = runCatching { normalized.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            ?: return false
        if (candidateReal.parent != rootReal || !candidateReal.startsWith(rootReal)) return false

        val modifiedAt = runCatching {
            Files.getLastModifiedTime(normalized, LinkOption.NOFOLLOW_LINKS).toMillis()
        }.getOrNull() ?: return false
        return modifiedAt < nowMillis() - DESKTOP_SUBTITLE_STALE_MILLIS
    }

    private fun staleSessions(): List<Path> {
        if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS) || isLinkOrReparse(rootPath)) {
            return emptyList()
        }
        return runCatching {
            Files.newDirectoryStream(rootPath, "$DESKTOP_SUBTITLE_SESSION_PREFIX*").use { entries ->
                entries.asSequence()
                    .map { it.toAbsolutePath().normalize() }
                    .filter(::isOwnedStaleSession)
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    /** 返回 null 表示目录树包含链接、重解析点、越界路径或读取失败。 */
    private fun safeTreeSize(session: Path): Long? {
        var total = 0L
        return try {
            Files.walkFileTree(session, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    ensureSafePath(session, dir, attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    ensureSafePath(session, file, attrs)
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    throw exc
                }
            })
            total
        } catch (_: Throwable) {
            null
        }
    }

    private fun deleteSafeTree(session: Path): Boolean {
        if (!isOwnedStaleSession(session)) return false
        return runCatching {
            Files.walkFileTree(session, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    ensureSafePath(session, dir, attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    ensureSafePath(session, file, attrs)
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    throw exc
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    ensureContained(session, dir)
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
            !Files.exists(session, LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false)
    }

    private fun ensureSafePath(session: Path, path: Path, attrs: BasicFileAttributes) {
        ensureContained(session, path)
        if (attrs.isSymbolicLink || attrs.isOther || Files.isSymbolicLink(path)) {
            throw UnsafeSubtitleTempTree()
        }
    }

    private fun ensureContained(session: Path, path: Path) {
        val normalizedSession = session.toAbsolutePath().normalize()
        val normalized = path.toAbsolutePath().normalize()
        if (
            normalizedSession.parent != rootPath ||
            !normalizedSession.startsWith(rootPath) ||
            !normalized.startsWith(normalizedSession)
        ) {
            throw UnsafeSubtitleTempTree()
        }
    }

    private fun isLinkOrReparse(path: Path): Boolean {
        if (Files.isSymbolicLink(path)) return true
        return runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isOther
        }.getOrDefault(true)
    }

    private class UnsafeSubtitleTempTree : IOException("字幕临时目录包含不安全路径")

    private companion object {
        val activeSessionLock = Any()
        val activeSessions = mutableSetOf<Path>()
    }
}
