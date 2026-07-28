package io.github.weiyongzenqi.unuplayer.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile

/**
 * 桌面实现, 对应 androidMain 的 ScrapedImage.android.kt。
 *
 * actual: WebDAV/媒体服务器经桌面 PosterCache 下载到本地返 File; 本地返文件路径 String(coil3 解析);
 * 加载中/null 返 null。
 *
 * coil3 桌面网络层由 coil3-network-ktor3 提供(KtorNetworkFetcherFactory)。
 * 本函数返回的 model 是本地 File 或路径 String, coil3 直接加载本地文件;
 * 但仍注册 KtorNetworkFetcherFactory 以备未来直接加载远程 URL 的场景。
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
    // 确保 coil3 ImageLoader 装了 KtorNetworkFetcherFactory(idempotent, 仅首次生效)
    ensureKtorNetworkLoader
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
            // 本地生成集照(绝对路径文件, 跨平台 /storage/.. 与 C:\..): 直接返 File 供 coil3 加载(阶段 A 桌面不生成, 接口预留)。
            // stat 探测包 IO: produceState 默认 EDT/主上下文, 多卡片并发探测时不在主线程堆积磁盘 IO(对齐 android 实现)。
            isLocalThumbFile(imagePath) -> java.io.File(imagePath)
            sourceKind == MediaSourceKind.LOCAL -> imagePath  // 本地文件路径 String, coil3 桌面解析
            sourceKind == MediaSourceKind.WEBDAV -> {
                // 缓存文件名: 优先 cacheName(剧集 thumb 传 "S01E01 标题.jpg"), 否则用 imagePath 末段
                val basename = cacheName ?: imagePath.substringAfterLast('/').ifBlank { "image.jpg" }
                PosterCache.get().get(
                    showKey = cacheSubdir,
                    imageBasename = basename,
                    sourceIdentity = "$libraryId:$imagePath",
                    maxSizeBytes = imageCacheSizeMb.coerceIn(50, 2000).toLong() * 1024L * 1024L,
                    downloader = { file -> downloader(PlatformFile(file.path)) },
                )
            }
            sourceKind == MediaSourceKind.JELLYFIN || sourceKind == MediaSourceKind.EMBY -> {
                val basename = cacheName ?: "$imagePath.img"
                PosterCache.get().get(
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

/** 注册 KtorNetworkFetcherFactory 到 coil3 单例 ImageLoader。顶层 run 块在类加载时执行一次。 */
private val ensureKtorNetworkLoader = run {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .build()
    }
}
