package io.github.weiyongzenqi.unuplayer.danmaku.source

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DanmakuMatcherPriorityTest {

    private val defaultOrder = listOf(
        DanmakuMatchMethod.TMDB_DATABASE,
        DanmakuMatchMethod.TMDB_PATH,
        DanmakuMatchMethod.HASH,
    )

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
                config = DanmakuMatchConfig("tmdb[=-](\\d+)", defaultOrder),
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
                config = DanmakuMatchConfig("tmdb[=-](\\d+)", defaultOrder),
                hashProvider = { hashCalls++; 100L to "hash" },
                databaseTmdbId = 100L,
                seasonHint = 1,
                episodeHint = 1,
            )

            // 两个 TMDB 都未命中时才调用哈希；请求顺序仍保持数据库 -> 路径。
            assertNull(result)
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
                config = DanmakuMatchConfig("tmdb[=-](\\d+)", defaultOrder),
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

    @Test
    fun `自定义顺序哈希在前时先算哈希命中且不发 TMDB 请求`() = runBlocking {
        val requests = mutableListOf<Long>()
        val server = startServer(requests, hashMatched = true)
        try {
            val matcher = DanmakuMatcher(
                DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base),
            )
            var hashCalls = 0
            val result = matcher.matchByPriority(
                fileName = "示例 S01E01.mkv",
                urlOrPath = "/tmdb=200/示例 S01E01.mkv",
                config = DanmakuMatchConfig(
                    "tmdb[=-](\\d+)",
                    listOf(
                        DanmakuMatchMethod.HASH,
                        DanmakuMatchMethod.TMDB_DATABASE,
                        DanmakuMatchMethod.TMDB_PATH,
                    ),
                ),
                hashProvider = { hashCalls++; 100L to "hash" },
                databaseTmdbId = 100L,
                seasonHint = 1,
                episodeHint = 1,
            )

            assertEquals(DanmakuMatchMethod.HASH, result?.matchMethod)
            assertEquals(9001L, result?.episodeId)
            assertEquals(1, hashCalls)
            assertEquals(emptyList(), requests, "哈希先命中时不应发任何 TMDB 请求")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `禁用路径 TMDB 后跳过路径提取回落哈希`() = runBlocking {
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
                config = DanmakuMatchConfig(
                    "tmdb[=-](\\d+)",
                    listOf(DanmakuMatchMethod.TMDB_DATABASE, DanmakuMatchMethod.HASH),
                ),
                hashProvider = { hashCalls++; 100L to "hash" },
                databaseTmdbId = 100L,
                seasonHint = 1,
                episodeHint = 1,
            )

            assertNull(result)
            assertEquals(listOf(100L), requests, "TMDB_PATH 未启用时不应请求路径提取的 id")
            assertEquals(1, hashCalls)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `路径 TMDB 在前且与数据库 id 相同时跳过路径由数据库命中`() = runBlocking {
        val requests = mutableListOf<Long>()
        val server = startServer(requests)
        try {
            val matcher = DanmakuMatcher(
                DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base),
            )
            val result = matcher.matchByPriority(
                fileName = "示例 S01E01.mkv",
                urlOrPath = "/tmdb=100/示例 S01E01.mkv",
                config = DanmakuMatchConfig(
                    "tmdb[=-](\\d+)",
                    listOf(
                        DanmakuMatchMethod.TMDB_PATH,
                        DanmakuMatchMethod.TMDB_DATABASE,
                        DanmakuMatchMethod.HASH,
                    ),
                ),
                hashProvider = { 100L to "hash" },
                databaseTmdbId = 100L,
                seasonHint = 1,
                episodeHint = 1,
            )

            // 同 ID 不重复请求: 跳过 PATH, 由数据库方式命中。
            assertEquals(DanmakuMatchMethod.TMDB_DATABASE, result?.matchMethod)
            assertEquals(listOf(100L), requests)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `禁用数据库TMDB后路径TMDB与数据库同ID仍放行`() = runBlocking {
        // 修复前失败点: "同 ID 跳过"逻辑假设 TMDB_DATABASE 恒在顺序中先试; 用户把数据库方式
        // 移出/禁用后, 路径标记是唯一剩余 TMDB 通道, 却因 pathTmdbId==databaseTmdbId 被跳过 → 弹幕漏匹配。
        val requests = mutableListOf<Long>()
        val server = startServer(requests)
        try {
            val matcher = DanmakuMatcher(
                DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base),
            )
            var hashCalls = 0
            val result = matcher.matchByPriority(
                fileName = "示例 S01E01.mkv",
                urlOrPath = "/tmdb=100/示例 S01E01.mkv",
                config = DanmakuMatchConfig(
                    "tmdb[=-](\\d+)",
                    listOf(DanmakuMatchMethod.TMDB_PATH, DanmakuMatchMethod.HASH),
                ),
                hashProvider = { hashCalls++; 100L to "hash" },
                databaseTmdbId = 100L,
                seasonHint = 1,
                episodeHint = 1,
            )

            assertEquals(DanmakuMatchMethod.TMDB_PATH, result?.matchMethod, "DATABASE 不在序中时路径 TMDB 必须放行")
            assertEquals(listOf(100L), requests)
            assertEquals(0, hashCalls)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `空匹配列表直接未匹配且无任何请求`() = runBlocking {
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
                config = DanmakuMatchConfig("tmdb[=-](\\d+)", emptyList()),
                hashProvider = { hashCalls++; 100L to "hash" },
                databaseTmdbId = 100L,
                seasonHint = 1,
                episodeHint = 1,
            )

            assertNull(result)
            assertEquals(emptyList(), requests)
            assertEquals(0, hashCalls)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `枚举名解析保持顺序且未知名忽略空列表合法`() {
        assertEquals(
            listOf(DanmakuMatchMethod.HASH, DanmakuMatchMethod.TMDB_DATABASE),
            parseDanmakuMatchOrder(listOf("HASH", "TMDB_DATABASE")),
        )
        // 未知名(旧版本枚举名/脏数据)忽略, 不中断也不清空其余项
        assertEquals(
            listOf(DanmakuMatchMethod.HASH),
            parseDanmakuMatchOrder(listOf("HASH", "NOT_A_METHOD", "")),
        )
        // 空列表 = 用户显式全部禁用, 不得被解析层回落成默认
        assertEquals(emptyList(), parseDanmakuMatchOrder(emptyList()))
    }

    private fun startServer(
        requests: MutableList<Long>,
        emptyIds: Set<Long> = emptySet(),
        hashMatched: Boolean = false,
    ): TestServer {
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
            createContext("/api/v2/match") { exchange ->
                exchange.respond(
                    if (hashMatched) {
                        "{\"isMatched\":true,\"matches\":[{" +
                            "\"episodeId\":9001,\"animeId\":123,\"animeTitle\":\"示例\"," +
                            "\"episodeTitle\":\"第一集\",\"shift\":0}]}"
                    } else {
                        "{\"isMatched\":false,\"matches\":[]}"
                    },
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
