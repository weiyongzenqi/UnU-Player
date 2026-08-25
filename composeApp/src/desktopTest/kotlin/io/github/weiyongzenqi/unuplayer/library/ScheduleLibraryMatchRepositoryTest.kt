package io.github.weiyongzenqi.unuplayer.library

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkSource
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkState
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabase
import io.github.weiyongzenqi.unuplayer.playback.configuredDesktopDataSource
import io.github.weiyongzenqi.unuplayer.playback.ensureCurrentDesktopSchema
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleLibraryMatchSource
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleLibraryMatchRepositoryTest {
    @Test
    fun `在线刮削 Bangumi 身份可反查时间表库内条目`() = runBlocking {
        val parent = Files.createTempDirectory("unu-schedule-match-")
        val dbFile = parent.resolve("schedule.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val repository = ScrapedLibraryRepositoryImpl(UnuDatabase(driver).scrapedQueries)
            val libraryId = repository.addLibrary(
                name = "时间表关联测试库",
                sourceKind = MediaSourceKind.LOCAL,
                connectionId = null,
                localUri = parent.toString(),
                rootPath = parent.toString(),
                scanDepth = 1,
            )
            val showId = repository.upsertShow(
                libraryId = libraryId,
                sourceKind = MediaSourceKind.LOCAL,
                tmdbId = 94664L,
                folderName = "schedule-show",
                showPath = "schedule-show",
                title = "时间表测试番剧",
                originalTitle = null,
                year = null,
                plot = null,
                rating = null,
                releaseDate = null,
                genres = emptyList(),
                studios = emptyList(),
                posterPath = null,
                fanartPath = null,
                clearlogoPath = null,
                scannedAt = 1L,
                seasons = listOf(
                    SeasonScanData(
                        nfo = SeasonNfo(
                            seasonNumber = 1,
                            title = "第1季",
                            year = null,
                            releaseDate = null,
                        ),
                        bangumi = BangumiIni(id = 111L, offset = -11),
                        seasonPath = "schedule-show/Season 01",
                        seasonPosterPath = null,
                        episodes = emptyList(),
                    ),
                ),
            )
            repository.upsertOnlineMeta(
                libraryId = libraryId,
                showPath = "schedule-show",
                seasonNumber = 1,
                source = ScrapeSource.BANGUMI,
                overwriteTitle = false,
                dandanplayId = null,
                bangumiId = 400602L,
                remotePosterUrl = null,
                localPosterPath = null,
                title = null,
                originalTitle = null,
                year = null,
                plot = null,
                rating = null,
                releaseDate = null,
                genres = emptyList(),
                studios = emptyList(),
                episodes = emptyList(),
                scrapedAt = 2L,
            )

            assertEquals(
                listOf(LibraryShowTitle(showId, libraryId, "时间表测试番剧")),
                repository.listVisibleShowTitles(),
            )

            val match = repository.findScheduleLibraryMatches(
                subjectIds = setOf(400602L),
                tmdbIds = emptySet(),
                animeIds = emptySet(),
            ).single()

            assertEquals(showId, match.showId)
            assertEquals(libraryId, match.libraryId)
            assertEquals(1, match.seasonNumber)
            assertEquals(94664L, match.tmdbId)
            assertEquals(ScheduleLibraryMatchSource.SCANNED, match.source)

            repository.upsertBangumiSeasonLink(
                BangumiSeasonLink(
                    identityKey = "tmdb-tv:94664:season:1:offset:-11",
                    subjectId = 500603L,
                    state = BangumiLinkState.CONFIRMED,
                    source = BangumiLinkSource.MANUAL,
                    evidence = "用户确认",
                    updatedAt = 3L,
                    verifiedAt = 3L,
                ),
            )
            val persisted = repository.findScheduleLibraryMatches(
                subjectIds = setOf(500603L),
                tmdbIds = emptySet(),
                animeIds = emptySet(),
            ).single()
            assertEquals(-11L, persisted.bangumiOffset)
            assertEquals(94664L, persisted.tmdbId)
            assertEquals(ScheduleLibraryMatchSource.PERSISTED, persisted.source)
        } finally {
            driver.close()
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }
}
