package io.github.weiyongzenqi.unuplayer.library

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.platform.AppNotif
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile

/**
 * 图片路径来源。媒体源路径必须交给对应 [MediaSourceKind] 读取；本地缓存文件只允许直接读文件。
 * 不再通过 `/`、盘符等字符串形态猜测来源，避免把 WebDAV 根路径误判为本地绝对路径。
 */
enum class ScrapedImagePathKind {
    MEDIA_SOURCE,
    LOCAL_FILE;

    companion object {
        fun fromStorage(value: String?): ScrapedImagePathKind =
            entries.firstOrNull { it.name == value } ?: MEDIA_SOURCE
    }
}

data class ScrapedImageCandidate(
    val path: String,
    val kind: ScrapedImagePathKind,
)

sealed interface ScrapedImageModelState {
    data object Loading : ScrapedImageModelState
    data class Ready(val model: Any) : ScrapedImageModelState
    data object Unavailable : ScrapedImageModelState
}

/**
 * 海报墙图片加载(compose, coil3 桥接)。
 *
 * - WebDAV/SMB/媒体服务器: 先流式下载到 PosterCache 本地文件(认证由调用方 downloader 注入), coil 加载本地 File
 * - 本地: content:// URI, coil 直接加载, 零下载
 * - 加载中/失败/无图: 纯色占位 + 文字(番剧名首字)
 *
 * @param downloader 媒体源图片下载器，参数为当前候选路径和目标文件。
 */
@Composable
fun ScrapedImage(
    sourceKind: MediaSourceKind,
    libraryId: Long,
    imagePath: String?,
    imagePathKind: ScrapedImagePathKind = ScrapedImagePathKind.MEDIA_SOURCE,
    fallbackImages: List<ScrapedImageCandidate> = emptyList(),
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholderText: String,
    imageCacheSizeMb: Int,
    downloader: suspend (String, PlatformFile) -> Boolean,
    /** 缓存子目录(番剧文件夹名, sanitize 后, 如 "尼古喵喵-312949")。远程图片下载到此目录后加载。 */
    cacheSubdir: String,
    /** 缓存文件名(null 用 imagePath 的 basename 如 "poster.jpg"; 剧集 thumb 传 "S01E01 标题.jpg")。 */
    cacheName: String? = null,
    onCandidateIndexChanged: (Int) -> Unit = {},
    previewEnabled: Boolean = false,
    saveFileStem: String = placeholderText,
) {
    val candidates = remember(imagePath, imagePathKind, fallbackImages) {
        buildList {
            imagePath?.takeIf { it.isNotBlank() }?.let { add(ScrapedImageCandidate(it, imagePathKind)) }
            addAll(fallbackImages.filter { it.path.isNotBlank() })
        }.distinct()
    }
    var candidateIndex by remember(candidates) { mutableStateOf(0) }
    val candidate = candidates.getOrNull(candidateIndex)
    val modelState by rememberScrapedImageModel(
        sourceKind = sourceKind,
        libraryId = libraryId,
        imagePath = candidate?.path,
        imagePathKind = candidate?.kind ?: ScrapedImagePathKind.MEDIA_SOURCE,
        imageCacheSizeMb = imageCacheSizeMb,
        downloader = downloader,
        cacheSubdir = cacheSubdir,
        cacheName = cacheName,
    )
    var loadFailed by remember(candidateIndex, candidates) { mutableStateOf(false) }
    var loadSucceeded by remember(candidateIndex, candidates) { mutableStateOf(false) }
    val ready = modelState as? ScrapedImageModelState.Ready
    LaunchedEffect(candidateIndex) {
        onCandidateIndexChanged(candidateIndex)
    }
    LaunchedEffect(modelState, candidateIndex, candidates.size) {
        if (modelState == ScrapedImageModelState.Unavailable && candidateIndex + 1 < candidates.size) {
            candidateIndex++
        }
    }
    if (previewEnabled) {
        InteractiveScrapedImageBox(
            model = ready?.model?.takeIf { loadSucceeded && !loadFailed },
            contentDescription = contentDescription,
            saveFileStem = saveFileStem,
            modifier = modifier.clip(MaterialTheme.shapes.medium),
        ) {
            ScrapedImageContent(
                ready = ready,
                loadFailed = loadFailed,
                contentDescription = contentDescription,
                placeholderText = placeholderText,
                onSuccess = { loadSucceeded = true },
                onError = {
                    if (candidateIndex + 1 < candidates.size) candidateIndex++ else loadFailed = true
                },
            )
        }
    } else {
        Box(modifier = modifier.clip(MaterialTheme.shapes.medium)) {
            ScrapedImageContent(
                ready = ready,
                loadFailed = loadFailed,
                contentDescription = contentDescription,
                placeholderText = placeholderText,
                onSuccess = { loadSucceeded = true },
                onError = {
                    if (candidateIndex + 1 < candidates.size) candidateIndex++ else loadFailed = true
                },
            )
        }
    }
}

