package io.github.weiyongzenqi.unuplayer.library

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabase
import io.github.weiyongzenqi.unuplayer.playback.configuredDesktopDataSource
import io.github.weiyongzenqi.unuplayer.playback.ensureCurrentDesktopSchema
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * 海报墙卡片三列(批次C)真实临时数据库查询测试(desktopTest):
 * card_online_fanart_path(部级头图本地路径) / card_remote_poster_url+card_remote_poster_season
 * (最新「有远程 URL 且无本地文件」的季级海报及其季号)。列由 scraped.sq 六个同构 card 查询输出,
 * 本测试走 repository.listShows(默认季度序 = listShowsByLibrary 查询)断言列语义。
 * 建库方式参考 RecentPlayQueryTest(configuredDesktopDataSource + Schema.create + ensureCurrentDesktopSchema)。
 */
class CardOnlineImageColumnsTest {

    @Test
    fun `六个卡片查询按当前show相关定位远程海报`() {
        val schema = listOf(
            Path.of("src/commonMain/sqldelight/io/github/weiyongzenqi/unuplayer/library/scraped.sq"),
            Path.of("composeApp/src/commonMain/sqldelight/io/github/weiyongzenqi/unuplayer/library/scraped.sq"),
        ).first { it.exists() }
        val sql = Files.readString(schema)

        assertEquals(6, Regex("LEFT JOIN ScrapedOnlineMeta pm").findAll(sql).count())
        assertEquals(6, Regex("SELECT MAX\\(m\\.season_number\\)").findAll(sql).count())
        assertFalse(sql.contains("GROUP BY library_id, show_path"), "不得物化全表 pm 聚合")
    }

    @Test
    fun `部级在线头图本地路径取到`() = runBlocking {
        // 修复前失败点: card_online_fanart_path 列不存在(SQLDelight 生成属性缺失 → 编译失败);
        // 列存在但子查询漏 season_number=0 过滤时会把季级海报路径误当头图。
        withCardDb { repo ->
            val libId = repo.addCardLibrary()
            repo.addCardShow(libId, "头图番", seasonCount = 1)
            repo.upsertOnlineMeta(
                libraryId = libId, showPath = "头图番", seasonNumber = 0,
                source = ScrapeSource.TMDB, overwriteTitle = false,
                dandanplayId = null, bangumiId = null,
                remotePosterUrl = null, localPosterPath = null,
                title = null, originalTitle = null, year = null, plot = null, rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = emptyList(), scrapedAt = 1L,
            )
            repo.updateOnlineMetaFanart(libId, "头图番", "https://example.com/bd.jpg", "/cache/backdrop.jpg")

            val card = repo.listShows(libId).single()
            assertEquals("/cache/backdrop.jpg", card.card_online_fanart_path)
            assertNull(card.card_remote_poster_url)
            assertNull(card.card_remote_poster_season)
        }
    }

    @Test
    fun `季级有URL无本地取最新季与季号`() = runBlocking {
        // 修复前失败点: 两列不存在(编译失败); 列存在但 ORDER BY season_number DESC 缺失时
        // 取到的不是最新缺文件季, 海报墙会把下载定位写错季。
        withCardDb { repo ->
            val libId = repo.addCardLibrary()
            repo.addCardShow(libId, "双季番", seasonCount = 2)
            repo.upsertSeasonPosterMeta(libId, "双季番", 1, "https://example.com/s1.jpg", null)
            repo.upsertSeasonPosterMeta(libId, "双季番", 2, "https://example.com/s2.jpg", null)

            val card = repo.listShows(libId).single()
            assertEquals("https://example.com/s2.jpg", card.card_remote_poster_url)
            assertEquals(2L, card.card_remote_poster_season)
            assertNull(card.card_online_fanart_path)
        }
    }

    @Test
    fun `唯一季已有本地文件时remote列为NULL`() = runBlocking {
        // 修复前失败点: 子查询漏「无本地文件」过滤时, 已有本地的季也给出 remote URL,
        // 海报墙会对已有封面的番发起无意义下载。
        withCardDb { repo ->
            val libId = repo.addCardLibrary()
            repo.addCardShow(libId, "已下载番", seasonCount = 1)
            repo.upsertSeasonPosterMeta(libId, "已下载番", 1, "https://example.com/s1.jpg", "/cache/season1.jpg")

            val card = repo.listShows(libId).single()
            assertNull(card.card_remote_poster_url)
            assertNull(card.card_remote_poster_season)
        }
    }

