package io.github.weiyongzenqi.unuplayer.library.export

import io.github.weiyongzenqi.unuplayer.library.PosterCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Desktop 图片服务实现: 经 PosterCache 单例(安全目录/原子发布复用)。 */
class DesktopLibraryImageService : LibraryImageService {
    private val cache = PosterCache.get()

    override suspend fun listShowFiles(showKey: String): List<ImageFileEntry> =
        cache.listShowFiles(showKey).map { ImageFileEntry(it.name, it.absolutePath) }

    override suspend fun writeShowImage(showKey: String, basename: String, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                cache.get(
                    showKey = showKey,
                    imageBasename = basename,
                    sourceIdentity = basename,
                    maxSizeBytes = Long.MAX_VALUE,
                    downloader = { file ->
                        runCatching { file.writeBytes(bytes); true }.getOrDefault(false)
                    },
                )?.absolutePath
            }.getOrNull()
        }

    override suspend fun writeEpisodeThumb(showKey: String, episodeId: Long, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = cache.episodeThumbFile(showKey, episodeId)
                file.writeBytes(bytes)
                file.absolutePath
            }.getOrNull()
        }
}