@Composable
private fun ScrapedImageContent(
    ready: ScrapedImageModelState.Ready?,
    loadFailed: Boolean,
    contentDescription: String?,
    placeholderText: String,
    onSuccess: () -> Unit,
    onError: () -> Unit,
) {
    if (ready != null && !loadFailed) {
        AsyncImage(
            model = ready.model,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onSuccess = { onSuccess() },
            onError = { onError() },
        )
    } else {
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

@Composable
private fun InteractiveScrapedImageBox(
    model: Any?,
    contentDescription: String?,
    saveFileStem: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    var previewModel by remember(model) { mutableStateOf<Any?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val platformContext = LocalPlatformContext.current
    fun saveImage(imageModel: Any) {
        if (saving) return
        saving = true
        scope.launch {
            val result = runSuspendCatching {
                saveScrapedImageModel(platformContext, imageModel, saveFileStem)
            }
            AppNotif.toast(result.fold(
                onSuccess = { "图片已保存：$it" },
                onFailure = { "图片保存失败：${it.message ?: "未知错误"}" },
            ))
            saving = false
        }
    }
    val interactionModifier = model?.let { imageModel ->
        Modifier.combinedClickable(
            onClickLabel = "查看大图",
            onLongClickLabel = "保存图片",
            onClick = { previewModel = imageModel },
            onLongClick = { saveImage(imageModel) },
        )
    } ?: Modifier
    Box(modifier = modifier.then(interactionModifier)) { content() }
    previewModel?.let { imageModel ->
        ScrapedImagePreviewDialog(
            model = imageModel,
            contentDescription = contentDescription,
            saving = saving,
            onSave = { saveImage(imageModel) },
            onDismiss = { previewModel = null },
        )
    }
}

@Composable
private fun ScrapedImagePreviewDialog(
    model: Any,
    contentDescription: String?,
    saving: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .combinedClickable(onClick = {}, onLongClick = onSave),
        ) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentScale = ContentScale.Fit,
            )
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (saving) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White)
                    }
                } else {
                    IconButton(onClick = onSave) {
                        Icon(Icons.Filled.Download, contentDescription = "保存图片", tint = Color.White)
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭大图", tint = Color.White)
                }
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
    posterPathKind: ScrapedImagePathKind = ScrapedImagePathKind.MEDIA_SOURCE,
    fallbackPosterPath: String? = null,
    imageCacheSizeMb: Int,
    downloader: suspend (PlatformFile) -> Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 缓存子目录(番剧文件夹名, 透传给 ScrapedImage)。 */
    cacheSubdir: String,
    /** 卡片右上角季徽章文本(如"第2季"); null=不显示。仅单季番传非空(card_season_number)。 */
    seasonBadge: String? = null,
) {
    Card(onClick = onClick, modifier = modifier) {
        Column {
            Box {
                ScrapedImage(
                    sourceKind = sourceKind,
                    libraryId = libraryId,
                    imagePath = posterPath,
                    imagePathKind = posterPathKind,
                    fallbackImages = fallbackPosterPath?.let {
                        listOf(ScrapedImageCandidate(it, ScrapedImagePathKind.LOCAL_FILE))
                    }.orEmpty(),
                    contentDescription = title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                    placeholderText = title,
                    imageCacheSizeMb = imageCacheSizeMb,
                    downloader = { _, dest -> downloader(dest) },
                    cacheSubdir = cacheSubdir,
                )
                if (seasonBadge != null) {
                    Text(
                        text = seasonBadge,
                        style = MaterialTheme.typography.labelSmall.copy(
                            shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), blurRadius = 2.5f, offset = Offset(0.5f, 0.5f)),
                        ),
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                }
            }
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

/** 取 coil model(远程来源=平台本地缓存文件/本地=content uri String/null)。actual 在平台 source set。 */
@Composable
expect fun rememberScrapedImageModel(
    sourceKind: MediaSourceKind,
    libraryId: Long,
    imagePath: String?,
    imagePathKind: ScrapedImagePathKind,
    imageCacheSizeMb: Int,
    downloader: suspend (String, PlatformFile) -> Boolean,
    cacheSubdir: String,
    cacheName: String?,
): State<ScrapedImageModelState>

/**
 * 清理文件/文件夹名非法字符 -> `_`, trim, 限长 120, 空返 "unknown"。
 * 用于生成可读缓存目录名(番剧名-tmdbid)。
 */
fun sanitizeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120).ifBlank { "unknown" }

/** 番剧缓存子目录名(番剧名-tmdbid, sanitize 后)。列表/详情/删除清缓存统一用, 避免公式散落漂移。 */
val ScrapedShow.cacheKey: String get() = "${sanitizeFileName(title)}-${tmdb_id ?: id}"
val ListShowsByLibrary.cacheKey: String get() = "${sanitizeFileName(title)}-${tmdb_id ?: id}"
