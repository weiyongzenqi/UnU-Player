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

    @Test
    fun `bgm表情码映射到表情图路径`() {
        // 经典 tv 系列(24..125, 上界含)
        assertEquals("img/smiles/tv/01.gif", bangumiEmojiImagePath("bgm24"))
        assertEquals("img/smiles/tv/81.gif", bangumiEmojiImagePath("bgm104"))
        assertEquals("img/smiles/tv/82.gif", bangumiEmojiImagePath("bgm105"))
        assertEquals("img/smiles/tv/77.gif", bangumiEmojiImagePath("bgm100"))
        assertEquals("img/smiles/tv/102.gif", bangumiEmojiImagePath("bgm125"))
        assertEquals(null, bangumiEmojiImagePath("bgm126"), "tv 系列上界之外回落文本")
        // 第一代 bgm 系列(1..23, 混合 png/gif)
        assertEquals("img/smiles/bgm/01.png", bangumiEmojiImagePath("bgm1"))
        assertEquals("img/smiles/bgm/10.png", bangumiEmojiImagePath("bgm10"))
        assertEquals("img/smiles/bgm/11.gif", bangumiEmojiImagePath("bgm11"))
        assertEquals("img/smiles/bgm/22.png", bangumiEmojiImagePath("bgm22"))
        assertEquals("img/smiles/bgm/23.gif", bangumiEmojiImagePath("bgm23"))
        // vs 系列(200..238, 全 png)
        assertEquals("img/smiles/tv_vs/bgm_200.png", bangumiEmojiImagePath("bgm200"))
        assertEquals("img/smiles/tv_vs/bgm_238.png", bangumiEmojiImagePath("bgm238"))
        assertEquals(null, bangumiEmojiImagePath("bgm239"))
        // 新表情包 tv_500 系列(500..529, 扩展名查表)
        assertEquals("img/smiles/tv_500/bgm_500.gif", bangumiEmojiImagePath("bgm500"))
        assertEquals("img/smiles/tv_500/bgm_502.png", bangumiEmojiImagePath("bgm502"))
        assertEquals("img/smiles/tv_500/bgm_529.png", bangumiEmojiImagePath("bgm529"))
        assertEquals(null, bangumiEmojiImagePath("bgm530"))
        // 无效区间与畸形码
        assertEquals(null, bangumiEmojiImagePath("bgm150"))
        assertEquals(null, bangumiEmojiImagePath("bgm300"))
        assertEquals(null, bangumiEmojiImagePath("bgm"))
        assertEquals(null, bangumiEmojiImagePath("bgm24a"))
        assertEquals(null, bangumiEmojiImagePath("bgm99999"))
    }

    @Test
    fun `musume与blake系列映射两位补零且上界校验`() {
        assertEquals("img/smiles/musume/musume_01.gif", bangumiEmojiImagePath("musume_1"))
        assertEquals("img/smiles/musume/musume_07.gif", bangumiEmojiImagePath("musume_7"))
        assertEquals("img/smiles/musume/musume_118.gif", bangumiEmojiImagePath("musume_118"))
        assertEquals(null, bangumiEmojiImagePath("musume_119"))
        assertEquals("img/smiles/blake/blake_03.gif", bangumiEmojiImagePath("blake_03"))
        assertEquals("img/smiles/blake/blake_118.gif", bangumiEmojiImagePath("blake_118"))
        assertEquals(null, bangumiEmojiImagePath("blake_"))
        assertEquals(null, bangumiEmojiImagePath("blake_abc"))
    }

    @Test
    fun `经典文字表情按官方倒序表映射`() {
        assertEquals("img/smiles/1.gif", bangumiEmojiImagePath("=A="))
        assertEquals("img/smiles/9.gif", bangumiEmojiImagePath("T_T"))
        assertEquals("img/smiles/12.gif", bangumiEmojiImagePath("= ='"))
        assertEquals("img/smiles/16.gif", bangumiEmojiImagePath("LOL"))
        assertEquals(null, bangumiEmojiImagePath("lol"), "大小写敏感, 与官方精确匹配一致")
    }

    @Test
    fun `文字表情含特殊字符被解析为Emoji节点`() {
        val parsed = BangumiBbCodeParser.parse("哈哈哈 (=w=) (= =') (T_T) (bgm1) (blake_03)")
        val emojis = parsed.nodes.filterIsInstance<BangumiRichTextNode.Emoji>()
        assertEquals(listOf("=w=", "= ='", "T_T", "bgm1", "blake_03"), emojis.map { it.code })
    }

    // ---------- [photo] 站内图床标签 ----------

    @Test
    fun `photo标签拼出站内图床完整URL`() {
        val parsed = BangumiBbCodeParser.parse(
            "[photo=162794]89/20/905741_IhKxi.jpg[/photo]",
            imageBaseUrl = "https://lain.bgm.tv",
        )
        val image = assertIs<BangumiRichTextNode.ImagePlaceholder>(parsed.nodes.single())
        assertEquals("https://lain.bgm.tv/pic/photo/l/89/20/905741_IhKxi.jpg", image.url)
    }

    @Test
    fun `photo标签无value也解析且镜像base生效`() {
        val parsed = BangumiBbCodeParser.parse(
            "[photo]89/20/905741_IhKxi.jpg[/photo]",
            imageBaseUrl = "https://lain.bangumi.lol",
        )
        val image = assertIs<BangumiRichTextNode.ImagePlaceholder>(parsed.nodes.single())
        assertEquals("https://lain.bangumi.lol/pic/photo/l/89/20/905741_IhKxi.jpg", image.url)
    }

    @Test
    fun `photo危险路径拒绝且不产出有效图片URL`() {
        val parsed = BangumiBbCodeParser.parse(
            "[photo]../../etc/passwd[/photo][photo]/abs/path.jpg[/photo][photo]https://evil.com/x.jpg[/photo][photo]a//b.jpg[/photo]",
        )
        // 与 img 语义一致: 标签合法但路径校验失败 → ImagePlaceholder(null), 渲染为"[无效图片]"
        val images = parsed.nodes.filterIsInstance<BangumiRichTextNode.ImagePlaceholder>()
        assertEquals(4, images.size)
        assertTrue(images.all { it.url == null }, "危险路径不产出有效图片 URL")
    }

    @Test
    fun `photo无闭合回落文本`() {
        val parsed = BangumiBbCodeParser.parse("[photo=1]89/20/x.jpg 没有闭合")
        assertTrue(parsed.nodes.none { it is BangumiRichTextNode.ImagePlaceholder })
        assertTrue(parsed.plainText().contains("[photo=1]"))
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
