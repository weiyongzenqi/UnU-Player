package io.github.weiyongzenqi.unuplayer.library

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    onPreviewTap: (() -> Unit)? = null,
    /** true=点击直接打开全屏预览(头图等无独立点击动作的图); 默认 false 转发 onPreviewTap。 */
    clickOpensPreview: Boolean = false,
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
            onPreviewTap = onPreviewTap,
            clickOpensPreview = clickOpensPreview,
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

/**
 * 保存图片到用户图片目录的公共流程(saving 防抖 + runSuspendCatching + 成功/失败 toast),
 * 海报墙预览与评论图片共用, 避免两份实现漂移。
 */
internal class ScrapedImageSaveController(
    private val platformContext: Any,
    private val scope: CoroutineScope,
) {
    var saving by mutableStateOf(false)
        private set

    fun save(model: Any, fileStem: String) {
        if (saving) return
        saving = true
        scope.launch {
            val result = runSuspendCatching {
                saveScrapedImageModel(platformContext, model, fileStem)
            }
            AppNotif.toast(result.fold(
                onSuccess = { "图片已保存：$it" },
                onFailure = { "图片保存失败：${it.message ?: "未知错误"}" },
            ))
            saving = false
        }
    }
}

@Composable
internal fun rememberScrapedImageSaveController(): ScrapedImageSaveController {
    val platformContext = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    return remember { ScrapedImageSaveController(platformContext, scope) }
}

@Composable
private fun InteractiveScrapedImageBox(
    model: Any?,
    contentDescription: String?,
    saveFileStem: String,
    onPreviewTap: (() -> Unit)?,
    /** true=点击直接打开全屏预览(头图等无独立点击动作的图); false=点击转发 onPreviewTap。 */
    clickOpensPreview: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    var previewModel by remember(model) { mutableStateOf<Any?>(null) }
    val saveController = rememberScrapedImageSaveController()
    val interactionModifier = model?.let { imageModel ->
        Modifier.combinedClickable(
            onClick = {
                if (clickOpensPreview) previewModel = imageModel else onPreviewTap?.invoke()
            },
            onLongClickLabel = "查看大图",
            onLongClick = { previewModel = imageModel },
        )
    } ?: Modifier
    Box(modifier = modifier.then(interactionModifier)) { content() }
    previewModel?.let { imageModel ->
        ScrapedImagePreviewDialog(
            model = imageModel,
            contentDescription = contentDescription,
            saving = saveController.saving,
            onSave = { saveController.save(imageModel, saveFileStem) },
            onDismiss = { previewModel = null },
        )
    }
}

/**
 * 在线图片复用海报墙预览手势的安全入口。
 *
 * 展示仍由调用方使用自己的 Coil model；真正打开预览前，经 [RemoteImageFetcher] 做 MIME、大小、
 * 重定向与鉴权边界检查并得到临时字节。这样 Android 保存流程不会把远端 URL 误当成本地文件。
 */
@Composable
internal fun RemotePreviewableImageBox(
    imageUrl: String?,
    contentDescription: String?,
    saveFileStem: String,
    onPreviewTap: (() -> Unit)? = null,
    clickOpensPreview: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var previewBytes by remember(imageUrl) { mutableStateOf<ByteArray?>(null) }
    var loading by remember(imageUrl) { mutableStateOf(false) }
    var loadJob by remember(imageUrl) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val saveController = rememberScrapedImageSaveController()

    DisposableEffect(imageUrl) {
        onDispose { loadJob?.cancel() }
    }

    fun openPreview() {
        val url = imageUrl ?: return
        if (loading) return
        loading = true
        loadJob = scope.launch {
            when (val outcome = RemoteImageFetcher.fetchImageDetailed(url)) {
                is RemoteImageFetcher.ImageFetchOutcome.Success -> previewBytes = outcome.bytes
                is RemoteImageFetcher.ImageFetchOutcome.Failure ->
                    AppNotif.toast("图片预览加载失败：${outcome.reason.description}")
            }
            loading = false
        }
    }

    val interactionModifier = if (imageUrl != null) {
        Modifier.combinedClickable(
            onClick = { if (clickOpensPreview) openPreview() else onPreviewTap?.invoke() },
            onLongClickLabel = "查看大图",
            onLongClick = ::openPreview,
        )
    } else {
        Modifier
    }
    Box(modifier = modifier.then(interactionModifier)) {
        content()
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(24.dp),
                strokeWidth = 2.dp,
            )
        }
    }
    previewBytes?.let { bytes ->
        ScrapedImagePreviewDialog(
            model = bytes,
            contentDescription = contentDescription,
            saving = saveController.saving,
            onSave = { saveController.save(bytes, saveFileStem) },
            onDismiss = { previewBytes = null },
        )
    }
}

