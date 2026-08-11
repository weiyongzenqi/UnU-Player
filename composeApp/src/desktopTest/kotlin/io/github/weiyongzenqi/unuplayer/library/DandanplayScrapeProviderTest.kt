package io.github.weiyongzenqi.unuplayer.library

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DandanplayScrapeProviderTest {

    // 专用客户端(不走进程级共享单例): 测试套件里 DanmakuNetworkLifecycleTest 会 closeSharedHttpClient,
    // 共享单例被关后后续请求全崩; 每测试新建私有 OkHttp 客户端, 短命 JVM 下可接受。
    private fun testClient(): HttpClient = HttpClient(OkHttp) { followRedirects = false }

    @Test
    fun `搜索转候选含季照首播简介评分`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"animes":[
                        {"animeId":123,"animeTitle":"葬送的芙莉莲","type":"tv","typeDescription":"TV",
                         "bangumiId":"400602","imageUrl":"/cover.jpg",
                         "startDate":"2023-09-29","episodeCount":28,"rating":8.7,"intro":"勇者一行…"}
                    ]}""",
                )
            }

            val candidates = DandanplayScrapeProvider(DandanplayApi(baseUrl = serverUrl, httpClient = testClient())).search("葬送的芙莉莲")

            assertEquals(1, candidates.size)
            val c = candidates.single()
            assertEquals(123L, c.identityId)
            assertEquals("葬送的芙莉莲", c.title)
            assertEquals(2023, c.year)
            assertEquals("$serverUrl/cover.jpg", c.posterUrl)
            assertEquals(8.7, c.rating)
            assertEquals("勇者一行…", c.intro)
            assertEquals(ScrapeSource.DANDANPLAY, c.source)
        }
    }

    @Test
    fun `详情只保留正整数集并带季照`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v2/bangumi/123") { exchange ->
                exchange.respond(
                    200,
                    """{"success":true,"bangumi":{
                        "animeId":123,"animeTitle":"葬送的芙莉莲","imageUrl":"https://lain.bgm.tv/b.jpg",
                        "episodes":[
                            {"episodeId":9001,"episodeTitle":"旅程的起点","episodeNumber":"1","airDate":"2023-09-29"},
                            {"episodeId":9002,"episodeTitle":"SP特典","episodeNumber":"0","airDate":"2023-10-01"},
                            {"episodeId":9003,"episodeTitle":"半话","episodeNumber":"1.5","airDate":"2023-10-08"}
                        ]
                    }}""",
                )
            }

            val detail = DandanplayScrapeProvider(DandanplayApi(baseUrl = serverUrl, httpClient = testClient()))
                .fetchDetail(ScrapeCandidate(ScrapeSource.DANDANPLAY, 123L, "葬送的芙莉莲"))

            // 只保留正整数主集, 跳过 0(SP) 与 1.5(半话)
            assertEquals(listOf(1), detail.episodes.map { it.episodeNumber })
            assertEquals("旅程的起点", detail.episodes[0].title)
            assertEquals("2023-09-29", detail.episodes[0].aired)
            assertEquals("https://lain.bgm.tv/b.jpg", detail.remotePosterUrl)
        }
    }

    @Test
    fun `hash未命中返回null`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v2/match") { exchange ->
                exchange.respond(200, """{"isMatched":false,"matches":[]}""")
            }

            val provider = DandanplayScrapeProvider(DandanplayApi(baseUrl = serverUrl, httpClient = testClient()))
            assertNull(provider.matchSeason("test.mkv", "abc", 1024))
        }
    }

    @Test
    fun `超限响应不会进入JSON解析`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v2/search/anime") { exchange ->
                exchange.respond(200, " ".repeat(4 * 1024 * 1024 + 1))
            }

            val provider = DandanplayScrapeProvider(DandanplayApi(baseUrl = serverUrl, httpClient = testClient()))
            assertFailsWith<RuntimeException> { provider.search("测试番剧") }
        }
    }

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
