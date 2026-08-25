package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.ui.posterwall.buildSeasonTabLabels
import io.github.weiyongzenqi.unuplayer.ui.posterwall.sortLogicalSeasons
import kotlin.test.Test
import kotlin.test.assertEquals

class LogicalShowGroupingTest {
    @Test
    fun `同库同TMDB折叠为一张卡但无身份目录保持独立`() {
        val first = show(id = 10, tmdbId = 94664, title = "无职转生", releaseDate = "2021-01-11", season = 1)
        val second = show(id = 20, tmdbId = 94664, title = "无职转生 下半部分", releaseDate = "2021-10-04", season = 1)
        val anchorA = show(id = 30, tmdbId = null, title = "同名番", releaseDate = null, season = null)
        val anchorB = show(id = 31, tmdbId = null, title = "同名番", releaseDate = null, season = null)

        val merged = mergeLogicalShowCards(listOf(first, second, anchorA, anchorB))

        assertEquals(listOf(10L, 30L, 31L), merged.map { it.id })
        assertEquals("2021-01-11", merged.first().min_release_date)
        assertEquals(1L, merged.first().card_season_number)
    }

    @Test
    fun `相同TMDB的不同季度保持独立并使用各自季度海报`() {
        val first = show(id = 10, tmdbId = 203737, title = "我推的孩子", releaseDate = "2023-04-12", season = 1)
        val second = show(
            id = 20,
            tmdbId = 203737,
            title = "我推的孩子 第二季",
            releaseDate = "2024-07-03",
            season = 2,
            favorite = 1,
            poster = "/cache/s2.jpg",
        )

        val merged = mergeLogicalShowCards(listOf(first, second))

        assertEquals(listOf(10L, 20L), merged.map { it.id })
        assertEquals(listOf(1L, 2L), merged.map { it.card_season_number })
        assertEquals(listOf(null, "/cache/s2.jpg"), merged.map { it.card_poster_path })
        assertEquals(1L, merged.last().is_favorite)
    }

    @Test
    fun `海报墙卡片只按同季分段合并且不同季度独立`() {
        val firstPart = show(id = 10, tmdbId = 94664, title = "无职转生", releaseDate = "2021-01-11", season = 1)
        val secondPart = show(id = 20, tmdbId = 94664, title = "无职转生 下半部分", releaseDate = "2021-10-04", season = 1)
        val thirdSeason = show(id = 30, tmdbId = 94664, title = "无职转生 第三季", releaseDate = "2026-07-04", season = 3)

        val merged = mergeLogicalShowCards(listOf(firstPart, secondPart, thirdSeason))

        assertEquals(listOf(10L, 30L), merged.map { it.id }, "上下部分合并为一张卡, 第三季保持独立")
        assertEquals("2021-01-11", merged.first().min_release_date)
    }

    @Test
    fun `详情页跨季排序与页签标签`() {
        // 无职转生形态: S1 上半(offset=0)、S1 下半(offset=-11)、S3 各在不同物理目录。
        val firstPart = season(id = 10, showId = 1, number = 1, offset = 0)
        val secondPart = season(id = 20, showId = 2, number = 1, offset = -11)
        val thirdSeason = season(id = 30, showId = 3, number = 3, offset = 0)

        val ordered = sortLogicalSeasons(listOf(thirdSeason, secondPart, firstPart))

        // 同季号按漂移升序(1-offset: offset=0 在前, -11 的"1-(-11)"更大在后), 再按季号。
        assertEquals(listOf(10L, 20L, 30L), ordered.map { it.id })
        val labels = buildSeasonTabLabels(ordered)
        assertEquals("第1季 · 第1部分", labels.getValue(10))
        assertEquals("第1季 · 第2部分", labels.getValue(20))
        assertEquals("第3季", labels.getValue(30))
    }

    private fun season(
        id: Long,
        showId: Long,
        number: Long,
        offset: Long,
    ) = ScrapedSeason(
        id = id,
        show_id = showId,
        season_number = number,
        season_path = "/番剧/$showId/Season $number",
        title = null,
        year = null,
        release_date = null,
        bangumi_id = null,
        bangumi_offset = offset,
        season_poster_path = null,
        episode_count = 1,
        scanned_at = 1,
    )

    private fun show(
        id: Long,
        tmdbId: Long?,
        title: String,
        releaseDate: String?,
        season: Long?,
        favorite: Long = 0,
        poster: String? = null,
    ) = ListShowsByLibrary(
        id = id,
        library_id = 1,
        source_kind = "LOCAL",
        tmdb_id = tmdbId,
        folder_name = title,
        show_path = "/番剧/$id",
        title = title,
        original_title = null,
        year = null,
        plot = null,
        rating = null,
        release_date = releaseDate,
        genres = null,
        studios = null,
        poster_path = null,
        fanart_path = null,
        clearlogo_path = null,
        is_favorite = favorite,
        favorited_at = null,
        favorite_sort_order = 0,
        is_hidden = 0,
        scanned_at = 1,
        min_release_date = releaseDate,
        card_poster_path = poster,
        card_online_poster_path = poster,
        card_online_fanart_path = null,
        card_remote_poster_url = null,
        card_remote_poster_season = null,
        card_poster_path_kind = ScrapedImagePathKind.LOCAL_FILE.name,
        card_season_number = season,
    )
}
