package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrapedLibraryScannerTest {

    @Test
    fun `重复和缺失集号获得稳定唯一兜底值`() {
        assertEquals(
            listOf(1, 3, 2, 4, 5, 0, 6),
            assignStableEpisodeNumbers(listOf(1, 1, 2, null, null, 0, 0)),
        )
    }

    @Test
    fun `没有明确集号时按稳定输入顺序从一开始编号`() {
        assertEquals(
            listOf(1, 2, 3),
            assignStableEpisodeNumbers(listOf(null, null, null)),
        )
    }

    @Test
    fun `NFO 集号优先于文件名提取的重复集号`() {
        assertEquals(
            listOf(2, 1),
            assignStableEpisodeNumbers(
                candidates = listOf(1, 1),
                preferred = listOf(false, true),
            ),
        )
    }

    @Test
    fun `季度索引保留目录顺序首个同名文件并自然排序视频`() {
        val firstNfo = MediaEntry("Episode S01E02.NFO", "/first.nfo", false)
        val entries = listOf(
            MediaEntry("Episode S01E10.mkv", "/10.mkv", false),
            firstNfo,
            MediaEntry("episode s01e02.nfo", "/duplicate.nfo", false),
            MediaEntry("Episode S01E02.mkv", "/2.mkv", false),
            MediaEntry("folder", "/folder", true),
        )

        val index = indexSeasonEntries(entries)

        assertEquals(firstNfo, index.firstFile("episode s01e02.nfo"))
        assertEquals(listOf("Episode S01E02.mkv", "Episode S01E10.mkv"), index.videoFiles.map { it.name })
    }

    @Test
    fun `季目录命名变体全识别且大小写不敏感`() {
        assertTrue(isSeasonDir("Season 1"))
        assertTrue(isSeasonDir("season 01"))
        assertTrue(isSeasonDir("某番-Season_03-[1080p]"))
        assertTrue(isSeasonDir("S01"))
        assertTrue(isSeasonDir("s 2"))
        assertTrue(isSeasonDir("第1季"))
        assertTrue(isSeasonDir("第 01 季"))
        assertTrue(isSeasonDir("[BDRip] 某番 第02季 完结"))
        assertTrue(isSeasonDir("合集_第三季_CHS"))
        assertTrue(isSeasonDir("2025年第2季度新番"))
        assertFalse(isSeasonDir("Specials"))
        assertFalse(isSeasonDir("Movie"))
        assertFalse(isSeasonDir("Season"))
        assertFalse(isSeasonDir("Offseason2"))
        assertFalse(isSeasonDir("S01E02"))
        assertFalse(isSeasonDir("Class05"))
        assertFalse(isSeasonDir("Pass01"))
        assertFalse(isSeasonDir("Videos2024"))
        assertFalse(isSeasonDir("S01 第2季"))
    }

    @Test
    fun `季目录名提取季号`() {
        assertEquals(1, extractSeasonNumber("Season 1"))
        assertEquals(1, extractSeasonNumber("Season 01"))
        assertEquals(2, extractSeasonNumber("S02"))
        assertEquals(3, extractSeasonNumber("第3季"))
        assertEquals(4, extractSeasonNumber("第 4 季"))
        assertEquals(12, extractSeasonNumber("[BD] 第十二季 完结"))
        assertEquals(2, extractSeasonNumber("某番 第2季度"))
        assertEquals(null, extractSeasonNumber("Season"))
        assertEquals(null, extractSeasonNumber("Class05"))
    }

    @Test
    fun `自然季度分组不向番剧继承季号而普通通配季目录会继承`() {
        val calendar = parseSeasonDirectoryMarker("2025年第2季度新番")
        val season = parseSeasonDirectoryMarker("[BDRip] 某番 第02季 完结")

        assertEquals(2, calendar?.seasonNumber)
        assertEquals(null, calendar?.inheritedSeasonNumber)
        assertEquals("", calendar?.titleHint)
        assertEquals(2, season?.seasonNumber)
        assertEquals(2, season?.inheritedSeasonNumber)
        assertEquals("某番", season?.titleHint)
    }
}
