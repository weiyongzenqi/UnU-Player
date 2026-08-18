package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiRichTextNode
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiTextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// compose 的 appendInlineContent 实现 = pushStringAnnotation(tag, contentId) + append(alternateText),
// tag 常量即 androidx.compose.foundation.text.INLINE_CONTENT_TAG(internal), 测试用字面量读取真实引用的 id。
private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

class BangumiRichTextRenderTest {

    private fun text(value: String) = BangumiRichTextNode.Text(value)
    private fun emoji(code: String) = BangumiRichTextNode.Emoji(code)
    private fun img(url: String?) = BangumiRichTextNode.ImagePlaceholder(url)
    private fun spoiler(vararg children: BangumiRichTextNode) =
        BangumiRichTextNode.Styled(BangumiTextStyle.SPOILER, children.toList())

    private fun quote(vararg children: BangumiRichTextNode) =
        BangumiRichTextNode.Styled(BangumiTextStyle.QUOTE, children.toList())

    private fun inlineIds(annotated: AnnotatedString): List<String> =
        annotated.getStringAnnotations(INLINE_CONTENT_TAG, 0, annotated.length).map { it.item }

    // ---------- splitRichTextSegments ----------

    @Test
    fun `顶层图片在文本间切分为三段`() {
        val segments = splitRichTextSegments(
            listOf(text("开头"), img("https://lain.bgm.tv/pic/a.png"), text("结尾")),
        )
        assertEquals(3, segments.size)
        assertIs<RichTextSegment.TextPart>(segments[0])
        assertEquals(
            "https://lain.bgm.tv/pic/a.png",
            assertIs<RichTextSegment.ImagePart>(segments[1]).url,
        )
        assertIs<RichTextSegment.TextPart>(segments[2])
    }

    @Test
    fun `开头即图片不产出空文本段`() {
        val segments = splitRichTextSegments(listOf(img("https://lain.bgm.tv/pic/b.png"), text("后面")))
        assertEquals(2, segments.size)
        assertIs<RichTextSegment.ImagePart>(segments[0])
        assertIs<RichTextSegment.TextPart>(segments[1])
    }

    @Test
    fun `结尾即图片不产出空文本段`() {
        val segments = splitRichTextSegments(listOf(text("开头"), img("https://lain.bgm.tv/pic/c.png")))
        assertEquals(2, segments.size)
        assertIs<RichTextSegment.TextPart>(segments[0])
        assertIs<RichTextSegment.ImagePart>(segments[1])
    }

    @Test
    fun `连续两图产出相邻两个图片段`() {
        val segments = splitRichTextSegments(
            listOf(img("https://lain.bgm.tv/pic/a.png"), img("https://lain.bgm.tv/pic/b.png")),
        )
        assertEquals(2, segments.size)
        assertIs<RichTextSegment.ImagePart>(segments[0])
        assertIs<RichTextSegment.ImagePart>(segments[1])
    }

    @Test
    fun `quote 内嵌套图片留在文本段`() {
        val nested = img("https://lain.bgm.tv/pic/n.png")
        val segments = splitRichTextSegments(listOf(quote(nested)))
        val part = assertIs<RichTextSegment.TextPart>(segments.single())
        val styled = assertIs<BangumiRichTextNode.Styled>(part.nodes.single())
        assertEquals(BangumiTextStyle.QUOTE, styled.style)
        assertEquals(nested, styled.children.single())
    }

    @Test
    fun `spoiler 内嵌套图片留在文本段`() {
        val nested = img("https://lain.bgm.tv/pic/n.png")
        val segments = splitRichTextSegments(listOf(spoiler(nested)))
        val part = assertIs<RichTextSegment.TextPart>(segments.single())
        val styled = assertIs<BangumiRichTextNode.Styled>(part.nodes.single())
        assertEquals(BangumiTextStyle.SPOILER, styled.style)
        assertEquals(nested, styled.children.single())
    }

