package io.github.weiyongzenqi.unuplayer.bangumi.comment

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
    fun parse(source: String): BangumiRichText {
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
        val close = source.indexOf(')', index + 1)
        if (close < 0 || close - index > MAX_EMOJI_LENGTH) return null
        val code = source.substring(index + 1, close)
        val validPrefix = code.startsWith("bgm") || code.startsWith("musume_")
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
    private val SAFE_COLOR_NAMES = setOf("black", "white", "red", "green", "blue", "gray", "yellow")
}

private fun BangumiRichTextNode.containsSpoiler(): Boolean = when (this) {
    is BangumiRichTextNode.Styled -> style == BangumiTextStyle.SPOILER || children.any(BangumiRichTextNode::containsSpoiler)
    is BangumiRichTextNode.Link -> children.any(BangumiRichTextNode::containsSpoiler)
    else -> false
}
