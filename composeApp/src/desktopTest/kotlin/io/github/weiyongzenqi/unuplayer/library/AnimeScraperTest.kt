package io.github.weiyongzenqi.unuplayer.library

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkSource
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkState
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiScrapeApi
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonIdentity
import io.github.weiyongzenqi.unuplayer.bangumi.TmdbScrapeApi
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabase
import io.github.weiyongzenqi.unuplayer.playback.configuredDesktopDataSource
import io.github.weiyongzenqi.unuplayer.playback.ensureCurrentDesktopSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteDataSource
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimeScraperTest {

    private class FakeDownloader : RemoteImageDownloader {
        override suspend fun downloadImage(
            libraryId: Long, showPath: String, fileName: String, remoteUrl: String,
        ): String? = "/cache/online-scrape/${libraryId}-$fileName"
    }

    // 专用客户端(不走进程级共享单例): 套件里 DanmakuNetworkLifecycleTest 会 closeSharedHttpClient,
    // 共享单例被关后后续请求全崩; 每测试新建私有 OkHttp 客户端, 短命 JVM 下可接受。
    private fun testClient(): HttpClient = HttpClient(OkHttp) { followRedirects = false }
    private fun dandanApi(serverUrl: String) = DandanplayApi(baseUrl = serverUrl, httpClient = testClient())
    private fun bangumiApi() = BangumiScrapeApi(baseUrl = "http://127.0.0.1:1", httpClient = testClient())

    @Test
    fun `TMDB搜索关键词会过滤季度标记`() {
        val cases = listOf(
            "测试番剧 第2季" to "测试番剧",
            "测试番剧 第二季" to "测试番剧",
            "测试番剧（第 2 期）" to "测试番剧",
            "测试番剧 第2部" to "测试番剧",
            "测试番剧 Season 2" to "测试番剧",
            "测试番剧 Season II" to "测试番剧",
            "测试番剧 2nd Season" to "测试番剧",
            "测试番剧 S02" to "测试番剧",
            "测试番剧S02" to "测试番剧",
            "测试番剧 第2季 2024" to "测试番剧",
            "[字幕组] 测试番剧（第十二季） 1080p.mkv" to "测试番剧",
            "测试番剧 {tmdb=-}" to "测试番剧",
            "测试番剧 {tmdb=123456}" to "测试番剧",
            "测试番剧{tmdb=-}" to "测试番剧",
            "测试番剧 (BD 1080P)" to "测试番剧",
            "测试番剧（BD 1080P）" to "测试番剧",
            "孤独摇滚 {tmdb=-} [BD 1080p]" to "孤独摇滚",
            // 含 CJK 文字(假名/谚文)的括号组是标题成分, 不得当质量标签剥掉
            "测试番剧 (サイドストーリー)" to "测试番剧 (サイドストーリー)",
            "测试番剧 (한국어 부제)" to "测试番剧 (한국어 부제)",
        )

        cases.forEach { (raw, expected) ->
            assertEquals(expected, cleanTmdbSearchKeyword(raw), raw)
        }
    }

    @Test
    fun `取消自动刮削后同一番剧可以再次启动`() = runBlocking {
        withDb { repo, libraryId, showPath, _ ->
            val scraper = AnimeScraper(
                dandanplay = null,
                bangumi = BangumiScrapeProvider(bangumiApi()),
                downloader = FakeDownloader(),
                repo = repo,
            )
            val progressStarted = CompletableDeferred<Unit>()
            val first = async {
                scraper.scrapeAuto(libraryOf(libraryId), showPath) {
                    progressStarted.complete(Unit)
                    awaitCancellation()
                }
            }

            progressStarted.await()
            first.cancelAndJoin()

            val second = scraper.scrapeAuto(libraryOf(libraryId), showPath)
            assertIs<AnimeScraper.AutoScrapeOutcome.RetryableFailure>(second)
            assertNull(repo.lastOnlineScrapeAt(libraryId, showPath))
        }
    }

    @Test
    fun `在线搜索失败不伪装成未命中也不写24小时节流`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(503, "service unavailable")
            }
            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertIs<AnimeScraper.AutoScrapeOutcome.RetryableFailure>(outcome)
                assertNull(repo.lastOnlineScrapeAt(libraryId, showPath))
                assertTrue(scraper.shouldAutoScrape(libraryId, showPath))
            }
        }
    }

    @Test
    fun `Bangumi基础资料成功但分集失败会保留数据并立即重试`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"id":400602,"type":2,"name":"Test","name_cn":"测试番剧","date":"2024-01-01"}]}""",
                )
            }
            server.createContext("/v0/subjects/400602") { exchange ->
                exchange.respond(
                    200,
                    """{"id":400602,"type":2,"name":"Test","name_cn":"测试番剧","date":"2024-01-01","summary":"已获取简介"}""",
                )
            }
            server.createContext("/v0/episodes") { exchange ->
                exchange.respond(503, "service unavailable")
            }
            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                val partial = assertIs<AnimeScraper.AutoScrapeOutcome.Partial>(outcome)
                assertEquals(1, partial.seasonsScraped)
                assertEquals("已获取简介", repo.getOnlineMeta(libraryId, showPath, 0)?.plot)
                assertTrue(repo.getOnlineMeta(libraryId, showPath, 1)?.decodedEpisodes?.isEmpty() == true)
                assertNull(repo.lastOnlineScrapeAt(libraryId, showPath))
                assertTrue(scraper.shouldAutoScrape(libraryId, showPath))
            }
        }
    }

    @Test
    fun `无评分与未配置TMDB不会让完整NFO重复进入批量补刮`() = runBlocking {
        withDb(
            showPosterPath = "/media/poster.jpg",
            seasonPosterPath = "/media/season-poster.jpg",
            showPlot = "完整简介",
            showRating = null,
            scanMode = ScanMode.NFO,
            episodeTitlePrefix = "第",
            episodeAired = "2024-01-01",
        ) { repo, libraryId, showPath, _ ->
            repo.upsertOnlineMeta(
                libraryId = libraryId, showPath = showPath, seasonNumber = 0,
                source = ScrapeSource.BANGUMI, overwriteTitle = false,
                dandanplayId = null, bangumiId = 10L,
                remotePosterUrl = null, localPosterPath = null,
                title = "测试番剧", originalTitle = null, year = 2024, plot = "完整简介", rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = emptyList(), scrapedAt = 1L,
            )

            assertFalse(
                repo.listScrapePending(libraryId, anchorOnly = false, requireTmdbIdentity = false)
                    .any { it.showPath == showPath },
            )
            assertTrue(
                repo.listScrapePending(libraryId, anchorOnly = false, requireTmdbIdentity = true)
                    .any { it.showPath == showPath },
            )
        }
    }

    @Test
    fun `在线刮削完整但无NFO海报的番剧不再重复进入批量补刮`() = runBlocking {
        // NFO 库番剧无 poster.jpg/season-poster, 但在线刮削已写完整部级(plot)+季级(集标题/放送日/本地季照)。
        // poster 判定应看在线 meta(经仓库文件复核), 不应被 NFO 媒体源字段 poster_path 恒 null 误判为待刮。
        val realPoster = Files.createTempFile("unu-online-poster", ".jpg").toAbsolutePath().toString()
        try {
            withDb(
                showPosterPath = null,
                seasonPosterPath = null,
                showPlot = null,
                scanMode = ScanMode.NFO,
                episodeTitlePrefix = "第",
                episodeAired = "2024-01-01",
            ) { repo, libraryId, showPath, _ ->
                repo.upsertOnlineMeta(
                    libraryId = libraryId, showPath = showPath, seasonNumber = 0,
                    source = ScrapeSource.BANGUMI, overwriteTitle = false,
                    dandanplayId = null, bangumiId = 10L,
                    remotePosterUrl = null, localPosterPath = null,
                    title = "测试番剧", originalTitle = null, year = 2024, plot = "完整简介", rating = null,
                    releaseDate = null, genres = emptyList(), studios = emptyList(),
                    episodes = emptyList(), scrapedAt = platformTimeMillis(),
                )
                repo.upsertOnlineMeta(
                    libraryId = libraryId, showPath = showPath, seasonNumber = 1,
                    source = ScrapeSource.BANGUMI, overwriteTitle = false,
                    dandanplayId = null, bangumiId = 10L,
                    remotePosterUrl = null, localPosterPath = realPoster,
                    title = null, originalTitle = null, year = null, plot = null, rating = null,
                    releaseDate = null, genres = emptyList(), studios = emptyList(),
                    episodes = listOf(
                        ScrapedOnlineEpisode(1, "第1集", "2024-01-01"),
                        ScrapedOnlineEpisode(2, "第2集", "2024-01-01"),
                    ),
                    scrapedAt = platformTimeMillis(),
                )
                // 真实流程: 刮削成功后 reapply 把 plot/标题回填到 ScrapedShow
                repo.reapplyOnlineMeta(libraryId, showPath)
                assertFalse(
                    repo.listScrapePending(libraryId, anchorOnly = false, requireTmdbIdentity = false)
                        .any { it.showPath == showPath },
                )
            }
        } finally {
            runCatching { Files.deleteIfExists(java.nio.file.Path.of(realPoster)) }
        }
    }

    @Test
    fun `批量冷却过滤最近已尝试未命中的番剧但保留重试标记`() = runBlocking {
        withDb(scanMode = ScanMode.ANCHOR) { repo, libraryId, showPath, _ ->
            // 部级 AUTO_ATTEMPT(最近尝试未命中), 集标题/aired 仍缺 -> gap=1
            repo.upsertOnlineMeta(
                libraryId = libraryId, showPath = showPath, seasonNumber = 0,
                source = ScrapeSource.AUTO_ATTEMPT, overwriteTitle = false,
                dandanplayId = null, bangumiId = null,
                remotePosterUrl = null, localPosterPath = null,
                title = null, originalTitle = null, year = null, plot = null, rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = emptyList(), scrapedAt = platformTimeMillis(),
            )
            val now = platformTimeMillis()
            val cooldown = 24L * 60L * 60L * 1000L
            // 无冷却: 进入 pending
            assertTrue(
                repo.listScrapePending(libraryId, anchorOnly = true, requireTmdbIdentity = false, cooldownMs = 0L, nowMs = now)
                    .any { it.showPath == showPath },
            )
            // 冷却期内: 跳过
            assertFalse(
                repo.listScrapePending(libraryId, anchorOnly = true, requireTmdbIdentity = false, cooldownMs = cooldown, nowMs = now)
                    .any { it.showPath == showPath },
            )
            // 冷却过期: 恢复 pending
            assertTrue(
                repo.listScrapePending(libraryId, anchorOnly = true, requireTmdbIdentity = false, cooldownMs = cooldown, nowMs = now + cooldown + 1000L)
                    .any { it.showPath == showPath },
            )
            // 重试标记: 冷却期内也立即重试
            repo.markAutoScrapeRetryable(libraryId, showPath)
            assertTrue(
                repo.listScrapePending(libraryId, anchorOnly = true, requireTmdbIdentity = false, cooldownMs = cooldown, nowMs = now)
                    .any { it.showPath == showPath },
            )
        }
    }

    @Test
    fun `在线刮削识别tmdb后本部覆盖设置从show键迁移到tmdb键`() = runBlocking {
        withDb(scanMode = ScanMode.ANCHOR) { repo, libraryId, showPath, _ ->
            val legacyKey = ShowOverrideIdentity.anchor(libraryId, showPath)
            val json = """{"danmakuOpacity":0.8,"subtitleSize":2}"""
            repo.upsertShowOverride(legacyKey, json, 123L)
            assertEquals(json, repo.getShowOverrideJson(legacyKey))

            repo.persistTmdbId(
                libraryId = libraryId, showPath = showPath, tmdbId = 777L,
                source = ScrapeSource.MANUAL_TMDB, scrapedAt = 1L,
            )

            // 旧 show: 键清空, 新 tmdb: 键拿到同一份覆盖
            assertNull(repo.getShowOverrideJson(legacyKey))
            assertEquals(json, repo.getShowOverrideJson(ShowOverrideIdentity.tmdb(777L)))
        }
    }

    @Test
    fun `覆盖设置迁移不覆盖更新的tmdb键设置`() = runBlocking {
        withDb(scanMode = ScanMode.ANCHOR) { repo, libraryId, showPath, _ ->
            val legacyKey = ShowOverrideIdentity.anchor(libraryId, showPath)
            repo.upsertShowOverride(legacyKey, """{"danmakuOpacity":0.8}""", 100L)
            // 目标 tmdb 键已有更新设置(updated_at 200 > 100): 迁移不得覆盖
            repo.upsertShowOverride(ShowOverrideIdentity.tmdb(777L), """{"danmakuOpacity":0.5}""", 200L)

            repo.persistTmdbId(
                libraryId = libraryId, showPath = showPath, tmdbId = 777L,
                source = ScrapeSource.MANUAL_TMDB, scrapedAt = 1L,
            )

            assertEquals("""{"danmakuOpacity":0.5}""", repo.getShowOverrideJson(ShowOverrideIdentity.tmdb(777L)))
            assertNull(repo.getShowOverrideJson(legacyKey), "来源孤儿键应清理")
        }
    }

    @Test
    fun `覆盖设置迁移用更新的来源覆盖更旧的tmdb键`() = runBlocking {
        withDb(scanMode = ScanMode.ANCHOR) { repo, libraryId, showPath, _ ->
            val legacyKey = ShowOverrideIdentity.anchor(libraryId, showPath)
            repo.upsertShowOverride(legacyKey, """{"danmakuOpacity":0.8}""", 300L)
            // 目标 tmdb 键更旧(updated_at 200 < 300): 用来源覆盖
            repo.upsertShowOverride(ShowOverrideIdentity.tmdb(777L), """{"danmakuOpacity":0.5}""", 200L)

            repo.persistTmdbId(
                libraryId = libraryId, showPath = showPath, tmdbId = 777L,
                source = ScrapeSource.MANUAL_TMDB, scrapedAt = 1L,
            )

            assertEquals("""{"danmakuOpacity":0.8}""", repo.getShowOverrideJson(ShowOverrideIdentity.tmdb(777L)))
            assertNull(repo.getShowOverrideJson(legacyKey))
        }
    }

    @Test
    fun `关闭唯一结果放宽时TMDB自动匹配不会持久化标题完全无关的首个候选`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(200, """{"data":[]}""")
            }
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                exchange.respond(
                    200,
                    """{"candidates":[{"tmdbId":999,"name":"完全不同作品","firstAirDate":"2024-01-01"}]}""",
                )
            }
            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                    uniqueCandidateAutoApply = false,
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertIs<AnimeScraper.AutoScrapeOutcome.NoMatch>(outcome)
                assertNull(repo.getShowByPath(libraryId, showPath)?.tmdb_id)
                val failure = assertNotNull(repo.getTmdbAutoMatchFailure(libraryId, showPath))
                assertFalse(failure.promptSuppressed)

                repo.suppressTmdbAutoMatchPrompt(libraryId, showPath)
                scraper.scrapeAuto(libraryOf(libraryId), showPath)
                assertTrue(repo.getTmdbAutoMatchFailure(libraryId, showPath)?.promptSuppressed == true)
            }
        }
    }

    @Test
    fun `TMDB唯一结果年份兼容默认自动应用`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(200, """{"data":[]}""")
            }
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                exchange.respond(
                    200,
                    """{"candidates":[{"tmdbId":999,"name":"完全无关作品","firstAirDate":"2024-01-01"}]}""",
                )
            }
            server.createContext("/api/v1/tmdb/tv/999/images") { exchange ->
                exchange.respond(200, """{"tvId":999,"backdrops":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/999/season/1/episodes") { exchange ->
                exchange.respond(200, """{"tvId":999,"seasonNumber":1,"episodes":[]}""")
            }
            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertIs<AnimeScraper.AutoScrapeOutcome.Done>(outcome)
                assertEquals(999L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
                assertNull(repo.getTmdbAutoMatchFailure(libraryId, showPath))
            }
        }
    }

    @Test
    fun `文件夹名带tmdb标记时自动匹配Bangumi并沿用清洗词搜TMDB`() = runBlocking {
        withServer { serverUrl, server ->
            var tmdbSearchHits = 0
            var tmdbQuery: String? = null
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"id":400602,"type":2,"name":"Test","name_cn":"测试番剧","date":"2024-01-01"}]}""",
                )
            }
            server.createContext("/v0/subjects/400602") { exchange ->
                exchange.respond(
                    200,
                    """{"id":400602,"type":2,"name":"Test","name_cn":"测试番剧","date":"2024-01-01",
                        "summary":"部级简介","rating":{"score":8.5,"total":10},"images":{},"eps":2}""",
                )
            }
            server.createContext("/v0/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[
                        {"id":9001,"type":0,"sort":1,"name_cn":"第一集","airdate":"2024-01-01"},
                        {"id":9002,"type":0,"sort":2,"name_cn":"第二集","airdate":"2024-01-08"}
                    ],"total":2}""",
                )
            }
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                tmdbSearchHits++
                tmdbQuery = exchange.requestURI.rawQuery
                    ?.split('&')
                    ?.firstOrNull { it.startsWith("query=") }
                    ?.substringAfter('=')
                    ?.let { URLDecoder.decode(it, "UTF-8") }
                exchange.respond(
                    200,
                    """{"candidates":[{"tmdbId":777,"name":"测试番剧","originalName":"Test","firstAirDate":"2024-01-01"}]}""",
                )
            }
            server.createContext("/api/v1/tmdb/tv/777/images") { exchange ->
                exchange.respond(200, """{"tvId":777,"backdrops":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/777/season/1/episodes") { exchange ->
                exchange.respond(200, """{"tvId":777,"seasonNumber":1,"episodes":[]}""")
            }

            withDb(showFolderName = "测试番剧 {tmdb=-}", showTitle = "测试番剧 {tmdb=-}") {
                    repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertIs<AnimeScraper.AutoScrapeOutcome.Done>(outcome)
                assertEquals("测试番剧", tmdbQuery, "TMDB 搜索词应剥除 {tmdb=-} 标记")
                assertEquals(1, tmdbSearchHits)
                assertEquals(777L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
            }
        }
    }

    @Test
    fun `Bangumi应用后用Bangumi规范标题重搜TMDB`() = runBlocking {
        withServer { serverUrl, server ->
            val tmdbQueries = mutableListOf<String>()
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"id":99,"type":2,"name":"Canonical Name","name_cn":"规范番剧名","date":"2024-01-01"}]}""",
                )
            }
            server.createContext("/v0/subjects/99") { exchange ->
                exchange.respond(
                    200,
                    """{"id":99,"type":2,"name":"Canonical Name","name_cn":"规范番剧名","date":"2024-01-01",
                        "summary":"部级简介","rating":{"score":8.5,"total":10},"images":{},"eps":2}""",
                )
            }
            server.createContext("/v0/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[
                        {"id":9001,"type":0,"sort":1,"name_cn":"第一集","airdate":"2024-01-01"},
                        {"id":9002,"type":0,"sort":2,"name_cn":"第二集","airdate":"2024-01-08"}
                    ],"total":2}""",
                )
            }
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                val query = exchange.requestURI.rawQuery
                    ?.split('&')
                    ?.firstOrNull { it.startsWith("query=") }
                    ?.substringAfter('=')
                    ?.let { URLDecoder.decode(it, "UTF-8") }
                if (query != null) tmdbQueries += query
                // 文件夹名搜索无命中(zh-CN/zh-TW 回退均空), 规范标题重搜命中唯一候选。
                if (query == "规范番剧名") {
                    exchange.respond(
                        200,
                        """{"candidates":[{"tmdbId":777,"name":"规范番剧名","originalName":"Canonical Name","firstAirDate":"2024-01-01"}]}""",
                    )
                } else {
                    exchange.respond(200, """{"candidates":[]}""")
                }
            }
            server.createContext("/api/v1/tmdb/tv/777/images") { exchange ->
                exchange.respond(200, """{"tvId":777,"backdrops":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/777/season/1/episodes") { exchange ->
                exchange.respond(200, """{"tvId":777,"seasonNumber":1,"episodes":[]}""")
            }

            withDb(showFolderName = "测试番剧别名", showTitle = "测试番剧别名") { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertIs<AnimeScraper.AutoScrapeOutcome.Done>(outcome)
                // 初始并行搜索用文件夹名, Bangumi 应用后按规范标题重搜(zh-CN/zh-TW 语言回退会产生重复查询)。
                assertTrue(tmdbQueries.any { it == "测试番剧别名" }, "应先用文件夹名搜索 TMDB: $tmdbQueries")
                assertEquals("规范番剧名", tmdbQueries.last())
                assertEquals(777L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
                assertNull(repo.getTmdbAutoMatchFailure(libraryId, showPath))
            }
        }
    }

    @Test
    fun `永久关闭自动刮削后详情页不自动触发且可恢复`() = runBlocking {
        withDb { repo, libraryId, showPath, _ ->
            val scraper = AnimeScraper(
                dandanplay = null,
                bangumi = BangumiScrapeProvider(bangumiApi()),
                downloader = FakeDownloader(),
                repo = repo,
            )

            assertTrue(scraper.shouldAutoScrape(libraryId, showPath))
            repo.suppressAutoScrape(libraryId, showPath, platformTimeMillis())
            assertTrue(repo.isAutoScrapeSuppressed(libraryId, showPath))
            assertFalse(scraper.shouldAutoScrape(libraryId, showPath))
            assertEquals(AnimeScraper.AutoScrapeMode.NONE, scraper.autoScrapeMode(libraryId, showPath))

            // 恢复入口: 详情页菜单「重新开启自动刮削」→ 立即回到可自动刮削状态。
            repo.unsuppressAutoScrape(libraryId, showPath)
            assertFalse(repo.isAutoScrapeSuppressed(libraryId, showPath))
            assertTrue(scraper.shouldAutoScrape(libraryId, showPath))
        }
    }

    @Test
    fun `未命中后写入尝试占位但不再被24小时节流拦住`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(200, """{"data":[]}""")
            }
            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient())),
                    downloader = FakeDownloader(),
                    repo = repo,
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertIs<AnimeScraper.AutoScrapeOutcome.NoMatch>(outcome)
                // 尝试占位仍写入(扫描后自动补的批量冷却依赖), 但详情页懒触发不再节流。
                assertEquals(
                    ScrapeSource.AUTO_ATTEMPT.storageName,
                    repo.getOnlineMeta(libraryId, showPath, 0)?.scrape_source,
                )
                assertTrue(scraper.shouldAutoScrape(libraryId, showPath))
                assertEquals(AnimeScraper.AutoScrapeMode.FULL, scraper.autoScrapeMode(libraryId, showPath))
            }
        }
    }

    @Test
    fun `Bangumi唯一应用但TMDB未确定时保留自动匹配失败记录`() = runBlocking {
        // 反例(评审 P2-2): 初始搜索经唯一放宽命中无关候选, 但规范标题重搜失败 -> 身份仍未确定,
        // 失败记录必须保留(详情页「TMDB 未能自动确定作品」提示仍可弹出), 不得因"搜到过候选"被清除。
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"id":99,"type":2,"name":"Canonical Name","name_cn":"规范番剧名","date":"2024-01-01"}]}""",
                )
            }
            server.createContext("/v0/subjects/99") { exchange ->
                exchange.respond(
                    200,
                    """{"id":99,"type":2,"name":"Canonical Name","name_cn":"规范番剧名","date":"2024-01-01",
                        "summary":"部级简介","rating":{"score":8.5,"total":10},"images":{},"eps":2}""",
                )
            }
            server.createContext("/v0/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[
                        {"id":9001,"type":0,"sort":1,"name_cn":"第一集","airdate":"2024-01-01"},
                        {"id":9002,"type":0,"sort":2,"name_cn":"第二集","airdate":"2024-01-08"}
                    ],"total":2}""",
                )
            }
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                val query = exchange.requestURI.rawQuery
                    ?.split('&')
                    ?.firstOrNull { it.startsWith("query=") }
                    ?.substringAfter('=')
                    ?.let { URLDecoder.decode(it, "UTF-8") }
                if (query == "规范番剧名") {
                    exchange.respond(200, """{"candidates":[]}""")
                } else {
                    exchange.respond(
                        200,
                        """{"candidates":[{"tmdbId":998,"name":"完全不同作品","firstAirDate":"2024-01-01"}]}""",
                    )
                }
            }

            withDb(showFolderName = "测试番剧别名", showTitle = "测试番剧别名") { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertIs<AnimeScraper.AutoScrapeOutcome.Done>(outcome)
                assertNull(repo.getShowByPath(libraryId, showPath)?.tmdb_id)
                assertNotNull(repo.getTmdbAutoMatchFailure(libraryId, showPath))
            }
        }
    }

    @Test
    fun `TMDB请求失败不会记录为自动匹配未命中`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(200, """{"data":[]}""")
            }
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                exchange.respond(503, "service unavailable")
            }
            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                )

                assertIs<AnimeScraper.AutoScrapeOutcome.RetryableFailure>(
                    scraper.scrapeAuto(libraryOf(libraryId), showPath),
                )
                assertNull(repo.getTmdbAutoMatchFailure(libraryId, showPath))
            }
        }
    }

    @Test
    fun `TMDB头图限流在其余数据完整时仍由标记立即重试并在成功后静默`() = runBlocking {
        val episodeThumb = Files.createTempFile("unu-nfo-episode-thumb-", ".jpg").toFile()
        try {
            withServer { serverUrl, server ->
                val imageRequests = AtomicInteger(0)
                server.createContext("/v0/search/subjects") { exchange ->
                    exchange.respond(200, """{"data":[]}""")
                }
                server.createContext("/api/v1/tmdb/tv/777/images") { exchange ->
                    if (imageRequests.incrementAndGet() == 1) {
                        exchange.respond(
                            429,
                            """{"error":{"code":"RATE_LIMITED","requestId":"images-429","retryAfterSeconds":2}}""",
                        )
                    } else {
                        exchange.respond(200, """{"tvId":777,"backdrops":[]}""")
                    }
                }
                server.createContext("/api/v1/tmdb/tv/777/season/1/episodes") { exchange ->
                    exchange.respond(200, """{"tvId":777,"seasonNumber":1,"episodes":[]}""")
                }
                withDb(
                    showTmdbId = 777L,
                    showPosterPath = "/media/poster.jpg",
                    showPlot = "完整简介",
                    episodeTitlePrefix = "第",
                    episodeAired = "2024-01-01",
                    episodeThumbPath = episodeThumb.absolutePath,
                ) { repo, libraryId, showPath, _ ->
                    val scraper = AnimeScraper(
                        dandanplay = null,
                        bangumi = BangumiScrapeProvider(
                            BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                        ),
                        downloader = FakeDownloader(),
                        repo = repo,
                        tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                    )

                    assertIs<AnimeScraper.AutoScrapeOutcome.Partial>(
                        scraper.scrapeAuto(libraryOf(libraryId), showPath),
                    )
                    assertEquals(777L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
                    assertTrue(repo.hasAutoScrapeRetryMarker(libraryId, showPath))
                    assertNull(repo.lastOnlineScrapeAt(libraryId, showPath))
                    assertTrue(scraper.shouldAutoScrape(libraryId, showPath))

                    assertIs<AnimeScraper.AutoScrapeOutcome.Done>(
                        scraper.scrapeAuto(libraryOf(libraryId), showPath),
                    )
                    assertEquals(2, imageRequests.get())
                    assertFalse(repo.hasAutoScrapeRetryMarker(libraryId, showPath))
                    assertNotNull(repo.lastOnlineScrapeAt(libraryId, showPath))
                    assertFalse(scraper.shouldAutoScrape(libraryId, showPath))
                }
            }
        } finally {
            episodeThumb.delete()
        }
    }

    @Test
    fun `TMDB季度请求失败会保留身份并返回部分成功且立即重试`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(200, """{"data":[]}""")
            }
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                exchange.respond(
                    200,
                    """{"candidates":[{"tmdbId":777,"name":"测试番剧","firstAirDate":"2024-01-01"}]}""",
                )
            }
            server.createContext("/api/v1/tmdb/tv/777/images") { exchange ->
                exchange.respond(200, """{"tvId":777,"backdrops":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/777/season/1/episodes") { exchange ->
                exchange.respond(503, "service unavailable")
            }
            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertIs<AnimeScraper.AutoScrapeOutcome.Partial>(outcome)
                assertEquals(777L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
                assertNull(repo.lastOnlineScrapeAt(libraryId, showPath))
                assertTrue(scraper.shouldAutoScrape(libraryId, showPath))
            }
        }
    }

    @Test
    fun `自动刮削并行查询三源且TMDB真实请求不带季度`() = runBlocking {
        withConcurrentServer { serverUrl, server ->
            val started = CountDownLatch(3)
            val concurrentWaiters = AtomicInteger(0)
            var tmdbQuery: String? = null
            var tmdbRawQuery: String? = null
            fun awaitOtherSources() {
                started.countDown()
                if (started.await(2, TimeUnit.SECONDS)) concurrentWaiters.incrementAndGet()
            }
            server.createContext("/v0/search/subjects") { exchange ->
                awaitOtherSources()
                exchange.respond(200, """{"data":[]}""")
            }
            server.createContext("/api/v2/search/anime") { exchange ->
                awaitOtherSources()
                exchange.respond(200, """{"success":true,"animes":[]}""")
            }
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                tmdbRawQuery = exchange.requestURI.rawQuery
                tmdbQuery = tmdbRawQuery
                    ?.split('&')
                    ?.firstOrNull { it.startsWith("query=") }
                    ?.substringAfter('=')
                    ?.let { URLDecoder.decode(it, "UTF-8") }
                awaitOtherSources()
                exchange.respond(200, """{"candidates":[]}""")
            }

            withDb(showFolderName = "测试番剧 第2季 2024", showTitle = "测试番剧 第2季 2024") {
                    repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi(
                        apiKey = "test-token",
                        httpClient = testClient(),
                        baseUrl = serverUrl,
                    ),
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertIs<AnimeScraper.AutoScrapeOutcome.NoMatch>(outcome)
                assertEquals("测试番剧", tmdbQuery)
                assertTrue(tmdbRawQuery?.contains("year=2024") == true)
                assertTrue(concurrentWaiters.get() >= 3, "Bangumi、弹弹和 TMDB 应并行启动，允许 TMDB 语言回退请求")
            }
        }
    }

    @Test
    fun `批量补刮从零开始并持续回报完成进度`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(200, """{"data":[]}""")
            }
            withDb { repo, libraryId, _, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                )
                val progress = mutableListOf<Triple<Int, Int, String>>()

                val successful = scraper.scrapePending(
                    library = libraryOf(libraryId),
                    anchorOnly = false,
                    concurrency = 1,
                    hashProvider = null,
                    onProgress = { completed, total, title -> progress += Triple(completed, total, title) },
                )

                assertEquals(0, successful)
                assertEquals(0, progress.first().first)
                assertEquals(1, progress.first().second)
                assertEquals(1, progress.last().first)
                assertEquals(1, progress.last().second)
            }
        }
    }

    @Test
    fun `文件名命中单季番写季级meta并由列表读取在线季照`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"animes":[
                        {"animeId":123,"animeTitle":"测试番剧","bangumiId":"400602","imageUrl":"https://lain.bgm.tv/a.jpg","startDate":"2023-09-29"}
                    ]}""",
                )
            }
            server.createContext("/api/v2/bangumi/123") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{
                        "animeId":123,"animeTitle":"测试番剧","imageUrl":"https://lain.bgm.tv/a.jpg",
                        "episodes":[
                            {"episodeId":9001,"episodeTitle":"旅程的起点","episodeNumber":"1","airDate":"2023-09-29"},
                            {"episodeId":9002,"episodeTitle":"魔法的使用","episodeNumber":"2","airDate":"2023-10-06"}
                        ],
                        "relateds":[]
                    }}""",
                )
            }

            withDb { repo, libraryId, showPath, showId ->
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(bangumiApi()),  // 不可达, provider 降级空
                    downloader = FakeDownloader(),
                    repo = repo,
                )
                val outcome = scraper.scrapeAuto(library = libraryOf(libraryId), showPath = showPath)

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.Done)
                assertEquals(1, outcome.seasonsScraped)

                val meta = repo.getOnlineMeta(libraryId, showPath, seasonNumber = 1)!!
                assertEquals(123L, meta.dandanplay_id)
                assertEquals("/cache/online-scrape/${libraryId}-season1.jpg", meta.local_poster_path)
                assertEquals(listOf(1, 2), meta.decodedEpisodes.map { it.episodeNumber })
                assertEquals("旅程的起点", meta.decodedEpisodes.first().title)

                // reapply 只回填文本字段；在线季照保持在 meta，由列表查询标记为本地缓存来源。
                val seasons = repo.listSeasons(showId)
                assertNull(seasons.single().season_poster_path)
                val card = repo.listShows(libraryId).single()
                assertEquals("/cache/online-scrape/${libraryId}-season1.jpg", card.card_poster_path)
                assertEquals(ScrapedImagePathKind.LOCAL_FILE.name, card.card_poster_path_kind)
                val episodes = repo.listEpisodes(seasons.single().id)
                assertEquals("旅程的起点", episodes.first { it.episode_number == 1L }.title)

                // 高置信命中(唯一候选 + bgm 桥)自动写 Bangumi 季度关联 -> 评论区立即可用
                val link = repo.getBangumiSeasonLink(BangumiSeasonIdentity.keyFor(null, libraryId, showPath, 1))
                assertTrue(link != null && link.state == BangumiLinkState.CONFIRMED)
                assertEquals(400602L, link.subjectId)
                assertEquals(BangumiLinkSource.AUTO, link.source)
            }
        }
    }

    @Test
    fun `每季hash锚点锁定各自animeId不遍历全文件`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(200, """{"success":true,"animes":[{"animeId":100,"animeTitle":"多季番"}]}""")
            }
            // 每季独立 hash 命中不同 animeId
            server.createContext("/api/v2/match") { exchange ->
                val body = exchange.requestBody.bufferedReader().readText()
                val animeId = when {
                    body.contains("season1.mkv") -> 100
                    body.contains("season2.mkv") -> 200
                    else -> 300
                }
                exchange.respond(
                    200,
                    """{"isMatched":true,"matches":[{"episodeId":1,"animeId":$animeId,"animeTitle":"季$animeId"}]}""",
                )
            }
            server.createContext("/api/v2/bangumi/100") { exchange ->
                exchange.respond(200, """{"success":true,"bangumi":{"animeId":100,"animeTitle":"季100","episodes":[{"episodeId":1,"episodeNumber":"1"}]}}""")
            }
            server.createContext("/api/v2/bangumi/200") { exchange ->
                exchange.respond(200, """{"success":true,"bangumi":{"animeId":200,"animeTitle":"季200","episodes":[{"episodeId":1,"episodeNumber":"1"}]}}""")
            }
            server.createContext("/api/v2/bangumi/300") { exchange ->
                exchange.respond(200, """{"success":true,"bangumi":{"animeId":300,"animeTitle":"季300","episodes":[{"episodeId":1,"episodeNumber":"1"}]}}""")
            }

            withDb(seasonCount = 3) { repo, libraryId, showPath, _ ->
                var hashCalls = 0
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                )
                val outcome = scraper.scrapeAuto(
                    library = libraryOf(libraryId),
                    showPath = showPath,
                    hashProvider = { path ->
                        hashCalls++
                        Pair(1024L, "hash-of-$path")
                    },
                )

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.Done)
                // 每季 hash 恰好 1 次, 绝不遍历全集
                assertEquals(3, hashCalls)
                for (seasonNumber in 1..3) {
                    val meta = repo.getOnlineMeta(libraryId, showPath, seasonNumber)!!
                    assertEquals((seasonNumber * 100).toLong(), meta.dandanplay_id)
                }
            }
        }
    }

    @Test
    fun `多季部分成功会清除旧尝试占位并保持立即重试`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(200, """{"data":[]}""")
            }
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(200, """{"success":true,"animes":[{"animeId":100,"animeTitle":"多季番"}]}""")
            }
            server.createContext("/api/v2/match") { exchange ->
                val body = exchange.requestBody.bufferedReader().readText()
                val animeId = if (body.contains("season1.mkv")) 100 else 200
                exchange.respond(
                    200,
                    """{"isMatched":true,"matches":[{"episodeId":1,"animeId":$animeId,"animeTitle":"季$animeId"}]}""",
                )
            }
            server.createContext("/api/v2/bangumi/100") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{"animeId":100,"animeTitle":"季100","episodes":[{"episodeId":1,"episodeNumber":"1"}]}}""",
                )
            }
            server.createContext("/api/v2/bangumi/200") { exchange ->
                exchange.respond(200, """{"success":false}""")
            }

            withDb(seasonCount = 2) { repo, libraryId, showPath, _ ->
                repo.recordAutoScrapeAttempt(libraryId, showPath, platformTimeMillis())
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                )

                val outcome = scraper.scrapeAuto(
                    library = libraryOf(libraryId),
                    showPath = showPath,
                    hashProvider = { path -> 1024L to "hash-of-$path" },
                )

                val partial = assertIs<AnimeScraper.AutoScrapeOutcome.Partial>(outcome)
                assertEquals(1, partial.seasonsScraped)
                assertNull(repo.lastOnlineScrapeAt(libraryId, showPath))
                assertTrue(scraper.shouldAutoScrape(libraryId, showPath))
            }
        }
    }

    @Test
    fun `hash未命中回落文件名候选`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(200, """{"success":true,"animes":[{"animeId":123,"animeTitle":"测试番剧","startDate":"2024-01-01"}]}""")
            }
            server.createContext("/api/v2/match") { exchange ->
                exchange.respond(200, """{"isMatched":false,"matches":[]}""")
            }
            server.createContext("/api/v2/bangumi/123") { exchange ->
                exchange.respond(200, """{"success":true,"bangumi":{"animeId":123,"animeTitle":"测试番剧","episodes":[{"episodeId":1,"episodeNumber":"1"}]}}""")
            }

            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                )
                val outcome = scraper.scrapeAuto(
                    library = libraryOf(libraryId),
                    showPath = showPath,
                    hashProvider = { Pair(1024L, "hash") },
                )

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.Done)
                val meta = repo.getOnlineMeta(libraryId, showPath, seasonNumber = 1)!!
                assertEquals(123L, meta.dandanplay_id)
            }
        }
    }

    @Test
    fun `ANCHOR番剧并行查询后优先应用Bangumi并带集简介自动写季关联`() = runBlocking {
        withServer { serverUrl, server ->
            var dandanSearchHits = 0
            server.createContext("/api/v2/search/anime") { exchange ->
                dandanSearchHits++
                exchange.respond(200, """{"success":true,"animes":[]}""")
            }
            server.createContext("/api/v2/match") { exchange ->
                exchange.respond(200, """{"isMatched":false,"matches":[]}""")
            }
            // Bangumi 降级源: subject + 集 desc(简介) + 季照
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"id":400602,"type":2,"name":"Test","name_cn":"测试番剧",
                        "date":"2024-01-01","summary":"部级简介","rating":{"score":8.5,"total":10},
                        "images":{"large":"https://lain.bgm.tv/cover.jpg"},"tags":[{"name":"奇幻"}],"eps":2}]}""",
                )
            }
            server.createContext("/v0/subjects/400602") { exchange ->
                exchange.respond(
                    200,
                    """{"id":400602,"type":2,"name":"Test","name_cn":"测试番剧","date":"2024-01-01",
                        "summary":"部级简介","rating":{"score":8.5,"total":10},
                        "images":{"large":"https://lain.bgm.tv/cover.jpg"},"tags":[{"name":"奇幻"}],"eps":2}""",
                )
            }
            server.createContext("/v0/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[
                        {"id":9001,"type":0,"sort":1,"name_cn":"第一集","airdate":"2024-01-01","desc":"第一集简介"},
                        {"id":9002,"type":0,"sort":2,"name_cn":"第二集","airdate":"2024-01-08","desc":"第二集简介"}
                    ],"total":2}""",
                )
            }

            withDb { repo, libraryId, showPath, showId ->
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient())),
                    downloader = FakeDownloader(),
                    repo = repo,
                )
                val outcome = scraper.scrapeAuto(library = libraryOf(libraryId), showPath = showPath)

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.Done)

                // 季级 meta: 集简介(Bangumi desc)入库
                val meta = repo.getOnlineMeta(libraryId, showPath, seasonNumber = 1)!!
                assertEquals(400602L, meta.bangumi_id)
                assertEquals("第一集简介", meta.decodedEpisodes.first().plot)
                assertEquals("第二集简介", meta.decodedEpisodes[1].plot)

                // reapply 后显示表: 集 plot 回填
                val seasons = repo.listSeasons(showId)
                val episodes = repo.listEpisodes(seasons.single().id)
                assertEquals("第一集简介", episodes.first { it.episode_number == 1L }.plot)

                // Bangumi 命中即高置信 -> 自动写季关联(评论区立即可用)
                val link = repo.getBangumiSeasonLink(BangumiSeasonIdentity.keyFor(null, libraryId, showPath, 1))
                assertTrue(link != null && link.state == BangumiLinkState.CONFIRMED)
                assertEquals(400602L, link.subjectId)
                assertEquals(BangumiLinkSource.AUTO, link.source)
                assertEquals(1, dandanSearchHits, "候选查询并行执行，但结果仍应优先应用 Bangumi")
            }
        }
    }

    @Test
    fun `单季ANCHOR的Bangumi唯一命中不等待慢弹弹回退源`() = runBlocking {
        withConcurrentServer { serverUrl, server ->
            val dandanStarted = CountDownLatch(1)
            server.createContext("/api/v2/search/anime") { exchange ->
                dandanStarted.countDown()
                Thread.sleep(3_000)
                exchange.respond(200, """{"success":true,"animes":[]}""")
            }
            server.createContext("/v0/search/subjects") { exchange ->
                assertTrue(dandanStarted.await(1, TimeUnit.SECONDS), "弹弹回退搜索应与 Bangumi 并行启动")
                exchange.respond(
                    200,
                    """{"data":[{"id":400602,"type":2,"name":"Test","name_cn":"测试番剧","date":"2024-01-01","total_episodes":2}]}""",
                )
            }
            server.createContext("/v0/subjects/400602") { exchange ->
                exchange.respond(
                    200,
                    """{"id":400602,"type":2,"name":"Test","name_cn":"测试番剧","date":"2024-01-01","total_episodes":2}""",
                )
            }
            server.createContext("/v0/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"type":0,"sort":1,"name_cn":"第一集"},{"type":0,"sort":2,"name_cn":"第二集"}]}""",
                )
            }

            withDb { repo, libraryId, showPath, _ ->
                val stages = mutableListOf<String>()
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                    interactiveSearchTimeoutMs = 5_000,
                )
                val startedAt = System.nanoTime()
                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath) { stages += it }
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

                assertIs<AnimeScraper.AutoScrapeOutcome.Done>(outcome)
                assertTrue(elapsedMs < 2_000, "不应等待 3 秒的弹弹回退源，实际 ${elapsedMs}ms")
                assertTrue(stages.any { it == "正在获取 Bangumi 详情与海报..." })
            }
        }
    }

    @Test
    fun `单季ANCHOR先进入Bangumi详情而不等待慢TMDB初始搜索`() = runBlocking {
        withConcurrentServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"id":400602,"type":2,"name":"Test","name_cn":"测试番剧","date":"2024-01-01","total_episodes":2}]}""",
                )
            }
            server.createContext("/v0/subjects/400602") { exchange ->
                exchange.respond(
                    200,
                    """{"id":400602,"type":2,"name":"Test","name_cn":"测试番剧","date":"2024-01-01","total_episodes":2}""",
                )
            }
            server.createContext("/v0/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"type":0,"sort":1,"name_cn":"第一集"},{"type":0,"sort":2,"name_cn":"第二集"}]}""",
                )
            }
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                Thread.sleep(3_000)
                exchange.respond(200, """{"candidates":[]}""")
            }

            withDb { repo, libraryId, showPath, _ ->
                val startedAt = System.nanoTime()
                var bangumiStageElapsedMs: Long? = null
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                    interactiveSearchTimeoutMs = 5_000,
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath) { stage ->
                    if (stage == "正在获取 Bangumi 详情与海报..." && bangumiStageElapsedMs == null) {
                        bangumiStageElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    }
                }

                assertTrue(
                    outcome is AnimeScraper.AutoScrapeOutcome.Done ||
                        outcome is AnimeScraper.AutoScrapeOutcome.Partial,
                    "Bangumi 结果应已落库，实际 $outcome",
                )
                val bangumiStageElapsed = bangumiStageElapsedMs
                assertTrue(
                    bangumiStageElapsed != null && bangumiStageElapsed < 1_500,
                    "Bangumi 详情阶段不应等待慢 TMDB 搜索，实际 ${bangumiStageElapsed}ms",
                )
            }
        }
    }

    @Test
    fun `详情页来源搜索超过交互预算会立即返回可重试失败`() = runBlocking {
        withConcurrentServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                Thread.sleep(3_000)
                exchange.respond(200, """{"data":[]}""")
            }
            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                    interactiveSearchTimeoutMs = 100,
                )
                val startedAt = System.nanoTime()
                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

                assertIs<AnimeScraper.AutoScrapeOutcome.RetryableFailure>(outcome)
                assertTrue(elapsedMs < 1_000, "来源超时应在交互预算后返回，实际 ${elapsedMs}ms")
                assertNull(repo.lastOnlineScrapeAt(libraryId, showPath))
            }
        }
    }

    @Test
    fun `弹弹命中但TMDB搜索失败时不掩败报Done而是Partial`() = runBlocking {
        // 修复前失败点: tmdbResult.getOrDefault(emptyList()) 把失败变空列表, 又因弹弹解析出的
        // 规范标题与文件夹名一致(preloadMatchesResolvedHint=true)被当作"搜索成功无结果"短路预载,
        // enrichWithTmdb 直接 success(empty) → 外层 hadRetryableFailure 不参与终判 → 错报 Done,
        // 用户看到"已在线补全"实际无 TMDB 身份/图, 失败未记录, 下次进详情页又整轮 FULL 重刮。
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(200, """{"data":[]}""")
            }
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(200, """{"success":true,"animes":[]}""")
            }
            server.createContext("/api/v2/match") { exchange ->
                exchange.respond(
                    200,
                    """{"isMatched":true,"matches":[{"episodeId":1,"animeId":321,"animeTitle":"测试番剧"}]}""",
                )
            }
            server.createContext("/api/v2/bangumi/321") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{"animeId":321,"animeTitle":"测试番剧","episodes":[{"episodeId":1,"episodeNumber":"1"}]}}""",
                )
            }
            var tmdbSearchHits = 0
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                tmdbSearchHits++
                exchange.respondBytes(500, "text/plain", ByteArray(0))
            }

            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient())),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                )
                val outcome = scraper.scrapeAuto(
                    library = libraryOf(libraryId),
                    showPath = showPath,
                    hashProvider = { Pair(1024L, "hash") },
                )

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.Partial, "TMDB 搜索失败不应错报 Done, 实际 $outcome")
                assertTrue(tmdbSearchHits >= 2, "修复后 enrich 应重搜 TMDB(至少初始+重搜), 实际 $tmdbSearchHits 次")
                // 弹弹命中数据仍落地
                assertEquals(321L, repo.getOnlineMeta(libraryId, showPath, 1)?.dandanplay_id)
            }
        }
    }

    @Test
    fun `取消自动刮削不写重试标记也不记录尝试`() = runBlocking {
        // 修复前失败点: 取消(CancellationException / ScrapePreemptedException)经 catch(Throwable)
        // 落入 markAutoScrapeRetryable, 下次进详情页因重试标记多一次不必要 FULL 重扫。
        withConcurrentServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                Thread.sleep(5_000) // 挂起让刮削半途, 由外部取消打断
                exchange.respond(200, """{"data":[]}""")
            }
            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                )
                val job = async { scraper.scrapeAuto(libraryOf(libraryId), showPath) }
                delay(300)
                job.cancelAndJoin()

                assertFalse(repo.hasAutoScrapeRetryMarker(libraryId, showPath), "取消不应写重试标记")
                assertNull(repo.lastOnlineScrapeAt(libraryId, showPath), "取消不应记录尝试(不烧冷却)")
            }
        }
    }

    @Test
    fun `文件名搜索为空仍执行hash兜底`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(200, """{"success":true,"animes":[]}""")
            }
            server.createContext("/api/v2/match") { exchange ->
                exchange.respond(
                    200,
                    """{"isMatched":true,"matches":[{"episodeId":1,"animeId":321,"animeTitle":"哈希命中番剧"}]}""",
                )
            }
            server.createContext("/api/v2/bangumi/321") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{"animeId":321,"animeTitle":"哈希命中番剧","episodes":[{"episodeId":1,"episodeNumber":"1"}]}}""",
                )
            }

            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                )
                val outcome = scraper.scrapeAuto(
                    library = libraryOf(libraryId),
                    showPath = showPath,
                    hashProvider = { Pair(1024L, "hash") },
                )

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.Done)
                assertEquals(321L, repo.getOnlineMeta(libraryId, showPath, 1)?.dandanplay_id)
            }
        }
    }

    @Test
    fun `无弹弹凭证时Bangumi仍可完成单季刮削`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"id":400602,"type":2,"name":"Test","name_cn":"测试番剧","date":"2024-01-01","total_episodes":2}]}""",
                )
            }
            server.createContext("/v0/subjects/400602") { exchange ->
                exchange.respond(
                    200,
                    """{"id":400602,"type":2,"name":"Test","name_cn":"测试番剧","date":"2024-01-01","total_episodes":2}""",
                )
            }
            server.createContext("/v0/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"type":0,"sort":1,"name_cn":"第一集"},{"type":0,"sort":2,"name_cn":"第二集"}]}""",
                )
            }

            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient())),
                    downloader = FakeDownloader(),
                    repo = repo,
                )
                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.Done)
                assertEquals(400602L, repo.getOnlineMeta(libraryId, showPath, 1)?.bangumi_id)
                assertEquals(2, repo.getOnlineMeta(libraryId, showPath, 1)?.decodedEpisodes?.size)
            }
        }
    }

    @Test
    fun `Bangumi多候选不自动落库`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[
                        {"id":1,"type":2,"name":"Test A","name_cn":"测试番剧 A","date":"2024-01-01"},
                        {"id":2,"type":2,"name":"Test B","name_cn":"测试番剧 B","date":"2024-04-01"}
                    ]}""",
                )
            }
            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient())),
                    downloader = FakeDownloader(),
                    repo = repo,
                )
                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.NeedsConfirmation)
                assertOnlyAttemptMeta(repo, libraryId, showPath, 1)
            }
        }
    }

    @Test
    fun `Bangumi唯一但标题不相似默认自动应用`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"id":99,"type":2,"name":"Test Movie","name_cn":"测试番剧 剧场版","date":"2024-01-01"}]}""",
                )
            }
            server.createContext("/v0/subjects/99") { exchange ->
                exchange.respond(
                    200,
                    """{"id":99,"type":2,"name":"Test Movie","name_cn":"测试番剧 剧场版","date":"2024-01-01",
                        "summary":"剧场版简介","rating":{"score":8.5,"total":10},"images":{},"eps":2}""",
                )
            }
            server.createContext("/v0/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[
                        {"id":9001,"type":0,"sort":1,"name_cn":"第一集","airdate":"2024-01-01"},
                        {"id":9002,"type":0,"sort":2,"name_cn":"第二集","airdate":"2024-01-08"}
                    ],"total":2}""",
                )
            }

            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient())),
                    downloader = FakeDownloader(),
                    repo = repo,
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertIs<AnimeScraper.AutoScrapeOutcome.Done>(outcome)
                assertEquals("测试番剧 剧场版", repo.getOnlineMeta(libraryId, showPath, 0)?.title)
                assertEquals("剧场版简介", repo.getOnlineMeta(libraryId, showPath, 0)?.plot)
            }
        }
    }

    @Test
    fun `关闭唯一结果放宽时Bangumi唯一但标题不相似不自动落库`() = runBlocking {
        withServer { serverUrl, server ->
            var detailHits = 0
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"id":99,"type":2,"name":"Unrelated","name_cn":"完全无关作品","date":"2024-01-01"}]}""",
                )
            }
            server.createContext("/v0/subjects/99") { exchange ->
                detailHits++
                exchange.respond(500)
            }

            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient())),
                    downloader = FakeDownloader(),
                    repo = repo,
                    uniqueCandidateAutoApply = false,
                )
                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.NeedsConfirmation)
                assertEquals(0, detailHits)
                assertOnlyAttemptMeta(repo, libraryId, showPath, 1)
            }
        }
    }

    @Test
    fun `关闭唯一结果放宽时Bangumi唯一但仅前缀包含不自动落库`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"id":99,"type":2,"name":"Test Sequel","name_cn":"测试番剧 第二季","date":"2024-01-01"}]}""",
                )
            }

            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient())),
                    downloader = FakeDownloader(),
                    repo = repo,
                    uniqueCandidateAutoApply = false,
                )
                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.NeedsConfirmation)
                assertOnlyAttemptMeta(repo, libraryId, showPath, 1)
            }
        }
    }

    @Test
    fun `不相似Bangumi候选不会污染弹弹部级元数据`() = runBlocking {
        withServer { serverUrl, server ->
            var bangumiDetailHits = 0
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"id":99,"type":2,"name":"Unrelated","name_cn":"完全无关作品","date":"2024-01-01"}]}""",
                )
            }
            server.createContext("/v0/subjects/99") { exchange ->
                bangumiDetailHits++
                exchange.respond(500)
            }
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"animes":[{"animeId":123,"animeTitle":"测试番剧","startDate":"2024-01-01"}]}""",
                )
            }
            server.createContext("/api/v2/bangumi/123") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{"animeId":123,"animeTitle":"测试番剧","episodes":[
                        {"episodeId":9001,"episodeTitle":"第一集","episodeNumber":"1","airDate":"2024-01-01"}
                    ],"relateds":[]}}""",
                )
            }

            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient())),
                    downloader = FakeDownloader(),
                    repo = repo,
                )
                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.Done)
                // 唯一放宽后早发 Bangumi 路径会尝试拉取该唯一候选详情(500 失败), 但部级数据仍来自弹弹。
                assertEquals(1, bangumiDetailHits)
                assertEquals("测试番剧", repo.getOnlineMeta(libraryId, showPath, 0)?.title)
            }
        }
    }

    @Test
    fun `弹弹无Bangumi桥时可信搜索结果仍持久化关联`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v2/search/episodes") { exchange ->
                exchange.respond(200, """{"success":true,"animes":[]}""")
            }
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"animes":[{"animeId":123,"animeTitle":"测试番剧","startDate":"2024-01-01"}]}""",
                )
            }
            server.createContext("/api/v2/bangumi/123") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{"animeId":123,"animeTitle":"测试番剧","episodes":[
                        {"episodeId":9001,"episodeTitle":"第一集","episodeNumber":"1","airDate":"2024-01-01"}
                    ],"relateds":[]}}""",
                )
            }
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"id":99,"type":2,"name":"Test","name_cn":"测试番剧","date":"2024-01-01"}]}""",
                )
            }
            server.createContext("/v0/subjects/99") { exchange ->
                exchange.respond(
                    200,
                    """{"id":99,"type":2,"name":"Test","name_cn":"测试番剧","date":"2024-01-01","summary":"Bangumi 简介"}""",
                )
            }
            server.createContext("/v0/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"id":1,"type":0,"sort":1,"name_cn":"第一集","airdate":"2024-01-01"}],"total":1}""",
                )
            }

            withDb(showTmdbId = 500L) { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient())),
                    downloader = FakeDownloader(),
                    repo = repo,
                )
                val outcome = scraper.scrapeAuto(libraryOf(libraryId, ScanMode.NFO), showPath)

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.Done)
                assertEquals(99L, repo.getOnlineMeta(libraryId, showPath, 0)?.bangumi_id)
                val link = repo.getBangumiSeasonLink(BangumiSeasonIdentity.keyFor(500L, libraryId, showPath, 1))
                assertEquals(99L, link?.subjectId)
                assertEquals(BangumiLinkSource.AUTO, link?.source)
            }
        }
    }

    @Test
    fun `TMDB增强填宽幅头图与剧集剧照`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"animes":[
                        {"animeId":123,"animeTitle":"测试番剧","bangumiId":"400602","imageUrl":"https://lain.bgm.tv/a.jpg","startDate":"2023-09-29"}
                    ]}""",
                )
            }
            server.createContext("/api/v2/bangumi/123") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{
                        "animeId":123,"animeTitle":"测试番剧","imageUrl":"https://lain.bgm.tv/a.jpg",
                        "episodes":[
                            {"episodeId":9001,"episodeTitle":"第一集","episodeNumber":"1","airDate":"2023-09-29"},
                            {"episodeId":9002,"episodeTitle":"第二集","episodeNumber":"2","airDate":"2023-10-06"}
                        ],
                        "relateds":[]
                    }}""",
                )
            }
            // TMDB 增强端点
            var tmdbSearchHit = false
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                tmdbSearchHit = true
                exchange.respond(
                    200,
                    """{"candidates":[{"tmdbId":777,"name":"测试番剧","firstAirDate":"2023-09-29","backdropPath":"/bd.jpg"}]}""",
                )
            }
            server.createContext("/api/v1/tmdb/tv/777/images") { exchange ->
                exchange.respond(200, """{"tvId":777,"backdrops":[{"filePath":"/bd.jpg"}]}""")
            }
            server.createContext("/api/v1/tmdb/tv/777/season/1/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"tvId":777,"seasonNumber":1,"episodes":[
                        {"episodeNumber":1,"stillPath":"/s1.jpg"},
                        {"episodeNumber":2,"stillPath":"/s2.jpg"}
                    ]}""",
                )
            }

            withDb { repo, libraryId, showPath, showId ->
                val legacyIdentityKey = BangumiSeasonIdentity.keyFor(null, libraryId, showPath, 1)
                repo.upsertBangumiSeasonLink(
                    io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink(
                        identityKey = legacyIdentityKey,
                        subjectId = 400602L,
                        state = BangumiLinkState.CONFIRMED,
                        source = BangumiLinkSource.MANUAL,
                        evidence = "legacy-show-key",
                        updatedAt = 1L,
                        verifiedAt = 1L,
                    ),
                )
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi(
                        apiKey = "test-token",
                        baseUrl = serverUrl,
                        httpClient = testClient(),
                    ),
                )
                val outcome = scraper.scrapeAuto(library = libraryOf(libraryId), showPath = showPath)

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.Done)
                assertTrue(tmdbSearchHit, "ANCHOR 番剧应触发 TMDB 搜索定位 tmdb_id")

                // 部级 meta: 宽幅头图 backdrop 落库
                val showMeta = repo.getOnlineMeta(libraryId, showPath, seasonNumber = 0)!!
                assertEquals(777L, showMeta.tmdb_id)
                assertEquals("https://image.tmdb.org/t/p/w1280/bd.jpg", showMeta.remote_fanart_url)
                assertEquals("/cache/online-scrape/${libraryId}-backdrop.jpg", showMeta.local_fanart_path)

                // 季级 meta: 剧集剧照 thumbPath 落库
                val seasonMeta = repo.getOnlineMeta(libraryId, showPath, seasonNumber = 1)!!
                assertEquals(
                    listOf("/cache/online-scrape/${libraryId}-s1e1.jpg", "/cache/online-scrape/${libraryId}-s1e2.jpg"),
                    seasonMeta.decodedEpisodes.map { it.thumbPath },
                )

                // TMDB ID 同步到显示表；在线头图/剧照保持在 meta，不污染 NFO/本地抽帧字段。
                val show = repo.getShowByPath(libraryId, showPath)!!
                assertEquals(777L, show.tmdb_id)
                assertNull(show.fanart_path)
                assertNull(repo.getBangumiSeasonLink(legacyIdentityKey))
                val migratedLink = repo.getBangumiSeasonLink(BangumiSeasonIdentity.keyFor(777L, libraryId, showPath, 1))
                assertEquals(BangumiLinkSource.MANUAL, migratedLink?.source)
                assertEquals(400602L, migratedLink?.subjectId)
                val seasons = repo.listSeasons(showId)
                val episodes = repo.listEpisodes(seasons.single().id)
                assertNull(episodes.first { it.episode_number == 1L }.local_thumb_path)
                assertNull(episodes.first { it.episode_number == 2L }.local_thumb_path)
            }
        }
    }

    @Test
    fun `NFO已有TMDB身份时直接补集照且不依赖其他匹配源`() = runBlocking {
        withServer { serverUrl, server ->
            val imageDir = Files.createTempDirectory("unu-tmdb-stills-")
            val downloader = object : RemoteImageDownloader {
                override suspend fun downloadImage(
                    libraryId: Long,
                    showPath: String,
                    fileName: String,
                    remoteUrl: String,
                ): String = Files.write(imageDir.resolve(fileName), ByteArray(2048) { 1 })
                    .toAbsolutePath()
                    .toString()
            }
            server.createContext("/api/v1/tmdb/tv/777/images") { exchange ->
                exchange.respond(200, """{"tvId":777,"backdrops":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/777/season/1/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"tvId":777,"seasonNumber":1,"episodes":[
                        {"episodeNumber":1,"stillPath":"/s1.jpg"},
                        {"episodeNumber":2,"stillPath":"/s2.jpg"}
                    ]}""",
                )
            }

            try {
                withDb(
                    showTmdbId = 777L,
                    showPosterPath = "/media/poster.jpg",
                    seasonPosterPath = "/media/season-poster.jpg",
                    showPlot = "完整简介",
                    showRating = 8.0,
                    scanMode = ScanMode.NFO,
                    episodeTitlePrefix = "第",
                    episodeAired = "2024-01-01",
                ) { repo, libraryId, showPath, showId ->
                    val scraper = AnimeScraper(
                        dandanplay = null,
                        bangumi = BangumiScrapeProvider(bangumiApi()),
                        downloader = downloader,
                        repo = repo,
                        tmdb = TmdbScrapeApi(
                            apiKey = "test-token",
                            baseUrl = serverUrl,
                            httpClient = testClient(),
                        ),
                    )

                    assertTrue(scraper.shouldAutoScrape(libraryId, showPath))
                    val outcome = scraper.scrapeAuto(libraryOf(libraryId, ScanMode.NFO), showPath)

                    assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.Done)
                    assertEquals(ScrapeSource.NFO.storageName, repo.getOnlineMeta(libraryId, showPath, 0)?.scrape_source)
                    assertNull(repo.getOnlineMeta(libraryId, showPath, 0)?.tmdb_id)
                    val seasonMeta = repo.getOnlineMeta(libraryId, showPath, 1)!!
                    assertEquals(ScrapeSource.NFO.storageName, seasonMeta.scrape_source)
                    assertEquals(
                        listOf(
                            imageDir.resolve("s1e1.jpg").toAbsolutePath().toString(),
                            imageDir.resolve("s1e2.jpg").toAbsolutePath().toString(),
                        ),
                        seasonMeta.decodedEpisodes.map { it.thumbPath },
                    )
                    assertTrue(seasonMeta.decodedEpisodes.all { it.tmdbStillAvailable == true })
                    val episodes = repo.listEpisodes(repo.listSeasons(showId).single().id)
                    assertTrue(episodes.all { it.thumb_path == null && it.local_thumb_path == null })
                    assertFalse(scraper.shouldAutoScrape(libraryId, showPath))
                }
            } finally {
                imageDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `已有完整目录但TMDB集照缺失时只执行图片回补`() = runBlocking {
        withServer { serverUrl, server ->
            val imageDir = Files.createTempDirectory("unu-tmdb-import-images-")
            val bangumiRequests = AtomicInteger(0)
            val downloader = object : RemoteImageDownloader {
                override suspend fun downloadImage(
                    libraryId: Long,
                    showPath: String,
                    fileName: String,
                    remoteUrl: String,
                ): String = Files.write(imageDir.resolve(fileName), ByteArray(8) { 1 })
                    .toAbsolutePath()
                    .toString()
            }
            server.createContext("/api/v1/tmdb/tv/777/images") { exchange ->
                exchange.respond(200, "{\"tvId\":777,\"backdrops\":[]}")
            }
            server.createContext("/v0/search/subjects") { exchange ->
                bangumiRequests.incrementAndGet()
                exchange.respond(200, "{\"data\":[]}")
            }
            server.createContext("/api/v1/tmdb/tv/777/season/1/episodes") { exchange ->
                exchange.respond(
                    200,
                    "{\"tvId\":777,\"seasonNumber\":1,\"episodes\":[" +
                        "{\"episodeNumber\":1,\"stillPath\":\"/s1.jpg\"}," +
                        "{\"episodeNumber\":2,\"stillPath\":\"/s2.jpg\"}]}",
                )
            }
            try {
                withDb(
                    showTmdbId = 777L,
                    showPosterPath = "/media/poster.jpg",
                    seasonPosterPath = "/media/season-poster.jpg",
                    showPlot = "完整简介",
                    scanMode = ScanMode.NFO,
                    episodeTitlePrefix = "第",
                    episodeAired = "2024-01-01",
                ) { repo, libraryId, showPath, _ ->
                    val now = platformTimeMillis()
                    repo.upsertOnlineMeta(
                        libraryId, showPath, 0, ScrapeSource.TMDB, false,
                        dandanplayId = null, bangumiId = null,
                        remotePosterUrl = null, localPosterPath = null,
                        title = "测试番剧", originalTitle = null, year = 2024, plot = "完整简介", rating = null,
                        releaseDate = null, genres = emptyList(), studios = emptyList(),
                        episodes = emptyList(), scrapedAt = now,
                    )
                    repo.upsertOnlineMeta(
                        libraryId, showPath, 1, ScrapeSource.TMDB, false,
                        dandanplayId = null, bangumiId = null,
                        remotePosterUrl = null, localPosterPath = null,
                        title = null, originalTitle = null, year = null, plot = null, rating = null,
                        releaseDate = null, genres = emptyList(), studios = emptyList(),
                        episodes = listOf(
                            ScrapedOnlineEpisode(1, "第1集", aired = "2024-01-01", tmdbStillAvailable = true),
                            ScrapedOnlineEpisode(2, "第2集", aired = "2024-01-08", tmdbStillAvailable = true),
                        ),
                        scrapedAt = now,
                    )
                    val scraper = AnimeScraper(
                        dandanplay = null,
                        bangumi = BangumiScrapeProvider(
                            BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                        ),
                        downloader = downloader,
                        repo = repo,
                        tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                    )
                    assertEquals(
                        AnimeScraper.AutoScrapeMode.IMAGES_ONLY,
                        scraper.autoScrapeMode(libraryId, showPath),
                    )
                    assertIs<AnimeScraper.AutoScrapeOutcome.Done>(
                        scraper.restoreOnlineImages(libraryOf(libraryId, ScanMode.NFO), showPath),
                    )
                    val refreshed = repo.getOnlineMeta(libraryId, showPath, 1)!!
                    assertTrue(refreshed.decodedEpisodes.all { it.thumbPath != null })
                    assertEquals(2, imageDir.toFile().listFiles()?.size ?: 0)
                    assertEquals(0, bangumiRequests.get())
                }
            } finally {
                imageDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `NFO直连TMDB无still时保留可靠身份并交给集照回退`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v1/tmdb/tv/777/images") { exchange ->
                exchange.respond(200, """{"tvId":777,"backdrops":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/777/season/1/episodes") { exchange ->
                exchange.respond(200, """{"tvId":777,"seasonNumber":1,"episodes":[]}""")
            }

            withDb(
                showTmdbId = 777L,
                showPosterPath = "/media/poster.jpg",
                seasonPosterPath = "/media/season-poster.jpg",
                showPlot = "完整简介",
                showRating = 8.0,
                scanMode = ScanMode.NFO,
                episodeTitlePrefix = "第",
                episodeAired = "2024-01-01",
            ) { repo, libraryId, showPath, showId ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi(
                        apiKey = "test-token",
                        baseUrl = serverUrl,
                        httpClient = testClient(),
                    ),
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId, ScanMode.NFO), showPath)

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.Done)
                assertEquals(777L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
                assertNull(repo.getTmdbAutoMatchFailure(libraryId, showPath))
                assertNull(repo.getOnlineMeta(libraryId, showPath, 0)?.tmdb_id)
                assertTrue(
                    repo.getOnlineMeta(libraryId, showPath, 1)?.decodedEpisodes.orEmpty().all {
                        it.thumbPath == null && it.tmdbStillAvailable == false
                    },
                )
                val episodes = repo.listEpisodes(repo.listSeasons(showId).single().id)
                assertTrue(episodes.all { it.thumb_path == null && it.local_thumb_path == null })
                assertFalse(scraper.shouldAutoScrape(libraryId, showPath))
            }
        }
    }

    @Test
    fun `完整NFO且未配置TMDB时不会仅因meta为空触发在线请求`() = runBlocking {
        withDb(
            showTmdbId = 777L,
            showPosterPath = "/media/poster.jpg",
            seasonPosterPath = "/media/season-poster.jpg",
            showPlot = "完整简介",
            showRating = 8.0,
            scanMode = ScanMode.NFO,
            episodeTitlePrefix = "第",
            episodeAired = "2024-01-01",
        ) { repo, libraryId, showPath, _ ->
            val scraper = AnimeScraper(
                dandanplay = null,
                bangumi = BangumiScrapeProvider(bangumiApi()),
                downloader = FakeDownloader(),
                repo = repo,
                tmdb = null,
            )

            assertFalse(scraper.shouldAutoScrape(libraryId, showPath))
        }
    }

    @Test
    fun `NFO海报优先于在线缓存且斜杠开头路径仍属于媒体源`() = runBlocking {
        val nfoPoster = "/root/测试番剧/poster.jpg"
        val nfoSeasonPoster = "/root/测试番剧/Season 1/season01-poster.jpg"
        withDb(showPosterPath = nfoPoster, seasonPosterPath = nfoSeasonPoster) { repo, libraryId, showPath, showId ->
            repo.upsertOnlineMeta(
                libraryId = libraryId, showPath = showPath, seasonNumber = 1,
                source = ScrapeSource.BANGUMI, overwriteTitle = false,
                dandanplayId = null, bangumiId = 400602L,
                remotePosterUrl = "https://example.com/online.jpg", localPosterPath = "/cache/online.jpg",
                title = null, originalTitle = null, year = null, plot = null, rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(), episodes = emptyList(), scrapedAt = 2L,
            )
            repo.reapplyOnlineMeta(libraryId, showPath)

            assertEquals(nfoSeasonPoster, repo.listSeasons(showId).single().season_poster_path)
            val card = repo.listShows(libraryId).single()
            assertEquals(nfoPoster, card.card_poster_path)
            assertEquals(ScrapedImagePathKind.MEDIA_SOURCE.name, card.card_poster_path_kind)
            assertEquals("/cache/online.jpg", card.card_online_poster_path)
        }
    }

    @Test
    fun `未配置TMDB时独立补全不会误报成功`() = runBlocking {
        withDb(
            showTmdbId = 777L,
            showPosterPath = "/media/poster.jpg",
            seasonPosterPath = "/media/season-poster.jpg",
            showPlot = "完整简介",
            showRating = 8.0,
            scanMode = ScanMode.NFO,
            episodeTitlePrefix = "第",
            episodeAired = "2024-01-01",
        ) { repo, libraryId, showPath, _ ->
            val scraper = AnimeScraper(
                dandanplay = null,
                bangumi = BangumiScrapeProvider(bangumiApi()),
                downloader = FakeDownloader(),
                repo = repo,
                tmdb = null,
            )

            assertFalse(scraper.hasTmdb)
            assertIs<AnimeScraper.AutoScrapeOutcome.NoMatch>(
                scraper.enrichTmdb(libraryOf(libraryId, ScanMode.NFO), showPath),
            )
        }
    }

    @Test
    fun `TMDB高置信不中跳过增强`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"animes":[
                        {"animeId":123,"animeTitle":"测试番剧","bangumiId":"400602","imageUrl":"https://lain.bgm.tv/a.jpg","startDate":"2023-09-29"}
                    ]}""",
                )
            }
            server.createContext("/api/v2/bangumi/123") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{
                        "animeId":123,"animeTitle":"测试番剧","imageUrl":"https://lain.bgm.tv/a.jpg",
                        "episodes":[{"episodeId":9001,"episodeTitle":"第一集","episodeNumber":"1","airDate":"2023-09-29"}],
                        "relateds":[]
                    }}""",
                )
            }
            // TMDB 返回年份不符的候选(名称命中但年份不中 -> 整段跳过)
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                exchange.respond(
                    200,
                    """{"candidates":[{"tmdbId":888,"name":"测试番剧","firstAirDate":"1999-01-01","backdropPath":"/bd.jpg"}]}""",
                )
            }

            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi(
                        apiKey = "test-token",
                        baseUrl = serverUrl,
                        httpClient = testClient(),
                    ),
                )
                val outcome = scraper.scrapeAuto(library = libraryOf(libraryId), showPath = showPath)

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.Done)
                // 高置信不中 -> 不写任何 TMDB 增强(宁缺勿错)
                val showMeta = repo.getOnlineMeta(libraryId, showPath, seasonNumber = 0)!!
                assertNull(showMeta.remote_fanart_url)
                assertNull(showMeta.local_fanart_path)
            }
        }
    }

    @Test
    fun `TMDB多个同名候选按返回顺序选择最高置信身份`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"animes":[
                        {"animeId":123,"animeTitle":"测试番剧","bangumiId":"400602","imageUrl":"https://lain.bgm.tv/a.jpg","startDate":"2023-09-29"}
                    ]}""",
                )
            }
            server.createContext("/api/v2/bangumi/123") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{
                        "animeId":123,"animeTitle":"测试番剧","imageUrl":"https://lain.bgm.tv/a.jpg",
                        "episodes":[{"episodeId":9001,"episodeTitle":"第一集","episodeNumber":"1","airDate":"2023-09-29"}],
                        "relateds":[]
                    }}""",
                )
            }
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                exchange.respond(
                    200,
                    """{"candidates":[
                        {"tmdbId":777,"name":"测试番剧","firstAirDate":"2023-01-01","backdropPath":"/a.jpg"},
                        {"tmdbId":888,"name":"测试番剧","firstAirDate":"2023-09-29","backdropPath":"/b.jpg"}
                    ]}""",
                )
            }
            server.createContext("/api/v1/tmdb/tv/777/images") { exchange ->
                exchange.respond(200, """{"tvId":777,"backdrops":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/777/season/1/episodes") { exchange ->
                exchange.respond(200, """{"tvId":777,"seasonNumber":1,"episodes":[]}""")
            }

            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi(
                        apiKey = "test-token",
                        baseUrl = serverUrl,
                        httpClient = testClient(),
                    ),
                )
                val outcome = scraper.scrapeAuto(library = libraryOf(libraryId), showPath = showPath)

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.Done)
                assertEquals(777L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
                assertEquals(777L, repo.getOnlineMeta(libraryId, showPath, 0)?.tmdb_id)
            }
        }
    }

    @Test
    fun `重刮空字段保留已有meta与剧照路径`() = runBlocking {
        withDb { repo, libraryId, showPath, _ ->
            repo.upsertOnlineMeta(
                libraryId, showPath, 0, ScrapeSource.DANDANPLAY, true,
                dandanplayId = 11L, bangumiId = 22L,
                remotePosterUrl = "https://example.com/poster.jpg", localPosterPath = "/cache/poster.jpg",
                title = "旧标题", originalTitle = "Old", year = 2024, plot = "旧简介", rating = 8.5,
                releaseDate = "2024-01-01", genres = listOf("奇幻"), studios = listOf("Studio"),
                episodes = emptyList(), scrapedAt = 1L,
            )
            repo.upsertOnlineMeta(
                libraryId, showPath, 0, ScrapeSource.BANGUMI, false,
                dandanplayId = null, bangumiId = null,
                remotePosterUrl = null, localPosterPath = null,
                title = null, originalTitle = null, year = null, plot = null, rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = emptyList(), scrapedAt = 2L,
            )
            repo.upsertOnlineMeta(
                libraryId, showPath, 1, ScrapeSource.DANDANPLAY, false,
                dandanplayId = 11L, bangumiId = 22L,
                remotePosterUrl = "https://example.com/season.jpg", localPosterPath = "/cache/season.jpg",
                title = null, originalTitle = null, year = null, plot = null, rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = listOf(ScrapedOnlineEpisode(1, "旧集名", thumbPath = "/cache/ep1.jpg")), scrapedAt = 1L,
            )
            repo.upsertOnlineMeta(
                libraryId, showPath, 1, ScrapeSource.BANGUMI, false,
                dandanplayId = null, bangumiId = null,
                remotePosterUrl = null, localPosterPath = null,
                title = null, originalTitle = null, year = null, plot = null, rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = listOf(ScrapedOnlineEpisode(1, "新集名")), scrapedAt = 2L,
            )

            val showMeta = repo.getOnlineMeta(libraryId, showPath, 0)!!
            assertEquals(11L, showMeta.dandanplay_id)
            assertEquals(22L, showMeta.bangumi_id)
            assertEquals("旧标题", showMeta.title)
            assertEquals("旧简介", showMeta.plot)
            assertEquals("奇幻", showMeta.genres)
            assertEquals("/cache/poster.jpg", showMeta.local_poster_path)
            val seasonMeta = repo.getOnlineMeta(libraryId, showPath, 1)!!
            assertEquals("新集名", seasonMeta.decodedEpisodes.single().title)
            assertEquals("/cache/ep1.jpg", seasonMeta.decodedEpisodes.single().thumbPath)
            assertEquals("/cache/season.jpg", seasonMeta.local_poster_path)
        }
    }

    @Test
    fun `手动切换来源会清理旧来源身份`() = runBlocking {
        withDb { repo, libraryId, showPath, _ ->
            repo.upsertOnlineMeta(
                libraryId = libraryId, showPath = showPath, seasonNumber = 0,
                source = ScrapeSource.DANDANPLAY, overwriteTitle = true,
                dandanplayId = 11L, bangumiId = 22L,
                remotePosterUrl = null, localPosterPath = null,
                title = "旧标题", originalTitle = null, year = null, plot = null, rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(), episodes = emptyList(), scrapedAt = 1L,
            )
            repo.upsertOnlineMeta(
                libraryId = libraryId, showPath = showPath, seasonNumber = 0,
                source = ScrapeSource.MANUAL_BANGUMI, overwriteTitle = true,
                dandanplayId = null, bangumiId = 33L,
                remotePosterUrl = null, localPosterPath = null,
                title = "新标题", originalTitle = null, year = null, plot = null, rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(), episodes = emptyList(), scrapedAt = 2L,
            )

            val meta = repo.getOnlineMeta(libraryId, showPath, 0)!!
            assertNull(meta.dandanplay_id)
            assertEquals(33L, meta.bangumi_id)
            assertEquals(ScrapeSource.MANUAL_BANGUMI.storageName, meta.scrape_source)
        }
    }

    @Test
    fun `关闭唯一结果放宽时唯一但标题不相似的弹弹候选不会自动落库`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(200, """{"data":[]}""")
            }
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"animes":[{"animeId":999,"animeTitle":"完全无关作品"}]}""",
                )
            }

            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                    uniqueCandidateAutoApply = false,
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.NoMatch)
                assertOnlyAttemptMeta(repo, libraryId, showPath, 1)
                // 节流已删除(2026-08-14): 未命中也写入尝试占位, 但详情页懒触发不再被 24h 节流拦住。
                assertTrue(scraper.shouldAutoScrape(libraryId, showPath))
            }
        }
    }

    @Test
    fun `弹弹唯一但标题不相似默认自动应用`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(200, """{"data":[]}""")
            }
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"animes":[{"animeId":998,"animeTitle":"测试番剧 剧场版","startDate":"2024-01-01"}]}""",
                )
            }
            server.createContext("/api/v2/bangumi/998") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{
                        "animeId":998,"animeTitle":"测试番剧 剧场版","imageUrl":"https://example.com/movie.jpg","startDate":"2024-01-01",
                        "episodes":[{"episodeNumber":"1","episodeTitle":"剧场版","airDate":"2024-01-01"}],
                        "relateds":[]}}""",
                )
            }

            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertIs<AnimeScraper.AutoScrapeOutcome.Done>(outcome)
                assertEquals(998L, repo.getOnlineMeta(libraryId, showPath, 1)?.dandanplay_id)
            }
        }
    }

    @Test
    fun `关闭唯一结果放宽时弹弹标题仅包含查询词时保留候选但不自动落库`() = runBlocking {
        withServer { serverUrl, server ->
            var detailHits = 0
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"animes":[{"animeId":998,"animeTitle":"测试番剧 剧场版","startDate":"2024-01-01"}]}""",
                )
            }
            server.createContext("/api/v2/bangumi/998") { exchange ->
                detailHits++
                exchange.respond(500)
            }

            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                    uniqueCandidateAutoApply = false,
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                val confirmation = assertIs<AnimeScraper.AutoScrapeOutcome.NeedsConfirmation>(outcome)
                assertEquals(listOf(998L), confirmation.candidates.map { it.identityId })
                assertEquals(0, detailHits)
                assertOnlyAttemptMeta(repo, libraryId, showPath, 1)
            }
        }
    }

    @Test
    fun `手动整部弹弹候选按related可靠映射多季并可重新确认禁用关联`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v2/bangumi/101") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{
                        "animeId":101,"animeTitle":"测试番剧","imageUrl":"https://example.com/s1.jpg","startDate":"2024-01-01",
                        "episodes":[{"episodeNumber":"1","episodeTitle":"第一集","airDate":"2024-01-01"}],
                        "relateds":[{"animeId":102,"animeTitle":"测试番剧 第二季","imageUrl":"https://example.com/s2.jpg","startDate":"2025-01-01"}]
                    }}""",
                )
            }
            server.createContext("/api/v2/bangumi/102") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{
                        "animeId":102,"animeTitle":"测试番剧 第二季","imageUrl":"https://example.com/s2.jpg",
                        "episodes":[{"episodeNumber":"1","episodeTitle":"第二季第一集","airDate":"2025-01-01"}],
                        "relateds":[]
                    }}""",
                )
            }

            withDb(seasonCount = 2) { repo, libraryId, showPath, _ ->
                val linkKey = BangumiSeasonIdentity.keyFor(null, libraryId, showPath, 1)
                repo.upsertBangumiSeasonLink(
                    io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink(
                        identityKey = linkKey,
                        subjectId = null,
                        state = BangumiLinkState.DISABLED,
                        source = BangumiLinkSource.MANUAL,
                        evidence = "user-disabled",
                        updatedAt = 1L,
                        verifiedAt = null,
                    ),
                )
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                )

                val applied = scraper.applyCandidate(
                    library = libraryOf(libraryId),
                    showPath = showPath,
                    seasonNumber = null,
                    candidate = ScrapeCandidate(
                        source = ScrapeSource.DANDANPLAY,
                        identityId = 101L,
                        title = "测试番剧",
                        date = "2024-01-01",
                        bgmSubjectId = 201L,
                    ),
                    manual = true,
                )

                assertTrue(applied)
                assertEquals(101L, repo.getOnlineMeta(libraryId, showPath, 1)?.dandanplay_id)
                assertEquals(102L, repo.getOnlineMeta(libraryId, showPath, 2)?.dandanplay_id)
                val link = repo.getBangumiSeasonLink(linkKey)
                assertEquals(BangumiLinkState.CONFIRMED, link?.state)
                assertEquals(BangumiLinkSource.MANUAL, link?.source)
                assertEquals(201L, link?.subjectId)
            }
        }
    }

    @Test
    fun `hash锚点命中远程第二季时按相对偏移映射本地后续季度`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"animes":[
                        {"animeId":202,"animeTitle":"测试番剧","startDate":"2024-01-01"}
                    ]}""",
                )
            }
            server.createContext("/api/v2/match") { exchange ->
                val body = exchange.requestBody.bufferedReader().readText()
                if (body.contains("season1.mkv")) {
                    exchange.respond(
                        200,
                        """{"isMatched":true,"matches":[{"episodeId":1,"animeId":202,"animeTitle":"测试番剧 第二季"}]}""",
                    )
                } else {
                    exchange.respond(200, """{"isMatched":false,"matches":[]}""")
                }
            }
            server.createContext("/api/v2/bangumi/202") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{
                        "animeId":202,"animeTitle":"测试番剧 第二季","imageUrl":"https://example.com/s2.jpg",
                        "episodes":[{"episodeNumber":"1","episodeTitle":"第二季第一集","airDate":"2024-01-01"}],
                        "relateds":[
                            {"animeId":303,"animeTitle":"测试番剧 第三季","imageUrl":"https://example.com/s3.jpg","startDate":"2025-01-01"},
                            {"animeId":101,"animeTitle":"测试番剧 第一季","imageUrl":"https://example.com/s1.jpg","startDate":"2023-01-01"}
                        ]
                    }}""",
                )
            }
            server.createContext("/api/v2/bangumi/303") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{
                        "animeId":303,"animeTitle":"测试番剧 第三季","imageUrl":"https://example.com/s3.jpg",
                        "episodes":[{"episodeNumber":"1","episodeTitle":"第三季第一集","airDate":"2025-01-01"}],
                        "relateds":[]
                    }}""",
                )
            }

            withDb(seasonCount = 2) { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                )
                val outcome = scraper.scrapeAuto(
                    library = libraryOf(libraryId),
                    showPath = showPath,
                    hashProvider = { path ->
                        if (path.endsWith("season1.mkv")) Pair(1024L, "hash-season1") else null
                    },
                )

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.Done)
                assertEquals(202L, repo.getOnlineMeta(libraryId, showPath, 1)?.dandanplay_id)
                assertEquals(303L, repo.getOnlineMeta(libraryId, showPath, 2)?.dandanplay_id)
            }
        }
    }

    @Test
    fun `多个hash锚点相对偏移冲突时不自动落库`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"animes":[
                        {"animeId":202,"animeTitle":"测试番剧","startDate":"2024-01-01"}
                    ]}""",
                )
            }
            server.createContext("/api/v2/match") { exchange ->
                val body = exchange.requestBody.bufferedReader().readText()
                val animeId = when {
                    body.contains("season1.mkv") -> 202
                    body.contains("season2.mkv") -> 101
                    else -> 0
                }
                if (animeId == 0) {
                    exchange.respond(200, """{"isMatched":false,"matches":[]}""")
                } else {
                    exchange.respond(
                        200,
                        """{"isMatched":true,"matches":[{"episodeId":1,"animeId":$animeId,"animeTitle":"测试番剧"}]}""",
                    )
                }
            }
            server.createContext("/api/v2/bangumi/202") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{
                        "animeId":202,"animeTitle":"测试番剧 第二季","episodes":[],
                        "relateds":[
                            {"animeId":303,"animeTitle":"测试番剧 第三季","startDate":"2025-01-01"},
                            {"animeId":101,"animeTitle":"测试番剧 第一季","startDate":"2023-01-01"}
                        ]
                    }}""",
                )
            }

            withDb(seasonCount = 3) { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                )
                val outcome = scraper.scrapeAuto(
                    library = libraryOf(libraryId),
                    showPath = showPath,
                    hashProvider = { path -> Pair(1024L, "hash-${path.substringAfterLast('/')}") },
                )

                assertTrue(
                    outcome is AnimeScraper.AutoScrapeOutcome.NeedsConfirmation ||
                        outcome is AnimeScraper.AutoScrapeOutcome.NoMatch,
                )
                assertOnlyAttemptMeta(repo, libraryId, showPath, 1, 2, 3)
            }
        }
    }

    @Test
    fun `persistTmdbId迁移时手动与禁用关联优先且重复执行幂等`() = runBlocking {
        withDb(seasonCount = 2) { repo, libraryId, showPath, _ ->
            val tmdbId = 888L
            val legacySeason1Key = BangumiSeasonIdentity.keyFor(null, libraryId, showPath, 1)
            val tmdbSeason1Key = BangumiSeasonIdentity.keyFor(tmdbId, libraryId, showPath, 1)
            val legacySeason2Key = BangumiSeasonIdentity.keyFor(null, libraryId, showPath, 2)
            val tmdbSeason2Key = BangumiSeasonIdentity.keyFor(tmdbId, libraryId, showPath, 2)

            repo.upsertBangumiSeasonLink(
                BangumiSeasonLink(
                    identityKey = legacySeason1Key, subjectId = 101L,
                    state = BangumiLinkState.CONFIRMED, source = BangumiLinkSource.MANUAL,
                    evidence = "旧手动关联", updatedAt = 10L, verifiedAt = 10L,
                ),
            )
            repo.upsertBangumiSeasonLink(
                BangumiSeasonLink(
                    identityKey = tmdbSeason1Key, subjectId = 202L,
                    state = BangumiLinkState.CONFIRMED, source = BangumiLinkSource.AUTO,
                    evidence = "新自动关联", updatedAt = 20L, verifiedAt = null,
                ),
            )
            repo.upsertBangumiSeasonLink(
                BangumiSeasonLink(
                    identityKey = legacySeason2Key, subjectId = null,
                    state = BangumiLinkState.DISABLED, source = BangumiLinkSource.MANUAL,
                    evidence = "旧禁用关联", updatedAt = 1L, verifiedAt = null,
                ),
            )
            repo.upsertBangumiSeasonLink(
                BangumiSeasonLink(
                    identityKey = tmdbSeason2Key, subjectId = 303L,
                    state = BangumiLinkState.CONFIRMED, source = BangumiLinkSource.AUTO,
                    evidence = "新自动关联", updatedAt = 30L, verifiedAt = null,
                ),
            )

            repo.persistTmdbId(libraryId, showPath, tmdbId, ScrapeSource.DANDANPLAY, scrapedAt = 40L)

            assertNull(repo.getBangumiSeasonLink(legacySeason1Key))
            assertNull(repo.getBangumiSeasonLink(legacySeason2Key))
            assertEquals(101L, repo.getBangumiSeasonLink(tmdbSeason1Key)?.subjectId)
            assertEquals(BangumiLinkSource.MANUAL, repo.getBangumiSeasonLink(tmdbSeason1Key)?.source)
            assertEquals(BangumiLinkState.DISABLED, repo.getBangumiSeasonLink(tmdbSeason2Key)?.state)

            repo.persistTmdbId(libraryId, showPath, tmdbId, ScrapeSource.DANDANPLAY, scrapedAt = 50L)

            assertEquals(101L, repo.getBangumiSeasonLink(tmdbSeason1Key)?.subjectId)
            assertEquals(BangumiLinkSource.MANUAL, repo.getBangumiSeasonLink(tmdbSeason1Key)?.source)
            assertEquals(BangumiLinkState.DISABLED, repo.getBangumiSeasonLink(tmdbSeason2Key)?.state)
            assertEquals(tmdbId, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
            assertEquals(tmdbId, repo.getOnlineMeta(libraryId, showPath, 0)?.tmdb_id)
        }
    }

    @Test
    fun `ANCHOR已有tmdbId但季级缺失时仍列为pending`() = runBlocking {
        withDb(seasonCount = 1, showTmdbId = 777L) { repo, libraryId, showPath, _ ->
            repo.upsertOnlineMeta(
                libraryId, showPath, 0, ScrapeSource.DANDANPLAY, true,
                dandanplayId = 777L, bangumiId = null,
                remotePosterUrl = null, localPosterPath = null,
                title = "测试番剧", originalTitle = null, year = 2024, plot = "简介", rating = 8.0,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = emptyList(), scrapedAt = platformTimeMillis(),
            )

            assertEquals(777L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
            assertTrue(repo.listScrapePending(libraryId, anchorOnly = true).any { it.showPath == showPath })
        }
    }

    @Test
    fun `NFO部级完整但季级缺失时仍列为SCAN ALL pending`() = runBlocking {
        withDb(
            showTmdbId = 777L,
            showPosterPath = "/root/测试番剧/poster.jpg",
            showPlot = "NFO 简介",
            showRating = 8.5,
            scanMode = ScanMode.NFO,
        ) { repo, libraryId, showPath, _ ->
            assertTrue(repo.listScrapePending(libraryId, anchorOnly = false).any { it.showPath == showPath })
            assertFalse(repo.listScrapePending(libraryId, anchorOnly = true).any { it.showPath == showPath })
        }
    }

    @Test
    fun `NFO后来写入的TMDB身份优先于旧在线派生身份`() = runBlocking {
        withDb { repo, libraryId, showPath, _ ->
            repo.persistTmdbId(libraryId, showPath, 111L, ScrapeSource.BANGUMI, 1L)
            repo.upsertShow(
                libraryId = libraryId,
                sourceKind = MediaSourceKind.LOCAL,
                tmdbId = 222L,
                folderName = "测试番剧",
                showPath = showPath,
                title = "测试番剧",
                originalTitle = null,
                year = 2024,
                plot = "NFO 简介",
                rating = 8.5,
                releaseDate = "2024-01-01",
                genres = emptyList(),
                studios = emptyList(),
                posterPath = "/root/测试番剧/poster.jpg",
                fanartPath = null,
                clearlogoPath = null,
                scannedAt = 2L,
                seasons = listOf(testSeasonScanData(1)),
            )

            repo.reapplyOnlineMeta(libraryId, showPath)

            assertEquals(222L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
            assertEquals(111L, repo.getOnlineMeta(libraryId, showPath, 0)?.tmdb_id)
        }
    }

    @Test
    fun `直接删除番剧同步清理在线meta`() = runBlocking {
        withDb { repo, libraryId, showPath, showId ->
            repo.upsertOnlineMeta(
                libraryId, showPath, 0, ScrapeSource.BANGUMI, true,
                dandanplayId = null, bangumiId = 10L,
                remotePosterUrl = null, localPosterPath = null,
                title = "在线标题", originalTitle = null, year = 2024, plot = "简介", rating = 8.0,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = emptyList(), scrapedAt = 1L,
            )

            repo.deleteShow(showId)

            assertNull(repo.getShow(showId))
            assertTrue(repo.listOnlineMeta(libraryId, showPath).isEmpty())
        }
    }

    @Test
    fun `手动整部换番清理旧TMDB派生数据并按新标题持久化新TMDB`() = runBlocking {
        withServer { serverUrl, server ->
            var tmdbQuery: String? = null
            server.createContext("/api/v2/bangumi/501") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{
                        "animeId":501,"animeTitle":"新番剧","imageUrl":"https://example.com/new-s1.jpg",
                        "episodes":[{"episodeNumber":"1","episodeTitle":"新第一集","airDate":"2024-01-01"}],
                        "relateds":[{"animeId":502,"animeTitle":"新番剧 第二季","imageUrl":"https://example.com/new-s2.jpg","startDate":"2025-01-01"}]
                    }}""",
                )
            }
            server.createContext("/api/v2/bangumi/502") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{
                        "animeId":502,"animeTitle":"新番剧 第二季","imageUrl":"https://example.com/new-s2.jpg",
                        "episodes":[{"episodeNumber":"1","episodeTitle":"新第二集","airDate":"2025-01-01"}],
                        "relateds":[]
                    }}""",
                )
            }
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                tmdbQuery = java.net.URLDecoder.decode(exchange.requestURI.rawQuery.orEmpty(), Charsets.UTF_8.name())
                exchange.respond(
                    200,
                    """{"candidates":[{"tmdbId":888,"name":"新番剧","originalName":"新番剧","firstAirDate":"2024-01-01"}]}""",
                )
            }
            server.createContext("/api/v1/tmdb/tv/888/images") { exchange ->
                exchange.respond(200, """{"tvId":888,"backdrops":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/888/season/1/episodes") { exchange ->
                exchange.respond(200, """{"tvId":888,"seasonNumber":1,"episodes":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/888/season/2/episodes") { exchange ->
                exchange.respond(200, """{"tvId":888,"seasonNumber":2,"episodes":[]}""")
            }

            withDb(seasonCount = 2, showTmdbId = 777L) { repo, libraryId, showPath, _ ->
                repo.persistTmdbId(libraryId, showPath, 777L, ScrapeSource.DANDANPLAY, scrapedAt = 1L)
                repo.updateOnlineMetaFanart(
                    libraryId, showPath,
                    remoteFanartUrl = "https://image.tmdb.org/t/p/w1280/old.jpg",
                    localFanartPath = "/cache/old-backdrop.jpg",
                )
                repo.upsertOnlineMeta(
                    libraryId, showPath, 1, ScrapeSource.DANDANPLAY, false,
                    dandanplayId = 11L, bangumiId = null,
                    remotePosterUrl = null, localPosterPath = null,
                    title = null, originalTitle = null, year = null, plot = null, rating = null,
                    releaseDate = null, genres = emptyList(), studios = emptyList(),
                    episodes = listOf(ScrapedOnlineEpisode(1, "旧集名", thumbPath = "/cache/old-still.jpg")),
                    scrapedAt = 1L,
                )

                val scraper = AnimeScraper(
                    dandanplay = DandanplayScrapeProvider(dandanApi(serverUrl)),
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi(
                        apiKey = "test-token",
                        baseUrl = serverUrl,
                        httpClient = testClient(),
                    ),
                )
                val applied = scraper.applyCandidate(
                    library = libraryOf(libraryId),
                    showPath = showPath,
                    seasonNumber = null,
                    candidate = ScrapeCandidate(
                        source = ScrapeSource.DANDANPLAY,
                        identityId = 501L,
                        title = "新番剧",
                        date = "2024-01-01",
                    ),
                    manual = true,
                )

                assertTrue(applied)
                assertTrue(tmdbQuery?.contains("query=新番剧") == true)
                assertEquals(888L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
                val showMeta = repo.getOnlineMeta(libraryId, showPath, 0)!!
                assertEquals(888L, showMeta.tmdb_id)
                assertNull(showMeta.remote_fanart_url)
                assertNull(showMeta.local_fanart_path)
                assertTrue(repo.listOnlineMeta(libraryId, showPath).all { meta ->
                    meta.decodedEpisodes.none { it.thumbPath == "/cache/old-still.jpg" }
                })
            }
        }
    }

    @Test
    fun `手动meta不被后续自动刮削覆盖`() = runBlocking {
        withDb { repo, libraryId, showPath, _ ->
            repo.upsertOnlineMeta(
                libraryId, showPath, 0, ScrapeSource.MANUAL_BANGUMI, true,
                dandanplayId = null, bangumiId = 10L,
                remotePosterUrl = null, localPosterPath = null,
                title = "手动标题", originalTitle = null, year = 2024, plot = "手动简介", rating = 9.0,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = emptyList(), scrapedAt = 1L,
            )
            repo.upsertOnlineMeta(
                libraryId, showPath, 0, ScrapeSource.DANDANPLAY, true,
                dandanplayId = 20L, bangumiId = 30L,
                remotePosterUrl = null, localPosterPath = null,
                title = "自动标题", originalTitle = null, year = 2025, plot = "自动简介", rating = 7.0,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = emptyList(), scrapedAt = 2L,
            )

            val meta = repo.getOnlineMeta(libraryId, showPath, 0)!!
            assertEquals(ScrapeSource.MANUAL_BANGUMI, meta.source)
            assertEquals("手动标题", meta.title)
            assertEquals("手动简介", meta.plot)
            assertEquals(10L, meta.bangumi_id)
            assertEquals(1L, meta.scraped_at)
        }
    }

    @Test
    fun `部级meta存在但季级缺失仍列为pending且缓存路径失效绕过24小时节流`() = runBlocking {
        withDb { repo, libraryId, showPath, _ ->
            repo.upsertOnlineMeta(
                libraryId, showPath, 0, ScrapeSource.BANGUMI, true,
                dandanplayId = null, bangumiId = 10L,
                remotePosterUrl = null, localPosterPath = null,
                title = "测试番剧", originalTitle = null, year = 2024, plot = "简介", rating = 8.0,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = emptyList(), scrapedAt = platformTimeMillis(),
            )
            assertTrue(repo.listScrapePending(libraryId, anchorOnly = true).any { it.showPath == showPath })

            repo.upsertOnlineMeta(
                libraryId, showPath, 1, ScrapeSource.BANGUMI, true,
                dandanplayId = null, bangumiId = 10L,
                remotePosterUrl = "https://example.com/season.jpg", localPosterPath = "/missing/season.jpg",
                title = null, originalTitle = null, year = null, plot = null, rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = listOf(
                    ScrapedOnlineEpisode(1, "第一集", "2024-01-01"),
                    ScrapedOnlineEpisode(2, "第二集", "2024-01-08"),
                ),
                scrapedAt = platformTimeMillis(),
            )
            assertTrue(repo.listScrapePending(libraryId, anchorOnly = true).any { it.showPath == showPath })

            val scraper = AnimeScraper(
                dandanplay = null,
                bangumi = BangumiScrapeProvider(bangumiApi()),
                downloader = FakeDownloader(),
                repo = repo,
            )
            assertTrue(scraper.shouldAutoScrape(libraryId, showPath))
        }
    }

    @Test
    fun `完整刮削即使超过24小时也不会重复懒触发`() = runBlocking {
        val posterFile = Files.createTempFile("unu-online-poster-", ".jpg").toFile()
        try {
            withDb(showTmdbId = 777L) { repo, libraryId, showPath, _ ->
                repo.upsertOnlineMeta(
                    libraryId, showPath, 0, ScrapeSource.BANGUMI, true,
                    dandanplayId = null, bangumiId = 10L,
                    remotePosterUrl = null, localPosterPath = null,
                    title = "测试番剧", originalTitle = null, year = 2024, plot = "完整简介", rating = 8.0,
                    releaseDate = null, genres = emptyList(), studios = emptyList(),
                    episodes = emptyList(), scrapedAt = 1L,
                )
                repo.upsertOnlineMeta(
                    libraryId, showPath, 1, ScrapeSource.BANGUMI, true,
                    dandanplayId = null, bangumiId = 10L,
                    remotePosterUrl = "https://example.com/season.jpg",
                    localPosterPath = posterFile.absolutePath,
                    title = null, originalTitle = null, year = null, plot = null, rating = null,
                    releaseDate = null, genres = emptyList(), studios = emptyList(),
                    episodes = listOf(
                        ScrapedOnlineEpisode(1, "第一集", "2024-01-01"),
                        ScrapedOnlineEpisode(2, "第二集", "2024-01-08"),
                    ),
                    scrapedAt = 1L,
                )
                repo.reapplyOnlineMeta(libraryId, showPath)
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                )

                assertFalse(scraper.shouldAutoScrape(libraryId, showPath))
            }
        } finally {
            posterFile.delete()
        }
    }

    @Test
    fun `配置TMDB后完整在线文本仍会补查缺失身份`() = runBlocking {
        withDb(
            showPosterPath = "/media/poster.jpg",
            seasonPosterPath = "/media/season-poster.jpg",
            showPlot = "完整简介",
            showRating = 8.0,
        ) { repo, libraryId, showPath, _ ->
            repo.upsertOnlineMeta(
                libraryId, showPath, 0, ScrapeSource.BANGUMI, true,
                dandanplayId = null, bangumiId = 10L,
                remotePosterUrl = null, localPosterPath = null,
                title = "测试番剧", originalTitle = null, year = 2024, plot = "完整简介", rating = 8.0,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = emptyList(), scrapedAt = 1L,
            )
            repo.upsertOnlineMeta(
                libraryId, showPath, 1, ScrapeSource.BANGUMI, true,
                dandanplayId = null, bangumiId = 10L,
                remotePosterUrl = null, localPosterPath = null,
                title = null, originalTitle = null, year = null, plot = null, rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = listOf(
                    ScrapedOnlineEpisode(1, "第一集", "2024-01-01"),
                    ScrapedOnlineEpisode(2, "第二集", "2024-01-08"),
                ),
                scrapedAt = 1L,
            )
            repo.reapplyOnlineMeta(libraryId, showPath)
            val scraper = AnimeScraper(
                dandanplay = null,
                bangumi = BangumiScrapeProvider(bangumiApi()),
                downloader = FakeDownloader(),
                repo = repo,
                tmdb = TmdbScrapeApi(
                    apiKey = "test-token",
                    baseUrl = "http://127.0.0.1:1",
                    httpClient = testClient(),
                ),
            )

            assertTrue(scraper.shouldAutoScrape(libraryId, showPath))
        }
    }

    @Test
    fun `Bangumi与弹弹未命中时回退TMDB并持久化身份和集照`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                exchange.respond(
                    200,
                    """{"candidates":[{"tmdbId":777,"name":"测试番剧","originalName":"Test Anime","firstAirDate":"2024-01-01"}]}""",
                )
            }
            server.createContext("/api/v1/tmdb/tv/777/images") { exchange ->
                exchange.respond(200, """{"tvId":777,"backdrops":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/777/season/1/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"tvId":777,"seasonNumber":1,"episodes":[{"episodeNumber":1,"stillPath":"/e1.jpg"},{"episodeNumber":2,"stillPath":"/e2.jpg"}]}""",
                )
            }

            withDb { repo, libraryId, showPath, _ ->
                repo.recordTmdbAutoMatchFailure(libraryId, showPath, 1L)
                repo.suppressTmdbAutoMatchPrompt(libraryId, showPath)
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi(
                        apiKey = "test-token",
                        baseUrl = serverUrl,
                        httpClient = testClient(),
                    ),
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertIs<AnimeScraper.AutoScrapeOutcome.Done>(outcome)
                assertEquals(777L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
                assertNull(repo.getTmdbAutoMatchFailure(libraryId, showPath))
                val showMeta = repo.getOnlineMeta(libraryId, showPath, 0)
                assertEquals(777L, showMeta?.tmdb_id)
                assertEquals(ScrapeSource.TMDB, showMeta?.source)
                assertEquals(
                    listOf(
                        "/cache/online-scrape/${libraryId}-s1e1.jpg",
                        "/cache/online-scrape/${libraryId}-s1e2.jpg",
                    ),
                    repo.getOnlineMeta(libraryId, showPath, 1)?.decodedEpisodes?.map { it.thumbPath },
                )
            }
        }
    }

    @Test
    fun `Bangumi候选模糊时仍继续尝试TMDB高置信补全`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[
                        {"id":1,"type":2,"name":"Test A","name_cn":"测试番剧 A","date":"2024-01-01"},
                        {"id":2,"type":2,"name":"Test B","name_cn":"测试番剧 B","date":"2024-04-01"}
                    ]}""",
                )
            }
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                exchange.respond(
                    200,
                    """{"candidates":[{"tmdbId":777,"name":"测试番剧","originalName":"Test Anime","firstAirDate":"2024-01-01"}]}""",
                )
            }
            server.createContext("/api/v1/tmdb/tv/777/images") { exchange ->
                exchange.respond(200, """{"tvId":777,"backdrops":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/777/season/1/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"tvId":777,"seasonNumber":1,"episodes":[{"episodeNumber":1,"stillPath":"/e1.jpg"}]}""",
                )
            }

            withDb { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi(
                        apiKey = "test-token",
                        baseUrl = serverUrl,
                        httpClient = testClient(),
                    ),
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertIs<AnimeScraper.AutoScrapeOutcome.Done>(outcome)
                assertEquals(777L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
                assertEquals(
                    "/cache/online-scrape/${libraryId}-s1e1.jpg",
                    repo.getOnlineMeta(libraryId, showPath, 1)?.decodedEpisodes
                        ?.first { it.episodeNumber == 1 }
                        ?.thumbPath,
                )
            }
        }
    }

    @Test
    fun `手动Bangumi应用在TMDB集照下载期间先解锁身份`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/subjects/400602") { exchange ->
                exchange.respond(
                    200,
                    """{"id":400602,"type":2,"name":"Test","name_cn":"测试番剧","date":"2024-01-01","total_episodes":2}""",
                )
            }
            server.createContext("/v0/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[{"type":0,"sort":1,"name_cn":"第一集"},{"type":0,"sort":2,"name_cn":"第二集"}]}""",
                )
            }
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                exchange.respond(
                    200,
                    """{"candidates":[{"tmdbId":777,"name":"测试番剧","originalName":"Test","firstAirDate":"2024-01-01"}]}""",
                )
            }
            server.createContext("/api/v1/tmdb/tv/777/images") { exchange ->
                exchange.respond(200, """{"tvId":777,"backdrops":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/777/season/1/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"tvId":777,"seasonNumber":1,"episodes":[{"episodeNumber":1,"stillPath":"/manual-e1.jpg"}]}""",
                )
            }

            withDb { repo, libraryId, showPath, _ ->
                val identityApplied = CompletableDeferred<Unit>()
                val downloadStarted = CompletableDeferred<Unit>()
                val allowDownload = CompletableDeferred<Unit>()
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(
                        BangumiScrapeApi(baseUrl = serverUrl, httpClient = testClient()),
                    ),
                    downloader = object : RemoteImageDownloader {
                        override suspend fun downloadImage(
                            libraryId: Long,
                            showPath: String,
                            fileName: String,
                            remoteUrl: String,
                        ): String? {
                            downloadStarted.complete(Unit)
                            allowDownload.await()
                            return "/cache/online-scrape/" + libraryId + "-" + fileName
                        }
                    },
                    repo = repo,
                    tmdb = TmdbScrapeApi(
                        apiKey = "test-token",
                        baseUrl = serverUrl,
                        httpClient = testClient(),
                    ),
                )

                val applying = async {
                    scraper.applyCandidate(
                        library = libraryOf(libraryId),
                        showPath = showPath,
                        seasonNumber = null,
                        candidate = ScrapeCandidate(
                            source = ScrapeSource.BANGUMI,
                            identityId = 400602L,
                            title = "测试番剧",
                            year = 2024,
                        ),
                        manual = true,
                        onTmdbIdentityApplied = { identityApplied.complete(Unit) },
                    )
                }

                identityApplied.await()
                downloadStarted.await()
                assertFalse(applying.isCompleted)
                assertEquals(777L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
                allowDownload.complete(Unit)
                assertTrue(applying.await())
            }
        }
    }

    @Test
    fun `手动指定TMDB候选会持久化身份并补全在线集照`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v1/tmdb/tv/888/images") { exchange ->
                exchange.respond(200, """{"tvId":888,"backdrops":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/888/season/1/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"tvId":888,"seasonNumber":1,"episodes":[{"episodeNumber":1,"stillPath":"/manual-e1.jpg"}]}""",
                )
            }

            withDb(showTmdbId = 777L, scanMode = ScanMode.NFO) { repo, libraryId, showPath, _ ->
                val identityApplied = CompletableDeferred<Unit>()
                val downloadStarted = CompletableDeferred<Unit>()
                val allowDownload = CompletableDeferred<Unit>()
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = object : RemoteImageDownloader {
                        override suspend fun downloadImage(
                            libraryId: Long,
                            showPath: String,
                            fileName: String,
                            remoteUrl: String,
                        ): String? {
                            downloadStarted.complete(Unit)
                            allowDownload.await()
                            return "/cache/online-scrape/${libraryId}-$fileName"
                        }
                    },
                    repo = repo,
                    tmdb = TmdbScrapeApi(
                        apiKey = "test-token",
                        baseUrl = serverUrl,
                        httpClient = testClient(),
                    ),
                )

                val applying = async {
                    scraper.applyCandidate(
                        library = libraryOf(libraryId),
                        showPath = showPath,
                        seasonNumber = null,
                        candidate = ScrapeCandidate(
                            source = ScrapeSource.TMDB,
                            identityId = 888L,
                            title = "手动指定番剧",
                            year = 2024,
                        ),
                        manual = true,
                        onTmdbIdentityApplied = { identityApplied.complete(Unit) },
                    )
                }

                identityApplied.await()
                downloadStarted.await()
                assertFalse(applying.isCompleted)
                assertEquals(888L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
                allowDownload.complete(Unit)
                val applied = applying.await()

                assertTrue(applied)
                assertEquals(888L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
                assertEquals(888L, repo.getOnlineMeta(libraryId, showPath, 0)?.tmdb_id)
                assertEquals(ScrapeSource.MANUAL_TMDB, repo.getOnlineMeta(libraryId, showPath, 0)?.source)
                assertEquals(
                    "/cache/online-scrape/${libraryId}-s1e1.jpg",
                    repo.getOnlineMeta(libraryId, showPath, 1)?.decodedEpisodes
                        ?.first { it.episodeNumber == 1 }
                        ?.thumbPath,
                )

                repo.upsertShow(
                    libraryId = libraryId,
                    sourceKind = MediaSourceKind.LOCAL,
                    tmdbId = 777L,
                    folderName = "测试番剧",
                    showPath = showPath,
                    title = "测试番剧",
                    originalTitle = null,
                    year = 2024,
                    plot = "NFO 简介",
                    rating = 8.0,
                    releaseDate = "2024-01-01",
                    genres = emptyList(),
                    studios = emptyList(),
                    posterPath = "/root/测试番剧/poster.jpg",
                    fanartPath = null,
                    clearlogoPath = null,
                    scannedAt = 2L,
                    seasons = listOf(testSeasonScanData(1)),
                )
                repo.reapplyOnlineMeta(libraryId, showPath)

                assertEquals(888L, repo.getShowByPath(libraryId, showPath)?.tmdb_id)
            }
        }
    }

    @Test
    fun `清除番剧缓存会失效化在线图片和本地集照但保留身份与文本`() = runBlocking {
        withDb(showTmdbId = 777L) { repo, libraryId, showPath, showId ->
            repo.upsertOnlineMeta(
                libraryId = libraryId, showPath = showPath, seasonNumber = 0,
                source = ScrapeSource.BANGUMI, overwriteTitle = false,
                dandanplayId = null, bangumiId = 400602L,
                remotePosterUrl = null, localPosterPath = null,
                title = "在线标题", originalTitle = null, year = 2024, plot = "在线简介", rating = 8.0,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = emptyList(), scrapedAt = 1L,
            )
            repo.persistTmdbId(libraryId, showPath, 777L, ScrapeSource.TMDB, 2L)
            repo.updateOnlineMetaFanart(
                libraryId = libraryId,
                showPath = showPath,
                remoteFanartUrl = "https://image.tmdb.org/backdrop.jpg",
                localFanartPath = "/cache/backdrop.jpg",
            )
            repo.upsertOnlineMeta(
                libraryId = libraryId, showPath = showPath, seasonNumber = 1,
                source = ScrapeSource.BANGUMI, overwriteTitle = false,
                dandanplayId = null, bangumiId = 400602L,
                remotePosterUrl = "https://example.com/season.jpg", localPosterPath = "/cache/season.jpg",
                title = null, originalTitle = null, year = null, plot = null, rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = listOf(
                    ScrapedOnlineEpisode(
                        1,
                        title = "第一集",
                        thumbPath = "/cache/e1.jpg",
                        tmdbStillAvailable = true,
                    ),
                ),
                scrapedAt = 3L,
            )
            val episode = repo.listEpisodes(repo.listSeasons(showId).single().id).first()
            repo.updateEpisodeLocalThumb(episode.id, "/cache/generated-e1.jpg")

            repo.clearShowCache(showId)

            val showMeta = repo.getOnlineMeta(libraryId, showPath, 0)
            assertEquals(777L, showMeta?.tmdb_id)
            assertEquals("在线标题", showMeta?.title)
            assertEquals("https://image.tmdb.org/backdrop.jpg", showMeta?.remote_fanart_url)
            assertNull(showMeta?.local_fanart_path)
            val seasonMeta = repo.getOnlineMeta(libraryId, showPath, 1)
            assertEquals("https://example.com/season.jpg", seasonMeta?.remote_poster_url)
            assertNull(seasonMeta?.local_poster_path)
            assertEquals("第一集", seasonMeta?.decodedEpisodes?.single()?.title)
            assertNull(seasonMeta?.decodedEpisodes?.single()?.thumbPath)
            assertEquals(true, seasonMeta?.decodedEpisodes?.single()?.tmdbStillAvailable)
            assertNull(repo.listEpisodes(repo.listSeasons(showId).single().id).first().local_thumb_path)
        }
    }

    @Test
    fun `恢复NFO状态会删除在线meta并清空本地生成集照`() = runBlocking {
        withDb { repo, libraryId, showPath, showId ->
            repo.upsertOnlineMeta(
                libraryId = libraryId, showPath = showPath, seasonNumber = 0,
                source = ScrapeSource.TMDB, overwriteTitle = false,
                dandanplayId = null, bangumiId = null,
                remotePosterUrl = null, localPosterPath = null,
                title = null, originalTitle = null, year = null, plot = null, rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = emptyList(), scrapedAt = 1L,
            )
            val season = repo.listSeasons(showId).single()
            val episode = repo.listEpisodes(season.id).first()
            repo.updateEpisodeLocalThumb(episode.id, "/cache/generated-e1.jpg")
            repo.recordTmdbAutoMatchFailure(libraryId, showPath, 1L)
            repo.suppressTmdbAutoMatchPrompt(libraryId, showPath)

            repo.restoreNfoState(showId)

            assertTrue(repo.listOnlineMeta(libraryId, showPath).isEmpty())
            assertNull(repo.getTmdbAutoMatchFailure(libraryId, showPath))
            assertNull(repo.listEpisodes(season.id).first().local_thumb_path)
        }
    }

    // === setup ===

    private suspend fun assertOnlyAttemptMeta(
        repo: ScrapedLibraryRepository,
        libraryId: Long,
        showPath: String,
        vararg seasonNumbers: Int,
    ) {
        val attempt = repo.getOnlineMeta(libraryId, showPath, 0)
        assertEquals(ScrapeSource.AUTO_ATTEMPT.storageName, attempt?.scrape_source)
        assertNull(attempt?.tmdb_id)
        assertNull(attempt?.dandanplay_id)
        assertNull(attempt?.bangumi_id)
        seasonNumbers.forEach { seasonNumber ->
            assertNull(repo.getOnlineMeta(libraryId, showPath, seasonNumber))
        }
    }

    private fun libraryOf(id: Long, scanMode: ScanMode = ScanMode.ANCHOR): LibraryConfig = LibraryConfig(
        id = id, name = "测试库", sourceKind = MediaSourceKind.LOCAL,
        connectionId = null, localUri = null, rootPath = "/root", scanDepth = 2,
        lastScannedAt = null, createdAt = 1L, scanMode = scanMode,
    )

    private suspend fun withDb(
        seasonCount: Int = 1,
        seasonNumbers: List<Int> = (1..seasonCount).toList(),
        showTmdbId: Long? = null,
        showPosterPath: String? = null,
        seasonPosterPath: String? = null,
        showPlot: String? = null,
        showRating: Double? = null,
        scanMode: ScanMode = ScanMode.ANCHOR,
        episodeTitlePrefix: String? = null,
        episodeAired: String? = null,
        episodeThumbPath: String? = null,
        showFolderName: String = "测试番剧",
        showTitle: String = showFolderName,
        block: suspend (ScrapedLibraryRepository, Long, String, Long) -> Unit,
    ) {
        val parent = Files.createTempDirectory("unu-scraper-")
        val dbFile = parent.resolve("scraper.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val database = UnuDatabase(driver)
            val repo = ScrapedLibraryRepositoryImpl(database.scrapedQueries)
            val libraryId = repo.addLibrary(
                name = "刮削测试库", sourceKind = MediaSourceKind.LOCAL,
                connectionId = null, localUri = null, rootPath = "/root", scanDepth = 2,
                scanMode = scanMode, anchorFilenames = listOf("poster.jpg"),
            )
            val showPath = "/root/$showFolderName"
            val seasons = seasonNumbers.map { seasonNumber ->
                SeasonScanData(
                    nfo = SeasonNfo(seasonNumber = seasonNumber, title = null, year = null, releaseDate = null),
                    bangumi = null,
                    seasonPath = "$showPath/Season $seasonNumber",
                    seasonPosterPath = seasonPosterPath,
                    episodes = listOf(1, 2).map { ep ->
                        EpisodeNfo(
                            title = episodeTitlePrefix?.let { "$it$ep 集" },
                            plot = null, rating = null, year = null, aired = episodeAired,
                            episode = ep, season = seasonNumber, runtime = null,
                        ) to EpisodeFile(
                            videoPath = "$showPath/Season $seasonNumber/season$seasonNumber.mkv",
                            videoName = "season$seasonNumber.mkv",
                            thumbPath = episodeThumbPath, mediaKey = null, fileSize = 1024L,
                        )
                    },
                )
            }
            val showId = repo.upsertShow(
                libraryId = libraryId, sourceKind = MediaSourceKind.LOCAL, tmdbId = showTmdbId,
                folderName = showFolderName, showPath = showPath,
                title = showTitle, originalTitle = null,
                year = null, plot = showPlot, rating = showRating, releaseDate = null,
                genres = emptyList(), studios = emptyList(),
                posterPath = showPosterPath, fanartPath = null, clearlogoPath = null,
                scannedAt = 1L, seasons = seasons,
            )
            block(repo, libraryId, showPath, showId)
        } finally {
            driver.close()
        }
    }

    private fun testSeasonScanData(seasonNumber: Int): SeasonScanData = SeasonScanData(
        nfo = SeasonNfo(seasonNumber = seasonNumber, title = null, year = null, releaseDate = null),
        bangumi = null,
        seasonPath = "/root/测试番剧/Season $seasonNumber",
        seasonPosterPath = null,
        episodes = listOf(1, 2).map { episodeNumber ->
            EpisodeNfo(
                title = null,
                plot = null,
                rating = null,
                year = null,
                aired = null,
                episode = episodeNumber,
                season = seasonNumber,
                runtime = null,
            ) to EpisodeFile(
                videoPath = "/root/测试番剧/Season $seasonNumber/season$seasonNumber.mkv",
                videoName = "season$seasonNumber.mkv",
                thumbPath = null,
                mediaKey = null,
                fileSize = 1024L,
            )
        },
    )

    private suspend fun withServer(block: suspend (String, HttpServer) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            start()
        }
        try {
            block("http://127.0.0.1:${server.address.port}", server)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private suspend fun withConcurrentServer(block: suspend (String, HttpServer) -> Unit) {
        val executor = Executors.newFixedThreadPool(3)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            start()
        }
        try {
            block("http://127.0.0.1:${server.address.port}", server)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    // === TMDB 海报兜底(批次B): ANCHOR 番无本地封面时季级海报对是卡片封面唯一在线来源 ===

    /** 记录 (fileName, remoteUrl) 调用的下载器, 断言海报下载行为用。 */
    private class RecordingDownloader : RemoteImageDownloader {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun downloadImage(
            libraryId: Long, showPath: String, fileName: String, remoteUrl: String,
        ): String? {
            calls += fileName to remoteUrl
            return "/cache/online-scrape/$libraryId-$fileName"
        }

        fun urlsFor(fileName: String): List<String> = calls.filter { it.first == fileName }.map { it.second }
    }

    /** 恒失败下载器: tryDownloadOnlinePoster 失败路径用。 */
    private class FailingDownloader : RemoteImageDownloader {
        override suspend fun downloadImage(
            libraryId: Long, showPath: String, fileName: String, remoteUrl: String,
        ): String? = null
    }

    private suspend fun upsertSeasonOneMeta(
        repo: ScrapedLibraryRepository,
        libraryId: Long,
        showPath: String,
        localPosterPath: String?,
    ) {
        repo.upsertOnlineMeta(
            libraryId = libraryId, showPath = showPath, seasonNumber = 1,
            source = ScrapeSource.BANGUMI, overwriteTitle = false,
            dandanplayId = null, bangumiId = null,
            remotePosterUrl = "https://example.com/s1.jpg", localPosterPath = localPosterPath,
            title = null, originalTitle = null, year = null, plot = null, rating = null,
            releaseDate = null, genres = emptyList(), studios = emptyList(),
            episodes = emptyList(), scrapedAt = 1L,
        )
    }

    // === 海报墙一次性在线补封(批次C): tryDownloadOnlinePoster / needsPosterRestore ===

    @Test
    fun `tryDownloadOnlinePoster成功后回写季级local路径`() = runBlocking {
        // 修复前失败点: tryDownloadOnlinePoster 不存在; 若只下载不回写 updateOnlineMetaLocalPoster,
        // local_poster_path 保持 null, 海报墙刷新后 card_poster_path 仍为空(下载等于白做)。
        withDb { repo, libraryId, showPath, _ ->
            upsertSeasonOneMeta(repo, libraryId, showPath, localPosterPath = null)
            val scraper = AnimeScraper(
                dandanplay = null,
                bangumi = BangumiScrapeProvider(bangumiApi()),
                downloader = FakeDownloader(),
                repo = repo,
            )

            assertTrue(scraper.tryDownloadOnlinePoster(libraryId, showPath, 1, "https://example.com/s1.jpg"))
            assertEquals(
                "/cache/online-scrape/$libraryId-season1.jpg",
                repo.getOnlineMeta(libraryId, showPath, 1)?.local_poster_path,
            )
        }
    }

    @Test
    fun `tryDownloadOnlinePoster失败返回false且不写库`() = runBlocking {
        // 修复前失败点: 下载失败时若返回 true(或向上抛), 海报墙会误 bump 无意义刷新/直接崩;
        // 且失败也写 local 的话会把远程 URL 对污染成"已恢复"。
        withDb { repo, libraryId, showPath, _ ->
            upsertSeasonOneMeta(repo, libraryId, showPath, localPosterPath = null)
            val scraper = AnimeScraper(
                dandanplay = null,
                bangumi = BangumiScrapeProvider(bangumiApi()),
                downloader = FailingDownloader(),
                repo = repo,
            )

            assertFalse(scraper.tryDownloadOnlinePoster(libraryId, showPath, 1, "https://example.com/s1.jpg"))
            assertNull(repo.getOnlineMeta(libraryId, showPath, 1)?.local_poster_path)
        }
    }

    @Test
    fun `needsPosterRestore有远程URL无本地文件时true`() = runBlocking {
        // 修复前失败点: needsPosterRestore 不存在, 详情页封面重试条无从判断显示时机。
        withDb { repo, libraryId, showPath, _ ->
            upsertSeasonOneMeta(repo, libraryId, showPath, localPosterPath = null)
            val scraper = AnimeScraper(
                dandanplay = null,
                bangumi = BangumiScrapeProvider(bangumiApi()),
                downloader = FakeDownloader(),
                repo = repo,
            )

            assertTrue(scraper.needsPosterRestore(libraryId, showPath))
        }
    }

    @Test
    fun `needsPosterRestore本地文件在位时false`() = runBlocking {
        // 修复前失败点: 若只比 DB 字段不做真实文件复核, 缓存被清后重试条不再出现(用户无从重下);
        // 反向: 文件在位时必须 false, 否则重试条永不下线。
        withDb { repo, libraryId, showPath, _ ->
            val posterFile = Files.createTempFile("unu-needs-restore", ".jpg")
            try {
                upsertSeasonOneMeta(repo, libraryId, showPath, localPosterPath = posterFile.toAbsolutePath().toString())
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                )

                assertFalse(scraper.needsPosterRestore(libraryId, showPath))
            } finally {
                posterFile.toFile().delete()
            }
        }
    }

    @Test
    fun `needsPosterRestore卡片已有可见封面时false_即使另有季缺封`() = runBlocking {
        // 修复前失败点: 任一季「有URL无local」即 true, 即使另一季本地海报已给卡片封面,
        // 详情页仍常驻「封面下载失败」误导条(与卡片实际有封面矛盾)。
        withDb { repo, libraryId, showPath, _ ->
            val posterFile = Files.createTempFile("unu-needs-restore", ".jpg")
            try {
                // 季2 本地海报已落地(卡片封面), 季1 仅远程 URL
                repo.upsertOnlineMeta(
                    libraryId = libraryId, showPath = showPath, seasonNumber = 2,
                    source = ScrapeSource.BANGUMI, overwriteTitle = false,
                    dandanplayId = null, bangumiId = null,
                    remotePosterUrl = "https://example.com/s2.jpg",
                    localPosterPath = posterFile.toAbsolutePath().toString(),
                    title = null, originalTitle = null, year = null, plot = null, rating = null,
                    releaseDate = null, genres = emptyList(), studios = emptyList(),
                    episodes = emptyList(), scrapedAt = 1L,
                )
                upsertSeasonOneMeta(repo, libraryId, showPath, localPosterPath = null)
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                )

                assertFalse(scraper.needsPosterRestore(libraryId, showPath))
            } finally {
                posterFile.toFile().delete()
            }
        }
    }

    @Test
    fun `TMDB-only路径ANCHOR番季海报落入在线meta`() = runBlocking {
        // 修复前失败点: enrichWithTmdb 不下载/不写季级海报, getOnlineMeta(...).remote_poster_url 为 null。
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(200, """{"data":[]}""")
            }
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                exchange.respond(
                    200,
                    """{"candidates":[{"tmdbId":999,"name":"测试番剧","firstAirDate":"2024-01-01"}]}""",
                )
            }
            server.createContext("/api/v1/tmdb/tv/999/images") { exchange ->
                exchange.respond(200, """{"tvId":999,"backdrops":[],"posters":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/999/season/1/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"tvId":999,"seasonNumber":1,"posterPath":"/season1-poster.jpg","episodes":[]}""",
                )
            }
            withDb { repo, libraryId, showPath, _ ->
                val downloader = RecordingDownloader()
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = downloader,
                    repo = repo,
                    tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertIs<AnimeScraper.AutoScrapeOutcome.Done>(outcome)
                val seasonMeta = assertNotNull(repo.getOnlineMeta(libraryId, showPath, 1))
                assertEquals(
                    "https://image.tmdb.org/t/p/w500/season1-poster.jpg",
                    seasonMeta.remote_poster_url,
                    "季级 meta 应写入 TMDB 季海报完整 URL",
                )
                assertEquals(
                    "/cache/online-scrape/$libraryId-season1.jpg",
                    seasonMeta.local_poster_path,
                    "下载成功后应写入本地缓存路径",
                )
                assertEquals(
                    listOf("https://image.tmdb.org/t/p/w500/season1-poster.jpg"),
                    downloader.urlsFor("season1.jpg"),
                )
            }
        }
    }

    @Test
    fun `TMDB季海报下载成功但落库失败时保持可重试`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v1/tmdb/tv/999/images") { exchange ->
                exchange.respond(200, """{"tvId":999,"backdrops":[],"posters":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/999/season/1/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"tvId":999,"seasonNumber":1,"posterPath":"/season1-poster.jpg","episodes":[]}""",
                )
            }
            withDb(showTmdbId = 999L) { repo, libraryId, showPath, _ ->
                val failingRepo = object : ScrapedLibraryRepository by repo {
                    override suspend fun upsertOnlineMeta(
                        libraryId: Long,
                        showPath: String,
                        seasonNumber: Int,
                        source: ScrapeSource,
                        overwriteTitle: Boolean,
                        dandanplayId: Long?,
                        bangumiId: Long?,
                        remotePosterUrl: String?,
                        localPosterPath: String?,
                        title: String?,
                        originalTitle: String?,
                        year: Int?,
                        plot: String?,
                        rating: Double?,
                        releaseDate: String?,
                        genres: List<String>,
                        studios: List<String>,
                        episodes: List<ScrapedOnlineEpisode>,
                        scrapedAt: Long,
                    ) {
                        if (source == ScrapeSource.TMDB && seasonNumber == 1 && localPosterPath != null) {
                            error("模拟 TMDB 季海报落库失败")
                        }
                        repo.upsertOnlineMeta(
                            libraryId, showPath, seasonNumber, source, overwriteTitle,
                            dandanplayId, bangumiId, remotePosterUrl, localPosterPath,
                            title, originalTitle, year, plot, rating, releaseDate, genres, studios,
                            episodes, scrapedAt,
                        )
                    }
                }
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = failingRepo,
                    tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                )

                assertIs<AnimeScraper.AutoScrapeOutcome.Partial>(
                    scraper.enrichTmdb(libraryOf(libraryId), showPath),
                )
                assertTrue(repo.hasAutoScrapeRetryMarker(libraryId, showPath))
                assertNull(repo.lastOnlineScrapeAt(libraryId, showPath))
                assertNull(repo.getOnlineMeta(libraryId, showPath, 1)?.local_poster_path)
            }
        }
    }

    @Test
    fun `季海报缺失时回退TV海报`() = runBlocking {
        // 修复前失败点: 无海报兜底逻辑, 季级 meta remote_poster_url 为 null(而非 TV 海报 URL)。
        withServer { serverUrl, server ->
            server.createContext("/api/v1/tmdb/tv/999/images") { exchange ->
                exchange.respond(
                    200,
                    """{"tvId":999,"backdrops":[],"posters":[{"filePath":"/tv-poster.jpg","language":"zh"}]}""",
                )
            }
            server.createContext("/api/v1/tmdb/tv/999/season/1/episodes") { exchange ->
                // 旧网关格式: 无 posterPath 字段
                exchange.respond(200, """{"tvId":999,"seasonNumber":1,"episodes":[]}""")
            }
            withDb(showTmdbId = 999L) { repo, libraryId, showPath, _ ->
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                )

                assertIs<AnimeScraper.AutoScrapeOutcome.Done>(
                    scraper.enrichTmdb(libraryOf(libraryId), showPath),
                )

                val seasonMeta = assertNotNull(repo.getOnlineMeta(libraryId, showPath, 1))
                assertEquals(
                    "https://image.tmdb.org/t/p/w500/tv-poster.jpg",
                    seasonMeta.remote_poster_url,
                    "季响应无海报时应回退 TV 海报",
                )
            }
        }
    }

    @Test
    fun `已有Bangumi季照时TMDB海报不顶掉`() = runBlocking {
        // 端到端双防线: enrich 步骤④跳过已有海报对的季 + repo 合并按来源优先级拒收,
        // 任一层撤掉另一层仍守住(合并语义的单元级锚点见「在线海报合并按来源优先级成对处理」)。
        withServer { serverUrl, server ->
            server.createContext("/api/v1/tmdb/tv/999/images") { exchange ->
                exchange.respond(200, """{"tvId":999,"backdrops":[],"posters":[{"filePath":"/tvp.jpg"}]}""")
            }
            server.createContext("/api/v1/tmdb/tv/999/season/1/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"tvId":999,"seasonNumber":1,"posterPath":"/sp.jpg","episodes":[]}""",
                )
            }
            withDb(showTmdbId = 999L) { repo, libraryId, showPath, _ ->
                repo.upsertOnlineMeta(
                    libraryId = libraryId, showPath = showPath, seasonNumber = 1,
                    source = ScrapeSource.BANGUMI, overwriteTitle = false,
                    dandanplayId = null, bangumiId = 400602L,
                    remotePosterUrl = "https://lain.bgm.tv/cover.jpg", localPosterPath = "/cache/bgm-season1.jpg",
                    title = null, originalTitle = null, year = null, plot = null, rating = null,
                    releaseDate = null, genres = emptyList(), studios = emptyList(),
                    episodes = emptyList(), scrapedAt = platformTimeMillis(),
                )
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                )

                assertIs<AnimeScraper.AutoScrapeOutcome.Done>(
                    scraper.enrichTmdb(libraryOf(libraryId), showPath),
                )

                val seasonMeta = assertNotNull(repo.getOnlineMeta(libraryId, showPath, 1))
                assertEquals("https://lain.bgm.tv/cover.jpg", seasonMeta.remote_poster_url, "Bangumi 存量 URL 不被 TMDB 顶掉")
                assertEquals("/cache/bgm-season1.jpg", seasonMeta.local_poster_path, "存量本地图同样保留")
            }
        }
    }

    @Test
    fun `本地show海报存在时整体跳过海报兜底`() = runBlocking {
        // 护栏测试: 撤掉 enrich 步骤④的 show.poster_path 前置跳过时,
        // urlsFor("season1.jpg") 断言会失败(产生不该有的海报下载调用)。
        withServer { serverUrl, server ->
            server.createContext("/api/v1/tmdb/tv/999/images") { exchange ->
                exchange.respond(200, """{"tvId":999,"backdrops":[],"posters":[{"filePath":"/tvp.jpg"}]}""")
            }
            server.createContext("/api/v1/tmdb/tv/999/season/1/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"tvId":999,"seasonNumber":1,"posterPath":"/sp.jpg","episodes":[]}""",
                )
            }
            withDb(showTmdbId = 999L, showPosterPath = "/media/poster.jpg") { repo, libraryId, showPath, _ ->
                val downloader = RecordingDownloader()
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = downloader,
                    repo = repo,
                    tmdb = TmdbScrapeApi("test-token", testClient(), serverUrl),
                )

                assertIs<AnimeScraper.AutoScrapeOutcome.Done>(
                    scraper.enrichTmdb(libraryOf(libraryId), showPath),
                )

                assertTrue(downloader.urlsFor("season1.jpg").isEmpty(), "本地海报已存在, 不应产生季海报下载")
                assertNull(
                    repo.getOnlineMeta(libraryId, showPath, 1)?.remote_poster_url,
                    "本地海报已存在, 不应写入在线季海报 URL",
                )
            }
        }
    }

    @Test
    fun `在线海报合并按来源优先级成对处理`() = runBlocking {
        // 直接打真实临时数据库的 repo 层(合并语义的单元级锚点)。
        // 修复前失败点: 旧 new-wins 语义下, 第②步弹弹后写会把 Bangumi 存量 URL 换成
        // "https://dandan.example/s1-new.jpg"、local 换成 "/cache/dd-new.jpg", 断言失败。
        withDb(showTmdbId = 999L) { repo, libraryId, showPath, _ ->
            suspend fun upsert(source: ScrapeSource, url: String, local: String, seasonNumber: Int = 1) {
                repo.upsertOnlineMeta(
                    libraryId = libraryId, showPath = showPath, seasonNumber = seasonNumber,
                    source = source, overwriteTitle = false,
                    dandanplayId = null, bangumiId = null,
                    remotePosterUrl = url, localPosterPath = local,
                    title = null, originalTitle = null, year = null, plot = null, rating = null,
                    releaseDate = null, genres = emptyList(), studios = emptyList(),
                    episodes = emptyList(), scrapedAt = platformTimeMillis(),
                )
            }

            // ① 弹弹先落, Bangumi 后写: 优先级 3>2, 成对顶掉
            upsert(ScrapeSource.DANDANPLAY, "https://dandan.example/s1.jpg", "/cache/dd-season1.jpg")
            upsert(ScrapeSource.BANGUMI, "https://lain.bgm.tv/s1.jpg", "/cache/bgm-season1.jpg")
            val afterBangumi = assertNotNull(repo.getOnlineMeta(libraryId, showPath, 1))
            assertEquals("https://lain.bgm.tv/s1.jpg", afterBangumi.remote_poster_url)
            assertEquals("/cache/bgm-season1.jpg", afterBangumi.local_poster_path)

            // ② 弹弹再写: 2<3 不顶掉, URL 与 local 成对保留(不允许只换其一造成错配)
            upsert(ScrapeSource.DANDANPLAY, "https://dandan.example/s1-new.jpg", "/cache/dd-new.jpg")
            val afterDandan = assertNotNull(repo.getOnlineMeta(libraryId, showPath, 1))
            assertEquals("https://lain.bgm.tv/s1.jpg", afterDandan.remote_poster_url, "低优先级后写不得顶掉 Bangumi URL")
            assertEquals("/cache/bgm-season1.jpg", afterDandan.local_poster_path, "URL 保留时 local 也不被换")

            // ③ TMDB 兜底同样不顶掉弹弹存量(season=2 独立行)
            upsert(ScrapeSource.DANDANPLAY, "https://dandan.example/s2.jpg", "/cache/dd-season2.jpg", seasonNumber = 2)
            upsert(ScrapeSource.TMDB, "https://image.tmdb.org/t/p/w500/s2.jpg", "/cache/tmdb-season2.jpg", seasonNumber = 2)
            val season2 = assertNotNull(repo.getOnlineMeta(libraryId, showPath, 2))
            assertEquals("https://dandan.example/s2.jpg", season2.remote_poster_url, "TMDB 兜底不得顶掉弹弹 URL")
            assertEquals("/cache/dd-season2.jpg", season2.local_poster_path)
            assertEquals(ScrapeSource.DANDANPLAY.storageName, season2.poster_source, "海报归属来源随弹弹写入")
        }
    }

    @Test
    fun `同级重刮补单腿且单字段传入不造成错配对`() = runBlocking {
        // 修复前失败点(回归): ①同级(同优先级)重刮下载成功的 full pair 被严格 > 比较拒收,
        //   首次下载失败留下的 URL-only 行永远补不上 local; ②高优先级只带 URL 会把新 URL 与旧 local 拼错配。
        withDb(showTmdbId = 999L) { repo, libraryId, showPath, _ ->
            suspend fun upsert(
                source: ScrapeSource, url: String?, local: String?, seasonNumber: Int = 1,
                overwriteTitle: Boolean = false,
            ) {
                repo.upsertOnlineMeta(
                    libraryId = libraryId, showPath = showPath, seasonNumber = seasonNumber,
                    source = source, overwriteTitle = overwriteTitle,
                    dandanplayId = null, bangumiId = null,
                    remotePosterUrl = url, localPosterPath = local,
                    title = null, originalTitle = null, year = null, plot = null, rating = null,
                    releaseDate = null, genres = emptyList(), studios = emptyList(),
                    episodes = emptyList(), scrapedAt = platformTimeMillis(),
                )
            }

            // ① 首次刮削下载失败: 只落 URL, local 缺失
            upsert(ScrapeSource.BANGUMI, "https://lain.bgm.tv/s1.jpg", null)
            var meta = assertNotNull(repo.getOnlineMeta(libraryId, showPath, 1))
            assertEquals("https://lain.bgm.tv/s1.jpg", meta.remote_poster_url)
            assertNull(meta.local_poster_path)
            assertEquals(ScrapeSource.BANGUMI.storageName, meta.poster_source)

            // ② 同源重刮下载成功(full pair): 同级允许补单腿 → local 被填上, URL 同步更新
            upsert(ScrapeSource.BANGUMI, "https://lain.bgm.tv/s1.jpg", "/cache/bgm-season1.jpg")
            meta = assertNotNull(repo.getOnlineMeta(libraryId, showPath, 1))
            assertEquals("/cache/bgm-season1.jpg", meta.local_poster_path, "同级 full pair 应补齐失败恢复缺腿")
            assertEquals("https://lain.bgm.tv/s1.jpg", meta.remote_poster_url)

            // ③ 同源存量对完整时同级再写(封面轮换): 严格同级仍拒收, 换图冻结是已拍板语义
            upsert(ScrapeSource.BANGUMI, "https://lain.bgm.tv/s1-new.jpg", "/cache/bgm-season1-new.jpg")
            meta = assertNotNull(repo.getOnlineMeta(libraryId, showPath, 1))
            assertEquals("https://lain.bgm.tv/s1.jpg", meta.remote_poster_url, "同级完整对不换图(封面轮换冻结)")
            assertEquals("/cache/bgm-season1.jpg", meta.local_poster_path)

            // ④ 高优先级源只带 URL(下载失败)不得与存量 local 拼错配: 整体保留存量对
            upsert(ScrapeSource.BANGUMI, "https://lain.bgm.tv/s1-bangumi.jpg", null, seasonNumber = 1)
            meta = assertNotNull(repo.getOnlineMeta(libraryId, showPath, 1))
            assertEquals("https://lain.bgm.tv/s1.jpg", meta.remote_poster_url, "URL-only 不换存量 URL")
            assertEquals("/cache/bgm-season1.jpg", meta.local_poster_path, "URL-only 不拼错配")

            // ⑤ 存量仅 local(remote 空)的行不被低优先级源劫持(双字段判空: local 非空即算存量对存在)
            upsert(ScrapeSource.BANGUMI, null, "/cache/bgm-localonly.jpg", seasonNumber = 2)
            var localOnly = assertNotNull(repo.getOnlineMeta(libraryId, showPath, 2))
            assertEquals(ScrapeSource.BANGUMI.storageName, localOnly.poster_source)
            upsert(ScrapeSource.TMDB, "https://image.tmdb.org/t/p/w500/s2.jpg", "/cache/tmdb-s2.jpg", seasonNumber = 2)
            localOnly = assertNotNull(repo.getOnlineMeta(libraryId, showPath, 2))
            assertNull(localOnly.remote_poster_url, "低优先级 TMDB 不得顶掉存量 local-only 行的对")
            assertEquals("/cache/bgm-localonly.jpg", localOnly.local_poster_path, "存量 local 被保留")
            assertEquals(ScrapeSource.BANGUMI.storageName, localOnly.poster_source)
        }
    }

    @Test
    fun `来源标签与海报归属解耦_MANUAL_TMDB行可被高优先级自动源补海报`() = runBlocking {
        // 修复前失败点: ①MANUAL_TMDB 行被 effectiveSource pin 后, 后续 auto BANGUMI full pair
        //   因 1>1 为 false 被拒, 用户手动指定 TMDB 身份后最高优先级海报永远补不上;
        //   ②存量 BANGUMI 对 + TMDB 文本写(海报被拒)把 scrape_source 降级为 TMDB, 后续弹弹
        //   借 2>1 合法顶掉本应保留的 Bangumi 图。poster_source 列把两类来源解耦修复。
        withDb(showTmdbId = 999L) { repo, libraryId, showPath, _ ->
            suspend fun upsert(
                source: ScrapeSource, url: String?, local: String?, seasonNumber: Int = 1,
                bangumiId: Long? = null, dandanplayId: Long? = null,
            ) {
                repo.upsertOnlineMeta(
                    libraryId = libraryId, showPath = showPath, seasonNumber = seasonNumber,
                    source = source, overwriteTitle = false,
                    dandanplayId = dandanplayId, bangumiId = bangumiId,
                    remotePosterUrl = url, localPosterPath = local,
                    title = null, originalTitle = null, year = null, plot = null, rating = null,
                    releaseDate = null, genres = emptyList(), studios = emptyList(),
                    episodes = emptyList(), scrapedAt = platformTimeMillis(),
                )
            }

            // ① MANUAL_TMDB 身份行(无海报)
            upsert(ScrapeSource.MANUAL_TMDB, null, null, bangumiId = null)
            // ② auto BANGUMI full pair 应补上(存量对为空 → 接受, 不被 MANUAL_TMDB pin 拒)
            upsert(ScrapeSource.BANGUMI, "https://lain.bgm.tv/s1.jpg", "/cache/bgm-season1.jpg")
            val meta = assertNotNull(repo.getOnlineMeta(libraryId, showPath, 1))
            assertEquals("https://lain.bgm.tv/s1.jpg", meta.remote_poster_url, "MANUAL_TMDB 行可被 Bangumi 补海报")
            assertEquals("/cache/bgm-season1.jpg", meta.local_poster_path)
            assertEquals(ScrapeSource.BANGUMI.storageName, meta.poster_source)
            // 身份 pin 保留: scrape_source 仍是 MANUAL_TMDB
            assertEquals(ScrapeSource.MANUAL_TMDB.storageName, meta.scrape_source)

            // ③ 后续弹弹(2<3, 海报对在)不得顶掉 Bangumi 海报
            upsert(ScrapeSource.DANDANPLAY, "https://dandan.example/s1.jpg", "/cache/dd-s1.jpg")
            val afterDandan = assertNotNull(repo.getOnlineMeta(libraryId, showPath, 1))
            assertEquals("https://lain.bgm.tv/s1.jpg", afterDandan.remote_poster_url, "弹弹不得顶掉 Bangumi 海报")
            assertEquals(ScrapeSource.BANGUMI.storageName, afterDandan.poster_source)
        }
    }


    @Test
    fun `fetchImageDetailed失败原因分类与429退避`() = runBlocking {
        // 修复前失败点: fetchImageDetailed 不存在(编译失败); 行为层面旧 fetchImage 一律返回 null 无原因分类。
        RemoteImageFetcher.setHttpClientForTest(testClient())
        try {
            assertEquals(
                0L,
                RemoteImageFetcher.rateLimitBackoffRemainingMsForUrl("https://example.com/img/ok"),
                "前置: 不应残留其他测试的退避状态",
            )
            withServer { serverUrl, server ->
                val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
                server.createContext("/img/ok") { exchange ->
                    exchange.respondBytes(200, "image/jpeg", jpegBytes)
                }
                server.createContext("/img/html") { exchange ->
                    exchange.respondBytes(200, "text/html; charset=utf-8", "<html/>".encodeToByteArray())
                }
                server.createContext("/img/too-large") { exchange ->
                    exchange.respondBytes(200, "image/jpeg", ByteArray(64))
                }
                server.createContext("/img/rate") { exchange ->
                    exchange.responseHeaders.add("Retry-After", "2")
                    exchange.respondBytes(429, "text/plain", ByteArray(0))
                }
                server.createContext("/img/no-location") { exchange ->
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                }

                val ok = RemoteImageFetcher.fetchImageDetailed("$serverUrl/img/ok")
                val okSuccess = assertIs<RemoteImageFetcher.ImageFetchOutcome.Success>(ok)
                assertTrue(okSuccess.bytes.contentEquals(jpegBytes))

                val hugeCallerLimit = RemoteImageFetcher.fetchImageDetailed(
                    "$serverUrl/img/ok",
                    maxBytes = Int.MAX_VALUE.toLong() - 1L,
                )
                assertTrue(
                    assertIs<RemoteImageFetcher.ImageFetchOutcome.Success>(hugeCallerLimit)
                        .bytes.contentEquals(jpegBytes),
                    "调用方传近2GiB上限时仍应按32MiB绝对上限小缓冲读取，不能预分配近2GiB",
                )

                val html = RemoteImageFetcher.fetchImageDetailed("$serverUrl/img/html")
                val notImage = assertIs<RemoteImageFetcher.ImageFetchOutcome.Reason.NotImageType>(
                    assertIs<RemoteImageFetcher.ImageFetchOutcome.Failure>(html).reason,
                )
                assertEquals("text/html", notImage.contentType)

                val tooLarge = RemoteImageFetcher.fetchImageDetailed("$serverUrl/img/too-large", maxBytes = 16L)
                assertIs<RemoteImageFetcher.ImageFetchOutcome.Reason.ExceededSizeLimit>(
                    assertIs<RemoteImageFetcher.ImageFetchOutcome.Failure>(tooLarge).reason,
                )

                val noLocation = RemoteImageFetcher.fetchImageDetailed("$serverUrl/img/no-location")
                assertIs<RemoteImageFetcher.ImageFetchOutcome.Reason.RedirectError>(
                    assertIs<RemoteImageFetcher.ImageFetchOutcome.Failure>(noLocation).reason,
                )

                val refused = RemoteImageFetcher.fetchImageDetailed("http://127.0.0.1:1/x.jpg")
                assertIs<RemoteImageFetcher.ImageFetchOutcome.Reason.NetworkError>(
                    assertIs<RemoteImageFetcher.ImageFetchOutcome.Failure>(refused).reason,
                )

                val rateLimited = RemoteImageFetcher.fetchImageDetailed("$serverUrl/img/rate")
                val httpError = assertIs<RemoteImageFetcher.ImageFetchOutcome.Reason.HttpError>(
                    assertIs<RemoteImageFetcher.ImageFetchOutcome.Failure>(rateLimited).reason,
                )
                assertEquals(429, httpError.statusCode)
                // 429 按触发主机记录: 触发主机进入 Retry-After=2s 退避, 其它主机不受影响
                val remaining = RemoteImageFetcher.rateLimitBackoffRemainingMsForUrl("$serverUrl/img/rate")
                assertTrue(remaining in 1..2000L, "429 后应进入 Retry-After=2s 退避, 实际剩余 ${remaining}ms")
                val otherHostRemaining = RemoteImageFetcher.rateLimitBackoffRemainingMsForUrl("https://image.tmdb.org/t/p/w500/x.jpg")
                assertEquals(0L, otherHostRemaining, "其它主机的 429 退避互不耦合(FP3-13)")
            }
        } finally {
            RemoteImageFetcher.resetRateLimitBackoffForTest()
            RemoteImageFetcher.setHttpClientForTest(null)
        }
        assertEquals(0L, RemoteImageFetcher.rateLimitBackoffRemainingMsForUrl("https://example.com/x.jpg"), "测试末尾必须清除进程级退避")
    }

    @Test
    fun `无剧照标记超 TTL 后重新探测`() = runBlocking {
        withServer { serverUrl, server ->
            val stillRequests = AtomicInteger(0)
            server.createContext("/api/v1/tmdb/tv/777/images") { exchange ->
                exchange.respond(200, """{"tvId":777,"backdrops":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/777/season/1/episodes") { exchange ->
                stillRequests.incrementAndGet()
                exchange.respond(200, """{"tvId":777,"seasonNumber":1,"episodes":[]}""")
            }
            withDb(
                showTmdbId = 777L,
                showPosterPath = "/media/poster.jpg",
                seasonPosterPath = "/media/season-poster.jpg",
                showPlot = "完整简介",
                episodeTitlePrefix = "第",
                episodeAired = "2024-01-01",
            ) { repo, libraryId, showPath, _ ->
                val expiredScrapedAt = platformTimeMillis() - 8L * 24L * 60L * 60L * 1000L
                // 季 meta: 明确无剧照(false), scraped_at 8 天前(超 TMDB_STILL_NEGATIVE_TTL_MS)
                repo.upsertOnlineMeta(
                    libraryId, showPath, 1, ScrapeSource.TMDB, false,
                    dandanplayId = null, bangumiId = null,
                    remotePosterUrl = null, localPosterPath = null,
                    title = null, originalTitle = null, year = null, plot = null, rating = null,
                    releaseDate = null, genres = emptyList(), studios = emptyList(),
                    episodes = listOf(1, 2).map {
                        ScrapedOnlineEpisode(it, "第${it}话", thumbPath = null, tmdbStillAvailable = false)
                    },
                    scrapedAt = expiredScrapedAt,
                )
                val scraper = AnimeScraper(
                    dandanplay = null,
                    bangumi = BangumiScrapeProvider(bangumiApi()),
                    downloader = FakeDownloader(),
                    repo = repo,
                    tmdb = TmdbScrapeApi(apiKey = "test-token", baseUrl = serverUrl, httpClient = testClient()),
                )

                assertTrue(scraper.shouldAutoScrape(libraryId, showPath))
                assertIs<AnimeScraper.AutoScrapeOutcome.Done>(
                    scraper.enrichTmdb(libraryOf(libraryId, ScanMode.NFO), showPath),
                )

                val refreshed = assertNotNull(repo.getOnlineMeta(libraryId, showPath, 1))
                assertEquals(1, stillRequests.get())
                assertTrue(refreshed.scraped_at > expiredScrapedAt, "成功确认仍无剧照后应重建完整 TTL")
                assertTrue(refreshed.decodedEpisodes.all { it.tmdbStillAvailable == false })
                assertFalse(scraper.shouldAutoScrape(libraryId, showPath))
            }
        }
    }

    private fun HttpExchange.respond(status: Int, body: String = "") {
        val bytes = body.encodeToByteArray()
        responseHeaders.add("Content-Type", "application/json")
        if (bytes.isEmpty()) {
            sendResponseHeaders(status, -1)
        } else {
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        close()
    }

    /** 自定义 Content-Type 的原始字节响应(fetchImageDetailed 图片语义测试用)。 */
    private fun HttpExchange.respondBytes(status: Int, contentType: String, bytes: ByteArray) {
        responseHeaders.set("Content-Type", contentType)
        if (bytes.isEmpty()) {
            sendResponseHeaders(status, -1)
        } else {
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        close()
    }
}
