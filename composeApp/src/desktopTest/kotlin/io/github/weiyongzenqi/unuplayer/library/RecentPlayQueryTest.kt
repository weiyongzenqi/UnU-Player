package io.github.weiyongzenqi.unuplayer.library

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteDataSource
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabase
import io.github.weiyongzenqi.unuplayer.playback.configuredDesktopDataSource
import io.github.weiyongzenqi.unuplayer.playback.ensureCurrentDesktopSchema
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "最近播放"数据层查询测试(desktopTest, in-memory JDBC SQLite)。
 *
 * 验证 listRecentlyPlayedShows / ScrapedLibraryRepositoryImpl.listRecentlyPlayed:
 * - 跨库混排, 按番剧最近播放时间倒序
 * - 按番剧聚合(一番剧多集只返回一行, lastPlayedAt = 最近一集时间)
 * - 过滤屏蔽(含隐藏的仍显示)
 * - 无播放记录的番剧不返回
 * - libraryId 非 null 限单库
 *
 * 建库复用 DesktopMediaLibraryIntegrationTest 的模式(configuredDesktopDataSource + Schema.create +
 * ensureCurrentDesktopSchema), 整番剧插入走 repository.upsertShow(自带 season/episode 事务),
 * PlaybackRecord 用 playbackQueries.upsertInsertIfAbsent 直插。
 */
class RecentPlayQueryTest {

    @Test
    fun `两番剧各播一集按最近播放时间倒序返回`() = runBlocking {
        withRecentPlayDb { db, repo ->
            val libId = repo.addTestLibrary("测试库")
            val showXId = repo.addTestShow(libId, title = "番剧X", showPath = "show-x", tmdbId = 101L,
                seasons = listOf(seasonWithEpisodes(1, listOf("x-ep1" to 1))))
            val showYId = repo.addTestShow(libId, title = "番剧Y", showPath = "show-y", tmdbId = 102L,
                seasons = listOf(seasonWithEpisodes(1, listOf("y-ep1" to 1))))
            db.insertPlayback("x-ep1", lastPlayedAt = 100L)
            db.insertPlayback("y-ep1", lastPlayedAt = 200L)

            val result = repo.listRecentlyPlayed()

            assertEquals(listOf(showYId, showXId), result.map { it.id })
            assertEquals(200L, result[0].lastPlayedAt)
            assertEquals(100L, result[1].lastPlayedAt)
            // cacheKey 与 ScrapedShow.cacheKey 公式一致: sanitize(title)-tmdbId
            assertEquals("番剧Y-102", result[0].cacheKey)
            assertEquals("番剧X-101", result[1].cacheKey)
        }
    }

    @Test
    fun `一番剧播多集按番剧聚合并取最近一集时间`() = runBlocking {
        withRecentPlayDb { db, repo ->
            val libId = repo.addTestLibrary("单库")
            val showZId = repo.addTestShow(libId, title = "番剧Z", showPath = "show-z", tmdbId = 201L,
                seasons = listOf(seasonWithEpisodes(1, listOf("z-ep1" to 1, "z-ep2" to 2, "z-ep3" to 3))))
            db.insertPlayback("z-ep1", lastPlayedAt = 100L)
            db.insertPlayback("z-ep2", lastPlayedAt = 300L)
            db.insertPlayback("z-ep3", lastPlayedAt = 200L)

            val result = repo.listRecentlyPlayed()

            assertEquals(listOf(showZId), result.map { it.id })
            assertEquals(1, result.size)
            // MAX(last_played_at) = 300, 而非最后一插入的 200
            assertEquals(300L, result.single().lastPlayedAt)
        }
    }

