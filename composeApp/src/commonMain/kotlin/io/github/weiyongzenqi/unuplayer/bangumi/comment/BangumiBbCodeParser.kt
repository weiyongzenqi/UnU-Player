package io.github.weiyongzenqi.unuplayer.bangumi.comment

import io.github.weiyongzenqi.unuplayer.bangumi.OFFICIAL_BANGUMI_ENDPOINTS

data class BangumiRichText(val nodes: List<BangumiRichTextNode>) {
    val hasSpoiler: Boolean get() = nodes.any(BangumiRichTextNode::containsSpoiler)
}

sealed interface BangumiRichTextNode {
    data class Text(val value: String) : BangumiRichTextNode
    data class Styled(
        val style: BangumiTextStyle,
        val children: List<BangumiRichTextNode>,
        val value: String? = null,
    ) : BangumiRichTextNode

    data class Link(val url: String, val children: List<BangumiRichTextNode>) : BangumiRichTextNode
    data class ImagePlaceholder(val url: String?) : BangumiRichTextNode
    data class Emoji(val code: String) : BangumiRichTextNode
}

enum class BangumiTextStyle { BOLD, ITALIC, UNDERLINE, STRIKE, QUOTE, SPOILER, COLOR, SIZE }

object BangumiBbCodeParser {
    fun parse(source: String, imageBaseUrl: String = OFFICIAL_BANGUMI_ENDPOINTS.imageBaseUrl): BangumiRichText {
        val truncated = source.take(MAX_INPUT_LENGTH)
        val root = Frame(tag = "", opening = "")
        val stack = mutableListOf(root)
        var index = 0
        while (index < truncated.length && root.totalNodes < MAX_NODES) {
            val emoji = parseEmojiAt(truncated, index)
            if (emoji != null) {
                stack.last().append(BangumiRichTextNode.Emoji(emoji.first))
                index = emoji.second
                continue
            }
            if (truncated[index] != '[') {
                val nextTag = truncated.indexOf('[', index).let { if (it < 0) truncated.length else it }
                val nextEmoji = truncated.indexOf('(', index).let { if (it < 0) truncated.length else it }
                val end = minOf(nextTag, nextEmoji).coerceAtLeast(index + 1)
                stack.last().appendText(truncated.substring(index, end))
                index = end
                continue
            }
            val close = truncated.indexOf(']', startIndex = index + 1)
            if (close < 0 || close - index > MAX_TAG_LENGTH) {
                stack.last().appendText("[")
                index++
                continue
            }
            val literal = truncated.substring(index, close + 1)
            val token = parseTag(literal)
            if (token == null) {
                stack.last().appendText(literal)
                index = close + 1
                continue
            }
            if (token.closing) {
                val current = stack.last()
                if (stack.size == 1 || current.tag != token.name) {
                    current.appendText(literal)
                } else {
                    stack.removeAt(stack.lastIndex)
                    stack.last().append(current.toNode())
                }
                index = close + 1
                continue
            }
            if (token.name == "img") {
                val closing = truncated.indexOf("[/img]", close + 1, ignoreCase = true)
                if (closing < 0) {
                    stack.last().appendText(literal)
                    index = close + 1
                } else {
                    val rawUrl = truncated.substring(close + 1, closing).trim()
                    stack.last().append(BangumiRichTextNode.ImagePlaceholder(safeRemoteUrl(rawUrl)))
                    index = closing + "[/img]".length
                }
                continue
            }
            // 旧版站内图床标签 [photo=ID]相对路径[/photo] → imageBaseUrl/pic/photo/l/{路径}
            // (实测 200; photo 的 value 是历史图片 ID, 无 URL 用途, 忽略)
            if (token.name == "photo") {
                val closing = truncated.indexOf("[/photo]", close + 1, ignoreCase = true)
                if (closing < 0) {
                    stack.last().appendText(literal)
                    index = close + 1
                } else {
                    val rawPath = truncated.substring(close + 1, closing).trim()
                    val url = safePhotoPath(rawPath)?.let { path ->
                        "${imageBaseUrl.trimEnd('/')}/pic/photo/l/$path"
                    }
                    stack.last().append(BangumiRichTextNode.ImagePlaceholder(url))
                    index = closing + "[/photo]".length
                }
                continue
            }
            if (stack.size >= MAX_DEPTH || !isSupportedOpening(token)) {
                stack.last().appendText(literal)
                index = close + 1
                continue
            }
            stack += Frame(token.name, literal, token.value)
            index = close + 1
        }
        if (index < truncated.length) root.appendText(truncated.substring(index).take(64) + "...")
        while (stack.size > 1) {
            val unfinished = stack.removeAt(stack.lastIndex)
            stack.last().appendText(unfinished.opening)
            unfinished.nodes.forEach(stack.last()::append)
        }
        if (source.length > MAX_INPUT_LENGTH) root.appendText("...")
        return BangumiRichText(root.nodes.toList())
    }

