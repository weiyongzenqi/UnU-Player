package io.github.weiyongzenqi.unuplayer.bangumi

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BangumiCatalogApiTest {
    @Test
    fun `标题搜索按官方v0契约请求并过滤非动画条目`() = runBlocking {
        var searchMethod: String? = null
        var searchQuery: String? = null
        var searchBody: String? = null
        withServer { serverUrl, server ->
            server.createContext("/v0/search/subjects") { exchange ->
                searchMethod = exchange.requestMethod
                searchQuery = exchange.requestURI.rawQuery
                searchBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                exchange.respond(
                    200,
                    """{"data":[
                        {"id":400602,"type":2,"name":"Sousou no Frieren","name_cn":"葬送的芙莉莲","date":"2023-09-29","total_episodes":28},
                        {"id":999,"type":1,"name":"Book","name_cn":"书籍"}
                    ]}""".trimIndent(),
                )
            }

            val results = BangumiCatalogApi(baseUrl = serverUrl).search("葬送的芙莉莲")

            assertEquals("POST", searchMethod)
            assertContains(searchQuery.orEmpty(), "limit=20")
            assertContains(searchQuery.orEmpty(), "offset=0")
            assertContains(searchBody.orEmpty(), "\"keyword\":\"葬送的芙莉莲\"")
            assertContains(searchBody.orEmpty(), "\"sort\":\"match\"")
            assertEquals(listOf(400602L), results.map { it.subjectId })
            assertEquals(28, results.single().episodeCount)
        }
    }

    @Test
    fun `ID查询接受动画并把404转为空结果`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/v0/subjects/42") { exchange ->
                exchange.respond(
                    200,
                    """{"id":42,"type":2,"name":"Test","name_cn":"测试","date":"2024-01-01","eps":12}""",
                )
            }
            server.createContext("/v0/subjects/404") { exchange -> exchange.respond(404) }

            val catalog = BangumiCatalogApi(baseUrl = serverUrl)
            assertEquals("测试", catalog.getSubject(42)?.title)
            assertNull(catalog.getSubject(404))
        }
    }

    @Test
    fun `JSON读取超过上限立即失败`() {
        runBlocking {
            assertFailsWith<BangumiApiException> {
                readLimitedJson(ByteReadChannel("12345".encodeToByteArray()), limit = 4)
            }
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
