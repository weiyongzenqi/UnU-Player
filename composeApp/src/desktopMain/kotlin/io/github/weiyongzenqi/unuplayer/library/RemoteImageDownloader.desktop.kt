package io.github.weiyongzenqi.unuplayer.library

import java.io.File

/**
 * 桌面远程图片下载: 经桌面 [PosterCache] 下载到稳定子目录 + 原子发布 + LRU 淘汰。
 * 返回绝对路径, 显示直读(rememberScrapedImageModel 的 isLocalThumbFile 绝对路径分支)。
 */
class DesktopRemoteImageDownloader(
    private val maxImageBytes: Long = 4L * 1024L * 1024L,
    private val cacheMaxSizeBytes: Long = 200L * 1024L * 1024L,
) : RemoteImageDownloader {

    override suspend fun downloadImage(
        libraryId: Long,
        showPath: String,
        fileName: String,
        remoteUrl: String,
    ): String? {
        val showKey = onlineScrapeCacheKey(libraryId, showPath)
        val file = PosterCache.get().get(
            showKey = showKey,
            imageBasename = fileName,
            sourceIdentity = remoteUrl,
            maxSizeBytes = cacheMaxSizeBytes,
            downloader = { dest -> writeImageToFile(remoteUrl, dest) },
        ) ?: return null
        return file.absolutePath
    }

    private suspend fun writeImageToFile(remoteUrl: String, dest: File): Boolean {
        val bytes = RemoteImageFetcher.fetchImage(remoteUrl, maxImageBytes) ?: return false
        return runCatching { dest.writeBytes(bytes); true }.getOrDefault(false)
    }
}
