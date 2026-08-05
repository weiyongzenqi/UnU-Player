package io.github.weiyongzenqi.unuplayer.bangumi.comment

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class BangumiCommentApiTest {
    @Test
    fun `季评论按Next API分页对象解析`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/p1/subjects/623854/comments") { exchange ->
                assertContains(exchange.requestURI.rawQuery.orEmpty(), "limit=2")
                assertContains(exchange.requestURI.rawQuery.orEmpty(), "offset=0")
                exchange.respond(
                    200,
                    """{"data":[{"id":52284889,"user":{"id":987453,"username":"user","nickname":"夜雪","sign":"签名"},"type":2,"rate":6,"comment":"补标","updatedAt":1785580616}],"total":1540}""",
                )
            }

            val response = BangumiCommentApi(
                officialBaseUrl = baseUrl,
                nextBaseUrl = "$baseUrl/p1",
            ).getSeasonComments(623854, 2, 0)

            assertEquals(1540, response.total)
            assertEquals("夜雪", response.data.single().user?.nickname)
            assertEquals(6, response.data.single().rate)
        }
    }

    @Test
    fun `剧集索引和单集评论数组按真实结构解析`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/v0/episodes") { exchange ->
                assertContains(exchange.requestURI.rawQuery.orEmpty(), "subject_id=623854")
                exchange.respond(
                    200,
                    """{"data":[{"id":1670640,"subject_id":623854,"type":0,"ep":1,"sort":1,"name":"Episode 1","name_cn":"第一集","comment":126}],"total":12,"limit":5,"offset":0}""",
                )
            }
            server.createContext("/p1/episodes/1670640/comments") { exchange ->
                exchange.respond(
                    200,
                    """[{"id":2094585,"mainID":1670640,"creatorID":1129853,"createdAt":1775585589,"content":"正文[img]https://example.com/a.png[/img]","user":{"id":1129853,"username":"a","nickname":"作者"},"replies":[{"id":2094600,"mainID":1670640,"creatorID":1129853,"relatedID":2094585,"createdAt":1775587330,"content":"回复","user":{"id":1129853,"username":"a","nickname":"作者"}}],"reactions":[{"value":140,"users":[{"id":400268,"username":"b","nickname":"读者"}]}]}]""",
                )
            }
            val api = BangumiCommentApi(
                officialBaseUrl = baseUrl,
                nextBaseUrl = "$baseUrl/p1",
            )

            val episodes = api.getEpisodes(623854, 5, 0)
            val comments = api.getEpisodeComments(1670640)

            assertEquals(12, episodes.total)
            assertEquals(1.0, episodes.data.single().sort)
            assertEquals("回复", comments.single().replies.single().content)
            assertEquals(1, comments.single().reactions.single().users.size)
        }
    }

    @Test
    fun `404 429和500均成为可重试错误`() = runBlocking {
        withServer { baseUrl, server ->
            listOf(404, 429, 500).forEach { status ->
                server.createContext("/p1/subjects/$status/comments") { it.respond(status) }
            }
            val api = BangumiCommentApi(officialBaseUrl = baseUrl, nextBaseUrl = "$baseUrl/p1")
            listOf(404, 429, 500).forEach { status ->
                val error = assertFailsWith<BangumiCommentApiException> {
                    api.getSeasonComments(status.toLong(), 20, 0)
                }
                assertEquals(status, error.statusCode)
            }
        }
    }

    @Test
    fun `季评论与单集评论分别执行响应体上限`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/p1/subjects/1/comments") { it.respond(200, "{\"data\":[],\"total\":0,\"padding\":\"1234567890\"}") }
            server.createContext("/p1/episodes/1/comments") { it.respond(200, "[{\"id\":1,\"content\":\"1234567890\"}]") }
            val api = BangumiCommentApi(
                officialBaseUrl = baseUrl,
                nextBaseUrl = "$baseUrl/p1",
                limits = BangumiCommentResponseLimits(16, 16, 16),
            )

            assertFails { api.getSeasonComments(1, 20, 0) }
            assertFails { api.getEpisodeComments(1) }
        }
    }

    @Test
    fun `缺失必需容器字段或评论正文时拒绝响应`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/p1/subjects/1/comments") {
                it.respond(200, """{"data":[]}""")
            }
            server.createContext("/p1/episodes/1/comments") {
                it.respond(200, """[{"id":1,"mainID":1}]""")
            }
            val api = BangumiCommentApi(officialBaseUrl = baseUrl, nextBaseUrl = "$baseUrl/p1")

            assertFails { api.getSeasonComments(1, 20, 0) }
            assertFails { api.getEpisodeComments(1) }
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
