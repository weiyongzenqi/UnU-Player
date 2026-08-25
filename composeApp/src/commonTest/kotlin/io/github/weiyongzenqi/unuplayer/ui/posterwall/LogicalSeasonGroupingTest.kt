package io.github.weiyongzenqi.unuplayer.ui.posterwall

import io.github.weiyongzenqi.unuplayer.library.ScrapedSeason
import kotlin.test.Test
import kotlin.test.assertEquals

class LogicalSeasonGroupingTest {
    @Test
    fun `同一季上下部分按offset连续起点排序并生成不冲突标签`() {
        val lower = season(id = 20, showId = 2, number = 1, offset = -11, date = "2021-10-04")
        val upper = season(id = 10, showId = 1, number = 1, offset = 0, date = "2021-01-11")
        val seasonTwo = season(id = 30, showId = 3, number = 2, offset = 0, date = "2023-07-03")

        val sorted = sortLogicalSeasons(listOf(lower, seasonTwo, upper))
        val labels = buildSeasonTabLabels(sorted)

        assertEquals(listOf(10L, 20L, 30L), sorted.map { it.id })
        assertEquals("第1季 · 第1部分", labels.getValue(10))
        assertEquals("第1季 · 第2部分", labels.getValue(20))
        assertEquals("自定义第二季", labels.getValue(30))
    }

    private fun season(
        id: Long,
        showId: Long,
        number: Long,
        offset: Long,
        date: String,
    ) = ScrapedSeason(
        id = id,
        show_id = showId,
        season_number = number,
        season_path = "/番剧/$showId/Season $number",
        title = if (number == 2L) "自定义第二季" else null,
        year = null,
        release_date = date,
        bangumi_id = null,
        bangumi_offset = offset,
        season_poster_path = null,
        episode_count = 12,
        scanned_at = 1,
    )
}
