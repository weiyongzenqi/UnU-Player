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
            assertEquals(2, response.data.single().type)
        }
    }

    @Test
    fun `主题列表按Next API分页对象解析`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/p1/subjects/623854/topics") { exchange ->
                assertContains(exchange.requestURI.rawQuery.orEmpty(), "limit=2")
                assertContains(exchange.requestURI.rawQuery.orEmpty(), "offset=3")
                exchange.respond(
                    200,
                    """{"data":[{"id":182024,"title":"动画讨论","creatorID":987453,"parentID":0,"replyCount":12,"createdAt":1775585589,"updatedAt":1785580616,"state":0,"display":1,"creator":{"id":987453,"username":"user","nickname":"夜雪","sign":"签名","avatar":{"small":"https://lain.bgm.tv/pic/user/s/1.jpg"}}}],"total":77}""",
                )
            }

            val response = BangumiCommentApi(
                officialBaseUrl = baseUrl,
                nextBaseUrl = "$baseUrl/p1",
            ).getSubjectTopics(623854, 2, 3)

            assertEquals(77, response.total)
            val topic = response.data.single()
            assertEquals(182024, topic.id)
            assertEquals("动画讨论", topic.title)
            assertEquals(12, topic.replyCount)
            assertEquals("夜雪", topic.creator?.nickname)
            assertEquals("签名", topic.creator?.sign)
            assertEquals("https://lain.bgm.tv/pic/user/s/1.jpg", topic.creator?.avatar?.small)
        }
    }

    @Test
    fun `主题详情按Next API解析嵌套回帖树`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/p1/subjects/-/topics/182024") { exchange ->
                exchange.respond(
                    200,
                    """{"id":182024,"creator":{"id":1,"username":"a","nickname":"楼主"},"replies":[{"id":9001,"content":"主楼","creatorID":1,"createdAt":1775585589,"creator":{"id":1,"username":"a","nickname":"楼主","avatar":{"small":"https://lain.bgm.tv/pic/user/s/1.jpg"}},"replies":[{"id":9002,"content":"二楼","creatorID":2,"createdAt":1775585590,"creator":{"id":2,"username":"b","nickname":"回帖者","avatar":{"medium":"https://lain.bgm.tv/pic/user/m/2.jpg"}},"replies":[{"id":9003,"content":"三楼","creatorID":3,"createdAt":1775585591}]}]}]}""",
                )
            }

            val detail = BangumiCommentApi(
                officialBaseUrl = baseUrl,
                nextBaseUrl = "$baseUrl/p1",
            ).getTopicDetail(182024)

            assertEquals(182024, detail.id)
            assertEquals("楼主", detail.creator?.nickname)
            val main = detail.replies.single()
            assertEquals(9001, main.id)
            assertEquals("主楼", main.content)
            // 实测形态: 回帖作者字段是 creator(user 缺省 null)
            assertEquals("楼主", main.creator?.nickname)
            assertEquals("https://lain.bgm.tv/pic/user/s/1.jpg", main.creator?.avatar?.small)
            assertEquals(null, main.user)
            val second = main.replies.single()
            assertEquals("二楼", second.content)
            assertEquals("回帖者", second.creator?.nickname)
            assertEquals("https://lain.bgm.tv/pic/user/m/2.jpg", second.creator?.avatar?.medium)
            // 三层回帖没有 creator/user 字段, 只有 creatorID 回落
            val third = second.replies.single()
            assertEquals(9003, third.id)
            assertEquals(3, third.creatorID)
            assertEquals(null, third.user)
            assertEquals(null, third.creator)
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
    fun `长评列表正文与回帖树按真实结构解析`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/p1/subjects/541285/reviews") { exchange ->
                assertContains(exchange.requestURI.rawQuery.orEmpty(), "limit=2")
                assertContains(exchange.requestURI.rawQuery.orEmpty(), "offset=0")
                exchange.respond(
                    200,
                    """{"data":[{"id":331919,"user":{"id":899220,"username":"899220","nickname":"作者","avatar":{"small":"https://lain.bgm.tv/r/100/pic/user/l/000/89/92/899220.jpg","medium":"https://lain.bgm.tv/r/200/pic/user/l/000/89/92/899220.jpg","large":"https://lain.bgm.tv/pic/user/l/000/89/92/899220.jpg"},"group":10,"sign":"","joinedAt":1721980478},"entry":{"id":378392,"type":1,"uid":899220,"title":"标题","icon":"https://lain.bgm.tv/pic/photo/g/no_photo.png","summary":"摘要文本","replies":1,"public":true,"createdAt":1786464515,"updatedAt":1786465597}}],"total":5}""",
                )
            }
            server.createContext("/p1/blogs/378392") {
                it.respond(
                    200,
                    """{"id":378392,"type":1,"uid":899220,"user":{"id":899220,"username":"899220","nickname":"作者"},"title":"标题","icon":"https://lain.bgm.tv/pic/photo/g/no_photo.png","content":"正文","tags":["标签"],"views":0,"replies":1,"createdAt":1786464515,"updatedAt":1786465597,"noreply":0,"related":1,"public":true}""",
                )
            }
            server.createContext("/p1/blogs/378392/comments") {
                it.respond(
                    200,
                    """[{"id":366220,"mainID":378392,"creatorID":899220,"relatedID":0,"createdAt":1786465597,"content":"回帖正文","state":0,"replies":[],"user":{"id":899220,"username":"899220","nickname":"作者"}}]""",
                )
            }
            val api = BangumiCommentApi(officialBaseUrl = baseUrl, nextBaseUrl = "$baseUrl/p1")

            val reviews = api.getSubjectReviews(541285, 2, 0)
            assertEquals(5, reviews.total)
            val item = reviews.data.single()
            assertEquals(331919, item.id)
            assertEquals(378392, item.entry?.id, "entry.id 即 blogId")
            assertEquals("标题", item.entry?.title)
            assertEquals(1, item.entry?.replies)
            assertEquals("作者", item.user?.nickname)
            // 头像带 lain 缩放前缀形态(/r/100/pic/...)
            assertEquals("https://lain.bgm.tv/r/100/pic/user/l/000/89/92/899220.jpg", item.user?.avatar?.small)

            val blog = api.getReviewDetail(378392)
            assertEquals(378392, blog.id)
            assertEquals(899220, blog.uid)
            assertEquals("正文", blog.content)
            assertEquals("作者", blog.user?.nickname)

            val comments = api.getReviewComments(378392)
            val comment = comments.single()
            assertEquals(378392, comment.mainID)
            assertEquals(0, comment.relatedID, "顶层回帖 relatedID=0")
            assertEquals("回帖正文", comment.content)
            assertEquals("作者", comment.user?.nickname, "长评回帖作者字段是 user(与讨论帖的 creator 相反)")
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
    fun `主题列表404 429和500均成为可重试错误`() = runBlocking {
        withServer { baseUrl, server ->
            listOf(404, 429, 500).forEach { status ->
                server.createContext("/p1/subjects/$status/topics") { it.respond(status) }
            }
            val api = BangumiCommentApi(officialBaseUrl = baseUrl, nextBaseUrl = "$baseUrl/p1")
            listOf(404, 429, 500).forEach { status ->
                val error = assertFailsWith<BangumiCommentApiException> {
                    api.getSubjectTopics(status.toLong(), 20, 0)
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
    fun `主题列表与主题详情分别执行响应体上限`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/p1/subjects/1/topics") { it.respond(200, "{\"data\":[],\"total\":0,\"padding\":\"1234567890\"}") }
            server.createContext("/p1/subjects/-/topics/1") { it.respond(200, "{\"id\":1,\"replies\":[{\"id\":2,\"content\":\"1234567890\"}],\"padding\":\"1234567890\"}") }
            val api = BangumiCommentApi(
                officialBaseUrl = baseUrl,
                nextBaseUrl = "$baseUrl/p1",
                limits = BangumiCommentResponseLimits(
                    subjectTopicsBytes = 16,
                    topicDetailBytes = 16,
                ),
            )

            assertFails { api.getSubjectTopics(1, 20, 0) }
            assertFails { api.getTopicDetail(1) }
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
