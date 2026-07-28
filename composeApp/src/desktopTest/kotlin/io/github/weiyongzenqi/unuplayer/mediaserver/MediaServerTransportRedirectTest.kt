package io.github.weiyongzenqi.unuplayer.mediaserver

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaServerTransportRedirectTest {

    @Test
    fun `transport 不跟随可能泄露 Emby token 的重定向`() = runBlocking {
        val targetHits = AtomicInteger()
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/target") { exchange ->
                targetHits.incrementAndGet()
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/redirect") { exchange ->
                exchange.responseHeaders.add(
                    "Location",
                    "http://127.0.0.1:${target.address.port}/target",
                )
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            start()
        }
        val baseClient = HttpClient(OkHttp)
        val transport = KtorMediaServerTransport(baseClient)

        try {
            val response = transport.execute(
                MediaServerHttpRequest(
                    operation = "emby.redirect-test",
                    method = MediaServerHttpMethod.GET,
                    url = "http://127.0.0.1:${source.address.port}/redirect",
                    headers = mapOf("X-Emby-Token" to "must-not-leak"),
                ),
            )

            assertEquals(302, response.statusCode)
            assertEquals(0, targetHits.get())
        } finally {
            transport.close()
            baseClient.close()
            source.stop(0)
            target.stop(0)
        }
    }
}
