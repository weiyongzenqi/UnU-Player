package io.github.weiyongzenqi.unuplayer.danmaku.source

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [DanmakuMatcher.matchByTmdb] 的 [episodeHint] 优先级与 season 选择测试。
 *
 * 用本地 HttpServer 模拟弹弹play `/api/v2/search/episodes` 与 `/api/v2/bangumi/{animeId}` 两个端点,
 * 验证: ① episodeHint 命中优先于文件名提取; ② episodeHint=null 回退文件名 extractEpisode;
 * ③ season 只按明确季度标题选择; ④ hint 未命中 bangumi episodeNumber 时回退文件名。
 */
class DanmakuMatcherTmdbHintTest {

    @Test
    fun `episodeHint 命中优先于文件名提取`() = runBlocking {
        // bangumi 剧集表: episodeNumber=3 存在; fileName 里 extractEpisode=10(故意不一致)
        val server = startServer(
            searchEpisodes = """{"success":true,"animes":[{"animeId":100,"animeTitle":"义妹生活","type":"tvseries"}]}""",
            bangumi = """{"success":true,"bangumi":{"animeId":100,"animeTitle":"义妹生活","episodes":[
                {"episodeId":1001,"episodeTitle":"第1话","episodeNumber":"1"},
                {"episodeId":1002,"episodeTitle":"第2话","episodeNumber":"2"},
                {"episodeId":1003,"episodeTitle":"第3话","episodeNumber":"3"}
            ]}}""",
        )
        try {
            val api = DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base)
            val matcher = DanmakuMatcher(api)

            // fileName 的 extractEpisode=10(无 SxxExx, 无 EPxx, 无第x话, 无 [xx], 有 "- 10" 命中尾锚 -> 10)
            // episodeHint=3 应优先命中 episodeId=1003
            val result = matcher.matchByTmdb(285574L, "某番 S01E10 - 10.mkv", season = 1, episodeHint = 3)

            assertEquals(1003L, result?.episodeId)
            assertEquals("义妹生活", result?.animeTitle)
            assertEquals("第3话", result?.episodeTitle)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `episodeHint null 回退文件名 extractEpisode`() = runBlocking {
        val server = startServer(
            searchEpisodes = """{"success":true,"animes":[{"animeId":200,"animeTitle":"某番"}]}""",
            bangumi = """{"success":true,"bangumi":{"animeId":200,"animeTitle":"某番","episodes":[
                {"episodeId":2001,"episodeTitle":"第1话","episodeNumber":"1"},
                {"episodeId":2002,"episodeTitle":"第2话","episodeNumber":"2"},
                {"episodeId":2005,"episodeTitle":"第5话","episodeNumber":"5"}
            ]}}""",
        )
        try {
            val api = DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base)
            val matcher = DanmakuMatcher(api)

            // S01E05 -> extractEpisode=5; episodeHint=null 回退文件名 -> 命中 episodeId=2005
            val result = matcher.matchByTmdb(111L, "某番 S01E05.mkv", season = 1, episodeHint = null)

            assertEquals(2005L, result?.episodeId)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `episodeHint 未命中 bangumi 回退文件名 extractEpisode`() = runBlocking {
        val server = startServer(
            searchEpisodes = """{"success":true,"animes":[{"animeId":300,"animeTitle":"某番"}]}""",
            bangumi = """{"success":true,"bangumi":{"animeId":300,"animeTitle":"某番","episodes":[
                {"episodeId":3001,"episodeTitle":"第1话","episodeNumber":"1"},
                {"episodeId":3002,"episodeTitle":"第2话","episodeNumber":"2"}
            ]}}""",
        )
        try {
            val api = DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base)
            val matcher = DanmakuMatcher(api)

            // episodeHint=99 bangumi 无此集 -> 回退文件名 S01E02 -> extractEpisode=2 -> episodeId=3002
            val result = matcher.matchByTmdb(222L, "某番 S01E02.mkv", season = 1, episodeHint = 99)

            assertEquals(3002L, result?.episodeId)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `season 按明确季度标题选择而不依赖 animeId 顺序`() = runBlocking {
        val server = startServer(
            searchEpisodes = """{"success":true,"animes":[
                {"animeId":100,"animeTitle":"某番 Season 2"},
                {"animeId":900,"animeTitle":"某番"}
            ]}""",
            bangumi = """{"success":true,"bangumi":{"animeId":100,"animeTitle":"某番 Season 2","episodes":[
                {"episodeId":5001,"episodeTitle":"第1话","episodeNumber":"1"}
            ]}}""",
        )
        try {
            val api = DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base)
            val matcher = DanmakuMatcher(api)

            val result = matcher.matchByTmdb(333L, "某番 S02E01.mkv", season = 2, episodeHint = 1)

            assertEquals(5001L, result?.episodeId)
            assertEquals("某番 Season 2", result?.animeTitle)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `第三季候选缺失时不回退第一季`() {
        val candidates = listOf(
            DandanplayAnime(animeId = 100, animeTitle = "某番"),
            DandanplayAnime(animeId = 200, animeTitle = "某番 Season 2"),
        )

        assertNull(DanmakuMatcher.selectAnimeForSeason(candidates, season = 3))
        assertNull(
            DanmakuMatcher.selectAnimeForSeason(
                listOf(DandanplayAnime(animeId = 100, animeTitle = "某番")),
                season = 3,
            ),
        )
    }

    @Test
    fun `中文季度标题可精确选择第三季`() {
        val candidates = listOf(
            DandanplayAnime(animeId = 900, animeTitle = "某番"),
            DandanplayAnime(animeId = 300, animeTitle = "某番 第三季"),
        )

        assertEquals(300L, DanmakuMatcher.selectAnimeForSeason(candidates, season = 3)?.animeId)
    }

    @Test
    fun `tmdbId 无搜索结果返回 null`() = runBlocking {
        val server = startServer(
            searchEpisodes = """{"success":true,"animes":[]}""",
            bangumi = """{"success":true,"bangumi":{"animeId":1,"episodes":[]}}""",
        )
        try {
            val api = DandanplayApi(appId = "test", appSecret = "secret", baseUrl = server.base)
            val matcher = DanmakuMatcher(api)

            assertNull(matcher.matchByTmdb(999L, "某番 S01E01.mkv", season = 1, episodeHint = 1))
        } finally {
            server.stop()
        }
    }

    private fun startServer(searchEpisodes: String, bangumi: String): TestServer {
        val executor = Executors.newSingleThreadExecutor()
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/api/v2/search/episodes") { exchange ->
                exchange.respond(200, searchEpisodes)
            }
            createContext("/api/v2/bangumi/") { exchange ->
                exchange.respond(200, bangumi)
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
            // 不调 closeSharedHttpClient(): 它关闭 DandanplayApi 默认 httpClient 复用的共享引擎,
            // 会取消同一 JVM 内其它正在挂起的弹弹 API 请求(竞态)。本测试不依赖共享客户端生命周期。
        }
    }
}
