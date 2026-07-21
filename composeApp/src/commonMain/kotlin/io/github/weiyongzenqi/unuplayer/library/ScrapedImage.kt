package io.github.weiyongzenqi.unuplayer.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile

/**
 * 海报墙图片加载(compose, coil3 桥接)。
 *
 * - WebDAV: 先下载到 PosterCache 本地文件(带 Basic Auth, 调用方 downloader), coil 加载本地 File
 * - 本地: content:// URI, coil 直接加载, 零下载
 * - 加载中/失败/无图: 纯色占位 + 文字(番剧名首字)
 *
 * @param downloader WebDAV 时用: suspend (dest: PlatformFile) -> Boolean, 调用方用 MediaSource.downloadToFile(imagePath, dest)
 */
@Composable
fun ScrapedImage(
    sourceKind: MediaSourceKind,
    libraryId: Long,
    imagePath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholderText: String,
    imageCacheSizeMb: Int,
    downloader: suspend (PlatformFile) -> Boolean,
    /** 缓存子目录(番剧文件夹名, sanitize 后, 如 "尼古喵喵-312949")。WebDAV 图片下载到此目录后加载。 */
    cacheSubdir: String,
    /** 缓存文件名(null 用 imagePath 的 basename 如 "poster.jpg"; 剧集 thumb 传 "S01E01 标题.jpg")。 */
    cacheName: String? = null,
) {
    val model by rememberScrapedImageModel(sourceKind, libraryId, imagePath, imageCacheSizeMb, downloader, cacheSubdir, cacheName)
    var loadFailed by remember(model) { mutableStateOf(false) }
    Box(modifier = modifier.clip(MaterialTheme.shapes.medium)) {
        if (model != null && !loadFailed) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                onError = { loadFailed = true },
            )
        } else {
            // 占位: 主题色背景 + 文字(番剧名首字)
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = placeholderText.firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * 海报卡片(海报墙网格 item): poster + 标题。
 * downloader 由调用方用 MediaSource.downloadToFile 提供。
 */
@Composable
fun PosterCard(
    title: String,
    sourceKind: MediaSourceKind,
    libraryId: Long,
    posterPath: String?,
    imageCacheSizeMb: Int,
    downloader: suspend (PlatformFile) -> Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 缓存子目录(番剧文件夹名, 透传给 ScrapedImage)。 */
    cacheSubdir: String,
) {
    Card(onClick = onClick, modifier = modifier) {
        Column {
            ScrapedImage(
                sourceKind = sourceKind,
                libraryId = libraryId,
                imagePath = posterPath,
                contentDescription = title,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                placeholderText = title,
                imageCacheSizeMb = imageCacheSizeMb,
                downloader = downloader,
                cacheSubdir = cacheSubdir,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }
}

/** 取 coil model(WebDAV=平台本地文件/本地=content uri String/null)。actual 在平台 source set。 */
@Composable
expect fun rememberScrapedImageModel(
    sourceKind: MediaSourceKind,
    libraryId: Long,
    imagePath: String?,
    imageCacheSizeMb: Int,
    downloader: suspend (PlatformFile) -> Boolean,
    cacheSubdir: String,
    cacheName: String?,
): State<Any?>

/**
 * 清理文件/文件夹名非法字符 -> `_`, trim, 限长 120, 空返 "unknown"。
 * 用于生成可读缓存目录名(番剧名-tmdbid)。
 */
fun sanitizeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120).ifBlank { "unknown" }

/** 番剧缓存子目录名(番剧名-tmdbid, sanitize 后)。列表/详情/删除清缓存统一用, 避免公式散落漂移。 */
val ScrapedShow.cacheKey: String get() = "${sanitizeFileName(title)}-${tmdb_id ?: id}"
val ListShowsByLibrary.cacheKey: String get() = "${sanitizeFileName(title)}-${tmdb_id ?: id}"
