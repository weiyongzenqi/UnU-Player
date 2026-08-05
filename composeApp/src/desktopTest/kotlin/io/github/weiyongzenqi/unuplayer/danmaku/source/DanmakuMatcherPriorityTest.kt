package io.github.weiyongzenqi.unuplayer.danmaku.source

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

class DanmakuMatcherPriorityTest {

    @Test
    fun `数据库 TMDB 命中时优先于路径并跳过哈希`() = runBlocking {
        val requests = mutableListOf<Long>()
        val server = startServer(requests)
        try {
            val matcher = DanmakuMatcher(
                DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base),
            )
            var hashCalls = 0
            val result = matcher.matchByPriority(
                fileName = "示例 S01E01.mkv",
                urlOrPath = "/tmdb=200/示例 S01E01.mkv",
                config = DanmakuMatchConfig(true, "tmdb[=-](\\d+)", true),
                hashProvider = { hashCalls++; 100L to "hash" },
                databaseTmdbId = 100L,
                seasonHint = 1,
                episodeHint = 1,
            )

            assertEquals(DanmakuMatchMethod.TMDB_DATABASE, result?.matchMethod)
            assertEquals(listOf(100L), requests)
            assertEquals(0, hashCalls)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `数据库 TMDB 失败后回落路径 TMDB 再回落哈希`() = runBlocking {
        val requests = mutableListOf<Long>()
        val server = startServer(requests, emptyIds = setOf(100L, 200L))
        try {
            val matcher = DanmakuMatcher(
                DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base),
            )
            var hashCalls = 0
            val result = matcher.matchByPriority(
                fileName = "示例 S01E01.mkv",
                urlOrPath = "/tmdb=200/示例 S01E01.mkv",
                config = DanmakuMatchConfig(true, "tmdb[=-](\\d+)", true),
                hashProvider = { hashCalls++; 100L to "hash" },
                databaseTmdbId = 100L,
                seasonHint = 1,
                episodeHint = 1,
            )

            // 两个 TMDB 都未命中时才调用哈希；请求顺序仍保持数据库 -> 路径。
            assertEquals(null, result)
            assertEquals(listOf(100L, 200L), requests)
            assertEquals(1, hashCalls)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `数据库 TMDB 失败后路径 TMDB 命中且不计算哈希`() = runBlocking {
        val requests = mutableListOf<Long>()
        val server = startServer(requests, emptyIds = setOf(100L))
        try {
            val matcher = DanmakuMatcher(
                DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base),
            )
            var hashCalls = 0
            val result = matcher.matchByPriority(
                fileName = "示例 S01E01.mkv",
                urlOrPath = "/tmdb=200/示例 S01E01.mkv",
                config = DanmakuMatchConfig(true, "tmdb[=-](\\d+)", true),
                hashProvider = { hashCalls++; 100L to "hash" },
                databaseTmdbId = 100L,
                seasonHint = 1,
                episodeHint = 1,
            )

            assertEquals(DanmakuMatchMethod.TMDB_PATH, result?.matchMethod)
            assertEquals(listOf(100L, 200L), requests)
            assertEquals(0, hashCalls)
        } finally {
            server.stop()
        }
    }

    private fun startServer(requests: MutableList<Long>, emptyIds: Set<Long> = emptySet()): TestServer {
        val executor = Executors.newSingleThreadExecutor()
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/api/v2/search/episodes") { exchange ->
                val tmdbId = exchange.requestURI.query.substringAfter("tmdbId=").toLong()
                synchronized(requests) { requests += tmdbId }
                val body = if (tmdbId in emptyIds) {
                    "{\"success\":true,\"animes\":[]}"
                } else {
                    "{\"success\":true,\"animes\":[{\"animeId\":$tmdbId,\"animeTitle\":\"示例\"}]}"
                }
                exchange.respond(body)
            }
            createContext("/api/v2/bangumi/") { exchange ->
                val animeId = exchange.requestURI.path.substringAfterLast('/').toLong()
                exchange.respond(
                    "{\"success\":true,\"bangumi\":{\"animeId\":$animeId,\"episodes\":[" +
                        "{\"episodeId\":${animeId + 1000},\"episodeTitle\":\"第一集\",\"episodeNumber\":\"1\"}]}}",
                )
            }
            start()
        }
        return TestServer(
            "http://127.0.0.1:${httpServer.address.port}",
            httpServer,
            executor,
        )
    }

    private fun HttpExchange.respond(body: String) {
        val bytes = body.encodeToByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private class TestServer(
        val base: String,
        private val server: HttpServer,
        private val executor: java.util.concurrent.ExecutorService,
    ) {
        fun stop() {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