@Composable
internal fun ScrapedImagePreviewDialog(
    model: Any,
    contentDescription: String?,
    saving: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var imageAspectRatio by remember(model) { mutableStateOf<Float?>(null) }
    // 缩放分两层: baseScale 是已 rebase 进布局尺寸的倍率(决定实际布局大小与 coil 解码分辨率,
    // 放大后图片真实超出视口被裁剪, 无黑边且不模糊); graphicsScale 是手势期间的临时倍率
    // (graphicsLayer 快速缩放, 手势帧不触发重解码), 手势结束 rebase 吸收进 baseScale。
    var baseScale by remember(model) { mutableFloatStateOf(1f) }
    var graphicsScale by remember(model) { mutableFloatStateOf(1f) }
    var previewOffsetX by remember(model) { mutableFloatStateOf(0f) }
    var previewOffsetY by remember(model) { mutableFloatStateOf(0f) }
    var dismissing by remember(model) { mutableStateOf(false) }
    var saveConfirmationVisible by remember(model) { mutableStateOf(false) }
    var offsetAnimationJob by remember(model) { mutableStateOf<Job?>(null) }
    var scaleAnimationJob by remember(model) { mutableStateOf<Job?>(null) }
    // 双指手势进行中标志: 第二指按下后 detectDragGestures 会被取消并触发 onDragCancel,
    // 需要用它挡住回弹动画, 避免与双指缩放帧竞争写偏移。
    var transformActive by remember(model) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            // 放大后图片布局超出视口, 裁剪掉超出部分(图片铺满屏幕无黑边)
            modifier = Modifier.fillMaxSize().clipToBounds(),
        ) {
            val viewportWidthPx = constraints.maxWidth.toFloat()
            val viewportHeightPx = constraints.maxHeight.toFloat()
            // 视口尺寸回调期新鲜读: pointerInput(model) 闭包只在节点创建时捕获一次本地 val,
            // 分屏/折叠屏调整 Dialog 窗口后手势回调若读旧值, 平移边界/退出阈值会按旧视口计算
            // (手势 state 变量走 Kotlin 委托捕获无此问题, 仅这两个 val 需要)。
            // graphicsLayer 块不受影响: 重组会用新捕获重建 lambda。
            val currentViewportWidthPx by rememberUpdatedState(viewportWidthPx)
            val currentViewportHeightPx by rememberUpdatedState(viewportHeightPx)
            val containerAspectRatio = maxWidth.value / maxHeight.value

            // 偏移回弹动画的单一实现(onDragEnd 未过阈值/onDragCancel/onGestureEnd 归整复位共用),
            // X/Y 同步动画回 0(负 Y 同样复位, 避免垂直偏心残留)。
            fun bounceOffsetsBack() {
                val startX = previewOffsetX
                val startY = previewOffsetY
                if (startX == 0f && startY == 0f) return
                offsetAnimationJob = scope.launch {
                    animate(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = tween(180),
                    ) { progress, _ ->
                        previewOffsetX = startX * (1f - progress)
                        previewOffsetY = startY * (1f - progress)
                    }
                    previewOffsetX = 0f
                    previewOffsetY = 0f
                }
            }

            // 松手后按下滑位移决定退出或回弹(单指 onDragEnd 与双指 onGestureEnd 共用同一语义)。
            fun dismissOrBounceOffset() {
                if (dismissing) return
                if (shouldDismissImagePreview(previewOffsetY, currentViewportHeightPx)) {
                    dismissing = true
                    val startY = previewOffsetY
                    offsetAnimationJob = scope.launch {
                        animate(
                            initialValue = startY,
                            targetValue = currentViewportHeightPx,
                            animationSpec = tween(180),
                        ) { value, _ -> previewOffsetY = value }
                        onDismiss()
                    }
                } else {
                    bounceOffsetsBack()
                }
            }
            // 手势值(偏移/倍率)全部只在 graphicsLayer 块与手势回调内读取(draw 阶段/回调期),
            // 组合期零读取 → 拖动与缩放帧只重绘不重组。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .graphicsLayer {
                        // 下滑退出时背景随位移渐隐
                        val offsetY = if (baseScale * graphicsScale <= 1.01f) previewOffsetY else 0f
                        alpha = imagePreviewBackgroundAlpha(offsetY, viewportHeightPx)
                    }
                    .combinedClickable(onClick = onDismiss),
            )
            val fittedImageModifier = imageAspectRatio?.let { aspectRatio ->
                // 布局尺寸 = fit 尺寸 × baseScale, 必须用 requiredSize 显式两端尺寸:
                // fillMaxWidth(fraction>1) 只放大外层尺寸报告, 内侧 aspectRatio 收到的仍是视口约束,
                // 图片内容不会真正变大(rebased 后 graphicsScale 归 1 会导致图片瞬间弹回原始大小)。
                // 放大后图片真实超出视口(父级 clipToBounds 裁剪), 无黑边且 coil 按新布局重解码(放大不模糊)。
                // 组合期只读 baseScale, rebase 才触发一次重组。
                if (aspectRatio >= containerAspectRatio) {
                    Modifier.requiredSize(
                        width = maxWidth * baseScale,
                        height = (maxWidth / aspectRatio) * baseScale,
                    )
                } else {
                    Modifier.requiredSize(
                        width = (maxHeight * aspectRatio) * baseScale,
                        height = maxHeight * baseScale,
                    )
                }
            } ?: Modifier.fillMaxSize()
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = fittedImageModifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        // 下滑退出时图片跟随手指逐渐缩小(dismissalScale 随位移平滑下降)
                        val offsetY = if (baseScale * graphicsScale <= 1.01f) previewOffsetY else 0f
                        val dismiss = imagePreviewDismissScale(offsetY, viewportHeightPx)
                        scaleX = graphicsScale * dismiss
                        scaleY = graphicsScale * dismiss
                        translationX = previewOffsetX
                        translationY = previewOffsetY
                    }
                    .pointerInput(model) {
                        detectDragGestures(
                            onDragStart = {
                                if (dismissing) return@detectDragGestures
                                offsetAnimationJob?.cancel()
                                scaleAnimationJob?.cancel()
                                // 双击缩放动画中途接拖动: 动画协程被取消后其末尾的归整不会执行,
                                // 总倍率会滞留 (1.01,1.1) 死区(未放大却可平移)或以 graphicsScale≠1 悬置
                                // (布局未吸收、发虚), 这里立即归整一次。
                                if (graphicsScale != 1f) {
                                    val rebase = imagePreviewRebase(baseScale, graphicsScale)
                                    baseScale = rebase.baseScale
                                    graphicsScale = 1f
                                    if (rebase.resetOffset) {
                                        previewOffsetX = 0f
                                        previewOffsetY = 0f
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (dismissing) return@detectDragGestures
                                change.consume()
                                // 回调期直接读委托变量即最新值(活引用), 组合期不再派生 panBounds
                                if (baseScale * graphicsScale <= 1.01f) {
                                    previewOffsetX = 0f
                                    previewOffsetY = (previewOffsetY + dragAmount.y).coerceAtLeast(0f)
                                } else {
                                    val bounds = imagePreviewPanBounds(
                                        viewportWidthPx = currentViewportWidthPx,
                                        viewportHeightPx = currentViewportHeightPx,
                                        imageAspectRatio = imageAspectRatio,
                                        scale = baseScale * graphicsScale,
                                    )
                                    previewOffsetX = (previewOffsetX + dragAmount.x)
                                        .coerceIn(-bounds.maxOffsetX, bounds.maxOffsetX)
                                    previewOffsetY = (previewOffsetY + dragAmount.y)
                                        .coerceIn(-bounds.maxOffsetY, bounds.maxOffsetY)
                                }
                            },
                            onDragEnd = {
                                if (dismissing || baseScale * graphicsScale > 1.01f) return@detectDragGestures
                                dismissOrBounceOffset()
                            },
                            onDragCancel = {
                                // 双指按下后 drag 被消费取消: 此时回弹交给双指手势的 onGestureEnd,
                                // 这里不再启动动画, 避免与缩放帧竞争写偏移。
                                if (dismissing || baseScale * graphicsScale > 1.01f || transformActive) {
                                    return@detectDragGestures
                                }
                                bounceOffsetsBack()
                            },
                        )
                    }
                    // 双指缩放: 单指阶段只观察不消费(把单指拖动/点击让给 drag 与 combinedClickable),
                    // 第二指按下才激活并开始消费事件(消费后 detectDragGestures 会自行取消)。
                    .pointerInput(model) {
                        detectTwoFingerZoom(
                            onGestureStart = {
                                transformActive = true
                                // 退出动画进行中不取消 offsetAnimationJob: 动画末尾才会调 onDismiss,
                                // 取消它会让 dismissing 永久卡 true(此后所有手势被拦死),
                                // 让动画跑完自然关窗、新手势直接忽略。
                                if (!dismissing) {
                                    offsetAnimationJob?.cancel()
                                    scaleAnimationJob?.cancel()
                                }
                            },
                            onGesture = { centroid, pan, zoom ->
                                if (dismissing) return@detectTwoFingerZoom
                                val transform = imagePreviewPinchTransform(
                                    viewportWidthPx = currentViewportWidthPx,
                                    viewportHeightPx = currentViewportHeightPx,
                                    imageAspectRatio = imageAspectRatio,
                                    totalScale = baseScale * graphicsScale,
                                    zoom = zoom,
                                    centroidX = centroid.x,
                                    centroidY = centroid.y,
                                    panX = pan.x,
                                    panY = pan.y,
                                    currentOffsetX = previewOffsetX,
                                    currentOffsetY = previewOffsetY,
                                )
                                // transform.scale 是新的总倍率: 只写入手势层(布局不动, 手势帧零重解码)
                                graphicsScale = transform.scale / baseScale
                                previewOffsetX = transform.offsetX
                                previewOffsetY = transform.offsetY
                            },
                            onGestureEnd = {
                                transformActive = false
                                val rebase = imagePreviewRebase(baseScale, graphicsScale)
                                baseScale = rebase.baseScale
                                graphicsScale = 1f
                                if (rebase.resetOffset) {
                                    // 与单指松手同一语义: 过阈值退出, 否则回弹(含负 Y 复位)
                                    dismissOrBounceOffset()
                                }
                            },
                        )
                    }
                    .combinedClickable(
                        onClick = {},
                        onDoubleClick = {
                            if (dismissing) return@combinedClickable
                            offsetAnimationJob?.cancel()
                            scaleAnimationJob?.cancel()
                            val initialTotal = baseScale * graphicsScale
                            val targetTotal = nextImagePreviewScale(initialTotal)
                            val initialGraphics = graphicsScale
                            val targetGraphics = targetTotal / baseScale
                            val initialOffsetX = previewOffsetX
                            val initialOffsetY = previewOffsetY
                            scaleAnimationJob = scope.launch {
                                animate(
                                    initialValue = 0f,
                                    targetValue = 1f,
                                    animationSpec = tween(180),
                                ) { progress, _ ->
                                    graphicsScale = initialGraphics + (targetGraphics - initialGraphics) * progress
                                    if (targetTotal <= 1.01f) {
                                        previewOffsetX = initialOffsetX * (1f - progress)
                                        previewOffsetY = initialOffsetY * (1f - progress)
                                    }
                                }
                                // 动画结束把倍率吸收进布局: coil 按新尺寸重解码, 放大后无黑边不模糊
                                val rebase = imagePreviewRebase(baseScale, graphicsScale)
                                baseScale = rebase.baseScale
                                graphicsScale = 1f
                                if (rebase.resetOffset) {
                                    previewOffsetX = 0f
                                    previewOffsetY = 0f
                                }
                            }
                        },
                        onLongClickLabel = "保存图片",
                        onLongClick = {
                            if (!dismissing && !saving) saveConfirmationVisible = true
                        },
                    ),
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    val image = state.result.image
                    if (image.width > 0 && image.height > 0) {
                        imageAspectRatio = image.width.toFloat() / image.height.toFloat()
                    }
                },
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .graphicsLayer {
                        val offsetY = if (baseScale * graphicsScale <= 1.01f) previewOffsetY else 0f
                        alpha = imagePreviewBackgroundAlpha(offsetY, viewportHeightPx)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (saving) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White)
                    }
                } else {
                    IconButton(onClick = { saveConfirmationVisible = true }) {
                        Icon(Icons.Filled.Download, contentDescription = "保存图片", tint = Color.White)
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭大图", tint = Color.White)
                }
            }
        }
    }
    if (saveConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { if (!saving) saveConfirmationVisible = false },
            title = { Text("保存图片") },
            text = { Text("确认保存当前图片？") },
            dismissButton = {
                TextButton(
                    onClick = { saveConfirmationVisible = false },
                    enabled = !saving,
                ) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveConfirmationVisible = false
                        onSave()
                    },
                    enabled = !saving,
                ) {
                    Text("保存")
                }
            },
        )
    }
}

