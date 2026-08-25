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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class DesktopMediaLibraryIntegrationTest {

    @Test
    fun `Windows 海报墙拼音排序忽略旧收藏字段并提供确定性回退`() = runBlocking {
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
            assertEquals(
                setOf(zheGeId, zhongGuoFirstId, zhongGuoSecondId, aBaoId, shenHuaId, daoJianId),
                quarter.map { it.id }.toSet(),
            )

            val pinyin = repository.listShows(libraryId, PosterWallSort.PINYIN)
            assertEquals(
                listOf(aBaoId, daoJianId, shenHuaId, zhongGuoFirstId, zhongGuoSecondId, zheGeId),
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
    fun `NFO库缺季度NFO时按一致季号接纳且歧义刷新保留旧剧集`() = runBlocking {
        val parent = Files.createTempDirectory("unu-library-missing-season-nfo-")
        val mediaRoot = parent.resolve("媒体库").createDirectories()
        val showDir = mediaRoot.resolve("【我推的孩子】 第二季 {tmdb-203737}").createDirectories()
        val seasonDir = showDir.resolve("Season 2").createDirectories()
        showDir.resolve("tvshow.nfo").writeText(
            """<tvshow><tmdbid>203737</tmdbid><title>【我推的孩子】</title><year>2023</year></tvshow>""",
        )
        seasonDir.resolve("bangumi.ini").writeText("[Bangumi]\nid=443428\noffset=-11\n")
        val firstVideo = seasonDir.resolve("[LoliHouse] 【我推的孩子】 第二季 S02E01.mkv").createFile()
        val secondVideo = seasonDir.resolve("[LoliHouse] 【我推的孩子】 第二季 S02E02.mkv").createFile()

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
                name = "缺季度 NFO 测试库",
                sourceKind = MediaSourceKind.LOCAL,
                connectionId = null,
                localUri = mediaRoot.toString(),
                rootPath = mediaRoot.toString(),
                scanDepth = 5,
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
                    depth = 5,
                    timeoutSeconds = 30,
                ),
            )

            val firstScan = scanner().scan()

            assertEquals(0, firstScan.errors, firstScan.toString())
            assertEquals(1, firstScan.foundShows)
            assertEquals(2, firstScan.foundEpisodes)
            val show = requireNotNull(repository.getShowByPath(libraryId, showDir.toString()))
            val season = repository.listSeasons(show.id).single()
            assertEquals(2L, season.season_number)
            assertEquals(443428L, season.bangumi_id)
            assertEquals(-11L, season.bangumi_offset)
            assertEquals(listOf(1L, 2L), repository.listEpisodes(season.id).map { it.episode_number })

            firstVideo.deleteIfExists()
            secondVideo.deleteIfExists()
            seasonDir.resolve("错误季号 S03E01.mkv").createFile()

            val ambiguousRefresh = scanner().scanOneShow(showDir.toString())

            assertTrue(ambiguousRefresh.errors > 0, ambiguousRefresh.toString())
            val preservedSeason = repository.listSeasons(show.id).single()
            assertEquals(2L, preservedSeason.season_number)
            assertEquals(listOf(1L, 2L), repository.listEpisodes(preservedSeason.id).map { it.episode_number })
        } finally {
            driver.close()
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }

    @Test
    fun `无职第二部分重扫后仍由精确Bangumi单集来源纠正错误NFO`() = runBlocking {
        val parent = Files.createTempDirectory("unu-library-mushoku-part2-")
        val mediaRoot = parent.resolve("媒体库").createDirectories()
        val showDir = mediaRoot.resolve("无职转生～到了异世界就拿出真本事～ 第2部分 {tmdb-94664}").createDirectories()
        val seasonDir = showDir.resolve("Season 1").createDirectories()
        showDir.resolve("tvshow.nfo").writeText(
            """<tvshow><tmdbid>94664</tmdbid><title>无职转生</title></tvshow>""",
        )
        seasonDir.resolve("season.nfo").writeText(
            """<season><seasonnumber>1</seasonnumber><title>第 1 季</title></season>""",
        )
        seasonDir.resolve("bangumi.ini").writeText("[Bangumi]\nid=325585\noffset=-11\n")
        val baseName = "[ANi] 无职转生 第2部分 - S01E01 - 持有魔眼的女人"
        seasonDir.resolve("$baseName.mkv").createFile()
        seasonDir.resolve("$baseName.nfo").writeText(
            """<episodedetails><title>无职转生</title><aired>2021-01-11</aired><season>1</season><episode>1</episode></episodedetails>""",
        )

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
                name = "无职分段测试库",
                sourceKind = MediaSourceKind.LOCAL,
                connectionId = null,
                localUri = mediaRoot.toString(),
                rootPath = mediaRoot.toString(),
                scanDepth = 5,
            )
            val library = requireNotNull(repository.getLibrary(libraryId))
            val source = DesktopLocalSource(mediaRoot.toString())
            fun scanner() = ScrapedLibraryScanner(
                source = source,
                library = library,
                repo = repository,
                config = ScanConfig(requestIntervalMs = 0, concurrency = 2, depth = 5, timeoutSeconds = 30),
            )

            assertEquals(0, scanner().scan().errors)
            val show = requireNotNull(repository.getShowByPath(libraryId, showDir.toString()))
            suspend fun scannedEpisode() = repository.listEpisodes(repository.listSeasons(show.id).single().id).single()
            assertEquals("无职转生", scannedEpisode().title, "夹具必须先复现 Ani-RSS 写入的第一部分错误 NFO")

            repository.upsertOnlineMeta(
                libraryId = libraryId,
                showPath = showDir.toString(),
                seasonNumber = 1,
                source = ScrapeSource.BANGUMI,
                overwriteTitle = false,
                dandanplayId = null,
                bangumiId = 325585L,
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
                episodes = listOf(
                    ScrapedOnlineEpisode(
                        episodeNumber = 1,
                        title = "持有魔眼的女人",
                        aired = "2021-10-03",
                        catalogCoordinates = EpisodeCatalogCoordinates(
                            provider = EpisodeCatalogProvider.BANGUMI,
                            seriesId = 325585L,
                            episodeId = 1002052L,
                            episodeNumber = 1,
                            absoluteEpisodeNumber = 12,
                            bangumiSubjectId = 325585L,
                        ),
                    ),
                ),
                scrapedAt = 10L,
            )
            repository.reapplyOnlineMeta(libraryId, showDir.toString())
            assertEquals("持有魔眼的女人", scannedEpisode().title)

            repository.upsertOnlineMeta(
                libraryId = libraryId,
                showPath = showDir.toString(),
                seasonNumber = 1,
                source = ScrapeSource.TMDB,
                overwriteTitle = false,
                dandanplayId = null,
                bangumiId = null,
                remotePosterUrl = "/season-1.jpg",
                localPosterPath = "/cache/season-1.jpg",
                title = null,
                originalTitle = null,
                year = null,
                plot = null,
                rating = null,
                releaseDate = null,
                genres = emptyList(),
                studios = emptyList(),
                episodes = emptyList(),
                scrapedAt = 20L,
            )
            assertEquals(ScrapeSource.BANGUMI, assertNotNull(repository.getOnlineMeta(libraryId, showDir.toString(), 1)).source)

            val refresh = scanner().scanOneShow(showDir.toString())
            assertEquals(0, refresh.errors, refresh.toString())
            assertEquals("持有魔眼的女人", scannedEpisode().title, "重扫读回错误 NFO 后必须再次应用当前 subject 的在线单集")
            assertEquals("2021-10-03", scannedEpisode().aired)
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
