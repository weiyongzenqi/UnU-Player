package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiImageUrlPolicy
import io.github.weiyongzenqi.unuplayer.bangumi.OFFICIAL_BANGUMI_ENDPOINTS
import io.github.weiyongzenqi.unuplayer.bangumi.bangumiContentImageUrlPolicy
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiRichText
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiRichTextNode
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiTextStyle
import io.github.weiyongzenqi.unuplayer.bangumi.comment.bangumiEmojiImagePath
import io.github.weiyongzenqi.unuplayer.bangumi.comment.loadBangumiContentImage
import io.github.weiyongzenqi.unuplayer.bangumi.comment.shouldRestoreBangumiContentImage
import io.github.weiyongzenqi.unuplayer.bangumi.comment.invalidateBangumiContentImage
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.library.ScrapedImagePreviewDialog
import io.github.weiyongzenqi.unuplayer.library.rememberScrapedImageSaveController
import io.ktor.http.Url
import kotlinx.coroutines.launch

// ---------- 分段 ----------

internal sealed interface RichTextSegment {
    data class TextPart(val nodes: List<BangumiRichTextNode>) : RichTextSegment
    data class ImagePart(val url: String) : RichTextSegment
}

/**
 * 把富文本节点切成 文本段/图片段 交替序列:
 * - 仅顶层 ImagePlaceholder 且 url 非 null 处切分, 连续顶层图片各成独立 ImagePart;
 * - 嵌套在 Styled/Link 里的图片不切分(留在文本段, 保住 SPOILER 遮罩/quote 包裹);
 * - url == null 的 ImagePlaceholder 留在文本段渲染为 "[无效图片]";
 * - 文本段为空不产出(开头/结尾即图片、连续图片之间)。
 */
internal fun splitRichTextSegments(nodes: List<BangumiRichTextNode>): List<RichTextSegment> {
    val segments = mutableListOf<RichTextSegment>()
    val textBuffer = mutableListOf<BangumiRichTextNode>()

    fun flushText() {
        if (textBuffer.isNotEmpty()) {
            segments += RichTextSegment.TextPart(textBuffer.toList())
            textBuffer.clear()
        }
    }

    nodes.forEach { node ->
        val image = node as? BangumiRichTextNode.ImagePlaceholder
        if (image?.url != null) {
            flushText()
            segments += RichTextSegment.ImagePart(image.url)
        } else {
            textBuffer += node
        }
    }
    flushText()
    return segments
}

/**
 * 把富文本节点切成 文本段/图片段 交替序列, 并同步为每段产出带唯一前缀的表情槽位。
 * 槽位 contentId 带段序前缀("s{index}-")保证跨段全局唯一——多条文本段各含表情时,
 * 共享 inlineContentMap 不会因同编号覆盖而错图(修复跨段 contentId 冲突缺陷)。
 */
internal data class RichTextSegmentWithSlots(
    val segment: RichTextSegment,
    val emojiSlots: List<EmojiSlot>, // TextPart 的槽位(带唯一前缀); ImagePart 恒为空
)

internal fun splitRichTextSegmentsWithSlots(
    nodes: List<BangumiRichTextNode>,
    emojiBaseUrl: String,
): List<RichTextSegmentWithSlots> = splitRichTextSegments(nodes).mapIndexed { index, segment ->
    RichTextSegmentWithSlots(
        segment = segment,
        emojiSlots = when (segment) {
            is RichTextSegment.TextPart -> collectEmojiSlots(segment.nodes, emojiBaseUrl, idPrefix = "s$index-")
            is RichTextSegment.ImagePart -> emptyList()
        },
    )
}

// ---------- 表情槽位 ----------

internal data class EmojiSlot(val contentId: String, val url: String, val code: String)