internal fun nextImagePreviewScale(currentScale: Float): Float =
    if (currentScale > 1.01f) 1f else 2f

internal const val IMAGE_PREVIEW_MAX_SCALE = 3f

/** 低于该总倍率的手势结果视为"未有效放大", rebase 时归整回 1 倍居中(避免视觉无感的轻微放大留下可平移中间态)。 */
internal const val IMAGE_PREVIEW_MIN_EFFECTIVE_SCALE = 1.1f

internal data class ImagePreviewRebaseResult(
    /** 归整后的布局倍率(手势层倍率由调用方置 1)。 */
    val baseScale: Float,
    /** true=总倍率不足有效放大, 偏移应归零回到居中 1 倍态。 */
    val resetOffset: Boolean,
)

/**
 * 手势/双击动画结束后的倍率归整: 总倍率钳制 [1, maxScale];
 * 不足 [IMAGE_PREVIEW_MIN_EFFECTIVE_SCALE] 时归回 1 倍并要求偏移归零
 * (否则 1.01~1.1 倍的"无感放大"会让单指拖动走平移分支, 表现为未放大却可左右移动)。
 */
internal fun imagePreviewRebase(
    baseScale: Float,
    graphicsScale: Float,
    maxScale: Float = IMAGE_PREVIEW_MAX_SCALE,
    minEffectiveScale: Float = IMAGE_PREVIEW_MIN_EFFECTIVE_SCALE,
): ImagePreviewRebaseResult {
    val total = baseScale * graphicsScale
    if (total < minEffectiveScale) return ImagePreviewRebaseResult(1f, resetOffset = true)
    return ImagePreviewRebaseResult(total.coerceAtMost(maxScale), resetOffset = false)
}

