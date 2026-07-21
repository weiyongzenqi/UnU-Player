package io.github.weiyongzenqi.unuplayer.core.media

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.webdav.WebDavClient
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.createHttpClient
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Windows 同目录字幕加载器。
 *
 * 本地媒体直接返回同目录原文件；WebDAV 通过 mediaKey 重建客户端并把选中的字幕下载到
 * 系统临时目录中的独立 session。共享 HttpClient 由外部管理，本类绝不关闭它。
 */
class DesktopSiblingSubtitleLoader(
    private val webDavRepository: WebDavConnectionRepository,
    private val httpClientProvider: () -> HttpClient = ::createHttpClient,
    private val tempRoot: Path = defaultDesktopSubtitleTempRoot(),
) : AutoCloseable {

    /** 候选只能由 loader 构造，调用方仅能读取展示名、源路径和来源类型。 */
    class Candidate internal constructor(
        val displayName: String,
        val sourcePath: String,
        val isRemote: Boolean,
        val size: Long,
        internal val fetch: suspend (File) -> Boolean,
    )

    private val lifecycleLock = Any()
    private val tempStorage = DesktopSubtitleTempStorage(tempRoot)
    private var sessionDirectory: Path? = null
    private var staleCleanupAttempted = false
    private var closed = false

    /** 列严格同名及中文语言段候选，排序规则由 commonMain 匹配器统一。 */
    suspend fun listCandidates(media: PlayableMedia, preference: String): List<Candidate> =
        withContext(Dispatchers.IO) {
            val entries = listEntries(media) ?: return@withContext emptyList()
            SiblingSubtitleMatcher.automaticCandidates(entries.entries, media.title, preference)
                .map(entries.toCandidate)
        }

    /** 列同目录全部字幕，不限是否与视频同名。 */
    suspend fun listAllSubtitles(media: PlayableMedia): List<Candidate> = withContext(Dispatchers.IO) {
        val entries = listEntries(media) ?: return@withContext emptyList()
        SiblingSubtitleMatcher.allSubtitles(entries.entries).map(entries.toCandidate)
    }

    /**
     * 把候选变为 mpv 可直接读取的本地文件。
     * 本地候选重新校验后返回原文件；远程候选限量下载到 session 临时目录。
     */
    suspend fun materialize(candidate: Candidate): File? = withContext(Dispatchers.IO) {
        if (!candidate.isRemote) return@withContext validateLocalCandidate(candidate.sourcePath)?.toFile()

        val extension = candidate.displayName.substringAfterLast('.', "").lowercase()
        if (extension !in SiblingSubtitleMatcher.extensions) return@withContext null
        if (candidate.size > MAX_SUBTITLE_BYTES) return@withContext null
        val session = ensureSessionDirectory() ?: return@withContext null
        val finalPath = runCatching { Files.createTempFile(session, "sub_", ".$extension") }
            .getOrNull() ?: return@withContext null
        runCatching { Files.deleteIfExists(finalPath) }
        val partPath = finalPath.resolveSibling("${finalPath.fileName}.part")

        try {
            val downloaded = candidate.fetch(partPath.toFile())
            if (!downloaded || !Files.isRegularFile(partPath, LinkOption.NOFOLLOW_LINKS)) {
                deletePartial(partPath, finalPath)
                return@withContext null
            }
            val size = Files.size(partPath)
            if (size > MAX_SUBTITLE_BYTES) {
                deletePartial(partPath, finalPath)
                return@withContext null
            }
            try {
                Files.move(
                    partPath,
                    finalPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(partPath, finalPath, StandardCopyOption.REPLACE_EXISTING)
            }
            finalPath.toFile()
        } catch (cancelled: CancellationException) {
            deletePartial(partPath, finalPath)
            throw cancelled
        } catch (_: Throwable) {
            deletePartial(partPath, finalPath)
            null
        }
    }

    /** 幂等删除本 loader 创建的 session；从未下载远程字幕时不会创建目录。 */
    override fun close() {
        val session = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            sessionDirectory.also { sessionDirectory = null }
        } ?: return
        tempStorage.markInactiveSession(session)
        runCatching {
            val normalizedRoot = tempRoot.toAbsolutePath().normalize()
            val normalizedSession = session.toAbsolutePath().normalize()
            if (normalizedSession.parent == normalizedRoot &&
                normalizedSession.fileName.toString().startsWith(DESKTOP_SUBTITLE_SESSION_PREFIX)) {
                deleteTree(normalizedSession)
            }
        }
    }

    private suspend fun listEntries(media: PlayableMedia): ListedEntries? = when (media.sourceKind) {
        MediaSourceKind.LOCAL -> listLocalEntries(media)
        MediaSourceKind.WEBDAV -> listWebDavEntries(media)
        else -> null
    }

    private fun listLocalEntries(media: PlayableMedia): ListedEntries? {
        val videoPath = parseLocalPath(media.url) ?: return null
        val video = validateLocalCandidate(videoPath.toString()) ?: return null
        val parent = video.parent ?: return null
        val entries = runCatching {
            Files.newDirectoryStream(parent).use { stream ->
                stream.asSequence()
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !isLinkOrReparse(it) }
                    .mapNotNull { path ->
                        val real = validateLocalCandidate(path.toString()) ?: return@mapNotNull null
                        MediaEntry(
                            name = real.fileName?.toString().orEmpty(),
                            path = real.toString(),
                            isDirectory = false,
                            size = runCatching { Files.size(real) }.getOrDefault(0L),
                            lastModified = runCatching { Files.getLastModifiedTime(real).toMillis() }.getOrDefault(0L),
                        )
                    }
                    .toList()
            }
        }.getOrDefault(emptyList())
        return ListedEntries(entries) { entry ->
            Candidate(entry.name, entry.path, false, entry.size) { false }
        }
    }

    private suspend fun listWebDavEntries(media: PlayableMedia): ListedEntries? {
        val mediaKey = media.mediaKey?.takeIf { it.startsWith("webdav:") } ?: return null
        val rest = mediaKey.removePrefix("webdav:")
        val separator = rest.indexOf(':')
        if (separator <= 0 || separator == rest.lastIndex) return null
        val connectionId = rest.substring(0, separator)
        val videoPath = rest.substring(separator + 1)
        val connections = try {
            webDavRepository.loadAll()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emptyList()
        }
        val connection = connections.firstOrNull { it.id == connectionId } ?: return null
        val client = WebDavClient(
            httpClientProvider(),
            connection.baseUrl,
            connection.username,
            connection.password,
        )
        val parentPath = videoPath.substringBeforeLast('/').ifEmpty { "/" }
        val entries = try {
            client.listDirectoryAll(parentPath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emptyList()
        }
        return ListedEntries(entries) { entry ->
            Candidate(entry.name, entry.path, true, entry.size) { destination ->
                client.downloadTo(entry.path, PlatformFile(destination.path), MAX_SUBTITLE_BYTES)
            }
        }
    }

    private fun parseLocalPath(url: String): Path? = runCatching {
        if (url.startsWith("file:", ignoreCase = true)) Paths.get(URI(url)) else Paths.get(url)
    }.getOrNull()?.toAbsolutePath()?.normalize()

    private fun validateLocalCandidate(path: String): Path? {
        val candidate = runCatching { Paths.get(path).toAbsolutePath().normalize() }.getOrNull() ?: return null
        if (isLinkOrReparse(candidate)) return null
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) return null
        return runCatching { candidate.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull()
    }

    private fun isLinkOrReparse(path: Path): Boolean {
        if (Files.isSymbolicLink(path)) return true
        return runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isOther
        }.getOrDefault(true)
    }

    private suspend fun ensureSessionDirectory(): Path? {
        val shouldCleanup = synchronized(lifecycleLock) {
            if (closed) return null
            sessionDirectory?.let { return it }
            (!staleCleanupAttempted).also { staleCleanupAttempted = true }
        }
        if (shouldCleanup) runCatching { tempStorage.clearStaleSessions() }

        return synchronized(lifecycleLock) {
            if (closed) return@synchronized null
            sessionDirectory?.let { return@synchronized it }
            runCatching {
                Files.createDirectories(tempRoot)
                Files.createTempDirectory(tempRoot, DESKTOP_SUBTITLE_SESSION_PREFIX)
            }.getOrNull()?.also {
                sessionDirectory = it
                tempStorage.markActiveSession(it)
            }
        }
    }

    private fun deletePartial(partPath: Path, finalPath: Path) {
        runCatching { Files.deleteIfExists(partPath) }
        runCatching { Files.deleteIfExists(finalPath) }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private class ListedEntries(
        val entries: List<MediaEntry>,
        val toCandidate: (MediaEntry) -> Candidate,
    )

    companion object {
        const val MAX_SUBTITLE_BYTES: Long = 16L * 1024L * 1024L
    }
}
