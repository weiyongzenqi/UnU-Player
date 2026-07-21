package io.github.weiyongzenqi.unuplayer.danmaku.source

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import io.github.weiyongzenqi.unuplayer.webdav.WebDavClient
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import io.github.weiyongzenqi.unuplayer.webdav.closeSharedHttpClient

class DanmakuNetworkLifecycleTest {

    @Test
    fun `远程哈希和弹弹 API 请求后可显式释放共享客户端`() = runBlocking {
        val videoBytes = "UnU Player 中文 Range".encodeToByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/video") { exchange ->
                exchange.responseHeaders.add(
                    "Content-Range",
                    "bytes 0-${videoBytes.lastIndex}/${videoBytes.size}",
                )
                exchange.sendResponseHeaders(206, videoBytes.size.toLong())
                exchange.responseBody.use { it.write(videoBytes) }
            }
            createContext("/api/v2/comment/1") { exchange ->
                val body = """{"count":0,"comments":[]}""".encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val hash = remoteHashForUrl("$baseUrl/video", "")
            assertEquals(videoBytes.size.toLong(), hash?.first)
            assertEquals(md5(videoBytes), hash?.second)

            val api = DandanplayApi(appId = "test", appSecret = "secret", baseUrl = baseUrl)
            assertEquals(0, api.comment(1).comments.size)
        } finally {
            server.stop(0)
            closeSharedHttpClient()
        }
    }

    @Test
    fun `远程哈希在 Range 被忽略时仅读取前 16MiB 并释放响应体`() = runBlocking {
        val receivedRange = AtomicReference<String?>()
        withStreamingServer({ exchange ->
            receivedRange.set(exchange.requestHeaders.getFirst("Range"))
            exchange.respondUntilCancelled(HttpStatusCode.OK.value, DECLARED_FILE_SIZE)
        }) { baseUrl, httpClient ->
            val hash = withTimeout(STREAM_OPERATION_TIMEOUT_MS) {
                remoteHashForUrl("$baseUrl/stream", "", httpClient)
            }

            assertEquals(DECLARED_FILE_SIZE, hash?.first)
            assertEquals(md5Repeated(STREAM_BYTE, HASH_LIMIT), hash?.second)
            assertEquals("bytes=0-${HASH_LIMIT - 1}", receivedRange.get())
            assertHealthAvailable(baseUrl, httpClient)
        }
    }

    @Test
    fun `WebDAV 哈希在 Range 被忽略时释放剩余响应体`() = runBlocking {
        withStreamingServer({ exchange ->
            exchange.respondUntilCancelled(HttpStatusCode.OK.value, DECLARED_FILE_SIZE)
        }) { baseUrl, httpClient ->
            val hash = withTimeout(STREAM_OPERATION_TIMEOUT_MS) {
                webDavClient(baseUrl, httpClient).fetchRangeForHash("/stream")
            }

            assertEquals(DECLARED_FILE_SIZE, hash?.first)
            assertEquals(md5Repeated(STREAM_BYTE, HASH_LIMIT), hash?.second)
            assertHealthAvailable(baseUrl, httpClient)
        }
    }

    @Test
    fun `远程哈希缺少长度头时返回 null 并释放响应体`() = runBlocking {
        withStreamingServer({ exchange ->
            exchange.respondUntilCancelled(HttpStatusCode.OK.value, declaredLength = null)
        }) { baseUrl, httpClient ->
            assertNull(withTimeout(STREAM_OPERATION_TIMEOUT_MS) {
                remoteHashForUrl("$baseUrl/stream", "", httpClient)
            })
            assertHealthAvailable(baseUrl, httpClient)
        }
    }

    @Test
    fun `WebDAV 超限文本返回 null 并释放响应体`() = runBlocking {
        withStreamingServer({ exchange ->
            exchange.respondUntilCancelled(HttpStatusCode.OK.value, declaredLength = null)
        }) { baseUrl, httpClient ->
            assertNull(withTimeout(STREAM_OPERATION_TIMEOUT_MS) {
                webDavClient(baseUrl, httpClient).fetchText("/stream")
            })
            assertHealthAvailable(baseUrl, httpClient)
        }
    }

    @Test
    fun `WebDAV DELETE 非 2xx 大响应返回 false 并释放响应体`() = runBlocking {
        withStreamingServer({ exchange ->
            exchange.respondUntilCancelled(HttpStatusCode.InternalServerError.value, declaredLength = null)
        }) { baseUrl, httpClient ->
            assertFalse(withTimeout(STREAM_OPERATION_TIMEOUT_MS) {
                webDavClient(baseUrl, httpClient).delete("/stream")
            })
            assertHealthAvailable(baseUrl, httpClient)
        }
    }

    private fun webDavClient(baseUrl: String, httpClient: HttpClient): WebDavClient = WebDavClient(
        httpClient = httpClient,
        baseUrl = baseUrl,
        username = "",
        password = "",
        fallbackRequestIntervalMs = 0L,
    )

    private suspend fun assertHealthAvailable(baseUrl: String, httpClient: HttpClient) {
        val status = withTimeout(1_500) {
            httpClient.get("$baseUrl/health").status
        }
        assertEquals(HttpStatusCode.OK, status)
    }

    private suspend fun withStreamingServer(
        streamHandler: (HttpExchange) -> Unit,
        block: suspend (baseUrl: String, httpClient: HttpClient) -> Unit,
    ) {
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/stream", streamHandler)
            createContext("/health") { exchange ->
                exchange.sendResponseHeaders(HttpStatusCode.OK.value, -1)
                exchange.close()
            }
            start()
        }
        val httpClient = HttpClient(OkHttp)
        try {
            block("http://127.0.0.1:${server.address.port}", httpClient)
        } finally {
            httpClient.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun HttpExchange.respondUntilCancelled(status: Int, declaredLength: Long?) {
        if (declaredLength == null) {
            sendResponseHeaders(status, 0)
        } else {
            sendResponseHeaders(status, declaredLength)
        }
        val chunk = ByteArray(STREAM_CHUNK_SIZE) { STREAM_BYTE }
        var remaining = STREAM_SIZE
        try {
            while (remaining > 0) {
                val count = minOf(remaining, chunk.size)
                responseBody.write(chunk, 0, count)
                responseBody.flush()
                remaining -= count
                Thread.sleep(STREAM_CHUNK_DELAY_MS)
            }
        } catch (_: IOException) {
            // 客户端取消响应体后，服务端写入应尽快在这里结束。
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { responseBody.close() }
            close()
        }
    }

    private fun md5Repeated(byte: Byte, size: Int): String {
        val digest = MessageDigest.getInstance("MD5")
        val chunk = ByteArray(STREAM_CHUNK_SIZE) { byte }
        var remaining = size
        while (remaining > 0) {
            val count = minOf(remaining, chunk.size)
            digest.update(chunk, 0, count)
            remaining -= count
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private companion object {
        const val HASH_LIMIT = 16 * 1024 * 1024
        const val STREAM_SIZE = 48 * 1024 * 1024
        const val STREAM_CHUNK_SIZE = 256 * 1024
        const val STREAM_CHUNK_DELAY_MS = 20L
        const val STREAM_OPERATION_TIMEOUT_MS = 2_500L
        const val DECLARED_FILE_SIZE = 48L * 1024 * 1024
        const val STREAM_BYTE: Byte = 0x5A
    }
}