    /**
     * 站内 photo 图床路径校验: 只接受安全相对路径(无协议/不以 / 开头/无 .. 穿越/无查询与片段/
     * 字符集受限/长度受限), 拒绝即回落文本。
     */
    fun safePhotoPath(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.length !in 1..MAX_PHOTO_PATH_LENGTH) return null
        if (trimmed.startsWith('/') || trimmed.startsWith("\\")) return null
        if (trimmed.startsWith("//") || "://" in trimmed) return null
        if ('?' in trimmed || '#' in trimmed) return null
        if (trimmed.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        if (trimmed.any { !it.isLetterOrDigit() && it !in "./_-" }) return null
        return trimmed
    }

    fun safeRemoteUrl(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.length !in 1..MAX_URL_LENGTH) return null
        val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (!trimmed.substringAfter(':', missingDelimiterValue = "").startsWith("//")) return null
        return trimmed
    }

    private fun isSupportedOpening(token: TagToken): Boolean = when (token.name) {
        "b", "i", "u", "s", "quote", "mask", "spoiler" -> token.value == null
        "url" -> token.value == null || safeRemoteUrl(token.value) != null
        "color" -> token.value?.let(::isSafeColor) == true
        "size" -> token.value?.toIntOrNull() in MIN_TEXT_SIZE..MAX_TEXT_SIZE
        else -> false
    }

    private fun Frame.toNode(): BangumiRichTextNode = when (tag) {
        "b" -> styled(BangumiTextStyle.BOLD)
        "i" -> styled(BangumiTextStyle.ITALIC)
        "u" -> styled(BangumiTextStyle.UNDERLINE)
        "s" -> styled(BangumiTextStyle.STRIKE)
        "quote" -> styled(BangumiTextStyle.QUOTE)
        "mask", "spoiler" -> styled(BangumiTextStyle.SPOILER)
        "color" -> styled(BangumiTextStyle.COLOR, value)
        "size" -> styled(BangumiTextStyle.SIZE, value)
        "url" -> {
            val destination = value?.let(::safeRemoteUrl)
                ?: safeRemoteUrl(nodes.plainText())
            destination?.let { BangumiRichTextNode.Link(it, nodes.toList()) }
                ?: BangumiRichTextNode.Text(opening + nodes.plainText() + "[/url]")
        }
        else -> BangumiRichTextNode.Text(opening + nodes.plainText())
    }

    private fun Frame.styled(style: BangumiTextStyle, value: String? = null) =
        BangumiRichTextNode.Styled(style, nodes.toList(), value)

    private fun parseTag(literal: String): TagToken? {
        val body = literal.substring(1, literal.length - 1).trim()
        if (body.isEmpty()) return null
        val closing = body.startsWith('/')
        val content = if (closing) body.drop(1).trim() else body
        val name = content.substringBefore('=').trim().lowercase()
        if (name.isEmpty() || name.any { !it.isLetter() }) return null
        val value = content.substringAfter('=', missingDelimiterValue = "").trim().ifEmpty { null }
        if (closing && value != null) return null
        return TagToken(name, value, closing)
    }

    private fun parseEmojiAt(source: String, index: Int): Pair<String, Int>? {
        if (source[index] != '(') return null
        // 经典文字表情优先: 码含 = / : / ' / @ 等特殊字符, 不能走通用字符过滤, 按码表精确匹配
        for (textCode in CLASSIC_TEXT_STICKERS) {
            val literal = "($textCode)"
            if (source.startsWith(literal, index)) return textCode to (index + literal.length)
        }
        val close = source.indexOf(')', index + 1)
        if (close < 0 || close - index > MAX_EMOJI_LENGTH) return null
        val code = source.substring(index + 1, close)
        val validPrefix = code.startsWith("bgm") || code.startsWith("musume_") || code.startsWith("blake_")
        if (!validPrefix || code.any { !it.isLetterOrDigit() && it != '_' }) return null
        return code to (close + 1)
    }

    private fun isSafeColor(value: String): Boolean {
        val lower = value.lowercase()
        if (lower in SAFE_COLOR_NAMES) return true
        return lower.length in 4..9 && lower.first() == '#' && lower.drop(1).all { it.isDigit() || it in 'a'..'f' }
    }

    private data class TagToken(val name: String, val value: String?, val closing: Boolean)

    private class Frame(val tag: String, val opening: String, val value: String? = null) {
        val nodes = mutableListOf<BangumiRichTextNode>()
        var totalNodes: Int = 0
            private set

        fun append(node: BangumiRichTextNode) {
            if (totalNodes >= MAX_NODES) return
            if (node is BangumiRichTextNode.Text) appendText(node.value)
            else {
                nodes += node
                totalNodes++
            }
        }

        fun appendText(text: String) {
            if (text.isEmpty() || totalNodes >= MAX_NODES) return
            val previous = nodes.lastOrNull()
            if (previous is BangumiRichTextNode.Text) {
                nodes[nodes.lastIndex] = previous.copy(value = previous.value + text)
            } else {
                nodes += BangumiRichTextNode.Text(text)
                totalNodes++
            }
        }
    }

    private fun List<BangumiRichTextNode>.plainText(): String = buildString {
        fun appendNodes(nodes: List<BangumiRichTextNode>) {
            nodes.forEach { node ->
                when (node) {
                    is BangumiRichTextNode.Text -> append(node.value)
                    is BangumiRichTextNode.Styled -> appendNodes(node.children)
                    is BangumiRichTextNode.Link -> appendNodes(node.children)
                    is BangumiRichTextNode.ImagePlaceholder -> append(node.url.orEmpty())
                    is BangumiRichTextNode.Emoji -> append('(').append(node.code).append(')')
                }
            }
        }
        appendNodes(this@plainText)
    }

    private const val MAX_INPUT_LENGTH = 20_000
    private const val MAX_URL_LENGTH = 2_048
    private const val MAX_TAG_LENGTH = 96
    private const val MAX_EMOJI_LENGTH = 32
    private const val MAX_DEPTH = 8
    private const val MAX_NODES = 1_024
    private const val MIN_TEXT_SIZE = 8
    private const val MAX_TEXT_SIZE = 48
    private const val MAX_PHOTO_PATH_LENGTH = 512
    private val SAFE_COLOR_NAMES = setOf("black", "white", "red", "green", "blue", "gray", "yellow")
}

