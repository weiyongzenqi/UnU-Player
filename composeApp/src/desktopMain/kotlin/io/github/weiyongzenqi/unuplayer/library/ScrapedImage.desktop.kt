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
    imagePathKind: ScrapedImagePathKind,
    imageCacheSizeMb: Int,
    downloader: suspend (String, PlatformFile) -> Boolean,
    cacheSubdir: String,
    cacheName: String?,
): State<ScrapedImageModelState> {
    // 确保 coil3 ImageLoader 装了 KtorNetworkFetcherFactory(idempotent, 仅首次生效)
    ensureKtorNetworkLoader
    return produceState<ScrapedImageModelState>(
        initialValue = ScrapedImageModelState.Loading,
        imagePath,
        imagePathKind,
        sourceKind,
        libraryId,
        imageCacheSizeMb,
        cacheSubdir,
        cacheName,
    ) {
        value = ScrapedImageModelState.Loading
        val model = when {
            imagePath == null -> null
            imagePathKind == ScrapedImagePathKind.LOCAL_FILE -> existingLocalFile(imagePath)
            sourceKind == MediaSourceKind.LOCAL -> imagePath  // 本地文件路径 String, coil3 桌面解析
            sourceKind == MediaSourceKind.WEBDAV -> {
                // 缓存文件名: 优先 cacheName(剧集 thumb 传 "S01E01 标题.jpg"), 否则用 imagePath 末段
                val basename = cacheName ?: imagePath.substringAfterLast('/').ifBlank { "image.jpg" }
                PosterCache.get().get(
                    showKey = cacheSubdir,
                    imageBasename = basename,
                    sourceIdentity = "$libraryId:$imagePath",
                    maxSizeBytes = imageCacheSizeMb.coerceIn(50, 2000).toLong() * 1024L * 1024L,
                    maxFileBytes = MAX_POSTER_IMAGE_BYTES,
                    downloader = { file -> downloader(imagePath, PlatformFile(file.path)) },
                )
            }
            sourceKind == MediaSourceKind.JELLYFIN || sourceKind == MediaSourceKind.EMBY -> {
                val basename = cacheName ?: "$imagePath.img"
                PosterCache.get().get(
                    showKey = cacheSubdir,
                    imageBasename = basename,
                    sourceIdentity = "$sourceKind:$imagePath",
                    maxSizeBytes = imageCacheSizeMb.coerceIn(50, 2000).toLong() * 1024L * 1024L,
                    maxFileBytes = MAX_POSTER_IMAGE_BYTES,
                    downloader = { file -> downloader(imagePath, PlatformFile(file.path)) },
                )
            }
            else -> null
        }
        value = model?.let(ScrapedImageModelState::Ready) ?: ScrapedImageModelState.Unavailable
    }
}

/** 本地缓存文件只在存在时交给 Coil；失效路径不得回落到媒体源下载分支。 */
private suspend fun existingLocalFile(imagePath: String): java.io.File? = withContext(Dispatchers.IO) {
    java.io.File(imagePath).takeIf { it.exists() }
}

/** 注册 KtorNetworkFetcherFactory 到 coil3 单例 ImageLoader。顶层 run 块在类加载时执行一次。 */
private val ensureKtorNetworkLoader = run {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .build()
    }
}
