package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
