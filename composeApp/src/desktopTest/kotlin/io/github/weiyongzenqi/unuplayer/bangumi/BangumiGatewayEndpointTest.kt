package io.github.weiyongzenqi.unuplayer.bangumi

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentApi
import io.github.weiyongzenqi.unuplayer.core.network.APP_USER_AGENT
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.ktor.http.Url
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GATEWAY 预设传输层: 中性路由映射 + X-API-Key 注入 + /q 请求体形态 + 错误语义(不静默回退)。 */
class BangumiGatewayEndpointTest {
    private class Recorded(var path: String? = null, var query: String? = null, var body: String = "", var headers: Map<String, List<String>> = emptyMap())

    private fun HttpExchange.recordTo(recorded: Recorded) {
        recorded.path = requestURI.path
        recorded.query = requestURI.rawQuery
        recorded.body = requestBody.readBytes().decodeToString()
        recorded.headers = requestHeaders
    }

    @Test
    fun `图片鉴权仅注入完整相同的网关origin`() {
        val gateway = Url(BangumiGatewayConfig.imageBaseUrl())
        val scheme = gateway.protocol.name
        val host = gateway.host
        val defaultPort = gateway.protocol.defaultPort
        val otherPort = if (gateway.port == 65_535) 65_534 else gateway.port + 1

        assertEquals(
            setOf("User-Agent", "X-API-Key"),
            gatewayImageAuthHeaders("$scheme://$host/other/path/avatar.jpg").keys,
            "路径不属于 origin，网关同源的其他路径仍应携带鉴权",
        )
        assertEquals(
            setOf("User-Agent", "X-API-Key"),
            gatewayImageAuthHeaders("$scheme://$host:$defaultPort/i/avatar.jpg").keys,
            "显式默认端口应与省略默认端口视为同源",
        )
        assertTrue(gatewayImageAuthHeaders("http://$host/i/avatar.jpg").isEmpty(), "HTTP 降级不得携带 key")
        assertTrue(
            gatewayImageAuthHeaders("$scheme://$host:$otherPort/i/avatar.jpg").isEmpty(),
            "同主机异端口不得携带 key",
        )
        assertTrue(
            gatewayImageAuthHeaders("$scheme://sub.$host/i/avatar.jpg").isEmpty(),
            "子域不得继承网关 key",
        )
        assertEquals(APP_USER_AGENT, gatewayImageAuthHeaders("$scheme://$host/i/avatar.jpg")["User-Agent"])
        assertEquals(APP_USER_AGENT, bangumiImageRequestHeaders("https://lain.bgm.tv/pic/avatar.jpg")["User-Agent"])
    }

    @Test
    fun `搜索走 POST 斜杠 q 且 limit offset 进请求体`() = runBlocking {
        withServer { baseUrl, server ->
            val recorded = Recorded()
            server.createContext("/bgm/q") { exchange ->
                exchange.recordTo(recorded)
                exchange.respond(200, "{\"data\":[]}")
            }
            BangumiGatewayEndpoint(baseUrl = "$baseUrl/bgm", apiKey = "test-key-0123456789abcdef")
                .searchSubjects("葬送的芙莉莲", 5, 10)

            assertEquals("/bgm/q", recorded.path)
            assertEquals("test-key-0123456789abcdef", recorded.headers["X-API-Key"]?.first())
            assertContains(recorded.body, "\"k\":\"葬送的芙莉莲\"")
            assertContains(recorded.body, "\"limit\":5")
            assertContains(recorded.body, "\"offset\":10")
            assertContains(recorded.body, "\"sort\":\"match\"")
        }
    }

