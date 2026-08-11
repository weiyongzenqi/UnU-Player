package io.github.weiyongzenqi.unuplayer.library

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteDataSource
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.local.DesktopLocalSource
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabase
import io.github.weiyongzenqi.unuplayer.playback.configuredDesktopDataSource
import io.github.weiyongzenqi.unuplayer.playback.ensureCurrentDesktopSchema
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime

class DesktopMediaLibraryIntegrationTest {

    @Test
    fun `Windows 海报墙拼音排序保留收藏置顶并提供确定性回退`() = runBlocking {
        val parent = Files.createTempDirectory("unu-library-pinyin-")
        val dbFile = parent.resolve("library.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val database = UnuDatabase(driver)
            val repository = ScrapedLibraryRepositoryImpl(database.scrapedQueries)
            val libraryId = repository.addLibrary(
                name = "拼音排序测试库",
                sourceKind = MediaSourceKind.LOCAL,
                connectionId = null,
                localUri = parent.toString(),
                rootPath = parent.toString(),
                scanDepth = 1,
            )

            suspend fun addShow(title: String, path: String): Long = repository.upsertShow(
                libraryId = libraryId,
                sourceKind = MediaSourceKind.LOCAL,
                tmdbId = null,
                folderName = path,
                showPath = path,
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
                seasons = emptyList(),
            )

            val zheGeId = addShow("这个", "show-zhe-ge")
            val zhongGuoFirstId = addShow("中国", "show-zhong-guo-1")
            val zhongGuoSecondId = addShow("中国", "show-zhong-guo-2")
            val aBaoId = addShow("阿宝", "show-a-bao")
            val shenHuaId = addShow("神话", "show-shen-hua")
            val daoJianId = addShow("刀剑", "show-dao-jian")
            val hiddenId = addShow("白夜", "show-hidden")
            val blockedId = addShow("测试", "show-blocked")
            database.scrapedQueries.setFavorite(is_favorite = 1L, favorited_at = 200L, id = shenHuaId)
            database.scrapedQueries.setFavorite(is_favorite = 1L, favorited_at = 100L, id = daoJianId)
            repository.setHidden(hiddenId, true)
            repository.blockShow(blockedId)

            val quarter = repository.listShows(libraryId, PosterWallSort.QUARTER)
            assertEquals(listOf(shenHuaId, daoJianId), quarter.take(2).map { it.id })

            val pinyin = repository.listShows(libraryId, PosterWallSort.PINYIN)
            assertEquals(
                listOf(shenHuaId, daoJianId, aBaoId, zhongGuoFirstId, zhongGuoSecondId, zheGeId),
                pinyin.map { it.id },
            )
            assertFalse(pinyin.any { it.id == hiddenId || it.id == blockedId })
        } finally {
            driver.close()
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }

    @Test
    fun `Windows 本地 NFO 库可扫描中文路径大写 NFO和重复集号`() = runBlocking {
        val parent = Files.createTempDirectory("unu-library-integration-")
        val mediaRoot = parent.resolve("媒体 库 [测试]").createDirectories()
        val showDir = mediaRoot.resolve("测试番剧 + Special").createDirectories()
        val seasonDir = showDir.resolve("[BDRip] 测试番剧 第01季 完结").createDirectories()
        showDir.resolve("tvshow.nfo").writeText(
            """<tvshow><tmdbid>42</tmdbid><title>测试番剧</title><year>2026</year></tvshow>""",
        )
        seasonDir.resolve("season.nfo").writeText(
            """<season><seasonnumber>1</seasonnumber><title>第一季</title></season>""",
        )
        seasonDir.resolve("Bonus.mkv").createFile()
        seasonDir.resolve("Episode S01E01.mkv").createFile()
        seasonDir.resolve("Episode S01E01.NFO").writeText(
            """<episodedetails><episode>1</episode><season>1</season><title>第一集</title></episodedetails>""",
        )
        seasonDir.resolve("Episode duplicate S01E01.mkv").createFile()

        val dbFile = parent.resolve("library.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val database = UnuDatabase(driver)
            val repository = ScrapedLibraryRepositoryImpl(database.scrapedQueries)
            val libraryId = repository.addLibrary(
                name = "本地测试库",
                sourceKind = MediaSourceKind.LOCAL,
                connectionId = null,
                localUri = mediaRoot.toString(),
                rootPath = mediaRoot.toString(),
                scanDepth = 5,
            )
            val library = requireNotNull(repository.getLibrary(libraryId))
            val source = DesktopLocalSource(mediaRoot.toString())
            val scanner = ScrapedLibraryScanner(
                source = source,
                library = library,
                repo = repository,
                config = ScanConfig(
                    requestIntervalMs = 1_000,
                    concurrency = 4,
                    depth = 5,
                    timeoutSeconds = 30,
                ),
            )

            lateinit var result: ScanResult
            val elapsed = measureTime { result = scanner.scan() }

            assertFalse(result.timedOut)
            assertFalse(result.stopped)
            assertEquals(0, result.errors, result.toString())
            assertEquals(1, result.foundShows)
            assertEquals(3, result.foundEpisodes)
            assertTrue(elapsed.inWholeMilliseconds < 3_000, "本地扫描不应应用 1 秒网络限流：$elapsed")

            val shows = repository.listShows(libraryId)
            assertEquals(listOf("测试番剧"), shows.map { it.title })
            val seasons = repository.listSeasons(shows.single().id)
            val episodes = repository.listEpisodes(seasons.single().id)
            assertEquals(listOf(1L, 2L, 3L), episodes.map { it.episode_number })
            assertEquals("第一集", episodes.single { it.episode_number == 1L }.title)
        } finally {
            driver.close()
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }

    @Test
    fun `ANCHOR扫描支持通配季目录季包装目录和自然季度分组`() = runBlocking {
        val parent = Files.createTempDirectory("unu-library-anchor-season-")
        val mediaRoot = parent.resolve("媒体库").createDirectories()
        val showA = mediaRoot.resolve("番剧A").createDirectories()
        val showASeason = showA.resolve("[BDRip] 番剧A 第02季 完结").createDirectories()
        showASeason.resolve("番剧A 第01话.mp4").createFile()

        val seasonWrapper = mediaRoot.resolve("第3季").createDirectories()
        val showB = seasonWrapper.resolve("番剧B").createDirectories()
        showB.resolve("番剧B 第01话.mkv").createFile()

        val calendarWrapper = mediaRoot.resolve("2025年第2季度新番").createDirectories()
        val showC = calendarWrapper.resolve("番剧C").createDirectories()
        showC.resolve("番剧C 第01话.mp4").createFile()

        val showD = mediaRoot.resolve("番剧D 第04季 [1080p]").createDirectories()
        showD.resolve("番剧D 第01话.mp4").createFile()

        val dbFile = parent.resolve("library.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val database = UnuDatabase(driver)
            val repository = ScrapedLibraryRepositoryImpl(database.scrapedQueries)
            val libraryId = repository.addLibrary(
                name = "ANCHOR季目录测试库",
                sourceKind = MediaSourceKind.LOCAL,
                connectionId = null,
                localUri = mediaRoot.toString(),
                rootPath = mediaRoot.toString(),
                scanDepth = 6,
                scanMode = ScanMode.ANCHOR,
                anchorFilenames = emptyList(),
            )
            val library = requireNotNull(repository.getLibrary(libraryId))
            val source = DesktopLocalSource(mediaRoot.toString())
            fun scanner() = ScrapedLibraryScanner(
                source = source,
                library = library,
                repo = repository,
                config = ScanConfig(
                    requestIntervalMs = 0,
                    concurrency = 4,
                    depth = 6,
                    timeoutSeconds = 30,
                ),
            )

            val result = scanner().scan()

            assertEquals(0, result.errors, result.toString())
            assertEquals(4, result.foundShows)
            assertEquals(4, result.foundEpisodes)
            val expectedSeasons = mapOf(
                showA.toString() to 2L,
                showB.toString() to 3L,
                showC.toString() to 1L,
                showD.toString() to 4L,
            )
            expectedSeasons.forEach { (showPath, expectedSeason) ->
                val show = requireNotNull(repository.getShowByPath(libraryId, showPath))
                assertEquals(expectedSeason, repository.listSeasons(show.id).single().season_number, showPath)
            }

            val showBBeforeRescan = requireNotNull(repository.getShowByPath(libraryId, showB.toString()))
            repository.deleteShow(showBBeforeRescan.id)
            val rescan = scanner().rescanDir(mediaRoot.toString())
            assertEquals(0, rescan.errors, rescan.toString())
            val showBAfterRescan = requireNotNull(repository.getShowByPath(libraryId, showB.toString()))
            assertEquals(3L, repository.listSeasons(showBAfterRescan.id).single().season_number)

            // 单番剧刷新必须保留包装目录或自身名称提供的季号，不能回落成第1季。
            listOf(showB, showC, showD).forEach { showPath ->
                val refresh = scanner().scanOneShow(showPath.toString())
                assertEquals(0, refresh.errors, showPath.toString())
            }
            expectedSeasons.forEach { (showPath, expectedSeason) ->
                val show = requireNotNull(repository.getShowByPath(libraryId, showPath))
                assertEquals(expectedSeason, repository.listSeasons(show.id).single().season_number, showPath)
            }
        } finally {
            driver.close()
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }
}