    @Test
    fun `屏蔽的番剧不返回`() = runBlocking {
        withRecentPlayDb { db, repo ->
            val libId = repo.addTestLibrary("屏蔽库")
            val showAId = repo.addTestShow(libId, title = "正常番", showPath = "show-a", tmdbId = 301L,
                seasons = listOf(seasonWithEpisodes(1, listOf("a-ep1" to 1))))
            val showBId = repo.addTestShow(libId, title = "屏蔽番", showPath = "show-b", tmdbId = 302L,
                seasons = listOf(seasonWithEpisodes(1, listOf("b-ep1" to 1))))
            db.insertPlayback("a-ep1", lastPlayedAt = 100L)
            db.insertPlayback("b-ep1", lastPlayedAt = 500L)  // 时间更新, 但被屏蔽应过滤
            repo.blockShow(showBId)

            val result = repo.listRecentlyPlayed()

            assertEquals(listOf(showAId), result.map { it.id })
            assertFalse(result.any { it.id == showBId })
        }
    }

    @Test
    fun `无播放记录的番剧不返回`() = runBlocking {
        withRecentPlayDb { db, repo ->
            val libId = repo.addTestLibrary("空记录库")
            val showPlayedId = repo.addTestShow(libId, title = "已播番", showPath = "show-played", tmdbId = 401L,
                seasons = listOf(seasonWithEpisodes(1, listOf("p-ep1" to 1))))
            // 未播番: episode 有 media_key 但无对应 PlaybackRecord
            repo.addTestShow(libId, title = "未播番", showPath = "show-unplayed", tmdbId = 402L,
                seasons = listOf(seasonWithEpisodes(1, listOf("u-ep1" to 1))))
            db.insertPlayback("p-ep1", lastPlayedAt = 100L)

            val result = repo.listRecentlyPlayed()

            assertEquals(listOf(showPlayedId), result.map { it.id })
            assertFalse(result.any { it.title == "未播番" })
        }
    }

    @Test
    fun `libraryId 非 null 只返回该库番剧`() = runBlocking {
        withRecentPlayDb { db, repo ->
            val libAId = repo.addTestLibrary("库A")
            val libBId = repo.addTestLibrary("库B")
            val showXId = repo.addTestShow(libAId, title = "番剧X", showPath = "show-x", tmdbId = 501L,
                seasons = listOf(seasonWithEpisodes(1, listOf("x-ep1" to 1))))
            val showYId = repo.addTestShow(libBId, title = "番剧Y", showPath = "show-y", tmdbId = 502L,
                seasons = listOf(seasonWithEpisodes(1, listOf("y-ep1" to 1))))
            db.insertPlayback("x-ep1", lastPlayedAt = 100L)
            db.insertPlayback("y-ep1", lastPlayedAt = 200L)

            // 限单库
            assertEquals(listOf(showXId), repo.listRecentlyPlayed(libraryId = libAId).map { it.id })
            assertEquals(listOf(showYId), repo.listRecentlyPlayed(libraryId = libBId).map { it.id })
            // 全库混排
            assertEquals(listOf(showYId, showXId), repo.listRecentlyPlayed().map { it.id })
        }
    }

    @Test
    fun `含隐藏番剧仍返回`() = runBlocking {
        withRecentPlayDb { db, repo ->
            val libId = repo.addTestLibrary("隐藏库")
            val showHiddenId = repo.addTestShow(libId, title = "隐藏番", showPath = "show-hidden", tmdbId = 601L,
                seasons = listOf(seasonWithEpisodes(1, listOf("h-ep1" to 1))))
            db.insertPlayback("h-ep1", lastPlayedAt = 100L)
            repo.setHidden(showHiddenId, true)

            val result = repo.listRecentlyPlayed()

            // 隐藏的仍应返回(仅过滤屏蔽), 与海报墙 is_hidden=0 过滤不同
            assertEquals(listOf(showHiddenId), result.map { it.id })
            assertTrue(result.single().title == "隐藏番")
        }
    }

