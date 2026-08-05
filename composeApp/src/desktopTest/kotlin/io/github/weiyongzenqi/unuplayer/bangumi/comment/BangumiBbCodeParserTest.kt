package io.github.weiyongzenqi.unuplayer.bangumi.comment

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BangumiBbCodeParserTest {
    @Test
    fun `支持嵌套强调链接遮罩和Bangumi表情`() {
        val parsed = BangumiBbCodeParser.parse(
            "[b]粗体[i]斜体[/i][/b] [url=https://bgm.tv/subject/1]链接[/url] [mask]剧透[/mask] (bgm38) (musume_04)",
        )

        assertIs<BangumiRichTextNode.Styled>(parsed.nodes.first())
        assertTrue(parsed.hasSpoiler)
        assertTrue(parsed.nodes.any { it is BangumiRichTextNode.Link })
        assertEquals(2, parsed.nodes.count { it is BangumiRichTextNode.Emoji })
    }

    @Test
    fun `未知标签和畸形闭合降级为纯文本`() {
        val parsed = BangumiBbCodeParser.parse("[foo]内容[/foo][/b][b]未闭合")
        val text = parsed.plainText()

        assertContains(text, "[foo]内容[/foo]")
        assertContains(text, "[/b]")
        assertContains(text, "[b]未闭合")
    }

    @Test
    fun `危险协议被拒绝且远程图片只生成占位`() {
        val parsed = BangumiBbCodeParser.parse(
            "[url=javascript:alert(1)]危险[/url][img]https://example.com/a.png[/img][img]file:///tmp/a.png[/img]",
        )

        assertTrue(parsed.nodes.none { it is BangumiRichTextNode.Link })
        val images = parsed.nodes.filterIsInstance<BangumiRichTextNode.ImagePlaceholder>()
        assertEquals("https://example.com/a.png", images[0].url)
        assertNull(images[1].url)
    }

    @Test
    fun `超长输入有界截断`() {
        val parsed = BangumiBbCodeParser.parse("x".repeat(40_000))
        assertTrue(parsed.plainText().length <= 20_003)
    }

    private fun BangumiRichText.plainText(): String = buildString {
        fun appendNodes(nodes: List<BangumiRichTextNode>) {
            nodes.forEach { node ->
                when (node) {
                    is BangumiRichTextNode.Text -> append(node.value)
                    is BangumiRichTextNode.Styled -> appendNodes(node.children)
                    is BangumiRichTextNode.Link -> appendNodes(node.children)
                    is BangumiRichTextNode.ImagePlaceholder -> append(node.url.orEmpty())
                    is BangumiRichTextNode.Emoji -> append(node.code)
                }
            }
        }
        appendNodes(nodes)
    }
}
