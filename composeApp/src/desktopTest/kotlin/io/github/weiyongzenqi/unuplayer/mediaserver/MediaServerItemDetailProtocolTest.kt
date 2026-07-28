package io.github.weiyongzenqi.unuplayer.mediaserver

import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 媒体服务器单条目详情端点(getItemDetail)解析与容错测试。
 *
 * 真机事实: Jellyfin/Emby 的 `GET /Users/{userId}/Items/{itemId}` 默认返回 ProviderIds;
 * 集条目(Episode)自己的 ProviderIds 通常为空 -> 必须用 SeriesId 二跳查系列 detail。
 */
class MediaServerItemDetailProtocolTest {

    @Test
    fun `Jellyfin getItemDetail 走 Users 路径并解析 ProviderIds 与季集号`() = runBlocking {
        val transport = RecordingTransport(
            response(
                """{
                    "Id":"episode-1","Name":"反射与修正","Type":"Episode",
                    "SeriesName":"义妹生活","SeriesId":"series-1","SeasonId":"season-1",
                    "IndexNumber":3,"ParentIndexNumber":1,
                    "ProviderIds":{"Tmdb":"285574","Imdb":"tt0000000"}
                }""".trimIndent(),
            ),
        )
        val adapter = JellyfinApiAdapter(transport)
        val session = session(MediaServerVendor.JELLYFIN, "jf-secret")

        val detail = adapter.getItemDetail(session, "episode-1")

        assertEquals("episode-1", detail.id)
        assertEquals(MediaServerItemKind.EPISODE, detail.kind)
        assertEquals("285574", detail.providerIds["Tmdb"])
        assertEquals("tt0000000", detail.providerIds["Imdb"])
        assertEquals("series-1", detail.seriesId)
        assertEquals("season-1", detail.seasonId)
        assertEquals("义妹生活", detail.seriesName)
        assertEquals(3, detail.indexNumber)
        assertEquals(1, detail.parentIndexNumber)
        // 路径形态必须是 Users/{userId}/Items/{itemId}(真机验证默认返回 ProviderIds)
        val url = Url(transport.requests.single().url)
        assertEquals("/reverse/jellyfin/Users/user-1/Items/episode-1", url.encodedPath)
        assertTrue(requireNotNull(transport.requests.single().headers["Authorization"]).contains("jf-secret"))
    }

    @Test
    fun `Jellyfin getItemDetail 容错字段缺失`() = runBlocking {
        // 测试服常见: IndexNumber/ParentIndexNumber 缺失, Episode 的 ProviderIds 为空 {}
        val transport = RecordingTransport(
            response("""{"Id":"episode-2","Name":"某集","Type":"Episode","SeriesName":"某番","SeriesId":"series-2"}""".trimIndent()),
        )
        val adapter = JellyfinApiAdapter(transport)

        val detail = adapter.getItemDetail(session(MediaServerVendor.JELLYFIN, "jf-secret"), "episode-2")

        assertEquals(MediaServerItemKind.EPISODE, detail.kind)
        assertTrue(detail.providerIds.isEmpty())
        assertEquals("series-2", detail.seriesId)
        assertNull(detail.seasonId)
        assertNull(detail.indexNumber)
        assertNull(detail.parentIndexNumber)
    }

    @Test
    fun `Jellyfin getItemDetail MOVIE 直取自身 ProviderIds`() = runBlocking {
        val transport = RecordingTransport(
            response("""{"Id":"movie-1","Name":"剧场版","Type":"Movie","ProviderIds":{"Tmdb":"999999"}}""".trimIndent()),
        )
        val adapter = JellyfinApiAdapter(transport)

        val detail = adapter.getItemDetail(session(MediaServerVendor.JELLYFIN, "jf-secret"), "movie-1")

        assertEquals(MediaServerItemKind.MOVIE, detail.kind)
        assertEquals("999999", detail.providerIds["Tmdb"])
        assertNull(detail.seriesId)
    }

