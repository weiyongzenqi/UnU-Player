package io.github.weiyongzenqi.unuplayer.bangumi

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbScrapeApiTest {

    // 不使用进程级共享客户端，避免其他网络生命周期测试关闭单例后影响本套件。
    private fun api(serverUrl: String) = TmdbScrapeApi(
        apiKey = "test-gateway-key",
        httpClient = HttpClient(OkHttp) { followRedirects = false },
        baseUrl = serverUrl,
    )

    @Test
    fun `搜索通过Gateway路径和API key头解析候选`() = runBlocking {
        withServer { serverUrl, server ->
            var apiKeyHeader: String? = null
            var authorizationHeader: String? = null
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                apiKeyHeader = exchange.requestHeaders.getFirst("X-API-Key")
                authorizationHeader = exchange.requestHeaders.getFirst("Authorization")
                assertTrue(exchange.requestURI.rawQuery.contains("year=2023"))
                assertTrue(exchange.requestURI.rawQuery.contains("language=zh-CN"))
                assertTrue(exchange.requestURI.rawQuery.contains("page=1"))
                exchange.respond(
                    200,
                    """{"page":1,"totalPages":1,"candidates":[
                        {"tmdbId":123,"name":"葬送的芙莉莲","originalName":"Sousou no Frieren",
                         "firstAirDate":"2023-09-29","backdropPath":"/bd1.jpg"},
                        {"tmdbId":999,"name":"别剧","firstAirDate":"2022-01-01"}
                    ]}""",
                )
            }

            val results = api(serverUrl).searchTv("葬送的芙莉莲", 2023)

            assertEquals("test-gateway-key", apiKeyHeader)
            assertNull(authorizationHeader)
            assertEquals(2, results.size)
            val hit = results.first { it.tmdbId == 123L }
            assertEquals("葬送的芙莉莲", hit.name)
            assertEquals("Sousou no Frieren", hit.originalName)
            assertEquals("2023", hit.firstAirDate?.take(4))
            assertEquals("/bd1.jpg", hit.backdropPath)
        }
    }

    @Test
    fun `TV详情解析中文简介评分类型和图片路径`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v1/tmdb/tv/123") { exchange ->
                assertTrue(exchange.requestURI.rawQuery.contains("language=zh-CN"))
                exchange.respond(
                    200,
                    """{"tvId":123,"name":"中文剧名","originalName":"Original","overview":"中文简介","voteAverage":8.6,"firstAirDate":"2026-07-01","posterPath":"/poster.jpg","backdropPath":"/backdrop.jpg","genres":["动画","奇幻"]}""",
                )
            }

            val details = api(serverUrl).fetchTvDetails(123)

            assertNotNull(details)
            assertEquals(123L, details.tmdbId)
            assertEquals("中文剧名", details.name)
            assertEquals("中文简介", details.overview)
            assertEquals(8.6, details.voteAverage)
            assertEquals(listOf("动画", "奇幻"), details.genres)
            assertEquals("/poster.jpg", details.posterPath)
            assertEquals("/backdrop.jpg", details.backdropPath)
        }
    }

    @Test
    fun `简体搜索无中文时回退繁体中文`() = runBlocking {
        withServer { serverUrl, server ->
            val languages = mutableListOf<String>()
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                val language = exchange.requestURI.rawQuery
                    .split('&')
                    .first { it.startsWith("language=") }
                    .substringAfter('=')
                languages += language
                exchange.respond(
                    200,
                    if (language == "zh-CN") {
                        """{"candidates":[{"tmdbId":123,"name":"Sousou no Frieren"}]}"""
                    } else {
                        """{"candidates":[{"tmdbId":123,"name":"葬送的芙莉蓮","originalName":"Sousou no Frieren"}]}"""
                    },
                )
            }

            val results = api(serverUrl).searchTv("Sousou no Frieren")

            assertEquals(listOf("zh-CN", "zh-TW"), languages)
            assertEquals("葬送的芙莉蓮", results.single().name)
        }
    }

    @Test
    fun `简体搜索失败时直接上抛且不请求繁体`() = runBlocking {
        withServer { serverUrl, server ->
            val languages = mutableListOf<String>()
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                languages += exchange.requestURI.rawQuery
                    .split('&')
                    .first { it.startsWith("language=") }
                    .substringAfter('=')
                exchange.respond(503, "service unavailable")
            }

            assertFailsWith<TmdbApiException> {
                api(serverUrl).searchTv("Frieren")
            }

            assertEquals(listOf("zh-CN"), languages)
        }
    }

    @Test
    fun `backdrop使用Gateway图片路由并按语言优先级选择`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v1/tmdb/tv/123/images") { exchange ->
                assertTrue(exchange.requestURI.rawQuery.contains("language=zh-CN"))
                exchange.respond(
                    200,
                    """{"tvId":123,"imageBaseUrl":"https://image.tmdb.org/t/p","backdrops":[
                        {"filePath":"/en.jpg","language":"en"},
                        {"filePath":"/a.jpg","language":"zh"},
                        {"filePath":"/neutral.jpg"}
                    ]}""",
                )
            }

            assertEquals("/a.jpg", api(serverUrl).fetchTvImagePaths(123).backdropPath)
            assertNull(api(serverUrl).fetchTvImagePaths(0).backdropPath)
        }
    }

    @Test
    fun `TV海报按语言优先级选择且旧网关无posters字段时为空`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v1/tmdb/tv/123/images") { exchange ->
                exchange.respond(
                    200,
                    """{"tvId":123,"backdrops":[{"filePath":"/bd.jpg"}],"posters":[
                        {"filePath":"/p-en.jpg","language":"en"},
                        {"filePath":"/p-zh.jpg","language":"zh-CN"}
                    ]}""",
                )
            }

            val images = api(serverUrl).fetchTvImagePaths(123)

            assertEquals("/p-zh.jpg", images.posterPath)
            assertEquals("/bd.jpg", images.backdropPath)
            // 旧网关响应(无 posters)容错: 空列表 -> 海报 null, 不抛
            withServer { legacyUrl, legacy ->
                legacy.createContext("/api/v1/tmdb/tv/123/images") { exchange ->
                    exchange.respond(200, """{"tvId":123,"backdrops":[]}""")
                }
                assertNull(api(legacyUrl).fetchTvImagePaths(123).posterPath)
            }
        }
    }

    @Test
    fun `整季剧照使用Gateway季度路由并跳过空集`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v1/tmdb/tv/123/season/1/episodes") { exchange ->
                assertTrue(exchange.requestURI.rawQuery.contains("language=zh-CN"))
                exchange.respond(
                    200,
                    """{"tvId":123,"seasonNumber":1,"episodes":[
                        {"episodeNumber":1,"name":"第一集","stillPath":"/s1.jpg"},
                        {"episodeNumber":2,"name":"第二集","stillPath":null},
                        {"episodeNumber":3,"name":"第三集","stillPath":"/s3.jpg"}
                    ],"imageBaseUrl":"https://image.tmdb.org/t/p"}""",
                )
            }
            server.createContext("/api/v1/tmdb/tv/123/season/0/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"tvId":123,"seasonNumber":0,"episodes":[{"episodeNumber":1,"stillPath":"/special1.jpg"}]}""",
                )
            }

            val images = api(serverUrl).fetchSeasonImages(123, 1)

            assertEquals(mapOf(1 to "/s1.jpg", 3 to "/s3.jpg"), images.stillPaths)
            assertEquals(listOf(1, 2, 3), images.episodes.map { it.episodeNumber })
            assertEquals("第一集", images.episodes.first().name)
            assertNull(images.posterPath, "旧网关季度响应无 posterPath 字段时应为 null")
            assertEquals(mapOf(1 to "/special1.jpg"), api(serverUrl).fetchSeasonImages(123, 0).stillPaths)
            assertTrue(api(serverUrl).fetchSeasonImages(123, -1).stillPaths.isEmpty())
        }
    }

    @Test
    fun `季度响应带posterPath时随剧照一并返回`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v1/tmdb/tv/123/season/1/episodes") { exchange ->
                exchange.respond(
                    200,
                    """{"tvId":123,"seasonNumber":1,"posterPath":"/season1.jpg","episodes":[]}""",
                )
            }

            assertEquals("/season1.jpg", api(serverUrl).fetchSeasonImages(123, 1).posterPath)
        }
    }

    @Test
    fun `HTTP 200缺少业务schema或返回错误信封时受控失败`() = runBlocking {
        listOf(
            "{}",
            """{"error":{"code":"UPSTREAM_FAILED","requestId":"request-1"}}""",
        ).forEach { responseBody ->
            withServer { serverUrl, server ->
                server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                    exchange.respond(200, responseBody)
                }

                val error = assertFailsWith<TmdbApiException> {
                    api(serverUrl).searchTv("Frieren")
                }

                assertTrue(error.message.orEmpty().contains("响应格式无效"))
            }
        }
    }

    @Test
    fun `图片和季度响应身份不匹配时拒绝消费`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v1/tmdb/tv/123/images") { exchange ->
                exchange.respond(200, """{"tvId":456,"backdrops":[]}""")
            }
            server.createContext("/api/v1/tmdb/tv/123/season/1/episodes") { exchange ->
                exchange.respond(200, """{"tvId":123,"seasonNumber":2,"episodes":[]}""")
            }

            val imagesError = assertFailsWith<TmdbApiException> {
                api(serverUrl).fetchTvImagePaths(123)
            }
            val seasonError = assertFailsWith<TmdbApiException> {
                api(serverUrl).fetchSeasonImages(123, 1)
            }

            assertTrue(imagesError.message.orEmpty().contains("身份不匹配"))
            assertTrue(seasonError.message.orEmpty().contains("身份不匹配"))
        }
    }

    @Test
    fun `429保留Gateway错误码请求ID和重试时间`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                exchange.responseHeaders.add("X-Request-Id", "header-request-id")
                exchange.responseHeaders.add("Retry-After", "9")
                exchange.respond(
                    429,
                    """{"error":{"code":"RATE_LIMITED","message":"请求过于频繁",
                        "requestId":"body-request-id","retryAfterSeconds":2}}""",
                )
            }

            val error = assertFailsWith<TmdbApiException> {
                api(serverUrl).searchTv("Frieren")
            }

            assertEquals(429, error.statusCode)
            assertEquals("RATE_LIMITED", error.errorCode)
            assertEquals("body-request-id", error.requestId)
            assertEquals(2, error.retryAfterSeconds)
            assertFalse(error.message.orEmpty().contains("Frieren"))
            assertFalse(error.message.orEmpty().contains("test-gateway-key"))
        }
    }

    @Test
    fun `成功响应超过一MiB上限会受控失败`() = runBlocking {
        withServer { serverUrl, server ->
            server.createContext("/api/v1/tmdb/search/tv") { exchange ->
                exchange.respond(200, "{\"padding\":\"${"x".repeat(1024 * 1024)}\"}")
            }

            val error = assertFailsWith<TmdbApiException> {
                api(serverUrl).searchTv("Frieren")
            }

            assertTrue(error.message.orEmpty().contains("响应超过大小上限"))
        }
    }

    @Test
    fun `图片CDN URL保持直连`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/s1.jpg",
            api("http://127.0.0.1:1").imageUrl("/s1.jpg", "w500"),
        )
    }

    @Test
    fun `内置Gateway配置解码结果符合格式`() {
        val baseUrl = URI(TmdbGatewayConfig.baseUrl())
        assertEquals("https", baseUrl.scheme)
        assertNotNull(baseUrl.host)
        assertNull(baseUrl.userInfo)
        // 统一网关形态: base 携带模块前缀路径 /tmdb(unified 秘密前缀), 不带 query/fragment
        assertEquals("/tmdb", baseUrl.path)
        assertNull(baseUrl.query)
        assertNull(baseUrl.fragment)
        val key = TmdbGatewayConfig.apiKey()
        assertTrue(key.length >= 40)
        assertTrue(key.all { it.isLetterOrDigit() || it == '_' || it == '-' })
        assertFalse(key.any(Char::isWhitespace))
    }

    @Test
    fun `真实Gateway可完成搜索图片和季度请求`() = runBlocking {
        if (System.getenv(LIVE_TEST_ENV) != "true") return@runBlocking

        val liveApi = TmdbScrapeApi(
            httpClient = HttpClient(OkHttp) { followRedirects = false },
        )
        val candidate = assertNotNull(liveApi.searchTv("Frieren").firstOrNull { it.tmdbId == 209867L })
        assertTrue(candidate.name.isNotBlank())
        assertNotNull(liveApi.fetchTvImagePaths(candidate.tmdbId).backdropPath)
        assertTrue(liveApi.fetchSeasonImages(candidate.tmdbId, 1).stillPaths.isNotEmpty())
    }

    @Test
    fun `真实Gateway中我推的孩子仍是合并第一季`() = runBlocking {
        if (System.getenv(LIVE_TEST_ENV) != "true") return@runBlocking

        val liveApi = TmdbScrapeApi(
            httpClient = HttpClient(OkHttp) { followRedirects = false },
        )
        val missingSeason = assertFailsWith<TmdbApiException> {
            liveApi.fetchSeasonImages(203737L, 2)
        }
        assertEquals(502, missingSeason.statusCode)
        assertEquals("UPSTREAM_REJECTED", missingSeason.errorCode)
        val mergedSeason = liveApi.fetchSeasonImages(203737L, 1)
        assertTrue((12..24).all { episode -> mergedSeason.episodes.any { it.episodeNumber == episode } })
        assertTrue((12..24).any { it in mergedSeason.stillPaths })
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

    private companion object {
        const val LIVE_TEST_ENV = "UNU_TMDB_GATEWAY_LIVE_TEST"
    }
}
