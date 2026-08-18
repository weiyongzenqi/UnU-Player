package io.github.weiyongzenqi.unuplayer.library.export

import io.github.weiyongzenqi.unuplayer.library.PosterCache
import java.io.File

/** Desktop 图片服务实现: 经 PosterCache 单例(安全目录/原子发布复用)。 */
class DesktopLibraryImageService : LibraryImageService {
    private val cache = PosterCache.get()

    override suspend fun listShowFiles(showKey: String): List<ImageFileEntry> =
        cache.listShowFiles(showKey).map { ImageFileEntry(it.name, it.absolutePath) }

    override suspend fun writeShowImage(
        showKey: String,
        basename: String,
        bytes: ByteArray,
    ): ImageWriteResult? = cache.importShowImage(showKey, basename, basename, bytes)?.let {
        ImageWriteResult(it.file.absolutePath, it.created)
    }

    override suspend fun deleteShowImage(showKey: String, absolutePath: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val target = File(absolutePath).canonicalFile
                cache.listShowFiles(showKey)
                    .firstOrNull { it.canonicalFile == target }
                    ?.delete() == true
            }.getOrDefault(false)
        }

    override suspend fun writeEpisodeThumb(
        showKey: String,
        episodeId: Long,
        bytes: ByteArray,
    ): ImageWriteResult? = cache.importEpisodeThumb(showKey, episodeId, bytes)?.let {
        ImageWriteResult(it.file.absolutePath, it.created)
    }

    override suspend fun finishRestore() {
        cache.trimToCurrentLimit()
    }
}
