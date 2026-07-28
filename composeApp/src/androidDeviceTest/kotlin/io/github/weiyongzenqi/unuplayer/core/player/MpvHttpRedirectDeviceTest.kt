package io.github.weiyongzenqi.unuplayer.core.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.weiyongzenqi.unuplayer.platform.AndroidPlatformInfo
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MpvHttpRedirectDeviceTest {

    @Test
    fun followReachesCrossOriginAndForwardsCustomHeader() {
        OneShotHttpServer { TestHttpResponse.notFound() }.use { target ->
            OneShotHttpServer { TestHttpResponse.redirect(target.url("/target")) }.use { source ->
                val engine = createEngine(
                    headers = mapOf(CANARY_HEADER to FOLLOW_CANARY),
                    redirectPolicy = HttpRedirectPolicy.FOLLOW,
                )
                try {
                    engine.load(source.url("/source"))

                    assertTrue(source.awaitRequest(REQUEST_TIMEOUT_MILLIS), "首个 origin 未收到请求")
                    assertEquals(FOLLOW_CANARY, source.requireRequest().header(CANARY_HEADER))
                    assertTrue(target.awaitRequest(REQUEST_TIMEOUT_MILLIS), "FOLLOW 未访问重定向目标")
                    assertEquals(FOLLOW_CANARY, target.requireRequest().header(CANARY_HEADER))
                } finally {
                    engine.destroy()
                }
            }
        }
    }

    @Test
    fun denyBlocksMediaServerHeaderAtCrossOriginRedirect() {
        OneShotHttpServer { TestHttpResponse.notFound() }.use { target ->
            OneShotHttpServer { TestHttpResponse.redirect(target.url("/target")) }.use { source ->
                val engine = createEngine(
                    headers = mapOf("X-Emby-Token" to MEDIA_SERVER_CANARY),
                    redirectPolicy = HttpRedirectPolicy.DENY,
                )
                try {
                    engine.load(source.url("/source"))

                    assertTrue(source.awaitRequest(REQUEST_TIMEOUT_MILLIS), "首个 origin 未收到请求")
                    assertEquals(MEDIA_SERVER_CANARY, source.requireRequest().header("X-Emby-Token"))
                    assertFalse(
                        target.awaitRequest(DENY_OBSERVATION_MILLIS),
                        "DENY 策略仍访问了重定向目标",
                    )
                } finally {
                    engine.destroy()
                }
            }
        }
    }

    private fun createEngine(
        headers: Map<String, String>,
        redirectPolicy: HttpRedirectPolicy,
    ): MpvPlayerEngine {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        return MpvPlayerEngine(
            context = context,
            platformInfo = AndroidPlatformInfo(context),
            mainDispatcher = Dispatchers.Main,
        ).also { engine ->
            engine.init(
                PlayerConfig(
                    hwdec = "no",
                    audioOutput = "null",
                    hdrMode = HdrMode.OFF,
                    vo = "null",
                    httpHeaders = headers,
                    httpRedirectPolicy = redirectPolicy,
                ),
            )
            assertFalse(
                engine.state.value.status == PlaybackStatus.ERROR,
                "libmpv 初始化失败",
            )
        }
    }

    private companion object {
        const val CANARY_HEADER = "X-Redirect-Canary"
        const val FOLLOW_CANARY = "follow-device-test"
        const val MEDIA_SERVER_CANARY = "media-server-device-test"
        const val REQUEST_TIMEOUT_MILLIS = 8_000L
        const val DENY_OBSERVATION_MILLIS = 3_000L
    }
}

private data class TestHttpRequest(
    val requestLine: String,
    val headers: Map<String, String>,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

private data class TestHttpResponse(
    val status: String,
    val headers: Map<String, String>,
) {
    fun encode(): ByteArray = buildString {
        append("HTTP/1.1 ").append(status).append("\r\n")
        headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
        append("Content-Length: 0\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }.toByteArray(StandardCharsets.US_ASCII)

    companion object {
        fun redirect(location: String): TestHttpResponse =
            TestHttpResponse(status = "302 Found", headers = mapOf("Location" to location))

        fun notFound(): TestHttpResponse = TestHttpResponse(status = "404 Not Found", headers = emptyMap())
    }
}

private class OneShotHttpServer(
    private val response: () -> TestHttpResponse,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val completed = CountDownLatch(1)

    @Volatile
    private var closed = false

    @Volatile
    private var request: TestHttpRequest? = null

    @Volatile
    private var failure: Throwable? = null

    private val worker = thread(
        start = true,
        isDaemon = true,
        name = "media-server-redirect-test-${serverSocket.localPort}",
    ) {
        try {
            serverSocket.accept().use { socket ->
                socket.soTimeout = REQUEST_IO_TIMEOUT_MILLIS
                val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                val requestLine = reader.readLine() ?: error("HTTP 请求行缺失")
                val headers = buildMap {
                    while (true) {
                        val line = reader.readLine() ?: error("HTTP 请求头未完整结束")
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        require(separator > 0) { "HTTP 请求头格式无效" }
                        put(line.substring(0, separator).trim().lowercase(), line.substring(separator + 1).trim())
                    }
                }
                request = TestHttpRequest(requestLine = requestLine, headers = headers)
                socket.getOutputStream().use { output ->
                    output.write(response().encode())
                    output.flush()
                }
            }
        } catch (error: Throwable) {
            if (!closed || error !is SocketException) failure = error
        } finally {
            completed.countDown()
        }
    }

    fun url(path: String): String {
        require(path.startsWith('/')) { "测试路径必须以 / 开头" }
        return "http://127.0.0.1:${serverSocket.localPort}$path"
    }

    fun awaitRequest(timeoutMillis: Long): Boolean {
        if (!completed.await(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        failure?.let { error -> throw AssertionError("本地测试服务器失败", error) }
        return request != null
    }

    fun requireRequest(): TestHttpRequest = assertNotNull(request)

    override fun close() {
        closed = true
        runCatching { serverSocket.close() }
        worker.join(WORKER_JOIN_TIMEOUT_MILLIS)
    }

    private companion object {
        const val REQUEST_IO_TIMEOUT_MILLIS = 8_000
        const val WORKER_JOIN_TIMEOUT_MILLIS = 2_000L
    }
}
