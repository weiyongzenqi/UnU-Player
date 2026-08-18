package io.github.weiyongzenqi.unuplayer.bangumi.comment

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BangumiContentImageRepositoryTest {

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

    private fun imageBytes(size: Int = 64): ByteArray = ByteArray(size) { ((it % 251) + 1).toByte() }

    /**
     * [declaredLength] 非 null 时按谎报长度发头(Content-Length 预检测试),
     * 实际只写 [body](客户端可能提前断开, 写入异常吞掉)。
     */
    private fun HttpExchange.respond(
        status: Int,
        contentType: String,
        body: ByteArray = ByteArray(0),
        declaredLength: Long? = null,
    ) {
        responseHeaders.add("Content-Type", contentType)
        val length = declaredLength ?: if (body.isEmpty()) -1 else body.size.toLong()
        sendResponseHeaders(status, length)
        if (body.isNotEmpty()) {
            runCatching { responseBody.use { it.write(body) } }
        }
        close()
    }

    @Test
    fun `成功返回图片字节`() = runBlocking {
        withServer { baseUrl, server ->
            val payload = imageBytes(128)
            server.createContext("/ok.png") { it.respond(200, "image/png", payload) }
            val repo = BangumiContentImageRepository(httpClient = HttpClient(OkHttp))

            assertContentEquals(payload, repo.load("$baseUrl/ok.png"))
        }
    }

    @Test
    fun `有限重定向跟随相对Location并读取最终图片`() = runBlocking {
        withServer { baseUrl, server ->
            val payload = imageBytes(96)
            server.createContext("/start") { exchange ->
                exchange.responseHeaders.add("Location", "/nested/final.png")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            server.createContext("/nested/final.png") { it.respond(200, "image/png", payload) }
            val repo = BangumiContentImageRepository(
                httpClient = HttpClient(OkHttp) { followRedirects = false },
            )

            assertContentEquals(payload, repo.load("$baseUrl/start"))
        }
    }

    @Test
    fun `Content-Type 非图片拒绝`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/page") { it.respond(200, "text/html", "hello".encodeToByteArray()) }
            val repo = BangumiContentImageRepository(httpClient = HttpClient(OkHttp))

            val error = assertFailsWith<Exception> { repo.load("$baseUrl/page") }
            assertTrue(error.message.orEmpty().contains("不是图片"))
        }
    }

    @Test
    fun `Content-Length 预检超 2MB 拒绝`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/big") {
                it.respond(200, "image/png", imageBytes(8), declaredLength = 3L * 1024 * 1024)
            }
            val repo = BangumiContentImageRepository(httpClient = HttpClient(OkHttp))

            val error = assertFailsWith<Exception> { repo.load("$baseUrl/big") }
            assertTrue(error.message.orEmpty().contains("大小上限"))
        }
    }

    @Test
    fun `chunked 实读超 2MB 拒绝`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/chunked") { exchange ->
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, 0) // 0 = chunked, 无 Content-Length
                runCatching {
                    exchange.responseBody.use { out -> out.write(ByteArray(3 * 1024 * 1024)) }
                }
                exchange.close()
            }
            val repo = BangumiContentImageRepository(httpClient = HttpClient(OkHttp))

            val error = assertFailsWith<Exception> { repo.load("$baseUrl/chunked") }
            assertTrue(error.message.orEmpty().contains("大小上限"))
        }
    }

    @Test
    fun `同 URL 并发两次合并为一次服务端请求`() = runBlocking {
        withServer { baseUrl, server ->
            val count = AtomicInteger(0)
            server.createContext("/once.png") { exchange ->
                count.incrementAndGet()
                exchange.respond(200, "image/png", imageBytes(64))
            }
            val repo = BangumiContentImageRepository(httpClient = HttpClient(OkHttp))
            val url = "$baseUrl/once.png"

            coroutineScope {
                val first = async { repo.load(url) }
                val second = async { repo.load(url) }
                assertContentEquals(imageBytes(64), first.await())
                assertContentEquals(imageBytes(64), second.await())
            }
            assertEquals(1, count.get())
        }
    }

    @Test
    fun `TTL 过期重取且未过期命中缓存`() = runBlocking {
        withServer { baseUrl, server ->
            val count = AtomicInteger(0)
            server.createContext("/ttl.png") { exchange ->
                count.incrementAndGet()
                exchange.respond(200, "image/png", imageBytes(32))
            }
            var fakeNow = 0L
            val repo = BangumiContentImageRepository(
                httpClient = HttpClient(OkHttp),
                ttlMillis = 100,
                nowMillis = { fakeNow },
            )
            val url = "$baseUrl/ttl.png"

            repo.load(url)
            assertEquals(1, count.get())

            fakeNow += 101
            repo.load(url)
            assertEquals(2, count.get())

            repo.load(url)
            assertEquals(2, count.get())
        }
    }

    @Test
    fun `失败不缓存可重试成功`() = runBlocking {
        withServer { baseUrl, server ->
            val mode = AtomicInteger(404)
            server.createContext("/flaky.png") { exchange ->
                if (mode.get() == 404) exchange.respond(404, "text/plain")
                else exchange.respond(200, "image/png", imageBytes(48))
            }
            val repo = BangumiContentImageRepository(httpClient = HttpClient(OkHttp))
            val url = "$baseUrl/flaky.png"

            val error = assertFailsWith<Exception> { repo.load(url) }
            assertTrue(error.message.orEmpty().contains("404"))

            mode.set(200)
            assertContentEquals(imageBytes(48), repo.load(url))
        }
    }

    @Test
    fun `非 2xx 状态成为异常且消息含 HTTP 状态码`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/gone") { it.respond(410, "text/plain") }
            val repo = BangumiContentImageRepository(httpClient = HttpClient(OkHttp))

            assertFails { repo.load("$baseUrl/gone") }
            val error = assertFailsWith<Exception> { repo.load("$baseUrl/gone") }
            assertTrue(error.message.orEmpty().contains("410"))
        }
    }
}