internal data class ImagePreviewPinchTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

/**
 * 双指缩放一帧的变换计算: 新总倍率钳制在 [1, maxScale], 围绕捏合中心缩放
 * (centroid 下的图片内容跟随手指保持不动, 推导: 屏幕点 = (布局点-视口中心)*scale + 偏移 + 视口中心,
 * 令 centroid 处内容点新屏幕位置 == centroid + pan 解得偏移公式), 再叠加双指平移并按新总倍率的
 * 实际溢出边界钳制(宽图/长图用对应轴, 见 [imagePreviewPanBounds])。
 * 总倍率不足有效缩放阈值(≤1.01)时退化为下滑拖动语义: Y 继续累积(双指也能退出预览)、X 保持 0,
 * 不清零偏移(否则第二指落在下滑拖动中途会瞬间抹掉已累积的位移, 图片猛跳回中心)。
 */
internal fun imagePreviewPinchTransform(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    imageAspectRatio: Float?,
    totalScale: Float,
    zoom: Float,
    centroidX: Float,
    centroidY: Float,
    panX: Float,
    panY: Float,
    currentOffsetX: Float,
    currentOffsetY: Float,
    maxScale: Float = IMAGE_PREVIEW_MAX_SCALE,
): ImagePreviewPinchTransform {
    val newScale = (totalScale * zoom).coerceIn(1f, maxScale)
    if (newScale <= 1.01f) {
        return ImagePreviewPinchTransform(1f, 0f, (currentOffsetY + panY).coerceAtLeast(0f))
    }
    val ratio = newScale / totalScale
    val offsetX = currentOffsetX * ratio + (centroidX - viewportWidthPx / 2f) * (1f - ratio) + panX
    val offsetY = currentOffsetY * ratio + (centroidY - viewportHeightPx / 2f) * (1f - ratio) + panY
    val bounds = imagePreviewPanBounds(viewportWidthPx, viewportHeightPx, imageAspectRatio, newScale)
    return ImagePreviewPinchTransform(
        scale = newScale,
        offsetX = offsetX.coerceIn(-bounds.maxOffsetX, bounds.maxOffsetX),
        offsetY = offsetY.coerceIn(-bounds.maxOffsetY, bounds.maxOffsetY),
    )
}