    @Test
    fun `Jellyfin getItemDetail 缺 Id 抛协议异常`() = runBlocking {
        val transport = RecordingTransport(response("""{"Name":"无 Id"}"""))
        val adapter = JellyfinApiAdapter(transport)

        assertFailsWith<MediaServerProtocolException> {
            adapter.getItemDetail(session(MediaServerVendor.JELLYFIN, "jf-secret"), "episode-1")
        }
        Unit
    }

    @Test
    fun `Jellyfin getItemDetail 空 itemId 抛参数异常`() = runBlocking {
        val adapter = JellyfinApiAdapter(RecordingTransport(response("""{"Id":"x"}""")))
        assertFailsWith<IllegalArgumentException> {
            adapter.getItemDetail(session(MediaServerVendor.JELLYFIN, "jf-secret"), "")
        }
        Unit
    }

    @Test
    fun `Emby getItemDetail 走 Users 路径并解析 ProviderIds 与 token 头`() = runBlocking {
        val transport = RecordingTransport(
            response(
                """{
                    "Id":"episode-e","Name":"反射","Type":"Episode",
                    "SeriesName":"义妹生活","SeriesId":"series-e",
                    "IndexNumber":3,"ParentIndexNumber":1,
                    "ProviderIds":{"Tmdb":"285574"}
                }""".trimIndent(),
            ),
        )
        val adapter = EmbyApiAdapter(transport)
        val session = session(MediaServerVendor.EMBY, "emby-secret")
            .copy(apiBaseUrl = "https://media.example.test/reverse/emby")

        val detail = adapter.getItemDetail(session, "episode-e")

        assertEquals("episode-e", detail.id)
        assertEquals(MediaServerItemKind.EPISODE, detail.kind)
        assertEquals("285574", detail.providerIds["Tmdb"])
        assertEquals("series-e", detail.seriesId)
        assertEquals(3, detail.indexNumber)
        assertEquals(1, detail.parentIndexNumber)
        val url = Url(transport.requests.single().url)
        assertEquals("/reverse/emby/Users/user-1/Items/episode-e", url.encodedPath)
        assertEquals("emby-secret", transport.requests.single().headers["X-Emby-Token"])
    }

    @Test
    fun `Emby getItemDetail 容错 ProviderIds 缺失`() = runBlocking {
        val transport = RecordingTransport(
            response("""{"Id":"movie-e","Name":"某电影","Type":"Movie"}""".trimIndent()),
        )
        val adapter = EmbyApiAdapter(transport)

        val detail = adapter.getItemDetail(
            session(MediaServerVendor.EMBY, "emby-secret").copy(apiBaseUrl = "https://media.example.test/reverse/emby"),
            "movie-e",
        )

        assertEquals(MediaServerItemKind.MOVIE, detail.kind)
        assertTrue(detail.providerIds.isEmpty())
        assertNull(detail.seriesId)
    }

    private fun session(vendor: MediaServerVendor, accessToken: String): MediaServerSession =
        MediaServerSession(
            vendor = vendor,
            connectionId = "connection-1",
            apiBaseUrl = "https://media.example.test/reverse/jellyfin",
            serverId = "server-1",
            serverVersion = "10.11.11",
            userId = "user-1",
            username = "alice",
            accessToken = accessToken,
            client = MediaServerClientIdentity(
                clientName = "UnU Player",
                clientVersion = "0.1.2",
                deviceName = "Android",
                deviceId = "device-1",
            ),
        )
}

internal class RecordingTransport(
    vararg responses: MediaServerHttpResponse,
) : MediaServerTransport {
    private val remaining = ArrayDeque(responses.toList())
    val requests = mutableListOf<MediaServerHttpRequest>()

    override suspend fun execute(request: MediaServerHttpRequest): MediaServerHttpResponse {
        requests += request
        return remaining.removeFirstOrNull() ?: error("测试响应队列已耗尽: ${request.operation}")
    }
}

private fun response(body: String, statusCode: Int = 200): MediaServerHttpResponse =
    MediaServerHttpResponse(statusCode, body)
