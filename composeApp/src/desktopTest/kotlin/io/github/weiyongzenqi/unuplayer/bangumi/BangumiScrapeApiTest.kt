package io.github.weiyongzenqi.unuplayer.bangumi

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BangumiScrapeApiTest {

    // 专用客户端(不走进程级共享单例): 套件里 DanmakuNetworkLifecycleTest 会 closeSharedHttpClient,
    // 共享单例被关后后续请求全崩; 每测试新建私有 OkHttp 客户端, 短命 JVM 下可接受。
    private fun api(serverUrl: String) = BangumiScrapeApi(baseUrl = serverUrl, httpClient = HttpClient(OkHttp) { followRedirects = false })

    @Test
    fun `搜索按type过滤并解析季照评分标签`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                exchange.respond(
                    200,
                    """{"data":[
                        {
                          "id":400602,"type":2,"name":"Sousou no Frieren","name_cn":"葬送的芙莉莲",
                          "date":"2023-09-29","summary":"勇者一行打败魔王后…",
                          "rating":{"score":8.7,"total":1234},
                          "images":{"large":"https://lain.bgm.tv/pic/cover/l/a.jpg","common":"https://lain.bgm.tv/pic/cover/c/a.jpg"},
                          "tags":[{"name":"奇幻"},{"name":"治愈"}],
                          "total_episodes":28
                        },
                        {"id":999,"type":1,"name":"Book","name_cn":"书籍"}
                    ]}""".trimIndent(),
                )
            }

            val results = api(serverUrl).search("葬送的芙莉莲")

            assertEquals(1, results.size)
            val subject = results.single()
            assertEquals(400602L, subject.subjectId)
            assertEquals("葬送的芙莉莲", subject.title)
            assertEquals("Sousou no Frieren", subject.originalTitle)
            assertEquals("2023-09-29", subject.date)
            assertEquals(8.7, subject.rating)
            assertEquals("https://lain.bgm.tv/pic/cover/l/a.jpg", subject.posterUrl)
            assertEquals(listOf("奇幻", "治愈"), subject.tags)
            assertEquals(28, subject.episodeCount)
        }
    }

    @Test
    fun `条目详情解析infobox制作方且404返回空`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/subjects/42") { exchange ->
                exchange.respond(
                    200,
                    """{"id":42,"type":2,"name":"Test","name_cn":"测试","date":"2024-01-01","eps":12,
                        "infobox":[
                          {"key":"别名","value":"别名1"},
                          {"key":"动画制作","value":[{"v":"MADHOUSE","k":"动画制作"}]},
                          {"key":"导演","value":"某人"}
                        ]}""".trimIndent(),
                )
            }
            server.createContext("/v0/subjects/404") { exchange -> exchange.respond(404) }

            val api = api(serverUrl)
            assertEquals(listOf("MADHOUSE"), api.getSubject(42)?.studios)
            assertNull(api.getSubject(404))
        }
    }

    @Test
    fun `剧集列表过滤SP番外并分页且带简介`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/episodes") { exchange ->
                val offset = exchange.requestURI.rawQuery.substringAfter("offset=").substringBefore("&")
                val page = when (offset) {
                    "0" -> """[{"type":0,"sort":1,"name_cn":"第一集","airdate":"2024-01-01","desc":"勇者出发"},
                               {"type":0,"sort":0.5,"name_cn":"SP","airdate":"2024-01-02"},
                               {"type":0,"sort":2,"name_cn":"第二集","airdate":"2024-01-08","desc":"击败魔王"}]"""
                    else -> "[]"
                }
                exchange.respond(200, """{"data":$page,"total":3}""")
            }

            val eps = api(serverUrl).getEpisodes(42)

            // 只保留正整数主集; 0.5 番外被过滤
            assertEquals(listOf(1L, 2L), eps.map { it.sort.toLong() })
            assertEquals("第一集", eps[0].title)
            assertEquals("2024-01-08", eps[1].aired)
            // 剧集简介(Bangumi episodes desc)应被解析
            assertEquals("勇者出发", eps[0].plot)
            assertEquals("击败魔王", eps[1].plot)
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
