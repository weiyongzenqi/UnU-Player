package io.github.weiyongzenqi.unuplayer.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * EpisodeNumberExtractor 集号提取回归测试。
 * 重点覆盖 B9 修复: "[-_]NN" 尾锚放宽后, 数字后接空格/[/. 等常见命名能正确命中,
 * 且不误配分辨率(1080p/720p)。
 */
class EpisodeNumberExtractorTest {

    // —— B9 五例(修复目标) ——

    @Test
    fun `数字后接空格加方括号仍命中`() {
        // a) 03 后是空格+[
        assertEquals(3, EpisodeNumberExtractor.extractEpisode("Title - 03 [1080p]"))
    }

    @Test
    fun `数字后接扩展名点号仍命中`() {
        // b) 03 后是 .
        assertEquals(3, EpisodeNumberExtractor.extractEpisode("Title - 03.mkv"))
    }

    @Test
    fun `多段标签中横线集号命中且不误配方括号`() {
        // c) [Group] 与 [1080p]/[x264] 均为非纯数字括号, 不应命中 [NN] 模式; 集号取 "- 12"
        assertEquals(12, EpisodeNumberExtractor.extractEpisode("[Group] Foo Bar - 12 [1080p][x264]"))
    }

    @Test
    fun `不误配分辨率_1080p_取后续真实集号`() {
        // d) 1080 不得被取为 108/1080; 集号取 "- 05"
        assertEquals(5, EpisodeNumberExtractor.extractEpisode("Foo - 1080p - 05.mkv"))
    }

    @Test
    fun `仅分辨率无集号返回null`() {
        // e) 720p 后接 . , 无独立集号
        assertNull(EpisodeNumberExtractor.extractEpisode("Foo - 720p.mkv"))
    }

    // —— 旧尾锚行为不回退(横线/下划线/串尾仍命中) ——

    @Test
    fun `数字后接横线仍命中`() {
        assertEquals(7, EpisodeNumberExtractor.extractEpisode("Title - 07 - 预告.mkv"))
    }

    @Test
    fun `下划线包裹集号仍命中`() {
        assertEquals(9, EpisodeNumberExtractor.extractEpisode("Title_09_.mkv"))
    }

    @Test
    fun `数字位于串尾仍命中`() {
        assertEquals(4, EpisodeNumberExtractor.extractEpisode("Title - 04"))
    }

    @Test
    fun `数字后接全角左括号命中`() {
        assertEquals(6, EpisodeNumberExtractor.extractEpisode("Title - 06（修正）.mkv"))
    }

    // —— 优先级顺序不回退 ——

    @Test
    fun `SxxExx_优先取集号`() {
        assertEquals(12, EpisodeNumberExtractor.extractEpisode("S01E12.mkv"))
        assertEquals(1, EpisodeNumberExtractor.extractSeason("S01E12.mkv"))
        assertEquals("S01E12", EpisodeNumberExtractor.formatSxxExx("S01E12.mkv"))
    }

    @Test
    fun `第x话模式命中`() {
        assertEquals(3, EpisodeNumberExtractor.extractEpisode("某番 第3话.mkv"))
        assertEquals(8, EpisodeNumberExtractor.extractEpisode("某番 第 08 話.mkv"))
    }

    @Test
    fun `EP模式命中且标题尾字母不误配`() {
        assertEquals(5, EpisodeNumberExtractor.extractEpisode("Foo EP05.mkv"))
        assertEquals(2, EpisodeNumberExtractor.extractEpisode("Foo E 02.mkv"))
        // \b 词边界修复: "Gate 0" 的 e+空格+0 不再被 EP? 误命中, 且无其他模式命中 → null
        assertNull(EpisodeNumberExtractor.extractEpisode("Steins;Gate 0.mkv"))
    }

    @Test
    fun `方括号纯数字集号命中`() {
        assertEquals(3, EpisodeNumberExtractor.extractEpisode("[Group] Title [03].mkv"))
    }

    @Test
    fun `无任何模式命中返回null`() {
        assertNull(EpisodeNumberExtractor.extractEpisode("readme.txt"))
    }
}