private fun BangumiRichTextNode.containsSpoiler(): Boolean = when (this) {
    is BangumiRichTextNode.Styled -> style == BangumiTextStyle.SPOILER || children.any(BangumiRichTextNode::containsSpoiler)
    is BangumiRichTextNode.Link -> children.any(BangumiRichTextNode::containsSpoiler)
    else -> false
}

/**
 * Bangumi 表情码 → 站内图片相对路径(相对 imageBaseUrl, 如 https://lain.bgm.tv)。
 * 码表来源(2026-08-14 联网核实): 官方编辑器 editor.min.css 全量类表 + bangumi/frontend
 * bbcode/convert.ts + 第三方客户端 Kazumi 语法 + 逐文件 HTTP 探测:
 * - (bgm1)~(bgm23): /img/smiles/bgm/{NN:02d}.png, 特例 (bgm11)/(bgm23) 为 .gif;
 * - (bgm24)~(bgm125): /img/smiles/tv/{NN-23:02d}.gif(共 102 张, 上界 125);
 * - (bgm200)~(bgm238): /img/smiles/tv_vs/bgm_{NN}.png(vs 系列, 全 png);
 * - (bgm500)~(bgm529): /img/smiles/tv_500/bgm_{NN}.{png|gif}(新表情包, 扩展名查表);
 * - (musume_N)/(blake_N), N∈1..118: /img/smiles/{musume|blake}/{前缀}_{NN:02d}.gif;
 * - 16 个经典文字表情((LOL)/(T_T)/(=w=) 等): /img/smiles/{1..16}.gif;
 * - BMO 合成表情((bmoC...) 等)无静态 URL → null 回落文本;
 * - 无效区间(bgm126..199 / bgm239..499 / bgm530+ / 超上界)一律 null, 不拼 404 URL。
 */