    @Test
    fun `url 为 null 的图片占位不切分`() {
        val segments = splitRichTextSegments(listOf(text("a"), img(null), text("b")))
        val part = assertIs<RichTextSegment.TextPart>(segments.single())
        assertEquals(3, part.nodes.size)
        assertEquals(img(null), part.nodes[1])
    }

    @Test
    fun `无图片产出单个文本段`() {
        val segments = splitRichTextSegments(listOf(text("a"), emoji("bgm24")))
        assertIs<RichTextSegment.TextPart>(segments.single())
    }

    // ---------- collectEmojiSlots ----------

    @Test
    fun `bgm24 入槽且拼接站内路径`() {
        val slots = collectEmojiSlots(listOf(emoji("bgm24")), "https://lain.bgm.tv")
        assertEquals(1, slots.size)
        assertEquals("emoji-0", slots.single().contentId)
        assertEquals("bgm24", slots.single().code)
        assertEquals("https://lain.bgm.tv/img/smiles/tv/01.gif", slots.single().url)
    }

    @Test
    fun `无效区间与畸形表情码不入槽`() {
        val slots = collectEmojiSlots(listOf(emoji("bgm150"), emoji("musume_119"), emoji("bgm24a")), "https://lain.bgm.tv")
        assertTrue(slots.isEmpty())
    }

    @Test
    fun `同 code 重复出现各自独立槽`() {
        val slots = collectEmojiSlots(listOf(emoji("bgm24"), emoji("bgm24")), "https://lain.bgm.tv")
        assertEquals(2, slots.size)
        assertEquals(listOf("emoji-0", "emoji-1"), slots.map { it.contentId })
        assertEquals(slots[0].url, slots[1].url)
    }

    @Test
    fun `base 带尾斜杠不产生双斜杠`() {
        val slots = collectEmojiSlots(listOf(emoji("bgm24")), "https://lain.bgm.tv/")
        assertEquals("https://lain.bgm.tv/img/smiles/tv/01.gif", slots.single().url)
    }

    @Test
    fun `槽位按全树先序产出且无效表情不占槽`() {
        val nodes = listOf(spoiler(emoji("bgm150"), emoji("bgm24")), emoji("bgm25"))
        val slots = collectEmojiSlots(nodes, "https://lain.bgm.tv")
        assertEquals(listOf("bgm24", "bgm25"), slots.map { it.code })
        assertEquals(listOf("emoji-0", "emoji-1"), slots.map { it.contentId })
    }

    // ---------- buildRichTextAnnotatedString ----------

    private fun build(nodes: List<BangumiRichTextNode>, revealSpoiler: Boolean): AnnotatedString =
        buildRichTextAnnotatedString(
            nodes = nodes,
            revealSpoiler = revealSpoiler,
            emojiSlots = collectEmojiSlots(nodes, "https://lain.bgm.tv"),
            onSurfaceVariant = Color(0xFF444444),
            surfaceVariant = Color(0xFF222222),
        )

    @Test
    fun `spoiler 隐藏时其后可见表情 id 不错位`() {
        val nodes = listOf(spoiler(emoji("bgm24")), emoji("bgm25"), emoji("bgm26"))
        val hidden = build(nodes, revealSpoiler = false)
        assertTrue(hidden.text.contains("[点击显示剧透]"))
        assertFalse(hidden.text.contains("(bgm24)"))
        assertTrue(hidden.text.contains("(bgm25)"))
        assertTrue(hidden.text.contains("(bgm26)"))
        // 可见表情必须引用 emoji-1/emoji-2(跳过 spoiler 子树内的 emoji-0), 错位即张冠李戴
        assertEquals(listOf("emoji-1", "emoji-2"), inlineIds(hidden))

        val revealed = build(nodes, revealSpoiler = true)
        assertFalse(revealed.text.contains("[点击显示剧透]"))
        assertEquals(listOf("emoji-0", "emoji-1", "emoji-2"), inlineIds(revealed))
    }

