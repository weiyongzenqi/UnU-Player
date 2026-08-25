package io.github.weiyongzenqi.unuplayer.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduleRepositoryTest {

    @Test
    fun `时间表搜索记录最近优先去重并保持有界`() {
        val updated = updateScheduleSearchHistory(
            history = listOf("旧番", "Frieren", "重复", "重复"),
            query = "  frieren  ",
            limit = 3,
        )

        assertEquals(listOf("frieren", "旧番", "重复"), updated)
        assertEquals(emptyList(), updateScheduleSearchHistory(updated, "新番", limit = 0))
    }

    @Test
    fun `月份归一到季度首月并限制输入边界`() {
        assertEquals(1, quarterStartMonth(0))
        assertEquals(1, quarterStartMonth(3))
        assertEquals(4, quarterStartMonth(4))
        assertEquals(7, quarterStartMonth(9))
        assertEquals(10, quarterStartMonth(12))
        assertEquals(10, quarterStartMonth(13))
    }

    @Test
    fun `标题规范化忽略大小写分隔符与第一季后缀`() {
        assertEquals("葬送的芙莉莲", normalizeScheduleTitle("葬送的芙莉莲 第1季"))
        assertEquals("frierenbeyondjourneysend", normalizeScheduleTitle("Frieren: Beyond Journey's End Season 1"))
        assertEquals("mygo", normalizeScheduleTitle("MyGO!!!!!"))
    }

    @Test
    fun `无效 broadcast 不产生本地放送时间`() {
        assertNull(parseBroadcastLocal(""))
        assertNull(parseBroadcastLocal("not-an-iso-time"))
    }

    @Test
    fun `同优先级多个库候选保持歧义而不随机关联`() {
        val entry = scheduleEntry(subjectId = 100L)
        val matches = listOf(
            scheduleMatch(subjectId = 100L, showId = 1L, libraryId = 10L),
            scheduleMatch(subjectId = 100L, showId = 2L, libraryId = 20L),
        )

        assertNull(selectPreferredScheduleMatch(entry, matches))
        assertNull(selectPreferredScheduleMatch(entry, matches.reversed()))
    }

    @Test
    fun `重复查询行指向同一目标时仍可稳定关联`() {
        val entry = scheduleEntry(subjectId = 100L)
        val duplicate = scheduleMatch(subjectId = 100L, showId = 1L, libraryId = 10L)

        assertEquals(duplicate.copy(animeId = null, tmdbId = null), selectPreferredScheduleMatch(entry, listOf(duplicate, duplicate)))
    }

    @Test
    fun `同一番剧同季不同offset仍是两个物理分段`() {
        val entry = scheduleEntry(subjectId = 325585L, tmdbId = 94664L)
        val firstPart = scheduleMatch(
            tmdbId = 94664L,
            showId = 43L,
            libraryId = 10L,
            source = ScheduleLibraryMatchSource.TMDB,
        ).copy(bangumiOffset = 0L)
        val secondPart = firstPart.copy(bangumiOffset = -11L)

        assertNull(selectPreferredScheduleMatch(entry, listOf(firstPart, secondPart)))
    }

    @Test
    fun `更高可信来源优先于低优先级候选`() {
        val entry = scheduleEntry(subjectId = 100L, animeId = 300L)
        val scanned = scheduleMatch(
            subjectId = 100L,
            showId = 1L,
            libraryId = 10L,
            source = ScheduleLibraryMatchSource.SCANNED,
        )
        val dandanplay = scheduleMatch(
            animeId = 300L,
            showId = 2L,
            libraryId = 20L,
            source = ScheduleLibraryMatchSource.DANDANPLAY,
        )

        assertEquals(
            scanned.copy(animeId = 300L, tmdbId = null),
            selectPreferredScheduleMatch(entry, listOf(dandanplay, scanned)),
        )
    }

    @Test
    fun `入库与未入库条目都可写入时间表状态`() {
        val confirmed = scheduleEntry(subjectId = 100L).copy(
            libraryMatch = scheduleMatch(subjectId = 100L, showId = 1L, libraryId = 10L),
        )
        val unconfirmed = confirmed.copy(
            libraryMatch = confirmed.libraryMatch?.copy(source = ScheduleLibraryMatchSource.TITLE_HINT),
        )

        assertEquals(100L, confirmed.toScheduleWatchOrNull(watchedAt = 123L)?.subjectId)
        assertEquals(100L, unconfirmed.toScheduleWatchOrNull(watchedAt = 123L)?.subjectId)
    }

    @Test
    fun `在线剧集按 bangumi offset 逆映射到本地集号`() {
        assertEquals(13L, scheduleLocalEpisodeNumber(bangumiSort = 1.0, bangumiOffset = 12L))
        assertEquals(1L, scheduleLocalEpisodeNumber(bangumiSort = 13.0, bangumiOffset = -12L))
        assertEquals(7L, scheduleLocalEpisodeNumber(bangumiSort = 7.0, bangumiOffset = 0L))
    }

    @Test
    fun `在线特别篇和越界集号不猜测为本地剧集`() {
        assertNull(scheduleLocalEpisodeNumber(bangumiSort = 1.5, bangumiOffset = 0L))
        assertNull(scheduleLocalEpisodeNumber(bangumiSort = Double.NaN, bangumiOffset = 0L))
        assertNull(scheduleLocalEpisodeNumber(bangumiSort = 1.0, bangumiOffset = -1L))
        assertNull(scheduleLocalEpisodeNumber(bangumiSort = Long.MAX_VALUE.toDouble(), bangumiOffset = 0L))
        assertNull(scheduleLocalEpisodeNumber(bangumiSort = Long.MAX_VALUE.toDouble(), bangumiOffset = 1L))
    }

    @Test
    fun `Bangumi评论季内集号只接受当前subject的正整数ep`() {
        assertEquals(1L, scheduleBangumiSeasonEpisodeNumber(1.0))
        assertEquals(13L, scheduleBangumiSeasonEpisodeNumber(13.0))
        assertNull(scheduleBangumiSeasonEpisodeNumber(null))
        assertNull(scheduleBangumiSeasonEpisodeNumber(0.0))
        assertNull(scheduleBangumiSeasonEpisodeNumber(1.5))
        assertNull(scheduleBangumiSeasonEpisodeNumber(Double.NaN))
        assertNull(scheduleBangumiSeasonEpisodeNumber(Long.MAX_VALUE.toDouble()))
    }

    @Test
    fun `在线正整数集号可以安全映射到TMDB`() {
        assertEquals(12, scheduleTmdbEpisodeNumber(12.0))
        assertNull(scheduleTmdbEpisodeNumber(12.5))
        assertNull(scheduleTmdbEpisodeNumber(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `分段季度先回到本地集号再应用TMDB偏移`() {
        assertEquals(
            12,
            scheduleMappedTmdbEpisodeNumber(
                bangumiSort = 12.0,
                bangumiEpisode = 1.0,
                bangumiOffset = -11L,
                tmdbEpisodeOffset = -11,
            ),
        )
        assertEquals(
            24,
            scheduleMappedTmdbEpisodeNumber(
                bangumiSort = 24.0,
                bangumiEpisode = 13.0,
                bangumiOffset = -11L,
                tmdbEpisodeOffset = -11,
            ),
        )
        assertEquals(
            24,
            scheduleMappedTmdbEpisodeNumber(
                bangumiSort = 24.0,
                bangumiEpisode = 24.0,
                bangumiOffset = -11L,
                tmdbEpisodeOffset = -11,
            ),
            "Bangumi ep 与 sort 都是连续集号时也必须应用本地 offset",
        )
        assertNull(
            scheduleMappedTmdbEpisodeNumber(
                bangumiSort = 12.0,
                bangumiEpisode = 2.0,
                bangumiOffset = -11L,
                tmdbEpisodeOffset = -11,
            ),
        )
    }

    @Test
    fun `Bangumi精确关联保留本地库已确认的TMDB身份`() {
        val entry = scheduleEntry(subjectId = 100L, tmdbId = 999L)
        val scanned = scheduleMatch(
            subjectId = 100L,
            tmdbId = 94664L,
            showId = 1L,
            libraryId = 10L,
            source = ScheduleLibraryMatchSource.SCANNED,
        )

        assertEquals(
            scanned.copy(animeId = null),
            selectPreferredScheduleMatch(entry, listOf(scanned)),
        )
    }

    @Test
    fun `纯在线TMDB身份需由发行窗口或标题交叉确认`() {
        assertEquals(
            true,
            isScheduleTmdbIdentityCompatible(
                confirmedLocalIdentity = false,
                bangumiTitle = "【我推的孩子】 第二季",
                bangumiOriginalTitle = "【推しの子】 第2期",
                bangumiAirDate = "2024-07-03",
                tmdbTitle = "【我推的孩子】",
                tmdbOriginalTitle = "【推しの子】",
                tmdbFirstAirDate = "2023-04-12",
            ),
        )
        assertEquals(
            false,
            isScheduleTmdbIdentityCompatible(
                confirmedLocalIdentity = false,
                bangumiTitle = "无职转生 第三季",
                bangumiOriginalTitle = "無職転生III",
                bangumiAirDate = "2026-07-04",
                tmdbTitle = "BBC 怪奇档案",
                tmdbOriginalTitle = "BBC Weird Files",
                tmdbFirstAirDate = "2006-05-20",
            ),
        )
        assertEquals(
            false,
            isScheduleTmdbIdentityCompatible(
                confirmedLocalIdentity = false,
                bangumiTitle = "无职转生 第三季",
                bangumiOriginalTitle = "無職転生III",
                bangumiAirDate = "2026-07-04",
                tmdbTitle = "无职转生",
                tmdbOriginalTitle = "無職転生III",
                tmdbFirstAirDate = "2006-05-20",
            ),
        )
    }

    @Test
    fun `TMDB季号优先使用确认关联并识别标题季数`() {
        assertEquals(
            4,
            inferScheduleTmdbSeasonNumber(
                confirmedSeasonNumber = 4,
                title = "测试番剧 第二季",
                originalTitle = "Test Season 2",
                bangumiAirDate = "2026-07-01",
                tmdbFirstAirDate = "2020-01-01",
            ),
        )
        assertEquals(
            2,
            inferScheduleTmdbSeasonNumber(
                confirmedSeasonNumber = null,
                title = "测试番剧 第2期",
                originalTitle = "Test 2nd Season",
                bangumiAirDate = "2026-07-01",
                tmdbFirstAirDate = "2020-01-01",
            ),
        )
        assertEquals(
            3,
            inferScheduleTmdbSeasonNumber(
                confirmedSeasonNumber = null,
                title = "测试番剧",
                originalTitle = "Test Season III",
                bangumiAirDate = null,
                tmdbFirstAirDate = null,
            ),
        )
    }

    @Test
    fun `独立TMDB条目取第一季而无证据的长篇不猜季号`() {
        assertEquals(
            1,
            inferScheduleTmdbSeasonNumber(
                confirmedSeasonNumber = null,
                title = "测试番剧 第二季",
                originalTitle = null,
                bangumiAirDate = "2026-07-03",
                tmdbFirstAirDate = "2026-07-01",
            ),
        )
        assertNull(
            inferScheduleTmdbSeasonNumber(
                confirmedSeasonNumber = null,
                title = "测试番剧 新章",
                originalTitle = null,
                bangumiAirDate = "2026-07-03",
                tmdbFirstAirDate = "2020-01-01",
            ),
        )
    }

    @Test
    fun `纯数字关键词可作为合法 subject id 候选`() {
        assertEquals(471231L, digitSubjectIdCandidate("471231"))
        assertEquals(7L, digitSubjectIdCandidate("0007"))
        assertNull(digitSubjectIdCandidate(""))
        assertNull(digitSubjectIdCandidate("0"))
        assertNull(digitSubjectIdCandidate("2019年"))
        assertNull(digitSubjectIdCandidate("1234567890"))
        assertNull(digitSubjectIdCandidate("ABC123"))
    }

    @Test
    fun `bangumi-data tmdb id 解析兼容前导斜杠与纯数字`() {
        fun metadata(tmdbSiteId: String?) = BangumiDataItemDto(
            broadcast = null,
            sites = listOf(
                BangumiDataSiteDto(site = "bangumi", id = "471231"),
                BangumiDataSiteDto(site = "tmdb", id = tmdbSiteId.orEmpty()),
            ),
        ).toMetadata()

        assertEquals(471231L, metadata("tv/286346")?.subjectId)
        assertEquals(286346L, metadata("tv/286346")?.tmdbId)
        assertEquals(286346L, metadata("/tv/286346")?.tmdbId)
        assertEquals(286346L, metadata("286346")?.tmdbId)
        assertNull(metadata("")?.tmdbId)
        assertNull(metadata(null)?.tmdbId)
        assertNull(metadata("movie/286346")?.tmdbId)
    }

    @Test
    fun `无 bangumi site 的 bangumi-data 条目不产生元数据`() {
        assertNull(
            BangumiDataItemDto(
                broadcast = null,
                sites = listOf(BangumiDataSiteDto(site = "mal", id = "500")),
            ).toMetadata(),
        )
    }

    private fun scheduleEntry(
        subjectId: Long,
        animeId: Long? = null,
        tmdbId: Long? = null,
    ) = ScheduleEntry(
        subjectId = subjectId,
        title = "测试番剧",
        originalTitle = null,
        weekday = 1,
        broadcastTime = null,
        airDate = null,
        posterUrl = null,
        rating = null,
        rank = null,
        watchingCount = null,
        animeId = animeId,
        tmdbId = tmdbId,
        libraryMatch = null,
        watched = false,
    )

    private fun scheduleMatch(
        subjectId: Long? = null,
        animeId: Long? = null,
        tmdbId: Long? = null,
        showId: Long,
        libraryId: Long,
        source: ScheduleLibraryMatchSource = ScheduleLibraryMatchSource.PERSISTED,
    ) = ScheduleLibraryMatch(
        subjectId = subjectId,
        animeId = animeId,
        tmdbId = tmdbId,
        showId = showId,
        libraryId = libraryId,
        seasonNumber = 1,
        localTitle = "测试番剧",
        source = source,
    )
}