/**
 * 下滑退出进度驱动的图片跟随缩小比例: 拖到视口 75% 高度时缩到 0.65,
 * 继续下拉最低缩到 0.6(与退出动画终点一致), 松手未过阈值则随回弹恢复 1。
 */
internal fun imagePreviewDismissScale(offsetPx: Float, viewportHeightPx: Float): Float {
    if (viewportHeightPx <= 0f) return 1f
    val progress = offsetPx.coerceAtLeast(0f) / (viewportHeightPx * 0.75f)
    return (1f - 0.35f * progress).coerceIn(0.6f, 1f)
}

/**
 * 双指缩放检测(与 [detectDragGestures] 并行共存):
 * 单指按下后只观察不消费, 把单指拖动/点击让给 drag 与点击检测; 等第二指按下才激活并开始消费事件——
 * 消费后 detectDragGestures 检测到事件被消费会自行取消(onDragCancel), 双指帧不会与单指拖动竞争。
 * 若单指拖动已被 drag 接管(事件带消费标记), 本检测直接放弃本轮手势。
 * 桌面鼠标右键按下不参与(过滤 secondary 按键, 避免右键触发伪"双指"缩放)。
 * 任意一指抬起即结束(剩余手指的移动不再转成平移, 重新按下一指拖动即可)。
 */