    @Test
    fun `spoiler 隐藏时不含子树文本内容`() {
        val nodes = listOf(spoiler(text("秘密内容"), emoji("bgm24")), text("可见"))
        val hidden = build(nodes, revealSpoiler = false)
        assertTrue(hidden.text.contains("[点击显示剧透]"))
        assertFalse(hidden.text.contains("秘密内容"))
        assertTrue(hidden.text.contains("可见"))
        assertTrue(inlineIds(hidden).isEmpty())

        val revealed = build(nodes, revealSpoiler = true)
        assertTrue(revealed.text.contains("秘密内容"))
        assertEquals(listOf("emoji-0"), inlineIds(revealed))
    }

    @Test
    fun `无效表情输出文本回退不占位`() {
        val nodes = listOf(text("哈哈"), emoji("bgm150"), text("之后"))
        val annotated = build(nodes, revealSpoiler = false)
        assertEquals("哈哈(bgm150)之后", annotated.text)
        assertTrue(inlineIds(annotated).isEmpty())
    }

    @Test
    fun `有效表情渲染为 inline content`() {
        val nodes = listOf(text("前"), emoji("bgm24"), text("后"))
        val annotated = build(nodes, revealSpoiler = false)
        assertEquals(listOf("emoji-0"), inlineIds(annotated))
        assertTrue(annotated.text.contains("(bgm24)"))
    }

    @Test
    fun `嵌套图片占位渲染为图片文本`() {
        val nodes = listOf(quote(img("https://lain.bgm.tv/pic/n.png")), img(null))
        val annotated = build(nodes, revealSpoiler = false)
        assertTrue(annotated.text.contains("[图片]"))
        assertTrue(annotated.text.contains("[无效图片]"))
        assertTrue(inlineIds(annotated).isEmpty())
    }

    // ---------- richTextPlainLength ----------

    @Test
    fun `纯文本长度统计字符数emoji与嵌套图片各算1`() {
        val nodes = listOf(
            text("你好"),
            emoji("bgm24"),
            spoiler(text("abc"), img("https://lain.bgm.tv/pic/x.png")),
            quote(text("xy")),
        )
        // 2(你好) + 1(emoji) + 3(abc) + 1(嵌套图片) + 2(xy)
        assertEquals(9, richTextPlainLength(nodes))
    }

    @Test
    fun `空列表长度为0`() {
        assertEquals(0, richTextPlainLength(emptyList()))
    }

    // ---------- truncatedRichTextNodes ----------

    @Test
    fun `长文本跨多节点截断且截断点补省略号`() {
        val result = truncatedRichTextNodes(listOf(text("aaaa"), text("bbbb")), maxChars = 3)
        assertEquals(1, result.size, "截断点后的节点全部消失")
        assertEquals("aaa…", assertIs<BangumiRichTextNode.Text>(result.single()).value)
    }

    @Test
    fun `截断点恰在节点边界时最后一个Text补省略号`() {
        val result = truncatedRichTextNodes(listOf(text("ab"), text("cde")), maxChars = 2)
        assertEquals(1, result.size)
        assertEquals("ab…", assertIs<BangumiRichTextNode.Text>(result.single()).value)
    }

    @Test
    fun `恰好等于预算时返回完整无省略号`() {
        val nodes = listOf(text("abc"), emoji("bgm24"))
        val result = truncatedRichTextNodes(nodes, maxChars = 4)
        assertEquals(nodes, result)
        assertEquals("abc", assertIs<BangumiRichTextNode.Text>(result.first()).value)
    }

    @Test
    fun `完全超预算的Styled节点被丢弃`() {
        val result = truncatedRichTextNodes(listOf(text("aa"), quote(text("bb")), text("c")), maxChars = 2)
        assertTrue(result.none { it is BangumiRichTextNode.Styled })
        assertEquals(1, result.size)
        assertEquals("aa…", assertIs<BangumiRichTextNode.Text>(result.single()).value)
    }

