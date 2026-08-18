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
 * actual: WebDAV/SMB/媒体服务器经 PosterCache 下载到本地返 File; 本地返 content uri String(coil3 解析);
 * 加载中/null 返 null。
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
    val context = LocalContext.current
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
            sourceKind == MediaSourceKind.LOCAL -> imagePath  // content:// String, coil3 Android 解析
            sourceKind == MediaSourceKind.WEBDAV || sourceKind == MediaSourceKind.SMB -> {
                // 缓存文件名: 优先 cacheName(剧集 thumb 传 "S01E01 标题.jpg"), 否则用 imagePath 末段(poster.jpg 等)
                val basename = cacheName ?: imagePath.substringAfterLast('/').ifBlank { "image.jpg" }
                PosterCache.get(context).get(
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
                PosterCache.get(context).get(
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
