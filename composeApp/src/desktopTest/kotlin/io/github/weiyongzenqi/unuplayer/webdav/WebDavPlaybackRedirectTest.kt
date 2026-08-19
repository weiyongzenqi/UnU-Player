package io.github.weiyongzenqi.unuplayer.webdav

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.github.weiyongzenqi.unuplayer.core.player.HttpRedirectPolicy
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebDavPlaybackRedirectTest {

    @Test
    fun `匿名标准重定向不预取目标且交给有限跳转链`() = runBlocking {
        val statuses = listOf(301, 302, 303, 307, 308)
        val hits = CopyOnWriteArrayList<String>()
        val server = server { exchange ->
            hits += "${exchange.requestURI.path}:${exchange.requestHeaders.getFirst("Range")}"
            when {
                exchange.requestURI.path == "/dav/final" -> respond(exchange, 206)
                exchange.requestURI.path.startsWith("/dav/status/") -> {
                    val status = exchange.requestURI.path.substringAfterLast('/').toInt()
                    redirect(exchange, status, "../final")
                }
                else -> respond(exchange, 404)
            }
        }.also { it.start() }
        val client = HttpClient(OkHttp)
        try {
            val webDav = WebDavClient(client, "http://127.0.0.1:${server.address.port}/dav", "", "")
            statuses.forEach { status ->
                val request = webDav.resolvePlaybackRequest("/status/$status")
                assertEquals("http://127.0.0.1:${server.address.port}/dav/final", request.url)
                assertTrue(request.headers.isEmpty())
                assertEquals(HttpRedirectPolicy.FOLLOW_LIMITED, request.redirectPolicy)
                assertEquals(206, client.get(request.url).status.value)
            }
            assertEquals(statuses.size * 2, hits.size)
            assertEquals(statuses.size, hits.count { it.endsWith(":bytes=0-0") })
            assertEquals(statuses.size, hits.count { it.endsWith(":null") })
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun `缺少 Location 和非成功非跳转状态直接失败`() = runBlocking {
        val server = server { exchange ->
            when (exchange.requestURI.path) {
                "/dav/missing" -> respond(exchange, 302)
                "/dav/unauthorized" -> respond(exchange, 401)
                else -> respond(exchange, 404)
            }
        }.also { it.start() }
        val client = HttpClient(OkHttp)
        try {
            val webDav = WebDavClient(client, "http://127.0.0.1:${server.address.port}/dav", "u", "p")
            assertFailsWith<WebDavException> { webDav.resolvePlaybackRequest("/missing") }
            val unauthorized = assertFailsWith<WebDavException> {
                webDav.resolvePlaybackRequest("/unauthorized")
            }
            assertEquals(401, unauthorized.statusCode)
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun `同源跳转保留 Basic 跨源目标不预取且后续链不恢复认证`() = runBlocking {
        val sourceHeaders = CopyOnWriteArrayList<String>()
        val targetHeaders = CopyOnWriteArrayList<String>()
        val targetPort = java.util.concurrent.atomic.AtomicInteger()
        val source = server { exchange ->
            sourceHeaders += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            when (exchange.requestURI.path) {
                "/dav/file" -> redirect(exchange, "/dav/step")
                "/dav/step" -> redirect(exchange, "http://127.0.0.1:${targetPort.get()}/target")
                "/dav/back" -> redirect(exchange, "http://127.0.0.1:${targetPort.get()}/back")
                else -> respond(exchange, 404)
            }
        }
        source.start()
        val target = server { exchange ->
            targetHeaders += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            when (exchange.requestURI.path) {
                "/target" -> redirect(exchange, "http://127.0.0.1:${source.address.port}/dav/back")
                "/back" -> respond(exchange, 206)
                else -> respond(exchange, 404)
            }
        }
        target.start()
        targetPort.set(target.address.port)
        val client = HttpClient(OkHttp)
        try {
            val request = WebDavClient(
                httpClient = client,
                baseUrl = "http://127.0.0.1:${source.address.port}/dav",
                username = "user",
                password = "pass",
            ).resolvePlaybackRequest("/dav/file")

            assertEquals("http://127.0.0.1:${target.address.port}/target", request.url)
            assertTrue(request.headers.isEmpty())
            assertEquals(HttpRedirectPolicy.FOLLOW_LIMITED, request.redirectPolicy)
            assertTrue(targetHeaders.isEmpty(), "resolver 不得预取跨源目标")

            assertEquals(206, client.get(request.url).status.value)
            assertEquals(listOf("Basic dXNlcjpwYXNz", "Basic dXNlcjpwYXNz", ""), sourceHeaders)
            assertEquals(listOf("", ""), targetHeaders)
        } finally {
            client.close()
            source.stop(0)
            target.stop(0)
        }
    }

    @Test
    fun `跨源一次性签名只由真实取流消费一次`() = runBlocking {
        val targetPort = java.util.concurrent.atomic.AtomicInteger()
        val targetHits = java.util.concurrent.atomic.AtomicInteger()
        val source = server { exchange ->
            redirect(exchange, "http://127.0.0.1:${targetPort.get()}/signed?ticket=once")
        }.also { it.start() }
        val target = server { exchange ->
            if (targetHits.incrementAndGet() == 1) respond(exchange, 206) else respond(exchange, 403)
        }.also { it.start() }
        targetPort.set(target.address.port)
        val client = HttpClient(OkHttp)
        try {
            val request = WebDavClient(
                httpClient = client,
                baseUrl = "http://127.0.0.1:${source.address.port}/dav",
                username = "user",
                password = "pass",
            ).resolvePlaybackRequest("/dav/file")

            assertEquals(0, targetHits.get(), "解析阶段不得消费一次性签名")
            assertTrue(request.headers.isEmpty())
            assertEquals(HttpRedirectPolicy.FOLLOW_LIMITED, request.redirectPolicy)
            assertEquals(206, client.get(request.url).status.value)
            assertEquals(1, targetHits.get())
        } finally {
            client.close()
            source.stop(0)
            target.stop(0)
        }
    }

    @Test
    fun `循环和跳数上限终止且请求数有界`() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val server = server { exchange ->
            requests += exchange.requestURI.path
            val next = exchange.requestURI.path.removePrefix("/hop/").toIntOrNull() ?: 0
            if (next >= 8) respond(exchange, 206) else redirect(exchange, "/hop/${next + 1}")
        }.also { it.start() }
        val loop = server { exchange ->
            requests += exchange.requestURI.path
            redirect(exchange, "/loop")
        }.also { it.start() }
        val client = HttpClient(OkHttp)
        try {
            val webDav = WebDavClient(client, "http://127.0.0.1:${server.address.port}/dav", "u", "p")
            assertFailsWith<WebDavException> { webDav.resolvePlaybackRequest("/hop/0") }
            assertTrue(requests.size <= 6)
            assertFailsWith<WebDavException> {
                WebDavClient(client, "http://127.0.0.1:${loop.address.port}/dav", "u", "p")
                    .resolvePlaybackRequest("/loop")
            }
        } finally {
            client.close()
            server.stop(0)
            loop.stop(0)
        }
        Unit
    }

    private fun server(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                try {
                    handler(exchange)
                } catch (_: Throwable) {
                    exchange.close()
                }
            }
        }

    private fun redirect(exchange: HttpExchange, location: String) = redirect(exchange, 302, location)

    private fun redirect(exchange: HttpExchange, status: Int, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    private fun respond(exchange: HttpExchange, status: Int) {
        exchange.sendResponseHeaders(status, 0)
        exchange.responseBody.close()
    }
}
