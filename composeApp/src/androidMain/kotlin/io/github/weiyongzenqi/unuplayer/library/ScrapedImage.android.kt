package io.github.weiyongzenqi.unuplayer.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile

/**
 * actual: WebDAV/媒体服务器经 PosterCache 下载到本地返 File; 本地返 content uri String(coil3 解析);
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
            // 本地生成集照(绝对路径文件, 跨平台 /storage/.. 与 C:\..): 优先于 content:// 判断, 直接返 File 供 coil3 加载。
            // stat 探测包 IO: produceState 默认主 dispatcher, 海报墙/详情页多卡片并发探测时不在主线程堆积磁盘 IO。
            isLocalThumbFile(imagePath) -> java.io.File(imagePath)
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
            sourceKind == MediaSourceKind.JELLYFIN || sourceKind == MediaSourceKind.EMBY -> {
                val basename = cacheName ?: "$imagePath.img"
                PosterCache.get(context).get(
                    showKey = cacheSubdir,
                    imageBasename = basename,
                    sourceIdentity = "$sourceKind:$imagePath",
                    maxSizeBytes = imageCacheSizeMb.coerceIn(50, 2000).toLong() * 1024L * 1024L,
                    downloader = { file -> downloader(PlatformFile(file.path)) },
                )
            }
            else -> null
        }
    }
}

/** 本地集照探测(绝对路径 + 存在): 磁盘 stat 在 IO 执行, 不阻塞主线程(见调用处注释)。 */
private suspend fun isLocalThumbFile(imagePath: String): Boolean = withContext(Dispatchers.IO) {
    val file = java.io.File(imagePath)
    file.isAbsolute && file.exists()
}