private suspend fun PointerInputScope.detectTwoFingerZoom(
    onGestureStart: () -> Unit,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onGestureEnd: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.fastAny { it.isConsumed } || event.buttons.isSecondaryPressed) {
                return@awaitEachGesture
            }
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount >= 2) break
            if (pressedCount == 0) return@awaitEachGesture
        }
        onGestureStart()
        try {
            while (true) {
                val event = awaitPointerEvent()
                if (event.buttons.isSecondaryPressed) break
                if (event.changes.count { it.pressed } < 2) break
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (zoomChange != 1f || panChange != Offset.Zero) {
                    onGesture(event.calculateCentroid(), panChange, zoomChange)
                }
                event.changes.fastForEach { it.consume() }
            }
        } finally {
            onGestureEnd()
        }
    }
}

internal data class ImagePreviewPanBounds(
    val maxOffsetX: Float,
    val maxOffsetY: Float,
)

/** 按 ContentScale.Fit 后的实际图片尺寸计算缩放溢出，避免宽图和长图使用错误轴或越界。 */
internal fun imagePreviewPanBounds(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    imageAspectRatio: Float?,
    scale: Float,
): ImagePreviewPanBounds {
    if (
        viewportWidthPx <= 0f || viewportHeightPx <= 0f ||
        imageAspectRatio == null || imageAspectRatio <= 0f || scale <= 1f
    ) {
        return ImagePreviewPanBounds(0f, 0f)
    }
    val viewportAspectRatio = viewportWidthPx / viewportHeightPx
    val fittedWidth: Float
    val fittedHeight: Float
    if (imageAspectRatio >= viewportAspectRatio) {
        fittedWidth = viewportWidthPx
        fittedHeight = viewportWidthPx / imageAspectRatio
    } else {
        fittedHeight = viewportHeightPx
        fittedWidth = viewportHeightPx * imageAspectRatio
    }
    return ImagePreviewPanBounds(
        maxOffsetX = ((fittedWidth * scale - viewportWidthPx) / 2f).coerceAtLeast(0f),
        maxOffsetY = ((fittedHeight * scale - viewportHeightPx) / 2f).coerceAtLeast(0f),
    )
}