    @Test
    fun `Emoji消耗预算且边界上保留或丢弃`() {
        val nodes = listOf(emoji("bgm24"), emoji("bgm25"), text("x"))
        val kept = truncatedRichTextNodes(nodes, maxChars = 2)
        assertEquals(3, kept.size)
        assertEquals(nodes[0], kept[0])
        assertEquals(nodes[1], kept[1])
        assertEquals("…", assertIs<BangumiRichTextNode.Text>(kept[2]).value)

        val dropped = truncatedRichTextNodes(nodes, maxChars = 1)
        assertEquals(2, dropped.size)
        assertEquals(nodes[0], dropped[0], "预算边界内的第一个表情保留")
        assertEquals("…", assertIs<BangumiRichTextNode.Text>(dropped[1]).value)
    }

    @Test
    fun `嵌套在Styled内的文本被截断后Styled仍在`() {
        val result = truncatedRichTextNodes(listOf(quote(text("aaaaaaaa"))), maxChars = 3)
        val styled = assertIs<BangumiRichTextNode.Styled>(result.single())
        assertEquals(BangumiTextStyle.QUOTE, styled.style)
        assertEquals("aaa…", assertIs<BangumiRichTextNode.Text>(styled.children.single()).value)
    }

    @Test
    fun `空列表与零预算边界`() {
        assertEquals(emptyList(), truncatedRichTextNodes(emptyList(), maxChars = 0))
        assertEquals(emptyList(), truncatedRichTextNodes(emptyList(), maxChars = 5))
        assertEquals(emptyList(), truncatedRichTextNodes(listOf(text("abc")), maxChars = 0))
    }

    // ---------- splitRichTextSegmentsWithSlots ----------

    @Test
    fun `多文本段各含表情时槽位contentId跨段唯一`() {
        // 回归: 顶层图片把文本切成两段, 两段各含表情——此前各段槽位都从 emoji-0 编号,
        // 共享 inlineContentMap 按 id associate 时同编号互相覆盖, 前段表情错图。
        val segmented = splitRichTextSegmentsWithSlots(
            listOf(text("哈哈"), emoji("bgm38"), img("https://lain.bgm.tv/pic/a.png"), text("草"), emoji("bgm24")),
            emojiBaseUrl = "https://lain.bgm.tv",
        )
        assertEquals(3, segmented.size)
        val slots = segmented.flatMap { it.emojiSlots }
        assertEquals(2, slots.size)
        assertEquals(slots.size, slots.map { it.contentId }.toSet().size, "contentId 必须跨段唯一")
        assertEquals("bgm38", slots[0].code)
        assertEquals("bgm24", slots[1].code)
    }

    @Test
    fun `分段槽位与段内渲染引用一致`() {
        val segmented = splitRichTextSegmentsWithSlots(
            listOf(text("哈哈"), emoji("bgm38"), img("https://lain.bgm.tv/pic/a.png"), text("草"), emoji("bgm24")),
            emojiBaseUrl = "https://lain.bgm.tv",
        )
        segmented.forEach { entry ->
            if (entry.segment is RichTextSegment.TextPart) {
                val annotated = buildRichTextAnnotatedString(
                    nodes = entry.segment.nodes,
                    revealSpoiler = false,
                    emojiSlots = entry.emojiSlots,
                    onSurfaceVariant = Color.Gray,
                    surfaceVariant = Color.DarkGray,
                )
                assertEquals(entry.emojiSlots.map { it.contentId }, inlineIds(annotated), "段内引用的 id 必须与本段槽位一致")
            }
        }
    }

    @Test
    fun `图片段槽位恒空且单段无图片前缀为s0`() {
        val segmented = splitRichTextSegmentsWithSlots(
            listOf(img("https://lain.bgm.tv/pic/a.png"), text("x"), emoji("bgm24")),
            emojiBaseUrl = "https://lain.bgm.tv",
        )
        assertEquals(2, segmented.size)
        assertTrue(assertIs<RichTextSegment.ImagePart>(segmented[0].segment).let { segmented[0].emojiSlots.isEmpty() })
        assertEquals(listOf("s1-emoji-0"), segmented[1].emojiSlots.map { it.contentId })
    }
}
