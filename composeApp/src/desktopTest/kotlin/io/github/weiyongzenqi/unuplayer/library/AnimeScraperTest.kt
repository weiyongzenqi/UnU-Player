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
    fun `TMDB自动匹配不会持久化标题完全无关的首个候选`() = runBlocking {
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
    fun `Bangumi唯一但标题不相似不自动落库`() = runBlocking {
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
                )
                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.NeedsConfirmation)
                assertEquals(0, detailHits)
                assertOnlyAttemptMeta(repo, libraryId, showPath, 1)
            }
        }
    }

    @Test
    fun `Bangumi唯一但仅前缀包含不自动落库`() = runBlocking {
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
                assertEquals(0, bangumiDetailHits)
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
                assertTrue(repo.getOnlineMeta(libraryId, showPath, 1)?.decodedEpisodes.orEmpty().all { it.thumbPath == null })
                val episodes = repo.listEpisodes(repo.listSeasons(showId).single().id)
                assertTrue(episodes.all { it.thumb_path == null && it.local_thumb_path == null })
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
    fun `唯一但标题不相似的弹弹候选不会自动落库`() = runBlocking {
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
                )

                val outcome = scraper.scrapeAuto(libraryOf(libraryId), showPath)

                assertTrue(outcome is AnimeScraper.AutoScrapeOutcome.NoMatch)
                assertOnlyAttemptMeta(repo, libraryId, showPath, 1)
                assertFalse(scraper.shouldAutoScrape(libraryId, showPath))
            }
        }
    }

    @Test
    fun `弹弹标题仅包含查询词时保留候选但不自动落库`() = runBlocking {
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
                episodes = listOf(ScrapedOnlineEpisode(1, title = "第一集", thumbPath = "/cache/e1.jpg")),
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
}