    @Test
    fun `全部季有本地文件时remote列为NULL且最新季已下载时取更早缺文件季`() = runBlocking {
        // 修复前失败点: ①全部有 local 时若不过滤会给出 remote(重复下载); ②最新季已有 local、
        // 更早季缺文件时若不过滤"无本地文件"条件, 会把最新季 URL 给海报墙(该季已不需要)。
        withCardDb { repo ->
            val libId = repo.addCardLibrary()
            repo.addCardShow(libId, "混合番", seasonCount = 2)
            repo.upsertSeasonPosterMeta(libId, "混合番", 1, "https://example.com/s1.jpg", null)
            repo.upsertSeasonPosterMeta(libId, "混合番", 2, "https://example.com/s2.jpg", "/cache/season2.jpg")

            // 最新季(2)已下载 → remote 指向更早的缺文件季(1)
            val mixed = repo.listShows(libId).single()
            assertEquals("https://example.com/s1.jpg", mixed.card_remote_poster_url)
            assertEquals(1L, mixed.card_remote_poster_season)

            // 第一季也补齐后 → 全部季有 local, remote 列 NULL
            repo.updateOnlineMetaLocalPoster(libId, "混合番", 1, "/cache/season1.jpg")
            val settled = repo.listShows(libId).single()
            assertNull(settled.card_remote_poster_url)
            assertNull(settled.card_remote_poster_season)
        }
    }

    // === helper ===

    private suspend fun withCardDb(block: suspend (ScrapedLibraryRepositoryImpl) -> Unit) {
        val parent = Files.createTempDirectory("unu-card-columns-")
        val dbFile = parent.resolve("card.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val database = UnuDatabase(driver)
            block(ScrapedLibraryRepositoryImpl(database.scrapedQueries))
        } finally {
            driver.close()
            cleanupDir(parent)
        }
    }

    private fun cleanupDir(parent: Path) {
        Files.walk(parent).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
        }
    }

    private suspend fun ScrapedLibraryRepositoryImpl.addCardLibrary(): Long = addLibrary(
        name = "卡片列测试库", sourceKind = MediaSourceKind.LOCAL,
        connectionId = null, localUri = null, rootPath = "/test", scanDepth = 1,
    )

    /** ANCHOR 形态番剧: 无 NFO 海报/季照, 仅文件夹 + N 个单集季。 */
    private suspend fun ScrapedLibraryRepositoryImpl.addCardShow(
        libraryId: Long,
        folder: String,
        seasonCount: Int,
    ): Long = upsertShow(
        libraryId = libraryId, sourceKind = MediaSourceKind.LOCAL, tmdbId = null,
        folderName = folder, showPath = folder, title = folder, originalTitle = null,
        year = null, plot = null, rating = null, releaseDate = null,
        genres = emptyList(), studios = emptyList(),
        posterPath = null, fanartPath = null, clearlogoPath = null,
        scannedAt = 1L,
        seasons = (1..seasonCount).map { seasonNumber ->
            SeasonScanData(
                nfo = SeasonNfo(seasonNumber = seasonNumber, title = null, year = null, releaseDate = "2026-01-0$seasonNumber"),
                bangumi = null,
                seasonPath = "$folder/Season $seasonNumber",
                seasonPosterPath = null,
                episodes = listOf(
                    EpisodeNfo(
                        title = null, plot = null, rating = null, year = null, aired = null,
                        episode = 1, season = seasonNumber, runtime = null,
                    ) to EpisodeFile(
                        videoPath = "$folder/Season $seasonNumber/01.mkv",
                        videoName = "01.mkv",
                        thumbPath = null,
                        mediaKey = null,
                        fileSize = 1L,
                    ),
                ),
            )
        },
    )

    private suspend fun ScrapedLibraryRepositoryImpl.upsertSeasonPosterMeta(
        libraryId: Long,
        showPath: String,
        seasonNumber: Int,
        remotePosterUrl: String,
        localPosterPath: String?,
    ) {
        upsertOnlineMeta(
            libraryId = libraryId, showPath = showPath, seasonNumber = seasonNumber,
            source = ScrapeSource.BANGUMI, overwriteTitle = false,
            dandanplayId = null, bangumiId = null,
            remotePosterUrl = remotePosterUrl, localPosterPath = localPosterPath,
            title = null, originalTitle = null, year = null, plot = null, rating = null,
            releaseDate = null, genres = emptyList(), studios = emptyList(),
            episodes = emptyList(), scrapedAt = 1L,
        )
    }
}
