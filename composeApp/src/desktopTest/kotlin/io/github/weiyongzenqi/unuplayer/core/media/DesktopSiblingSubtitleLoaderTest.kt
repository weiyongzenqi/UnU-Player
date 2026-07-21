package io.github.weiyongzenqi.unuplayer.core.media

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import io.github.weiyongzenqi.unuplayer.core.security.DesktopCredentialCipher
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionStore
import io.github.weiyongzenqi.unuplayer.webdav.parsePropfindResponse
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopSiblingSubtitleLoaderTest {
    @Test
    fun `本地候选排序物化与生命周期不触发 HTTP 或删除用户文件`() = runBlocking {
        val root = createTempDirectory("unu-sub-local-")
        try {
            val mediaDir = root.resolve("media").createDirectories()
            val video = mediaDir.resolve("Anime S01E04.mkv").apply { writeText("video") }
            val strict = mediaDir.resolve("Anime S01E04.ass").apply { writeText("strict") }
            mediaDir.resolve("Anime S01E04.sc.srt").writeText("sc")
            mediaDir.resolve("Anime S01E04.tc.ass").writeText("tc")
            mediaDir.resolve("Anime S01E04.en.vtt").writeText("en")
            root.resolve("outside.srt").writeText("outside")
            val link = mediaDir.resolve("Anime S01E04.link.srt")
            val linkCreated = runCatching {
                Files.createSymbolicLink(link, root.resolve("outside.srt"))
                true
            }.getOrDefault(false)

            val loader = DesktopSiblingSubtitleLoader(
                webDavRepository = WebDavConnectionRepository(InMemoryWebDavConnectionStore(), DesktopCredentialCipher()),
                httpClientProvider = { error("本地字幕不应创建 HTTP client") },
                tempRoot = root.resolve("runtime"),
            )
            val media = PlayableMedia(
                url = video.toString(),
                title = video.name,
                sourceKind = MediaSourceKind.LOCAL,
                mediaKey = MediaKeys.local(video.toString()),
            )

            val automatic = loader.listCandidates(media, "sc")
            assertEquals(
                listOf("Anime S01E04.sc.srt", "Anime S01E04.tc.ass", "Anime S01E04.ass"),
                automatic.map { it.displayName },
            )
            val all = loader.listAllSubtitles(media)
            assertTrue(all.any { it.displayName == "Anime S01E04.en.vtt" })
            assertFalse(all.any { it.displayName == "outside.srt" })
            if (linkCreated) assertFalse(all.any { it.displayName == link.name })

            val materialized = assertNotNull(loader.materialize(automatic.last()))
            assertEquals(strict.toRealPath().toFile(), materialized)
            assertTrue(loader.listCandidates(media.copy(url = video.toUri().toString()), "none").isNotEmpty())

            loader.close()
            loader.close()
            assertTrue(video.exists())
            assertTrue(strict.exists())
            assertFalse(root.resolve("runtime").resolve("session-unused").exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `WebDAV 使用原始路径认证并安全下载到独立 session`() = runBlocking {
        val root = createTempDirectory("unu-sub-webdav-")
        assertTrue(
            parsePropfindResponse(propfindResponse()).any { it.name == "Anime S01E04.sc.srt" },
            "测试 PROPFIND fixture 必须可被桌面解析器读取",
        )
        val requestedPropfindPath = AtomicReference<String>()
        val receivedAuthorization = AtomicReference<String>()
        val scBytes = "简体字幕".encodeToByteArray()
        val evilBytes = "恶意文件名但内容安全".encodeToByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            receivedAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            when (exchange.requestMethod) {
                "PROPFIND" -> {
                    requestedPropfindPath.set(exchange.requestURI.path)
                    exchange.respondXml(propfindResponse())
                }
                "GET" -> when (exchange.requestURI.path) {
                    "/dav/anime/Anime S01E04.sc.srt" -> exchange.respondBytes(scBytes)
                    "/dav/anime/evil.srt" -> exchange.respondBytes(evilBytes)
                    else -> exchange.sendResponseHeaders(404, -1)
                }
                else -> exchange.sendResponseHeaders(405, -1)
            }
        }
        server.start()
        val client = HttpClient(OkHttp)
        try {
            val repository = WebDavConnectionRepository(InMemoryWebDavConnectionStore(), DesktopCredentialCipher())
            repository.add(
                WebDavConnection(
                    id = "test-id",
                    name = "测试",
                    baseUrl = "http://127.0.0.1:${server.address.port}/dav",
                    username = "user",
                    password = "pass",
                ),
                allowCleartext = true,
            )
            val tempRoot = root.resolve("runtime")
            val loader = DesktopSiblingSubtitleLoader(repository, { client }, tempRoot)
            val media = PlayableMedia(
                url = "http://127.0.0.1:${server.address.port}/dav/anime/Anime%20S01E04.mkv",
                headers = mapOf("Authorization" to expectedAuthorization()),
                title = "Anime S01E04.mkv",
                sourceKind = MediaSourceKind.WEBDAV,
                mediaKey = "webdav:test-id:/dav/anime/Anime S01E04:final.mkv",
            )

            val automatic = loader.listCandidates(media, "sc")
            assertEquals("/dav/anime", requestedPropfindPath.get())
            assertEquals(expectedAuthorization(), receivedAuthorization.get())
            assertEquals(listOf("Anime S01E04.sc.srt"), automatic.map { it.displayName })

            val all = loader.listAllSubtitles(media)
            assertTrue(all.any { it.displayName == "Anime S01E04.en.ass" })
            val malicious = assertNotNull(all.firstOrNull { it.displayName == "../evil.srt" })

            val downloaded = assertNotNull(loader.materialize(automatic.single()))
            assertContentEquals(scBytes, downloaded.toPath().readBytes())
            assertTrue(downloaded.toPath().startsWith(tempRoot.toAbsolutePath()))
            assertTrue(downloaded.parentFile.name.startsWith("session-"))

            val safeMalicious = assertNotNull(loader.materialize(malicious))
            assertContentEquals(evilBytes, safeMalicious.toPath().readBytes())
            assertTrue(safeMalicious.name.startsWith("sub_"))
            assertFalse(safeMalicious.name.contains("evil"))
            assertFalse(safeMalicious.name.contains(".."))

            val session = downloaded.parentFile.toPath()
            loader.close()
            loader.close()
            assertFalse(session.exists())
        } finally {
            client.close()
            server.stop(0)
            root.toFile().deleteRecursively()
        }
    }

    private fun propfindResponse(): String = """
        <D:multistatus xmlns:D="DAV:">
          ${response("/dav/anime/", "anime", true)}
          ${response("/dav/anime/Anime S01E04.mkv", "Anime S01E04.mkv")}
          ${response("/dav/anime/Anime S01E04.sc.srt", "Anime S01E04.sc.srt")}
          ${response("/dav/anime/Anime S01E04.en.ass", "Anime S01E04.en.ass")}
          ${response("/dav/anime/evil.srt", "../evil.srt")}
          ${response("/dav/anime/README.txt", "README.txt")}
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

    private fun expectedAuthorization(): String = "Basic " + Base64.getEncoder()
        .encodeToString("user:pass".encodeToByteArray())

    private fun HttpExchange.respondXml(text: String) {
        responseHeaders.add("Content-Type", "application/xml; charset=utf-8")
        respondBytes(text.encodeToByteArray())
    }

    private fun HttpExchange.respondBytes(bytes: ByteArray) {
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private class InMemoryWebDavConnectionStore : WebDavConnectionStore {
        private var connections = emptyList<WebDavConnection>()

        override suspend fun loadAll(): List<WebDavConnection> = connections

        override suspend fun replaceAll(connections: List<WebDavConnection>) {
            this.connections = connections.toList()
        }
    }
}
