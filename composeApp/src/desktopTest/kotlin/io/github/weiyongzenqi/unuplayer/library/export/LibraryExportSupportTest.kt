package io.github.weiyongzenqi.unuplayer.library.export

import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkState
import io.github.weiyongzenqi.unuplayer.library.ScrapedOnlineEpisode
import io.github.weiyongzenqi.unuplayer.library.TmdbEpisodeMappingEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class LibraryExportSupportTest {
    @Test
    fun `导入拒绝非正的已确认 Bangumi subjectId`() {
        fun link(subjectId: Long?) = BangumiLinkExport(
            identityKey = "tmdb-tv:1:season:1",
            subjectId = subjectId,
            state = "CONFIRMED",
            source = "MANUAL",
            evidence = null,
            updatedAt = 1,
            verifiedAt = 1,
        )

        assertNull(link(null).toBangumiSeasonLinkOrNull())
        assertNull(link(0).toBangumiSeasonLinkOrNull())
        assertNull(link(-1).toBangumiSeasonLinkOrNull())
        assertEquals(1L, link(1).toBangumiSeasonLinkOrNull()?.subjectId)
    }

    @Test
    fun `禁用关联允许不携带 subjectId`() {
        val imported = BangumiLinkExport(
            identityKey = "tmdb-tv:1:season:1",
            subjectId = null,
            state = "DISABLED",
            source = "MANUAL",
            evidence = null,
            updatedAt = 1,
            verifiedAt = null,
        ).toBangumiSeasonLinkOrNull()

        assertEquals(BangumiLinkState.DISABLED, imported?.state)
        assertNull(imported?.subjectId)
    }

    @Test
    fun `在线图片恢复目标名保留 role identity`() {
        val key = "online-scrape/key"
        val entries = listOf(
            requireNotNull(parseOnlineImageEntry(onlineImageEntryName(key, "poster", "image.jpg"))),
            requireNotNull(parseOnlineImageEntry(onlineImageEntryName(key, "fanart", "image.jpg"))),
            requireNotNull(parseOnlineImageEntry(onlineImageEntryName(key, "season1-poster", "image.jpg"))),
            requireNotNull(parseOnlineImageEntry(onlineImageEntryName(key, onlineEpisodeImageRole(1, 1), "image.jpg"))),
        )
        val targets = entries.map(::onlineImageRestoreBasename)
        assertEquals(4, targets.toSet().size)
        assertEquals("poster-image.jpg", targets[0])
        assertNotEquals(targets[0], targets[1])
    }

    @Test
    fun `导入TMDB集映射必须成对且不能生成非正集号`() {
        fun meta(season: Int?, offset: Int?) = OnlineMetaExport(
            seasonNumber = 2,
            scrapeSource = "NFO",
            episodes = listOf(ScrapedOnlineEpisode(episodeNumber = 1)),
            tmdbSeasonNumber = season,
            tmdbEpisodeOffset = offset,
            scrapedAt = 1L,
        )

        assertNull(meta(1, null).validatedTmdbEpisodeMapping())
        assertNull(meta(null, -11).validatedTmdbEpisodeMapping())
        assertNull(meta(1, 1).validatedTmdbEpisodeMapping())
        assertEquals(-11, meta(1, -11).validatedTmdbEpisodeMapping()?.episodeOffset)
    }

    @Test
    fun `迁移包的offset映射必须与当前季度subject证据一致`() {
        fun season(evidence: TmdbEpisodeMappingEvidence?) = SeasonExport(
            seasonNumber = 2,
            seasonPath = "/番剧/Season 2",
            bangumiId = 443428L,
            bangumiOffset = -11,
            episodes = listOf(
                EpisodeExport(
                    episodeNumber = 1,
                    videoPath = "/番剧/Season 2/S02E01.mkv",
                    videoName = "S02E01.mkv",
                ),
            ),
            onlineMeta = OnlineMetaExport(
                seasonNumber = 2,
                scrapeSource = "TMDB",
                episodes = listOf(ScrapedOnlineEpisode(episodeNumber = 1)),
                tmdbSeasonNumber = 1,
                tmdbEpisodeOffset = -11,
                tmdbMappingEvidence = evidence,
                scrapedAt = 1L,
            ),
        )

        assertNull(season(null).validatedTmdbEpisodeMapping(), "旧包缺少分段证据时不能恢复远端 E12")
        assertNull(
            season(TmdbEpisodeMappingEvidence(1, 325585L, -11)).validatedTmdbEpisodeMapping(),
            "另一物理分段的 subject 证据不能跨包复用",
        )
        assertEquals(
            -11,
            season(TmdbEpisodeMappingEvidence(1, 443428L, -11))
                .validatedTmdbEpisodeMapping()
                ?.episodeOffset,
        )
    }
}