    @Test
    fun `limit 参数限制返回行数`() = runBlocking {
        withRecentPlayDb { db, repo ->
            val libId = repo.addTestLibrary("限数库")
            repo.addTestShow(libId, title = "番1", showPath = "s1", tmdbId = 701L,
                seasons = listOf(seasonWithEpisodes(1, listOf("m1" to 1))))
            repo.addTestShow(libId, title = "番2", showPath = "s2", tmdbId = 702L,
                seasons = listOf(seasonWithEpisodes(1, listOf("m2" to 1))))
            repo.addTestShow(libId, title = "番3", showPath = "s3", tmdbId = 703L,
                seasons = listOf(seasonWithEpisodes(1, listOf("m3" to 1))))
            db.insertPlayback("m1", lastPlayedAt = 100L)
            db.insertPlayback("m2", lastPlayedAt = 200L)
            db.insertPlayback("m3", lastPlayedAt = 300L)

            val result = repo.listRecentlyPlayed(limit = 2)

            assertEquals(2, result.size)
            // 前 2 条 = 最近时间的两条
            assertEquals(listOf("番3", "番2"), result.map { it.title })
        }
    }

    // === helper ===

    private suspend fun withRecentPlayDb(
        block: suspend (UnuDatabase, ScrapedLibraryRepositoryImpl) -> Unit,
    ) {
        val parent = Files.createTempDirectory("unu-recent-play-")
        val dbFile = parent.resolve("recent.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val database = UnuDatabase(driver)
            val repository = ScrapedLibraryRepositoryImpl(database.scrapedQueries)
            block(database, repository)
        } finally {
            driver.close()
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }

    /** 简化 upsertShow 调用: 默认 LOCAL 源, 无海报/元数据, 仅注入标题/路径/tmdbId/季剧集。 */
    private suspend fun ScrapedLibraryRepositoryImpl.addTestShow(
        libraryId: Long,
        title: String,
        showPath: String,
        tmdbId: Long?,
        seasons: List<SeasonScanData>,
    ): Long = upsertShow(
        libraryId = libraryId,
        sourceKind = MediaSourceKind.LOCAL,
        tmdbId = tmdbId,
        folderName = showPath,
        showPath = showPath,
        title = title,
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
        seasons = seasons,
    )

    private suspend fun ScrapedLibraryRepositoryImpl.addTestLibrary(name: String): Long = addLibrary(
        name = name,
        sourceKind = MediaSourceKind.LOCAL,
        connectionId = null,
        localUri = null,
        rootPath = "/test/$name",
        scanDepth = 1,
    )

    /** 构造一季 + N 集, 每集 (mediaKey, episodeNumber)。release_date 给定保证 min_release_date 非 null。 */
    private fun seasonWithEpisodes(
        seasonNumber: Int,
        episodes: List<Pair<String, Int>>,
    ): SeasonScanData = SeasonScanData(
        nfo = SeasonNfo(seasonNumber = seasonNumber, title = null, year = null, releaseDate = "2026-01-01"),
        bangumi = null,
        seasonPath = "/season-$seasonNumber",
        seasonPosterPath = null,
        episodes = episodes.map { (mediaKey, epNum) ->
            EpisodeNfo(
                title = null, plot = null, rating = null, year = null, aired = null,
                episode = epNum, season = seasonNumber, runtime = null,
            ) to EpisodeFile(
                videoPath = "/video-$epNum.mkv",
                videoName = "video-$epNum.mkv",
                thumbPath = null,
                mediaKey = mediaKey,
                fileSize = 0L,
            )
        },
    )

    /** 直插 PlaybackRecord(走 upsertInsertIfAbsent, media_key 不存在则插入)。 */
    private fun UnuDatabase.insertPlayback(mediaKey: String, lastPlayedAt: Long) {
        playbackQueries.transaction {
            playbackQueries.upsertInsertIfAbsent(
                media_key = mediaKey,
                source_kind = "LOCAL",
                url = "file:///$mediaKey",
                content_uri = null,
                title = mediaKey,
                position_ms = 1000L,
                duration_ms = 100_000L,
                watch_progress = 0.01,
                is_completed = 0L,
                danmaku_episode_id = null,
                danmaku_anime_id = null,
                danmaku_anime_title = null,
                danmaku_episode_title = null,
                danmaku_match_method = null,
                last_played_at = lastPlayedAt,
                sync_status = 0L,
                sync_version = 0L,
            )
        }
    }
}