/** 全树先序遍历(与 AnnotatedString 构建遍历结构一致), 每个有效 Emoji 占一个槽; 无效表情不占槽。 */
internal fun collectEmojiSlots(
    nodes: List<BangumiRichTextNode>,
    emojiBaseUrl: String,
    idPrefix: String = "",
): List<EmojiSlot> {
    val base = emojiBaseUrl.trimEnd('/')
    val slots = mutableListOf<EmojiSlot>()

    fun visit(list: List<BangumiRichTextNode>) {
        list.forEach { node ->
            when (node) {
                is BangumiRichTextNode.Styled -> visit(node.children)
                is BangumiRichTextNode.Link -> visit(node.children)
                is BangumiRichTextNode.Emoji -> {
                    val path = bangumiEmojiImagePath(node.code)
                    if (path != null) {
                        slots += EmojiSlot(
                            contentId = "${idPrefix}emoji-${slots.size}",
                            url = "$base/$path",
                            code = node.code,
                        )
                    }
                }
                else -> Unit
            }
        }
    }
    visit(nodes)
    return slots
}

/** 与 [collectEmojiSlots] 同一遍历规则数有效表情个数(SPOILER 隐藏跳过子树时光标推进用)。 */
internal fun countValidEmojis(nodes: List<BangumiRichTextNode>): Int {
    var count = 0

    fun visit(list: List<BangumiRichTextNode>) {
        list.forEach { node ->
            when (node) {
                is BangumiRichTextNode.Styled -> visit(node.children)
                is BangumiRichTextNode.Link -> visit(node.children)
                is BangumiRichTextNode.Emoji -> if (bangumiEmojiImagePath(node.code) != null) count++
                else -> Unit
            }
        }
    }
    visit(nodes)
    return count
}

// ---------- 折叠截断 ----------

/** 富文本纯文本总长: Text 按字符数, Emoji 与 ImagePlaceholder 各算 1, 递归计入 Styled/Link 子节点。 */
internal fun richTextPlainLength(nodes: List<BangumiRichTextNode>): Int {
    var total = 0

    fun visit(list: List<BangumiRichTextNode>) {
        list.forEach { node ->
            when (node) {
                is BangumiRichTextNode.Text -> total += node.value.length
                is BangumiRichTextNode.Styled -> visit(node.children)
                is BangumiRichTextNode.Link -> visit(node.children)
                is BangumiRichTextNode.Emoji, is BangumiRichTextNode.ImagePlaceholder -> total += 1
            }
        }
    }
    visit(nodes)
    return total
}

private fun containsSpoiler(nodes: List<BangumiRichTextNode>): Boolean = nodes.any { node ->
    when (node) {
        is BangumiRichTextNode.Styled ->
            node.style == BangumiTextStyle.SPOILER || containsSpoiler(node.children)
        is BangumiRichTextNode.Link -> containsSpoiler(node.children)
        else -> false
    }
}

/**
 * 按字符预算截断节点树(先序, 共享预算): 预算耗尽即停; 截断点若在 Text 中部则截断并加 "…";
 * 被截空/完全超预算的 Styled/Link 节点丢弃; 总长不超过 [maxChars] 时返回原 nodes(不加省略号)。
 * 截断点恰在节点边界且后续还有内容时, "…" 补在最后一个输出 Text 尾部(末尾不是 Text 则追加独立 Text 节点)。
 */
