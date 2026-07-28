package io.github.weiyongzenqi.unuplayer.mediaserver

import com.sun.net.httpserver.HttpServer
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaServerImageDownloaderTest {

    @Test
    fun `图片流式下载携带认证头并写入目标`() = runBlocking {
        val receivedToken = AtomicReference<String?>()
        val content = "streamed-poster-data".encodeToByteArray()
        val server = localServer { exchange ->
            receivedToken.set(exchange.requestHeaders.getFirst("X-Emby-Token"))
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        val root = Files.createTempDirectory("unu-media-server-image-test-")
        val destination = root.resolve("poster.part")
        val baseClient = HttpClient(OkHttp)
        val downloader = KtorMediaServerImageDownloader(baseClient)

        try {
            val downloaded = downloader.download(
                request = MediaServerImageRequest(
                    url = "http://127.0.0.1:${server.address.port}/image",
                    headers = mapOf("X-Emby-Token" to "header-canary"),
                    cacheKey = "cache-key",
                ),
                destination = PlatformFile(destination.toString()),
            )

            assertTrue(downloaded)
            assertEquals("header-canary", receivedToken.get())
            assertContentEquals(content, Files.readAllBytes(destination))
        } finally {
            downloader.close()
            baseClient.close()
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `图片下载拒绝重定向且不触达目标`() = runBlocking {
        val targetHits = AtomicInteger()
        val target = localServer { exchange ->
            targetHits.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val source = localServer { exchange ->
            exchange.responseHeaders.add(
                "Location",
                "http://127.0.0.1:${target.address.port}/target",
            )
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        val root = Files.createTempDirectory("unu-media-server-redirect-test-")
        val destination = root.resolve("redirect.part")
        val baseClient = HttpClient(OkHttp)
        val downloader = KtorMediaServerImageDownloader(baseClient)

        try {
            val downloaded = downloader.download(
                request = MediaServerImageRequest(
                    url = "http://127.0.0.1:${source.address.port}/redirect",
                    headers = mapOf("X-Emby-Token" to "must-not-leak"),
                    cacheKey = "cache-key",
                ),
                destination = PlatformFile(destination.toString()),
            )

            assertFalse(downloaded)
            assertEquals(0, targetHits.get())
            assertFalse(Files.exists(destination))
        } finally {
            downloader.close()
            baseClient.close()
            source.stop(0)
            target.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `图片响应超过上限时删除部分文件`() = runBlocking {
        val content = ByteArray(64) { it.toByte() }
        val server = localServer { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(content) }
        }
        val root = Files.createTempDirectory("unu-media-server-limit-test-")
        val destination = root.resolve("oversized.part")
        val baseClient = HttpClient(OkHttp)
        val downloader = KtorMediaServerImageDownloader(baseClient, maxImageBytes = 16)

        try {
            val downloaded = downloader.download(
                request = MediaServerImageRequest(
                    url = "http://127.0.0.1:${server.address.port}/image",
                    headers = emptyMap(),
                    cacheKey = "cache-key",
                ),
                destination = PlatformFile(destination.toString()),
            )

            assertFalse(downloaded)
            assertFalse(Files.exists(destination))
        } finally {
            downloader.close()
            baseClient.close()
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    private fun localServer(handler: com.sun.net.httpserver.HttpHandler): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/", handler)
            start()
        }
}
