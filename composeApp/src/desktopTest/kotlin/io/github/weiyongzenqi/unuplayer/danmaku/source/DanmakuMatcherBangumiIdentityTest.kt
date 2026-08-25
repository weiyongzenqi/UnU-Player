package io.github.weiyongzenqi.unuplayer.danmaku.source

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DanmakuMatcherBangumiIdentityTest {

    @Test
    fun `Bangumi 第三季身份只请求第三季第七集弹幕`() = runBlocking {
        val requestedPaths = Collections.synchronizedList(mutableListOf<String>())
        val server = startServer(requestedPaths, ambiguous = false)
        try {
            val matcher = DanmakuMatcher(
                DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base),
            )

            val animeId = matcher.resolveAnimeIdByBangumiSubject(
                subjectId = 569116,
                keywords = listOf("碧蓝之海 第三季"),
                seasonHint = 3,
            )
            val result = animeId?.let {
                matcher.matchByAnimeId(
                    animeId = it,
                    fileName = "碧蓝之海 S03E07.mkv",
                    episodeHint = 7,
                    matchMethod = DanmakuMatchMethod.BANGUMI_DATABASE,
                )
            }

            assertEquals(300L, animeId)
            assertEquals(3007L, result?.episodeId)
            assertEquals(300L, result?.animeId)
            assertEquals(DanmakuMatchMethod.BANGUMI_DATABASE, result?.matchMethod)
            assertFalse(requestedPaths.any { it == "/api/v2/bangumi/100" })
            assertEquals(1, requestedPaths.count { it == "/api/v2/bangumi/300" })
        } finally {
            server.stop()
        }
    }

    @Test
    fun `同一 Bangumi 条目对应多个弹弹候选时拒绝猜测`() = runBlocking {
        val server = startServer(Collections.synchronizedList(mutableListOf()), ambiguous = true)
        try {
            val matcher = DanmakuMatcher(
                DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base),
            )

            assertNull(
                matcher.resolveAnimeIdByBangumiSubject(
                    subjectId = 569116,
                    keywords = listOf("碧蓝之海 第三季"),
                    seasonHint = 3,
                ),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `弹弹连续集号按已确认季度内顺序定位`() = runBlocking {
        val requestedPaths = Collections.synchronizedList(mutableListOf<String>())
        val server = startServer(requestedPaths, ambiguous = false, continuousEpisodes = true)
        try {
            val matcher = DanmakuMatcher(
                DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base),
            )

            val result = matcher.matchByAnimeId(
                animeId = 300,
                fileName = "我推的孩子 S02E12.mkv",
                episodeOrdinalHint = 12,
            )

            assertEquals(3023L, result?.episodeId)
            assertEquals("第23话", result?.episodeTitle)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `分段番剧本地集号经 offset 换算匹配连续编号条目`() = runBlocking {
        // 我推的孩子第二季形态: 弹弹条目 episodeNumber 为全系列连续 12..24,
        // 本地分段集号 E4 无法按值命中, offset=-11 换算出全系列 15 后应命中第 15 话。
        val server = startServer(Collections.synchronizedList(mutableListOf()), ambiguous = false, continuousEpisodes = true)
        try {
            val matcher = DanmakuMatcher(
                DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base),
            )

            val result = matcher.matchByAnimeId(
                animeId = 300,
                fileName = "【我推的孩子】 第二季 S02E04 情感演技.mkv",
                episodeHint = 4,
                bangumiEpisodeOffset = -11L,
            )

            assertEquals(3015L, result?.episodeId)
            assertEquals("第15话", result?.episodeTitle)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `tmdb 匹配按季标选条目并按顺序号定位连续编号`() = runBlocking {
        // tmdbId 反查返回全部季度条目时, 季号唯一仲裁选中"第二季"条目(12..24 连续编号),
        // 本地 E4 的顺序号与 offset 换算都应落到第 15 话, 而不是第一季的第 4 话。
        val server = startServer(
            Collections.synchronizedList(mutableListOf()),
            ambiguous = false,
            continuousEpisodes = true,
            tmdbSearchEpisodes = true,
        )
        try {
            val matcher = DanmakuMatcher(
                DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base),
            )

            val result = matcher.matchByTmdb(
                tmdbId = 203737L,
                fileName = "【我推的孩子】 第二季 S02E04 情感演技.mkv",
                season = 2,
                episodeHint = 4,
                episodeOrdinalHint = 4,
                bangumiEpisodeOffset = -11L,
                matchMethod = DanmakuMatchMethod.TMDB_DATABASE,
            )

            assertEquals(18086L, result?.animeId)
            assertEquals("第15话", result?.episodeTitle)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `库集号为全系列编号且形态歧义时安全失败交给哈希兜底`() = runBlocking {
        // 库按 TMDB 合并季组织(season=1, E15)时, 季标仲裁会错选无标的第一季条目且集内定位失败;
        // 覆盖兜底里原值(15 -> 第二季条目)与换算(26 -> 第三季条目)都能唯一命中不同条目,
        // 属形态歧义: 必须返回 null 走哈希兜底, 而不是猜测任一形态错配相邻分段。
        val server = startServer(
            Collections.synchronizedList(mutableListOf()),
            ambiguous = false,
            tmdbSearchEpisodes = true,
        )
        try {
            val matcher = DanmakuMatcher(
                DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base),
            )

            val result = matcher.matchByTmdb(
                tmdbId = 203737L,
                fileName = "【我推的孩子】 S01E15 情感演技.mkv",
                season = 1,
                episodeHint = 15,
                episodeOrdinalHint = 15,
                bangumiEpisodeOffset = -11L,
                matchMethod = DanmakuMatchMethod.TMDB_DATABASE,
            )

            assertNull(result)
        } finally {
            server.stop()
        }
    }

    private fun startServer(
        requestedPaths: MutableList<String>,
        ambiguous: Boolean,
        continuousEpisodes: Boolean = false,
        tmdbSearchEpisodes: Boolean = false,
    ): TestServer {
        val executor = Executors.newSingleThreadExecutor()
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/api/v2/search/anime") { exchange ->
                requestedPaths += exchange.requestURI.path
                val extra = if (ambiguous) {
                    ",{\"animeId\":301,\"animeTitle\":\"碧蓝之海 第三季 别名\",\"bangumiId\":\"569116\"}"
                } else {
                    ""
                }
                exchange.respond(
                    200,
                    """{"success":true,"animes":[
                        {"animeId":100,"animeTitle":"碧蓝之海","bangumiId":"235130"},
                        {"animeId":300,"animeTitle":"碧蓝之海 Season 3","bangumiId":"569116"}$extra
                    ]}""",
                )
            }
            createContext("/api/v2/search/episodes") { exchange ->
                requestedPaths += exchange.requestURI.path
                val body = if (tmdbSearchEpisodes) {
                    // 还原 tmdbId=203737(我推的孩子)的真实形态: 4 个季度条目, 第二季条目 12..24 连续编号。
                    """{"success":true,"animes":[
                        {"animeId":17449,"animeTitle":"我推的孩子","type":"tvseries"},
                        {"animeId":18086,"animeTitle":"我推的孩子 第二季","type":"tvseries"},
                        {"animeId":18901,"animeTitle":"【我推的孩子】 第三季","type":"tvseries"},
                        {"animeId":19969,"animeTitle":"【我推的孩子】 第四季 最终季","type":"tvseries"}
                    ]}"""
                } else {
                    """{"success":true,"animes":[]}"""
                }
                exchange.respond(200, body)
            }
            createContext("/api/v2/bangumi/") { exchange ->
                requestedPaths += exchange.requestURI.path
                val animeId = exchange.requestURI.path.substringAfterLast('/').toLongOrNull()
                val body = if (tmdbSearchEpisodes && animeId == 17449L) {
                    """{"success":true,"bangumi":{"animeId":17449,"animeTitle":"我推的孩子","episodes":[${
                        (1..11).joinToString(",") { number ->
                            """{"episodeId":${174490000 + number},"episodeTitle":"第${number}话","episodeNumber":"$number"}"""
                        }
                    }]}}"""
                } else if (tmdbSearchEpisodes && animeId == 18086L) {
                    """{"success":true,"bangumi":{"animeId":18086,"animeTitle":"我推的孩子 第二季","episodes":[${
                        (12..24).joinToString(",") { number ->
                            """{"episodeId":${180860000 + number},"episodeTitle":"第${number}话","episodeNumber":"$number"}"""
                        }
                    }]}}"""
                } else if (tmdbSearchEpisodes && animeId == 18901L) {
                    """{"success":true,"bangumi":{"animeId":18901,"animeTitle":"【我推的孩子】 第三季","episodes":[${
                        (25..35).joinToString(",") { number ->
                            """{"episodeId":${189010000 + number},"episodeTitle":"第${number}话","episodeNumber":"$number"}"""
                        }
                    }]}}"""
                } else if (tmdbSearchEpisodes && animeId == 19969L) {
                    """{"success":true,"bangumi":{"animeId":19969,"animeTitle":"【我推的孩子】 第四季 最终季","episodes":[${
                        (1..12).joinToString(",") { number ->
                            """{"episodeId":${199690000 + number},"episodeTitle":"第${number}话","episodeNumber":"$number"}"""
                        }
                    }]}}"""
                } else if (animeId == 300L) {
                    val episodes = if (continuousEpisodes) {
                        (12..24).joinToString(",") { number ->
                            """{"episodeId":${3000 + number},"episodeTitle":"第${number}话","episodeNumber":"$number"}"""
                        }
                    } else {
                        (1..7).joinToString(",") { number ->
                            """{"episodeId":${3000 + number},"episodeTitle":"第${number}话","episodeNumber":"$number"}"""
                        }
                    }
                    """{"success":true,"bangumi":{"animeId":300,"animeTitle":"碧蓝之海 Season 3","episodes":[$episodes]}}"""
                } else {
                    """{"success":true,"bangumi":{"animeId":100,"animeTitle":"碧蓝之海","episodes":[
                        {"episodeId":1007,"episodeTitle":"第7话","episodeNumber":"7"}
                    ]}}"""
                }
                exchange.respond(200, body)
            }
            start()
        }
        return TestServer("http://127.0.0.1:${httpServer.address.port}", httpServer, executor)
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.encodeToByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
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
