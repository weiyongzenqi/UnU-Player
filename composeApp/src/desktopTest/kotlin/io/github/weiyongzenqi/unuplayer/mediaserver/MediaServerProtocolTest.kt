package io.github.weiyongzenqi.unuplayer.mediaserver

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MediaServerProtocolTest {

    @Test
    fun `分页游标使用服务端原始条目数而不是过滤后的有效条目数`() {
        val page = MediaServerPage(
            items = listOf("valid"),
            startIndex = 20,
            limit = 2,
            totalRecordCount = 50,
            returnedItemCount = 2,
        )

        assertEquals(22, page.nextStartIndex)
        assertTrue(page.hasMore)
    }

    @Test
    fun `过滤后空页仍可前进而服务端零返回会停止分页`() {
        val filteredPage = MediaServerPage(
            items = emptyList<String>(),
            startIndex = 20,
            limit = 2,
            totalRecordCount = 50,
            returnedItemCount = 2,
        )
        val emptyPage = MediaServerPage(
            items = emptyList<String>(),
            startIndex = 22,
            limit = 2,
            totalRecordCount = 50,
            returnedItemCount = 0,
        )

        assertEquals(22, filteredPage.nextStartIndex)
        assertTrue(filteredPage.hasMore)
        assertEquals(22, emptyPage.nextStartIndex)
        assertFalse(emptyPage.hasMore)
    }

    @Test
    fun `服务地址保留子路径并为 Emby 补充 API 根`() {
        val jellyfin = validateMediaServerBaseUrl(
            " https://media.example.test/reverse/jellyfin/ ",
            MediaServerVendor.JELLYFIN,
        )
        val emby = validateMediaServerBaseUrl(
            "https://media.example.test/reverse",
            MediaServerVendor.EMBY,
        )
        val existingEmby = validateMediaServerBaseUrl(
            "https://media.example.test/reverse/Emby/",
            MediaServerVendor.EMBY,
        )

        assertEquals("https://media.example.test/reverse/jellyfin", jellyfin.normalizedApiBaseUrl)
        assertEquals("https://media.example.test/reverse/emby", emby.normalizedApiBaseUrl)
        assertEquals("https://media.example.test/reverse/Emby", existingEmby.normalizedApiBaseUrl)
        assertEquals(
            "https://media.example.test/reverse/jellyfin/System/Info/Public",
            buildMediaServerUrl(
                requireNotNull(jellyfin.normalizedApiBaseUrl),
                listOf("System", "Info", "Public"),
            ),
        )
    }

    @Test
    fun `服务地址拒绝隐式 scheme 和凭据参数且标记明文连接`() {
        assertFalse(validateMediaServerBaseUrl("media.example.test", MediaServerVendor.JELLYFIN).isValid)
        assertFalse(
            validateMediaServerBaseUrl(
                "https://user:password@media.example.test",
                MediaServerVendor.JELLYFIN,
            ).isValid,
        )
        assertFalse(
            validateMediaServerBaseUrl(
                "https://media.example.test?api_key=secret",
                MediaServerVendor.JELLYFIN,
            ).isValid,
        )
        assertFalse(
            validateMediaServerBaseUrl(
                "https://media.example.test/#fragment",
                MediaServerVendor.JELLYFIN,
            ).isValid,
        )
        assertTrue(
            validateMediaServerBaseUrl(
                "http://media.example.test",
                MediaServerVendor.JELLYFIN,
            ).requiresCleartextConfirmation,
        )
    }

    @Test
    fun `adapter 默认拒绝 HTTP 且只在明确授权后探测`() = runBlocking {
        val transport = QueuedMediaServerTransport(
            response(
                """{"Id":"jf-server","ServerName":"家庭服","Version":"10.11.11","ProductName":"Jellyfin Server"}""",
            ),
        )
        val adapter = JellyfinApiAdapter(transport)

        assertFailsWith<IllegalArgumentException> {
            adapter.getPublicInfo("http://media.example.test")
        }
        assertTrue(transport.requests.isEmpty())

        val publicInfo = adapter.getPublicInfo(
            baseUrl = "http://media.example.test",
            allowCleartext = true,
        )

        assertEquals("jf-server", publicInfo.serverId)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `Jellyfin 厂商识别失败时不会继续发送登录密码`() = runBlocking {
        val transport = QueuedMediaServerTransport(
            response(
                """{"Id":"emby-server","ServerName":"Emby","Version":"4.8","ProductName":"Emby Server"}""",
            ),
        )
        val adapter = JellyfinApiAdapter(transport)

        assertFailsWith<MediaServerProtocolException> {
            adapter.authenticate(
                "https://media.example.test",
                "alice",
                "must-not-send",
                testClient(),
            )
        }

        assertEquals(1, transport.requests.size)
        assertEquals(MediaServerHttpMethod.GET, transport.requests.single().method)
        assertNull(transport.requests.single().body)
    }

    @Test
    fun `认证头按厂商规则编码且匿名 Jellyfin 头不携带 Token`() {
        val client = testClient()
        val anonymous = jellyfinAuthorization(client)
        val authenticated = jellyfinAuthorization(client, "token value")
        val emby = embyAuthorization(client, "user id")

        assertTrue(anonymous.startsWith("MediaBrowser Client=\"UnU%20Player\""))
        assertTrue(anonymous.contains("Device=\"Android%20%E5%AE%A2%E5%8E%85\""))
        assertFalse(anonymous.contains("Token="))
        assertTrue(authenticated.contains("Token=\"token%20value\""))
        assertTrue(emby.startsWith("Emby Client=\"UnU%20Player\""))
        assertTrue(emby.contains("UserId=\"user%20id\""))
        assertFalse(emby.contains("Token="))
    }

    @Test
    fun `Jellyfin 完成匿名探测登录分页与播放信息映射`() = runBlocking {
        val transport = QueuedMediaServerTransport(
            response(
                """{"Id":"jf-server","ServerName":"家庭服","Version":"10.11.11","ProductName":"Jellyfin Server","Future":true}""",
            ),
            response(
                """{"AccessToken":"jf-secret","ServerId":"jf-server","User":{"Id":"jf-user","Name":"alice"}}""",
            ),
            response(
                """{"Items":[{"Id":"lib-1","Name":"动画","CollectionType":"tvshows","ImageTags":{"Primary":"tag-1"}}]}""",
            ),
            response(
                """{
                    "Items":[
                      {"Id":"episode-1","Name":"第一话","Type":"Episode","RunTimeTicks":123450000,
                       "UserData":{"PlaybackPositionTicks":50000000,"Played":false,"PlayedPercentage":40.5},
                       "UnknownObject":{"Value":1}},
                      {"Id":"future-1","Name":"未来类型","Type":"FutureVideo"}
                    ],
                    "TotalRecordCount":50,"StartIndex":20,"FutureField":"ignored"
                }""".trimIndent(),
            ),
            response(
                """{
                    "PlaySessionId":"play-jf","ErrorCode":"NoCompatibleStream",
                    "MediaSources":[
                      {"Id":"source-safe","Name":"原画","Container":"mkv","RunTimeTicks":123450000,
                       "SupportsDirectPlay":true,"SupportsDirectStream":true,"SupportsTranscoding":false,
                       "DirectStreamUrl":"/Videos/episode-1/stream.mkv?static=true",
                       "RequiredHttpHeaders":{"Referer":"https://upstream.example/secret"},
                       "MediaStreams":[
                         {"Index":2,"Type":"Subtitle","Codec":"ass","Language":"chi","IsExternal":true,
                          "DeliveryMethod":"External","DeliveryUrl":"/Videos/episode-1/Subtitles/2/Stream.ass",
                          "SupportsExternalStream":true},
                         {"Index":-1,"Type":"Subtitle"}
                       ]},
                      {"Id":"source-tokenized","SupportsDirectPlay":true,
                       "DirectStreamUrl":"/Videos/episode-1/stream?api_key=jf-secret",
                       "TranscodingUrl":"/Videos/episode-1/master.m3u8?access_token=other-secret",
                       "MediaStreams":[{"Index":3,"DeliveryUrl":"/subtitle?ApiKey=jf-secret"}]}
                    ]
                }""".trimIndent(),
            ),
        )
        val adapter = JellyfinApiAdapter(transport)

        val session = adapter.authenticate(
            "https://media.example.test/jellyfin/",
            " alice ",
            "password-secret",
            testClient(),
        )
        val libraries = adapter.listLibraries(session)
        val page = adapter.listItems(
            session,
            MediaServerItemsQuery(
                parentId = "lib-1",
                startIndex = 20,
                limit = 2,
                recursive = true,
                includeItemTypes = setOf(MediaServerItemKind.EPISODE, MediaServerItemKind.UNKNOWN),
                searchTerm = " 第一 ",
            ),
        )
        val playback = adapter.getPlaybackInfo(
            session,
            MediaServerPlaybackRequest(itemId = "episode-1", startPositionMs = 1_200),
        )

        assertEquals("jf-server", session.serverId)
        assertEquals("alice", session.username)
        assertEquals("lib-1", libraries.single().id)
        assertEquals(20, page.startIndex)
        assertEquals(50, page.totalRecordCount)
        assertTrue(page.hasMore)
        assertEquals(12_345, page.items.first().runTimeMs)
        assertEquals(5_000, page.items.first().userData?.playbackPositionMs)
        assertEquals(MediaServerItemKind.UNKNOWN, page.items.last().kind)
        assertEquals("play-jf", playback.playSessionId)
        assertEquals("NoCompatibleStream", playback.errorCode)
        assertEquals(2, playback.mediaSources.size)
        assertEquals(1, playback.mediaSources.first().mediaStreams.size)
        assertNull(playback.mediaSources.last().directStreamUrl)
        assertNull(playback.mediaSources.last().transcodingUrl)
        assertNull(playback.mediaSources.last().mediaStreams.single().deliveryUrl)

        assertEquals(5, transport.requests.size)
        assertEquals("/jellyfin/System/Info/Public", pathOf(transport.requests[0]))
        assertFalse(transport.requests[0].headers.values.any { "jf-secret" in it })
        assertEquals("/jellyfin/Users/AuthenticateByName", pathOf(transport.requests[1]))
        assertFalse(requireNotNull(transport.requests[1].headers["Authorization"]).contains("Token="))
        assertEquals("/jellyfin/UserViews", pathOf(transport.requests[2]))
        assertEquals("jf-user", Url(transport.requests[2].url).parameters["UserId"])
        assertEquals("/jellyfin/Items", pathOf(transport.requests[3]))
        assertEquals("Episode", Url(transport.requests[3].url).parameters["IncludeItemTypes"])
        assertEquals("第一", Url(transport.requests[3].url).parameters["SearchTerm"])
        assertEquals("/jellyfin/Items/episode-1/PlaybackInfo", pathOf(transport.requests[4]))
        assertTrue(requireNotNull(transport.requests[4].headers["Authorization"]).contains("Token=\"jf-secret\""))
        assertTrue(requireNotNull(transport.requests[4].body).contains("\"StartTimeTicks\":12000000"))
        assertTrue(requireNotNull(transport.requests[4].body).contains("\"EnableDirectStream\":false"))
        assertTrue(requireNotNull(transport.requests[4].body).contains("\"EnableTranscoding\":false"))
    }

    @Test
    fun `Emby 使用独立 API 根用户路径与认证头`() = runBlocking {
        val transport = QueuedMediaServerTransport(
            response(
                """{"Id":"emby-server","ServerName":"Emby Home","Version":"4.8.11","ProductName":"Emby Server"}""",
            ),
            response(
                """{"AccessToken":"emby-secret","ServerId":"emby-server","User":{"Id":"emby-user","Name":"bob"}}""",
            ),
            response("""{"Items":[{"Id":"lib-e","Name":"电影","CollectionType":"movies"}]}"""),
            response(
                """{"Items":[{"Id":"movie-e","Name":"电影一","Type":"Movie","RunTimeTicks":90000000}],"StartIndex":0}""",
            ),
            response(
                """{"PlaySessionId":"play-e","MediaSources":[{"Id":"source-e","SupportsDirectPlay":true,
                    "DirectStreamUrl":"/emby/Videos/movie-e/stream.mp4?static=true",
                    "MediaStreams":[{"Index":1,"Type":"Audio","Codec":"aac"}]}]}""",
            ),
        )
        val adapter = EmbyApiAdapter(transport)

        val session = adapter.authenticate(
            "https://emby.example.test/proxy",
            "bob",
            "emby-password",
            testClient(),
        )
        val libraries = adapter.listLibraries(session)
        val page = adapter.listItems(session, MediaServerItemsQuery(limit = 1))
        val playback = adapter.getPlaybackInfo(
            session,
            MediaServerPlaybackRequest(itemId = "movie-e"),
        )

        assertEquals("https://emby.example.test/proxy/emby", session.apiBaseUrl)
        assertEquals("lib-e", libraries.single().id)
        assertEquals(MediaServerItemKind.MOVIE, page.items.single().kind)
        assertTrue(page.hasMore)
        assertEquals("play-e", playback.playSessionId)
        assertEquals("source-e", playback.mediaSources.single().id)

        assertEquals("/proxy/emby/System/Info/Public", pathOf(transport.requests[0]))
        assertEquals("/proxy/emby/Users/AuthenticateByName", pathOf(transport.requests[1]))
        assertTrue(transport.requests[1].headers.containsKey("X-Emby-Authorization"))
        assertFalse(transport.requests[1].headers.containsKey("X-Emby-Token"))
        assertEquals("/proxy/emby/Users/emby-user/Views", pathOf(transport.requests[2]))
        assertEquals("emby-secret", transport.requests[2].headers["X-Emby-Token"])
        assertEquals("/proxy/emby/Users/emby-user/Items", pathOf(transport.requests[3]))
        assertEquals("/proxy/emby/Items/movie-e/PlaybackInfo", pathOf(transport.requests[4]))
        assertTrue(requireNotNull(transport.requests[4].body).contains("\"EnableDirectStream\":false"))
    }

    @Test
    fun `请求响应会话与异常文本不泄露凭据`() = runBlocking {
        val request = MediaServerHttpRequest(
            operation = "test.secret",
            method = MediaServerHttpMethod.POST,
            url = "https://media.example.test/Items?api_key=url-secret",
            headers = mapOf("X-Emby-Token" to "header-secret"),
            body = "{\"Pw\":\"body-secret\"}",
        )
        val response = MediaServerHttpResponse(200, "{\"AccessToken\":\"response-secret\"}")
        val session = testSession(
            vendor = MediaServerVendor.JELLYFIN,
            accessToken = "session-secret",
            apiBaseUrl = "https://media.example.test?api_key=session-url-secret",
        )
        val source = MediaServerMediaSource(
            id = "source-1",
            name = null,
            container = null,
            runTimeMs = null,
            supportsDirectPlay = true,
            supportsDirectStream = false,
            supportsTranscoding = false,
            directStreamUrl = "/video?api_key=source-url-secret",
            transcodingUrl = null,
            requiredHttpHeaders = mapOf("Authorization" to "source-header-secret"),
            defaultAudioStreamIndex = null,
            defaultSubtitleStreamIndex = null,
            mediaStreams = listOf(
                MediaServerMediaStream(
                    index = 0,
                    type = "Subtitle",
                    codec = "srt",
                    language = null,
                    displayTitle = null,
                    isExternal = true,
                    deliveryMethod = "External",
                    deliveryUrl = "/subtitle?api_key=subtitle-url-secret",
                    supportsExternalStream = true,
                ),
            ),
        )

        listOf(request.toString(), response.toString(), session.toString(), source.toString()).forEach { text ->
            listOf(
                "url-secret",
                "header-secret",
                "body-secret",
                "response-secret",
                "session-secret",
                "session-url-secret",
                "source-url-secret",
                "source-header-secret",
                "subtitle-url-secret",
            )
                .forEach { secret -> assertFalse(text.contains(secret), "toString 泄露敏感文本: $secret") }
        }

        val adapter = JellyfinApiAdapter(
            QueuedMediaServerTransport(response("{\"AccessToken\":\"parse-secret\"")),
        )
        val exception = assertFailsWith<MediaServerProtocolException> {
            adapter.getPublicInfo("https://media.example.test")
        }
        assertFalse(exception.message.orEmpty().contains("parse-secret"))
    }

    @Test
    fun `adapter 保留协程取消传播`() = runBlocking {
        val cancellation = CancellationException("cancel-secret")
        val adapter = JellyfinApiAdapter(MediaServerTransport { throw cancellation })

        val thrown = assertFailsWith<CancellationException> {
            adapter.getPublicInfo("https://media.example.test")
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `ticks 转换检查边界且负服务端值归零`() {
        assertEquals(1, ticksToMilliseconds(19_999))
        assertEquals(0, ticksToMilliseconds(-1))
        assertEquals(10_000, millisecondsToTicks(1))
        assertFailsWith<IllegalArgumentException> { millisecondsToTicks(-1) }
        assertFailsWith<IllegalArgumentException> {
            millisecondsToTicks(Long.MAX_VALUE / 10_000 + 1)
        }
    }

    private fun pathOf(request: MediaServerHttpRequest): String = Url(request.url).encodedPath

    private fun response(body: String, statusCode: Int = 200): MediaServerHttpResponse =
        MediaServerHttpResponse(statusCode, body)

    private fun testClient(): MediaServerClientIdentity = MediaServerClientIdentity(
        clientName = "UnU Player",
        clientVersion = "0.1.2",
        deviceName = "Android 客厅",
        deviceId = "device-1",
    )

    private fun testSession(
        vendor: MediaServerVendor,
        accessToken: String,
        apiBaseUrl: String = "https://media.example.test",
    ): MediaServerSession =
        MediaServerSession(
            vendor = vendor,
            apiBaseUrl = apiBaseUrl,
            serverId = "server-1",
            serverVersion = "1.0",
            userId = "user-1",
            username = "alice",
            accessToken = accessToken,
            client = testClient(),
        )
}

private class QueuedMediaServerTransport(
    vararg responses: MediaServerHttpResponse,
) : MediaServerTransport {
    private val remaining = ArrayDeque(responses.toList())
    val requests = mutableListOf<MediaServerHttpRequest>()

    override suspend fun execute(request: MediaServerHttpRequest): MediaServerHttpResponse {
        requests += request
        return remaining.removeFirstOrNull() ?: error("测试响应队列已耗尽: ${request.operation}")
    }
}
