package io.github.weiyongzenqi.unuplayer.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile

/**
 * actual: WebDAV 经 PosterCache 下载到本地返 File; 本地返 content uri String(coil3 解析);
 * 加载中/null 返 null。
 */
@Composable
actual fun rememberScrapedImageModel(
    sourceKind: MediaSourceKind,
    libraryId: Long,
    imagePath: String?,
    imageCacheSizeMb: Int,
    downloader: suspend (PlatformFile) -> Boolean,
    cacheSubdir: String,
    cacheName: String?,
): State<Any?> {
    val context = LocalContext.current
    return produceState<Any?>(
        initialValue = null,
        imagePath,
        sourceKind,
        libraryId,
        imageCacheSizeMb,
        cacheSubdir,
        cacheName,
    ) {
        value = when {
            imagePath == null -> null
            sourceKind == MediaSourceKind.LOCAL -> imagePath  // content:// String, coil3 Android 解析
            sourceKind == MediaSourceKind.WEBDAV -> {
                // 缓存文件名: 优先 cacheName(剧集 thumb 传 "S01E01 标题.jpg"), 否则用 imagePath 末段(poster.jpg 等)
                val basename = cacheName ?: imagePath.substringAfterLast('/').ifBlank { "image.jpg" }
                PosterCache.get(context).get(
                    showKey = cacheSubdir,
                    imageBasename = basename,
                    sourceIdentity = "$libraryId:$imagePath",
                    maxSizeBytes = imageCacheSizeMb.coerceIn(50, 2000).toLong() * 1024L * 1024L,
                    downloader = { file -> downloader(PlatformFile(file.path)) },
                )
            }
            else -> null
        }
    }
}