internal fun shouldDismissImagePreview(offsetPx: Float, viewportHeightPx: Float): Boolean =
    viewportHeightPx > 0f && offsetPx >= viewportHeightPx * 0.2f

internal fun imagePreviewBackgroundAlpha(offsetPx: Float, viewportHeightPx: Float): Float {
    if (viewportHeightPx <= 0f) return 1f
    return (1f - offsetPx.coerceAtLeast(0f) / (viewportHeightPx * 0.75f)).coerceIn(0f, 1f)
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
    /** 卡片无任何封面(posterPath 与 fallbackPosterPath 均空)时的模糊兜底背景: 部级在线头图本地路径(LOCAL_FILE)。 */
    fallbackFanartPath: String? = null,
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
                // 无任何封面且有部级头图 → 头图模糊兜底; 否则走原封面/占位逻辑(不变)。
                val fanartPath = fallbackFanartPath?.takeIf { it.isNotBlank() }
                if (posterPath.isNullOrBlank() && fallbackPosterPath.isNullOrBlank() && fanartPath != null) {
                    FanartBackdropCardImage(
                        sourceKind = sourceKind,
                        libraryId = libraryId,
                        fanartPath = fanartPath,
                        imageCacheSizeMb = imageCacheSizeMb,
                        downloader = downloader,
                        cacheSubdir = cacheSubdir,
                        placeholderText = title,
                        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                    )
                } else {
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
                }
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

/**
 * 无任何封面时的卡片底图(批次C): 部级在线头图(LOCAL_FILE)铺满 + 20dp 模糊 + 轻微暗色遮罩,
 * 上层仍是标题占位文字(与 [ScrapedImageContent] 同款"标题首字"逻辑, 深色遮罩上用白色保证可读)。
 * 头图不可用(Unavailable/加载失败)时退回纯色背景 + 文字占位(与原占位视觉一致)。
 * 注: Modifier.blur 基于 RenderEffect, Android 12(API 31)以下系统会静默降级为不模糊(原图直出),
 * 可接受; 桌面端 Skiko 恒可用。
 */
@Composable
private fun FanartBackdropCardImage(
    sourceKind: MediaSourceKind,
    libraryId: Long,
    fanartPath: String,
    imageCacheSizeMb: Int,
    downloader: suspend (PlatformFile) -> Boolean,
    cacheSubdir: String,
    placeholderText: String,
    modifier: Modifier = Modifier,
) {
    val modelState by rememberScrapedImageModel(
        sourceKind = sourceKind,
        libraryId = libraryId,
        imagePath = fanartPath,
        imagePathKind = ScrapedImagePathKind.LOCAL_FILE,
        imageCacheSizeMb = imageCacheSizeMb,
        downloader = { _, dest -> downloader(dest) },
        cacheSubdir = cacheSubdir,
        cacheName = null,
    )
    val fanartModel = (modelState as? ScrapedImageModelState.Ready)?.model
    Box(modifier = modifier) {
        if (fanartModel != null) {
            AsyncImage(
                model = fanartModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(20.dp),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
            Text(
                text = placeholderText.firstOrNull()?.toString() ?: "?",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
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