fun bangumiEmojiImagePath(code: String): String? {
    CLASSIC_TEXT_STICKER_FILES[code]?.let { return "img/smiles/$it.gif" }
    if (code.startsWith("bgm")) return bgmEmojiImagePath(code.substring(3))
    for (prefix in NUMBERED_STICKER_PREFIXES) {
        if (code.startsWith("${prefix}_")) {
            val number = code.substringAfter('_').toIntOrNull() ?: return null
            if (number !in 1..NUMBERED_STICKER_MAX) return null
            return "img/smiles/$prefix/${prefix}_${number.toString().padStart(2, '0')}.gif"
        }
    }
    return null
}

private fun bgmEmojiImagePath(numberPart: String): String? {
    val number = numberPart.toIntOrNull() ?: return null
    val name = number.toString().padStart(2, '0')
    return when {
        number in 1..10 -> "img/smiles/bgm/$name.png"
        number == 11 -> "img/smiles/bgm/11.gif"
        number in 12..22 -> "img/smiles/bgm/$name.png"
        number == 23 -> "img/smiles/bgm/23.gif"
        number in 24..125 -> "img/smiles/tv/${(number - 23).toString().padStart(2, '0')}.gif"
        number in 200..238 -> "img/smiles/tv_vs/bgm_$number.png"
        number in 500..529 -> TV_500_STICKER_EXTENSIONS[number]?.let { "img/smiles/tv_500/bgm_$number.$it" }
        else -> null
    }
}

/** 经典文字表情码(不含括号): 官方 constants.ts EMOJI_ARRAY 顺序(=A= → 1.gif ... LOL → 16.gif)。 */
internal val CLASSIC_TEXT_STICKERS: List<String> = listOf(
    "=A=", "=w=", "-w=", "S_S", "=v=", "@_@", "=W=", "TAT",
    "T_T", "='=", "=3=", "= ='", "=///=", "=.,=", ":P", "LOL",
)

private val CLASSIC_TEXT_STICKER_FILES: Map<String, Int> =
    CLASSIC_TEXT_STICKERS.mapIndexed { index, code -> code to (index + 1) }.toMap()

private val NUMBERED_STICKER_PREFIXES = listOf("musume", "blake")
private const val NUMBERED_STICKER_MAX = 118

/** tv_500 新表情包扩展名查表(实测 30 张, gif/png 混合)。 */
private val TV_500_STICKER_EXTENSIONS: Map<Int, String> = mapOf(
    500 to "gif", 501 to "gif", 502 to "png", 503 to "png", 504 to "png",
    505 to "gif", 506 to "png", 507 to "png", 508 to "png", 509 to "png",
    510 to "png", 511 to "png", 512 to "png", 513 to "png", 514 to "png",
    515 to "gif", 516 to "gif", 517 to "gif", 518 to "gif", 519 to "gif",
    520 to "png", 521 to "gif", 522 to "gif", 523 to "gif", 524 to "png",
    525 to "png", 526 to "png", 527 to "png", 528 to "png", 529 to "png",
)