internal fun truncatedRichTextNodes(
    nodes: List<BangumiRichTextNode>,
    maxChars: Int,
): List<BangumiRichTextNode> {
    require(maxChars >= 0) { "maxChars 不能为负" }
    if (richTextPlainLength(nodes) <= maxChars) return nodes

    var remaining = maxChars
    var stopped = false // 预算耗尽后置位, 其后所有内容丢弃
    var midTextCut = false // Text 中部截断时已内联补过 "…"

    fun truncate(list: List<BangumiRichTextNode>): List<BangumiRichTextNode> {
        if (stopped) return emptyList()
        val out = mutableListOf<BangumiRichTextNode>()
        for (node in list) {
            if (stopped) break
            when (node) {
                is BangumiRichTextNode.Text -> {
                    if (remaining <= 0) {
                        stopped = true
                    } else if (node.value.length <= remaining) {
                        out += node
                        remaining -= node.value.length
                    } else {
                        out += node.copy(value = node.value.take(remaining) + "…")
                        remaining = 0
                        stopped = true
                        midTextCut = true
                    }
                }
                is BangumiRichTextNode.Emoji,
                is BangumiRichTextNode.ImagePlaceholder,
                -> {
                    if (remaining > 0) {
                        out += node
                        remaining -= 1
                    } else {
                        stopped = true
                    }
                }
                is BangumiRichTextNode.Styled -> {
                    val children = truncate(node.children)
                    if (children.isNotEmpty()) out += node.copy(children = children)
                }
                is BangumiRichTextNode.Link -> {
                    val children = truncate(node.children)
                    if (children.isNotEmpty()) out += node.copy(children = children)
                }
            }
        }
        return out
    }

    val result = truncate(nodes).toMutableList()
    if (stopped && !midTextCut && result.isNotEmpty()) {
        val last = result.last()
        if (last is BangumiRichTextNode.Text) {
            result[result.lastIndex] = last.copy(value = last.value + "…")
        } else {
            result += BangumiRichTextNode.Text("…")
        }
    }
    return result
}

// ---------- 文本段 AnnotatedString ----------

/**
 * 把节点列表构建为 AnnotatedString。[emojiSlots] 须由 [collectEmojiSlots] 按同一遍历顺序产出
 * (对该节点列表所在分段的槽位切片)。
 *
 * 表情光标对齐: SPOILER 隐藏时跳过整个子树, 光标必须同步推进越过子树内的全部有效表情,
 * 否则后续可见表情的 contentId 会错位(表情张冠李戴)。
 */
internal fun buildRichTextAnnotatedString(
    nodes: List<BangumiRichTextNode>,
    revealSpoiler: Boolean,
    emojiSlots: List<EmojiSlot>,
    onSurfaceVariant: Color,
    surfaceVariant: Color,
    allowedImageHosts: Set<String> = OFFICIAL_BANGUMI_ENDPOINTS.allowedAvatarHosts,
): AnnotatedString = buildAnnotatedString {
    var emojiCursor = 0

    fun appendNodes(nodes: List<BangumiRichTextNode>) {
        nodes.forEach { node ->
            when (node) {
                is BangumiRichTextNode.Text -> append(node.value)
                is BangumiRichTextNode.Emoji -> {
                    val slot = emojiSlots.getOrNull(emojiCursor)
                    if (slot != null && slot.code == node.code) {
                        appendInlineContent(slot.contentId, "(${node.code})")
                        emojiCursor++
                    } else {
                        append("(${node.code})")
                    }
                }
                is BangumiRichTextNode.ImagePlaceholder -> append(
                    when (val imageUrl = node.url) {
                        null -> "[无效图片]"
                        else -> when (bangumiContentImageUrlPolicy(imageUrl, allowedImageHosts)) {
                            BangumiImageUrlPolicy.REJECT -> "[图片已拦截]"
                            BangumiImageUrlPolicy.AUTO_LOAD -> "[图片]"
                            BangumiImageUrlPolicy.CLICK_TO_LOAD -> "[远程图片，未自动加载]"
                        }
                    },
                )
                is BangumiRichTextNode.Link -> withStyle(
                    SpanStyle(color = Color(0xFF1976D2), textDecoration = TextDecoration.Underline),
                ) {
                    appendNodes(node.children)
                }
                is BangumiRichTextNode.Styled -> {
                    if (node.style == BangumiTextStyle.SPOILER && !revealSpoiler) {
                        append("[点击显示剧透]")
                        emojiCursor += countValidEmojis(node.children)
                    } else {
                        val style = when (node.style) {
                            BangumiTextStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                            BangumiTextStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                            BangumiTextStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                            BangumiTextStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                            BangumiTextStyle.QUOTE -> SpanStyle(color = onSurfaceVariant)
                            BangumiTextStyle.COLOR -> node.value?.let(::safeTextColor)?.let { SpanStyle(color = it) } ?: SpanStyle()
                            BangumiTextStyle.SIZE -> node.value?.toIntOrNull()?.takeIf { it in 8..48 }?.let { SpanStyle(fontSize = it.sp) } ?: SpanStyle()
                            BangumiTextStyle.SPOILER -> SpanStyle(background = surfaceVariant)
                        }
                        if (node.style == BangumiTextStyle.QUOTE) append("> ")
                        withStyle(style) { appendNodes(node.children) }
                    }
                }
            }
        }
    }
    appendNodes(nodes)
}