    @Test
    fun `条目详情走 GET 斜杠 s 且 404 返回 null`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/bgm/s/400602") { it.respond(404) }
            assertNull(BangumiGatewayEndpoint(baseUrl = "$baseUrl/bgm").subject(400602, allowNotFound = true))
        }
    }

    @Test
    fun `分页 limit 超网关上限被钳制到 100`() = runBlocking {
        withServer { baseUrl, server ->
            val recorded = Recorded()
            server.createContext("/bgm/e/623854") { exchange ->
                exchange.recordTo(recorded)
                exchange.respond(200, "{\"data\":[],\"total\":0}")
            }
            BangumiGatewayEndpoint(baseUrl = "$baseUrl/bgm").episodes(623854, limit = 200, offset = 100)
            assertEquals("/bgm/e/623854", recorded.path)
            assertEquals("type=0&limit=100&offset=100", recorded.query)
        }
    }

    @Test
    fun `非 2xx 抛出携带状态码的网关异常, 不静默回退`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/bgm/s/1") { it.respond(401) }
            val error = assertFailsWith<BangumiGatewayHttpException> {
                BangumiGatewayEndpoint(baseUrl = "$baseUrl/bgm", apiKey = "bad").subject(1)
            }
            assertEquals(401, error.statusCode)
        }
    }

    @Test
    fun `评论 API 注入网关后五类请求全部走中性路由`() = runBlocking {
        withServer { baseUrl, server ->
            val paths = mutableListOf<String>()
            fun route(path: String, payload: String = "{\"data\":[],\"total\":0}") = server.createContext(path) { exchange ->
                paths += exchange.requestURI.path
                exchange.respond(200, payload)
            }
            route("/bgm/c/623854")
            route("/bgm/e/623854")
            route("/bgm/ec/1670640", "[]")   // 单集评论是全量数组形态
            route("/bgm/t/623854")
            route("/bgm/d/36710")
            val api = BangumiCommentApi(gateway = BangumiGatewayEndpoint(baseUrl = "$baseUrl/bgm", apiKey = "k"))
            api.getSeasonComments(623854, 20, 0)
            api.getEpisodes(623854, 100, 0)
            api.getEpisodeComments(1670640)
            api.getSubjectTopics(623854, 20, 0)
            api.getTopicDetail(36710)
            assertEquals(
                listOf("/bgm/c/623854", "/bgm/e/623854", "/bgm/ec/1670640", "/bgm/t/623854", "/bgm/d/36710"),
                paths,
            )
        }
    }

    @Test
    fun `长评三请求走中性路由且列表带分页参数`() = runBlocking {
        withServer { baseUrl, server ->
            val recorded = Recorded()
            fun route(path: String, payload: String) = server.createContext(path) { exchange ->
                if (exchange.requestURI.path == "/bgm/rv/541285") exchange.recordTo(recorded)
                exchange.respond(200, payload)
            }
            route("/bgm/rv/541285", "{\"data\":[],\"total\":0}")
            route("/bgm/rd/377551", "{\"id\":377551,\"content\":\"正文\"}")
            route("/bgm/rdc/377551", "[]")
            val api = BangumiCommentApi(gateway = BangumiGatewayEndpoint(baseUrl = "$baseUrl/bgm", apiKey = "k"))
            api.getSubjectReviews(541285, 20, 40)
            val blog = api.getReviewDetail(377551)
            val comments = api.getReviewComments(377551)

            assertEquals("limit=20&offset=40", recorded.query, "列表分页进 query")
            assertEquals(377551, blog.id)
            assertEquals("正文", blog.content)
            assertEquals(0, comments.size)
        }
    }

    @Test
    fun `网关JSON请求按275毫秒节流门串行放行`() = runBlocking {
        BangumiGatewayRequestGate.resetForTest()
        try {
            withServer { baseUrl, server ->
                val hits = mutableListOf<Long>()
                server.createContext("/bgm/s/1") { exchange ->
                    hits += platformTimeMillis()
                    exchange.respond(200, "{\"id\":1}")
                }
                val endpoint = BangumiGatewayEndpoint(baseUrl = "$baseUrl/bgm", apiKey = "k")
                val startedAt = platformTimeMillis()
                endpoint.subject(1)
                endpoint.subject(1)
                endpoint.subject(1)
                val elapsed = platformTimeMillis() - startedAt
                assertEquals(3, hits.size)
                // 首次连接建立会让服务端首个 hit 晚于客户端取得 slot，不能用相邻 hit 差值断言。
                // 三次顺序调用至少跨越两个 275ms slot；留 100ms 调度/计时容差。
                assertTrue(elapsed >= 450L, "三次网关请求应跨越两个节流窗口, 实际 ${elapsed}ms")
            }
        } finally {
            BangumiGatewayRequestGate.resetForTest()
        }
    }

    @Test
    fun `网关429读取RetryAfter并推迟所有后续JSON请求`() = runBlocking {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        BangumiGatewayRequestGate.resetForTest(
            newClock = { now },
            newSleeper = { waitMillis ->
                waits += waitMillis
                now += waitMillis
            },
        )
        try {
            withServer { baseUrl, server ->
                server.createContext("/bgm/s/1") { exchange ->
                    exchange.responseHeaders.add("Retry-After", "2")
                    exchange.respond(429)
                }
                assertFailsWith<BangumiGatewayHttpException> {
                    BangumiGatewayEndpoint(baseUrl = "$baseUrl/bgm", apiKey = "k").subject(1)
                }

                BangumiGatewayRequestGate.awaitRequestSlot()
                assertEquals(listOf(2_000L), waits, "429 deadline 应覆盖原275ms窗口")
            }
        } finally {
            BangumiGatewayRequestGate.resetForTest()
        }
    }

    @Test
    fun `排队等待期间429可立即延长共享deadline`() = runBlocking {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val firstSleepStarted = CompletableDeferred<Unit>()
        val releaseFirstSleep = CompletableDeferred<Unit>()
        BangumiGatewayRequestGate.resetForTest(
            newClock = { now },
            newSleeper = { waitMillis ->
                waits += waitMillis
                if (waits.size == 1) {
                    firstSleepStarted.complete(Unit)
                    releaseFirstSleep.await()
                }
                now += waitMillis
            },
        )
        try {
            BangumiGatewayRequestGate.awaitRequestSlot()
            val queued = launch { BangumiGatewayRequestGate.awaitRequestSlot() }
            firstSleepStarted.await()

            // queued 仍在等待首个 275ms 窗口；recordRateLimit 不得被它持锁阻塞。
            withTimeout(1_000L) {
                BangumiGatewayRequestGate.recordRateLimit(2L)
            }
            releaseFirstSleep.complete(Unit)
            queued.join()

            assertEquals(listOf(275L, 1_725L), waits)
            assertEquals(3_000L, now)
        } finally {
            releaseFirstSleep.complete(Unit)
            BangumiGatewayRequestGate.resetForTest()
        }
    }

    @Test
    fun `节流门在注入时钟回拨时不会提前放行`() = runBlocking {
        var now = 10_000L
        val waits = mutableListOf<Long>()
        BangumiGatewayRequestGate.resetForTest(
            newClock = { now },
            newSleeper = { waitMillis ->
                waits += waitMillis
                now += waitMillis
            },
        )
        try {
            BangumiGatewayRequestGate.awaitRequestSlot()
            now -= 500L
            BangumiGatewayRequestGate.awaitRequestSlot()

            assertEquals(listOf(775L), waits)
        } finally {
            BangumiGatewayRequestGate.resetForTest()
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
        responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        if (bytes.isEmpty()) sendResponseHeaders(status, -1)
        else {
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        close()
    }
}
