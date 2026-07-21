package io.github.weiyongzenqi.unuplayer.webdav

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebDavPropfindFallbackTest {

    @Test
    fun `标准 PROPFIND 成功时不发送回退请求`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<RequestRecord>())
        withServer({ exchange ->
            requests += exchange.record()
            exchange.respond(207, propfindXml("/", "/Anime.mkv"))
        }) { baseUrl, httpClient ->
            val entries = client(httpClient, baseUrl).listDirectory("/")

            assertEquals(listOf("Anime.mkv"), entries.map { it.name })
            assertEquals(1, requests.size)
            assertEquals("1", requests.single().depth)
            assertTrue(requests.single().contentType.orEmpty().startsWith("text/xml"))
            assertTrue(requests.single().body.isNotEmpty())
        }
    }

    @Test
    fun `标准请求失败后第二个 PROPFIND 变体成功`() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<RequestRecord>())
        withServer({ exchange ->
            val request = exchange.record()
            requests += request
            if (request.depth == "0" && request.contentType.orEmpty().startsWith("text/xml") && request.body.isNotEmpty()) {
                exchange.respond(207, propfindXml("/", "/Variant.mkv"))
            } else {
                exchange.respond(405)
            }
        }) { baseUrl, httpClient ->
            val client = client(httpClient, baseUrl)
            val entries = client.listDirectory("/")
            client.listDirectory("/")

            assertEquals(listOf("Variant.mkv"), entries.map { it.name })
            assertEquals(listOf("1", "0", "0"), requests.map { it.depth })
        }
    }

    @Test
    fun `纯 origin 在五个原地址变体失败后探测 dav 子路径`() = runBlocking {
        val requestedPaths = Collections.synchronizedList(mutableListOf<String>())
        withServer({ exchange ->
            exchange.record()
            requestedPaths += exchange.requestURI.path
            when (exchange.requestURI.path) {
                "/dav/" -> exchange.respond(207, propfindXml("/dav/", "Subpath.mkv"))
                "/dav/Anime/" -> exchange.respond(207, propfindXml("/dav/Anime/", "Episode.mkv"))
                else -> exchange.respond(404)
            }
        }) { baseUrl, httpClient ->
            val client = client(httpClient, baseUrl)
            val entries = client.listDirectory("/")

            assertEquals(listOf("Subpath.mkv"), entries.map { it.name })
            assertEquals("/dav/Subpath.mkv", entries.single().path)
            assertEquals(6, requestedPaths.size)
            assertTrue(requestedPaths.take(5).all { it == "/" })
            assertEquals("/dav/", requestedPaths.last())

            val nested = client.listDirectory("/dav/Anime/")
            assertEquals("/dav/Anime/Episode.mkv", nested.single().path)
            assertEquals("/dav/Anime/", requestedPaths.last())
        }
    }

    @Test
    fun `认证失败立即终止且不扩散请求`() = runBlocking {
        val requestCount = AtomicInteger()
        withServer({ exchange ->
            exchange.record()
            requestCount.incrementAndGet()
            exchange.respond(401)
        }) { baseUrl, httpClient ->
            val error = try {
                client(httpClient, baseUrl).listDirectory("/")
                null
            } catch (caught: WebDavException) {
                caught
            }

            assertNotNull(error)
            assertTrue(error.message.orEmpty().contains("认证失败"))
            assertEquals(1, requestCount.get())
        }
    }

    @Test
    fun `候选链保持 origin 且有硬上限`() {
        val candidates = buildPropfindCandidates("https://example.com:8443")
        val origins = candidates.map { candidate ->
            URI(candidate.baseUrl).let { "${it.scheme}://${it.rawAuthority}" }
        }.toSet()

        assertEquals(15, candidates.size)
        assertEquals(setOf("https://example.com:8443"), origins)
        assertTrue(candidates.none { it.baseUrl.startsWith("http://") })
        assertEquals(5, buildPropfindCandidates("https://example.com:8443/mounted").size)
    }

    private fun client(httpClient: HttpClient, baseUrl: String): WebDavClient = WebDavClient(
        httpClient = httpClient,
        baseUrl = baseUrl,
        username = "user",
        password = "pass",
        fallbackRequestIntervalMs = 0L,
    )

    private suspend fun <T> withServer(
        handler: (HttpExchange) -> Unit,
        block: suspend (baseUrl: String, httpClient: HttpClient) -> T,
    ): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", handler)
        server.start()
        val httpClient = HttpClient(OkHttp)
        return try {
            block("http://127.0.0.1:${server.address.port}", httpClient)
        } finally {
            httpClient.close()
            server.stop(0)
        }
    }

    private fun HttpExchange.record(): RequestRecord {
        val body = requestBody.use { it.readBytes().decodeToString() }
        return RequestRecord(
            path = requestURI.path,
            depth = requestHeaders.getFirst("Depth"),
            contentType = requestHeaders.getFirst("Content-Type"),
            body = body,
        )
    }

    private fun HttpExchange.respond(status: Int, text: String = "") {
        val bytes = text.encodeToByteArray()
        if (bytes.isEmpty()) {
            sendResponseHeaders(status, -1)
            close()
        } else {
            responseHeaders.add("Content-Type", "application/xml; charset=utf-8")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }

    private fun propfindXml(selfPath: String, filePath: String): String = """
        <D:multistatus xmlns:D="DAV:">
          ${response(selfPath, selfPath.trim('/').ifEmpty { "root" }, directory = true)}
          ${response(filePath, filePath.substringAfterLast('/'))}
        </D:multistatus>
    """.trimIndent()

    private fun response(path: String, name: String, directory: Boolean = false): String = """
        <D:response>
          <D:href>$path</D:href>
          <D:propstat><D:prop>
            <D:displayname>$name</D:displayname>
            <D:getcontentlength>8</D:getcontentlength>
            <D:resourcetype>${if (directory) "<D:collection/>" else ""}</D:resourcetype>
          </D:prop></D:propstat>
        </D:response>
    """.trimIndent()

    private data class RequestRecord(
        val path: String,
        val depth: String?,
        val contentType: String?,
        val body: String,
    )
}
