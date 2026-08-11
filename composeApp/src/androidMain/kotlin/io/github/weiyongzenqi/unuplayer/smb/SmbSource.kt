package io.github.weiyongzenqi.unuplayer.smb

import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.MediaKeys
import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream

/** Android SMB2/3 路径型媒体源。 */
class SmbSource(
    private val connection: SmbConnection,
) : MediaSource {
    override val kind: MediaSourceKind = MediaSourceKind.SMB
    override val displayName: String = connection.name

    private fun client(): AndroidSmbClient = AndroidSmbClient(connection)

    override suspend fun listFolder(path: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        client().use { it.list(path) }
    }

    override suspend fun resolvePlayMedia(entry: MediaEntry): PlayableMedia =
        PlayableMedia(
            // mpv 不认识该 scheme; Android 引擎在 load 时打开 ProxyFileDescriptor 后交给 fdclose://。
            url = SmbPlaybackLocator(connection.id, entry.path).toUrl(),
            title = entry.name,
            sourceKind = MediaSourceKind.SMB,
            mediaKey = MediaKeys.smb(connection.id, entry.path),
            tmdbId = entry.tmdbId,
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
        )

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        runSuspendCatching { client().use { it.list("") }; true }.getOrDefault(false)
    }

    override suspend fun readTextFile(path: String): String? = withContext(Dispatchers.IO) {
        runSuspendCatching {
            client().use { smb ->
                smb.openRead(path).use { source ->
                    if (source.size !in 0L..MAX_TEXT_FILE_BYTES) return@runSuspendCatching null
                    val output = ByteArrayOutputStream(source.size.toInt())
                    val buffer = ByteArray(IO_BUFFER_BYTES)
                    var offset = 0L
                    while (offset < source.size) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(
                            offset,
                            buffer,
                            (source.size - offset).coerceAtMost(buffer.size.toLong()).toInt(),
                        )
                        if (count <= 0) return@runSuspendCatching null
                        output.write(buffer, 0, count)
                        offset += count
                    }
                    output.toString(Charsets.UTF_8.name())
                }
            }
        }.getOrNull()
    }

    override suspend fun downloadToFile(path: String, dest: PlatformFile): Boolean = withContext(Dispatchers.IO) {
        val target = File(dest.path)
        val succeeded = runSuspendCatching {
            target.parentFile?.mkdirs()
            client().use { smb ->
                smb.openRead(path).use { source ->
                    target.outputStream().use { output ->
                        var offset = 0L
                        val buffer = ByteArray(DEFAULT_COPY_BUFFER_SIZE)
                        while (offset < source.size) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(
                                offset,
                                buffer,
                                (source.size - offset).coerceAtMost(buffer.size.toLong()).toInt(),
                            )
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                            offset += count
                        }
                        offset == source.size
                    }
                }
            }
        }.getOrDefault(false)
        if (!succeeded) runCatching { target.delete() }
        succeeded
    }

    override fun close() {
        // 每次操作拥有独立 SMBJ client/session，操作结束即关闭；source 本身无共享资源。
    }

    private companion object {
        const val MAX_TEXT_FILE_BYTES = 8L * 1024L * 1024L
        const val IO_BUFFER_BYTES = 64 * 1024
        const val DEFAULT_COPY_BUFFER_SIZE = 256 * 1024
    }
}