private fun safeTextColor(value: String): Color? = when (value.lowercase()) {
    "black" -> Color.Black
    "white" -> Color.White
    "red" -> Color.Red
    "green" -> Color.Green
    "blue" -> Color.Blue
    "gray" -> Color.Gray
    "yellow" -> Color.Yellow
    else -> value.takeIf {
        it.length in 7..9 && it.first() == '#' && it.drop(1).all { char -> char.isDigit() || char.lowercaseChar() in 'a'..'f' }
    }?.let { Color(it.removePrefix("#").toLong(16) or if (it.length == 7) 0xFF000000 else 0L) }
}

// ---------- 组合渲染 ----------

/**
 * Bangumi 富文本渲染入口: 文本段(内联表情 + 样式)与 [img] 图片段纵向排列。
 * [emojiBaseUrl] / [allowedImageHosts] 由上层(详情页/播放器)按数据源端点传入。
 */
@Composable
internal fun BangumiRichTextText(
    richText: BangumiRichText,
    key: String,
    small: Boolean = false,
    emojiBaseUrl: String = OFFICIAL_BANGUMI_ENDPOINTS.imageBaseUrl,
    allowedImageHosts: Set<String> = OFFICIAL_BANGUMI_ENDPOINTS.allowedAvatarHosts,
    maxCollapsedChars: Int = 200,
) {
    var revealSpoiler by remember(key) { mutableStateOf(false) }
    val plainLength = remember(richText) { richTextPlainLength(richText.nodes) }
    val collapsible = plainLength > maxCollapsedChars
    var expanded by remember(key) { mutableStateOf(false) }
    val renderNodes = remember(richText, collapsible, expanded, maxCollapsedChars) {
        if (collapsible && !expanded) truncatedRichTextNodes(richText.nodes, maxCollapsedChars) else richText.nodes
    }
    val renderHasSpoiler = remember(renderNodes) { containsSpoiler(renderNodes) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val segmented = remember(renderNodes, emojiBaseUrl) { splitRichTextSegmentsWithSlots(renderNodes, emojiBaseUrl) }
    val inlineContentMap = remember(segmented) {
        segmented.flatMap { it.emojiSlots }.associate { slot ->
            slot.contentId to InlineTextContent(
                placeholder = Placeholder(
                    width = 22.sp,
                    height = 22.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                ),
            ) {
                BangumiEmojiImage(slot.url, slot.code, allowedImageHosts)
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segmented.forEach { entry ->
            when (val segment = entry.segment) {
                is RichTextSegment.TextPart -> {
                    val text = remember(
                        segment,
                        entry.emojiSlots,
                        revealSpoiler,
                        onSurfaceVariant,
                        surfaceVariant,
                    ) {
                        buildRichTextAnnotatedString(
                            nodes = segment.nodes,
                            revealSpoiler = revealSpoiler,
                            emojiSlots = entry.emojiSlots,
                            onSurfaceVariant = onSurfaceVariant,
                            surfaceVariant = surfaceVariant,
                            allowedImageHosts = allowedImageHosts,
                        )
                    }
                    Text(
                        text = text,
                        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = onSurface,
                        modifier = if (renderHasSpoiler) Modifier.clickable { revealSpoiler = !revealSpoiler } else Modifier,
                        inlineContent = inlineContentMap,
                    )
                }
                is RichTextSegment.ImagePart -> BangumiContentImageBlock(segment.url, allowedImageHosts)
            }
        }
        if (collapsible) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun BangumiEmojiImage(url: String, code: String, allowedImageHosts: Set<String>) {
    var state by remember(url, allowedImageHosts) {
        mutableStateOf<EmojiImageUiState>(EmojiImageUiState.Idle)
    }
    val scope = rememberCoroutineScope()
    fun load() {
        scope.launch {
            state = EmojiImageUiState.Loading
            state = runSuspendCatching { loadBangumiContentImage(url, allowedImageHosts) }.fold(
                onSuccess = { EmojiImageUiState.Loaded(it) },
                onFailure = { EmojiImageUiState.Failed },
            )
        }
    }
    LaunchedEffect(url, allowedImageHosts) { load() }
    val platformContext = LocalPlatformContext.current
    val bytes = (state as? EmojiImageUiState.Loaded)?.bytes
    val imageRequest = remember(bytes, platformContext) {
        bytes?.let {
            ImageRequest.Builder(platformContext)
                .data(it)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
        }
    }
    if (imageRequest != null) {
        AsyncImage(
            model = imageRequest,
            contentDescription = "表情 $code",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(22.dp),
        )
    } else if (state is EmojiImageUiState.Failed) {
        Text(
            "($code)",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.size(22.dp).clickable(onClick = ::load),
        )
    } else {
        Text("($code)", style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

private sealed interface EmojiImageUiState {
    data object Idle : EmojiImageUiState
    data object Loading : EmojiImageUiState
    data class Loaded(val bytes: ByteArray) : EmojiImageUiState
    data object Failed : EmojiImageUiState
}

// ---------- [img] 内容图片块 ----------

private sealed interface ContentImageUiState {
    data object Idle : ContentImageUiState
    data object Loading : ContentImageUiState
    data class Loaded(val bytes: ByteArray) : ContentImageUiState
    data object Failed : ContentImageUiState
}

/**
 * 评论正文 [img] 图片块, 按 [bangumiContentImageUrlPolicy] 分三档:
 * REJECT(不可点占位) / AUTO_LOAD(自动加载) / CLICK_TO_LOAD(点击加载外链)。
 */
@Composable
internal fun BangumiContentImageBlock(url: String, allowedHosts: Set<String>) {
    val policy = remember(url, allowedHosts) { bangumiContentImageUrlPolicy(url, allowedHosts) }
    var state by remember(url, allowedHosts) { mutableStateOf<ContentImageUiState>(ContentImageUiState.Idle) }
    var previewVisible by remember(url) { mutableStateOf(false) }
    val saveController = rememberScrapedImageSaveController()
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            state = ContentImageUiState.Loading
            state = runSuspendCatching { loadBangumiContentImage(url, allowedHosts) }.fold(
                onSuccess = { ContentImageUiState.Loaded(it) },
                onFailure = { ContentImageUiState.Failed },
            )
        }
    }

    LaunchedEffect(url, allowedHosts, policy) {
        if (policy == BangumiImageUrlPolicy.AUTO_LOAD || shouldRestoreBangumiContentImage(url, allowedHosts)) {
            load()
        }
    }

    when (val current = state) {
        is ContentImageUiState.Loaded -> LoadedContentImage(
            bytes = current.bytes,
            url = url,
            allowedHosts = allowedHosts,
            onDecodeError = { state = ContentImageUiState.Failed },
            onClickPreview = { previewVisible = true },
        )
        is ContentImageUiState.Loading -> ContentImageLoadingBox()
        is ContentImageUiState.Failed -> ContentImageBox("图片加载失败，点击重试", filled = false) { load() }
        is ContentImageUiState.Idle -> when (policy) {
            BangumiImageUrlPolicy.REJECT -> ContentImageBox("[图片已拦截]", filled = false, onClick = null)
            BangumiImageUrlPolicy.AUTO_LOAD -> ContentImageLoadingBox()
            BangumiImageUrlPolicy.CLICK_TO_LOAD -> ContentImageBox(
                "点击加载外部图片 · ${contentImageHostLabel(url)}",
                filled = true,
            ) { load() }
        }
    }

    if (previewVisible) {
        (state as? ContentImageUiState.Loaded)?.let { loaded ->
            ScrapedImagePreviewDialog(
                model = loaded.bytes,
                contentDescription = "评论图片",
                saving = saveController.saving,
                onSave = { saveController.save(loaded.bytes, bangumiContentImageFileStem(url)) },
                onDismiss = { previewVisible = false },
            )
        }
    }
}

@Composable
private fun LoadedContentImage(
    bytes: ByteArray,
    url: String,
    allowedHosts: Set<String>,
    onDecodeError: () -> Unit,
    onClickPreview: () -> Unit,
) {
    val platformContext = LocalPlatformContext.current
    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(platformContext)
            .data(bytes)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build(),
    )
    // coil3 的 painter.state 是 StateFlow(非快照 State), 必须 collectAsState 订阅后才能拿到最新解码状态;
    // 直接 `painter.state is State.Error` 恒 false(编译器已告警), 解码失败分支从未生效过。
    val painterState by painter.state.collectAsState()
    // 观察到解码错误立即失效共享缓存(坏字节不留驻 10 分钟)并切到父级单一失败态,
    // 用户随后一次点击"图片加载失败，点击重试"即完成重载(此前需两次点击: 第一次只失效+换占位)。
    LaunchedEffect(painterState) {
        if (painterState is AsyncImagePainter.State.Error) {
            invalidateBangumiContentImage(url, allowedHosts)
            onDecodeError()
        }
    }
    val intrinsicSize = painter.intrinsicSize
    if (painterState is AsyncImagePainter.State.Error) {
        // 兜底分支: LaunchedEffect 尚未执行前的一帧, 点击同样切失败态
        ContentImageBox("图片解码失败，点击重试", filled = false, onClick = onDecodeError)
    } else if (intrinsicSize.width > 0f && intrinsicSize.height > 0f) {
        val ratio = (intrinsicSize.width / intrinsicSize.height).coerceIn(0.5f, 3f)
        Image(
            painter = painter,
            contentDescription = "评论图片",
            contentScale = ContentScale.FillWidth,
            // 比例被钳到 [0.5,3] 定高; 图片按真实比例 FillWidth 绘制会溢出容器(如竖屏截图 0.46),
            // clipToBounds 防止溢出绘制覆盖相邻评论
            modifier = Modifier.fillMaxWidth().aspectRatio(ratio).clipToBounds().clickable(onClick = onClickPreview),
        )
    } else {
        // 解码中(painter 未就绪)先占位
        Box(
            Modifier.fillMaxWidth().height(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ContentImageLoadingBox() {
    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(16.dp))
    }
}

@Composable
private fun ContentImageBox(label: String, filled: Boolean, onClick: (() -> Unit)?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(
                if (filled) Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun contentImageHostLabel(url: String): String {
    val host = runCatching { Url(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
    return host ?: url.take(40)
}

/** 从评论图片 URL 派生保存文件名主干(取路径尾段并去扩展名), 解析失败/空路径/纯点主干回落通用名。 */
internal fun bangumiContentImageFileStem(url: String): String {
    // pathSegments 已按百分号解码(encodedPath 会让中文/空格外链保存出 '%E5%9B%BE' 形态文件名)
    val base = runCatching { Url(url).pathSegments }.getOrNull()
        ?.lastOrNull()?.takeIf { it.isNotBlank() } ?: return "Bangumi_Comment_Image"
    val stem = base.substringBeforeLast('.', base)
    return if (stem.isBlank() || stem.all { it == '.' }) "Bangumi_Comment_Image" else stem
}
