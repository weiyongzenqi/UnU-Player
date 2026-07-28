package io.github.weiyongzenqi.unuplayer.mediaserver

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaServerPlaybackProtocolTest {

    @Test
    fun `资源 URL 保留服务子路径并拒绝跨源凭据与非 HTTP scheme`() {
        val baseUrl = "https://media.example.test/reverse/jellyfin"
        val relative = resolveMediaServerResourceUrl(
            baseUrl,
            "/Videos/item/Subtitles/2/Stream.ass?format=ass",
            "session-secret",
        )
        val absolute = resolveMediaServerResourceUrl(
            baseUrl,
            "https://MEDIA.example.test/reverse/jellyfin/Videos/item/stream.mkv?static=true",
            "session-secret",
        )

        assertEquals("/reverse/jellyfin/Videos/item/Subtitles/2/Stream.ass", Url(requireNotNull(relative)).encodedPath)
        assertEquals("ass", Url(relative).parameters["format"])
        assertEquals("/reverse/jellyfin/Videos/item/stream.mkv", Url(requireNotNull(absolute)).encodedPath)
        assertNull(
            resolveMediaServerResourceUrl(
                baseUrl,
                "https://media.example.test/Videos/item/stream.mkv",
                "session-secret",
            ),
        )
        assertNull(
            resolveMediaServerResourceUrl(
                baseUrl,
                "https://attacker.example.test/video",
                "session-secret",
            ),
        )
        assertNull(
            resolveMediaServerResourceUrl(
                baseUrl,
                "http://media.example.test/video",
                "session-secret",
            ),
        )
        assertNull(resolveMediaServerResourceUrl(baseUrl, "/video?api_key=secret", "session-secret"))
        assertNull(resolveMediaServerResourceUrl(baseUrl, "/video?%61pi_key=secret", "session-secret"))
        assertNull(resolveMediaServerResourceUrl(baseUrl, "/video?signature=session-secret", "session-secret"))
        assertNull(resolveMediaServerResourceUrl(baseUrl, "/../admin", "session-secret"))
        assertNull(resolveMediaServerResourceUrl(baseUrl, "/%2e%2e/admin", "session-secret"))
        assertNull(resolveMediaServerResourceUrl(baseUrl, "//attacker.example.test/video", "session-secret"))
        assertNull(resolveMediaServerResourceUrl(baseUrl, "javascript:alert(1)", "session-secret"))
        assertNull(resolveMediaServerResourceUrl(baseUrl, "/video#fragment", "session-secret"))
    }

    @Test
    fun `Jellyfin 直放计划构造安全 URL 认证头和同源外挂字幕`() = runBlocking {
        val transport = RecordingMediaServerTransport(
            response(
                """{
                    "PlaySessionId":"play-1",
                    "MediaSources":[{
                      "Id":"source-1","Container":"mkv","SupportsDirectPlay":true,
                      "RequiredHttpHeaders":{
                        "Referer":"https://upstream.example/ref-secret",
                        "Authorization":"Bearer attacker-value",
                        "Host":"attacker.example",
                        "X-Injected":"safe\r\nAuthorization: Bearer attacker-value",
                        "X-Comma":"a,b"
                      },
                      "MediaStreams":[
                        {"Index":2,"Type":"Subtitle","Codec":"ass","Language":"chi","DisplayTitle":"中文",
                         "IsExternal":true,"DeliveryUrl":"/Videos/item-1/Subtitles/2/Stream.ass"},
                        {"Index":3,"Type":"Subtitle","IsExternal":true,
                         "DeliveryUrl":"https://attacker.example.test/subtitle.srt"},
                        {"Index":4,"Type":"Subtitle","IsExternal":true,
                         "DeliveryUrl":"/subtitle?api_key=jf-secret"},
                        {"Index":5,"Type":"Subtitle","IsExternal":false,
                         "DeliveryUrl":"/subtitle/internal"}
                      ]
                    }]
                }""".trimIndent(),
            ),
        )
        val adapter = JellyfinApiAdapter(transport)
        val session = session(MediaServerVendor.JELLYFIN, "jf-secret")

        val plan = adapter.preparePlayback(
            session,
            MediaServerPlaybackRequest(itemId = "item-1", startPositionMs = 12_345),
        )

        val playUrl = Url(plan.url)
        assertEquals(MediaServerPlayMethod.DIRECT_PLAY, plan.playMethod)
        assertEquals("/reverse/jellyfin/Videos/item-1/stream.mkv", playUrl.encodedPath)
        assertEquals("true", playUrl.parameters["Static"])
        assertEquals("source-1", playUrl.parameters["MediaSourceId"])
        assertEquals("play-1", playUrl.parameters["PlaySessionId"])
        assertEquals("device-1", playUrl.parameters["DeviceId"])
        assertFalse(plan.url.contains("jf-secret"))
        assertTrue(requireNotNull(plan.headers["Authorization"]).contains("Token=\"jf-secret\""))
        // mpv http-header-fields 是逗号分隔列表且无转义: 播放头值含逗号会被拆成非法头行,
        // 服务器直接 400(真机 Jellyfin + openresty 实测)。完整 MediaBrowser 形态禁止进播放头。
        plan.headers.forEach { (name, value) ->
            assertFalse(value.contains(','), "播放头 $name 的值不得含逗号(mpv 列表分隔符)")
            assertFalse(value.contains('\r') || value.contains('\n'), "播放头 $name 的值不得含换行")
        }
        assertFalse(plan.headers.containsKey("Content-Type"))
        assertEquals("https://upstream.example/ref-secret", plan.headers["Referer"])
        assertFalse(plan.headers.containsKey("Host"))
        assertFalse(plan.headers.containsKey("X-Injected"))
        // A-04: 含逗号的头值会被 mpv 头列表拆成非法头行(400), 必须滤除
        assertFalse(plan.headers.containsKey("X-Comma"))
        assertEquals(1, plan.externalSubtitles.size)
        assertEquals(
            "/reverse/jellyfin/Videos/item-1/Subtitles/2/Stream.ass",
            Url(plan.externalSubtitles.single().url).encodedPath,
        )
        assertEquals(12_345, plan.initialPositionMs)

        val debugText = plan.toString()
        listOf("jf-secret", "ref-secret", plan.url, plan.externalSubtitles.single().url).forEach { secret ->
            assertFalse(debugText.contains(secret), "播放计划文本泄露: $secret")
        }
    }

    @Test
    fun `无直放版本时保留错误码但异常文本不展开服务端内容`() = runBlocking {
        val transport = RecordingMediaServerTransport(
            response("""{"ErrorCode":"server-secret-error","MediaSources":[]}"""),
        )
        val adapter = JellyfinApiAdapter(transport)

        val error = assertFailsWith<MediaServerPlaybackUnavailableException> {
            adapter.preparePlayback(
                session(MediaServerVendor.JELLYFIN, "jf-secret"),
                MediaServerPlaybackRequest(itemId = "item-1"),
            )
        }

        assertEquals("server-secret-error", error.errorCode)
        assertFalse(error.message.orEmpty().contains("server-secret-error"))
    }

    @Test
    fun `图片请求使用 tag 尺寸和认证头但 URL 与缓存键不含 token`() {
        val adapter = JellyfinApiAdapter(RecordingMediaServerTransport())
        val request = adapter.imageRequest(
            session = session(MediaServerVendor.JELLYFIN, "jf-secret"),
            itemId = "item-1",
            imageType = MediaServerImageType.BACKDROP,
            imageIndex = 1,
            imageTag = "image-tag",
            maxWidth = 1_280,
            maxHeight = 720,
        )
        val url = Url(request.url)
        val reference = buildImageReference(
            vendor = MediaServerVendor.JELLYFIN,
            serverId = "server-1",
            itemId = "item-1",
            imageType = MediaServerImageType.BACKDROP,
            imageIndex = 1,
            imageTag = "image-tag",
            maxWidth = 1_280,
            maxHeight = 720,
        )

        assertEquals("/reverse/jellyfin/Items/item-1/Images/Backdrop/1", url.encodedPath)
        assertEquals("image-tag", url.parameters["tag"])
        assertEquals("1280", url.parameters["maxWidth"])
        assertEquals("720", url.parameters["maxHeight"])
        assertTrue(requireNotNull(request.headers["Authorization"]).contains("jf-secret"))
        assertFalse(request.headers.containsKey("Content-Type"))
        assertFalse(request.url.contains("jf-secret"))
        assertFalse(request.cacheKey.contains("jf-secret"))
        assertEquals(request.cacheKey, reference.cacheKey)
        assertTrue(request.cacheKey.matches(Regex("[A-Za-z0-9_-]+")))
        assertFalse(request.toString().contains("jf-secret"))
        assertFailsWith<IllegalArgumentException> {
            adapter.imageRequest(
                session(MediaServerVendor.JELLYFIN, "jf-secret"),
                itemId = "item-1",
                maxWidth = 4_097,
            )
        }
    }

    @Test
    fun `Jellyfin reporter 串行上报并确保 Stopped 恰好一次`() = runBlocking {
        val transport = RecordingMediaServerTransport(
            response("", 204),
            response("", 204),
            response("", 204),
            response("", 204),
        )
        val adapter = JellyfinApiAdapter(transport)
        val session = session(MediaServerVendor.JELLYFIN, "jf-secret")
        val reporter = MediaServerSessionReporter(adapter, session)
        val state = playbackState()

        assertTrue(reporter.reportStarted(state))
        assertFalse(reporter.reportStarted(state))
        assertTrue(reporter.reportProgress(state.copy(positionMs = 2_000, isPaused = true)))
        val stopped = listOf(
            async(Dispatchers.Default) { reporter.reportStopped(state.copy(positionMs = 3_000)) },
            async(Dispatchers.Default) { reporter.reportStopped(state.copy(positionMs = 4_000)) },
        ).awaitAll()
        assertEquals(1, stopped.count { it })
        assertFalse(reporter.reportProgress(state.copy(positionMs = 5_000)))
        adapter.logout(session)

        assertEquals(4, transport.requests.size)
        assertEquals("/reverse/jellyfin/Sessions/Playing", pathOf(transport.requests[0]))
        assertEquals("/reverse/jellyfin/Sessions/Playing/Progress", pathOf(transport.requests[1]))
        assertEquals("/reverse/jellyfin/Sessions/Playing/Stopped", pathOf(transport.requests[2]))
        assertEquals("/reverse/jellyfin/Sessions/Logout", pathOf(transport.requests[3]))
        assertTrue(requireNotNull(transport.requests[0].body).contains("\"PositionTicks\":12340000"))
        assertTrue(requireNotNull(transport.requests[0].body).contains("\"PlayMethod\":\"DirectPlay\""))
        assertTrue(requireNotNull(transport.requests[1].body).contains("\"IsPaused\":true"))
        assertTrue(requireNotNull(transport.requests[2].body).contains("\"Failed\":false"))
        assertNull(transport.requests[3].body)
    }

    @Test
    fun `非幂等开始报告失败后 reporter 不自动重试`() = runBlocking {
        val transport = RecordingMediaServerTransport(
            response("failure", 503),
            response("", 204),
        )
        val reporter = MediaServerSessionReporter(
            JellyfinApiAdapter(transport),
            session(MediaServerVendor.JELLYFIN, "jf-secret"),
        )

        assertFailsWith<MediaServerHttpException> { reporter.reportStarted(playbackState()) }
        assertFalse(reporter.reportStarted(playbackState()))
        assertFalse(reporter.reportProgress(playbackState()))
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `播放报告调度器每十秒上报且暂停时只等待即时事件`() = runBlocking {
        val transport = RecordingMediaServerTransport(
            response("", 204),
            response("", 204),
            response("", 204),
            response("", 204),
        )
        var state = playbackState().copy(positionMs = 0)
        var tick = 0
        val coordinator = MediaServerPlaybackReportCoordinator(
            reporter = MediaServerSessionReporter(
                JellyfinApiAdapter(transport),
                session(MediaServerVendor.JELLYFIN, "jf-secret"),
            ),
            awaitInterval = { interval ->
                assertEquals(10_000L, interval)
                tick++
                state = when (tick) {
                    1 -> state.copy(positionMs = 10_000, isPaused = false)
                    2 -> state.copy(positionMs = 20_000, isPaused = true)
                    3 -> state.copy(positionMs = 30_000, isPaused = false)
                    else -> throw CancellationException("测试结束")
                }
            },
        )

        assertFailsWith<CancellationException> {
            coordinator.runPeriodic(
                currentState = { state },
                startedState = { state.copy(positionMs = 12_345) },
            )
        }
        assertTrue(coordinator.reportNow(state.copy(positionMs = 40_000, isPaused = true)).getOrThrow())

        assertEquals(4, transport.requests.size)
        assertEquals("/reverse/jellyfin/Sessions/Playing", pathOf(transport.requests[0]))
        assertTrue(requireNotNull(transport.requests[0].body).contains("\"PositionTicks\":123450000"))
        assertTrue(requireNotNull(transport.requests[1].body).contains("\"PositionTicks\":100000000"))
        assertTrue(requireNotNull(transport.requests[2].body).contains("\"PositionTicks\":300000000"))
        assertTrue(requireNotNull(transport.requests[3].body).contains("\"IsPaused\":true"))
        Unit
    }

    @Test
    fun `周期报告失败不终止后续上报且停止仍最多尝试一次`() = runBlocking {
        val transport = RecordingMediaServerTransport(
            response("", 204),
            response("failure", 503),
            response("", 204),
            response("", 204),
        )
        var state = playbackState()
        var tick = 0
        val failures = mutableListOf<Throwable>()
        val coordinator = MediaServerPlaybackReportCoordinator(
            reporter = MediaServerSessionReporter(
                JellyfinApiAdapter(transport),
                session(MediaServerVendor.JELLYFIN, "jf-secret"),
            ),
            intervalMillis = 1,
            awaitInterval = {
                tick++
                if (tick > 2) throw CancellationException("测试结束")
                state = state.copy(positionMs = tick * 10_000L)
            },
        )

        assertFailsWith<CancellationException> {
            coordinator.runPeriodic(currentState = { state }, onFailure = failures::add)
        }
        assertEquals(1, failures.size)
        assertTrue(failures.single() is MediaServerHttpException)
        assertTrue(coordinator.reportStopped(state.copy(positionMs = 25_000)).getOrThrow())
        assertFalse(coordinator.reportStopped(state.copy(positionMs = 26_000)).getOrThrow())

        assertEquals(4, transport.requests.size)
        assertEquals("/reverse/jellyfin/Sessions/Playing/Progress", pathOf(transport.requests[1]))
        assertEquals("/reverse/jellyfin/Sessions/Playing/Progress", pathOf(transport.requests[2]))
        assertEquals("/reverse/jellyfin/Sessions/Playing/Stopped", pathOf(transport.requests[3]))
    }

    @Test
    fun `开始报告失败后调度器不进入周期等待`() = runBlocking {
        val transport = RecordingMediaServerTransport(response("failure", 503))
        val failures = mutableListOf<Throwable>()
        var waitCount = 0
        val coordinator = MediaServerPlaybackReportCoordinator(
            reporter = MediaServerSessionReporter(
                JellyfinApiAdapter(transport),
                session(MediaServerVendor.JELLYFIN, "jf-secret"),
            ),
            awaitInterval = { waitCount++ },
        )

        coordinator.runPeriodic(currentState = ::playbackState, onFailure = failures::add)

        assertEquals(0, waitCount)
        assertEquals(1, transport.requests.size)
        assertTrue(failures.single() is MediaServerHttpException)
    }

    @Test
    fun `即时报告保留 transport 取消传播`() = runBlocking {
        val transport = MediaServerTransport { request ->
            if (request.operation == "jellyfin.progress") {
                throw CancellationException("调用方已取消")
            }
            response("", 204)
        }
        val coordinator = MediaServerPlaybackReportCoordinator(
            reporter = MediaServerSessionReporter(
                JellyfinApiAdapter(transport),
                session(MediaServerVendor.JELLYFIN, "jf-secret"),
            ),
            awaitInterval = { throw CancellationException("测试结束") },
        )

        assertFailsWith<CancellationException> {
            coordinator.runPeriodic(currentState = ::playbackState)
        }
        assertFailsWith<CancellationException> {
            coordinator.reportNow(playbackState())
        }
        Unit
    }

    @Test
    fun `失败回调取消在开始和周期报告中均向上传播`() = runBlocking {
        val startedCoordinator = MediaServerPlaybackReportCoordinator(
            reporter = MediaServerSessionReporter(
                JellyfinApiAdapter(RecordingMediaServerTransport(response("failure", 503))),
                session(MediaServerVendor.JELLYFIN, "jf-secret"),
            ),
        )
        assertFailsWith<CancellationException> {
            startedCoordinator.runPeriodic(
                currentState = ::playbackState,
                onFailure = { throw CancellationException("开始失败回调取消") },
            )
        }

        val progressCoordinator = MediaServerPlaybackReportCoordinator(
            reporter = MediaServerSessionReporter(
                JellyfinApiAdapter(
                    RecordingMediaServerTransport(
                        response("", 204),
                        response("failure", 503),
                    ),
                ),
                session(MediaServerVendor.JELLYFIN, "jf-secret"),
            ),
            awaitInterval = {},
        )
        assertFailsWith<CancellationException> {
            progressCoordinator.runPeriodic(
                currentState = ::playbackState,
                onFailure = { throw CancellationException("周期失败回调取消") },
            )
        }
        Unit
    }

    @Test
    fun `Emby 图片进度与注销使用独立 API 根和 token 头`() = runBlocking {
        val transport = RecordingMediaServerTransport(response("", 204), response("", 204))
        val adapter = EmbyApiAdapter(transport)
        val session = session(MediaServerVendor.EMBY, "emby-secret").copy(
            apiBaseUrl = "https://media.example.test/reverse/emby",
        )
        val image = adapter.imageRequest(
            session = session,
            itemId = "item-e",
            imageTag = "tag-e",
            maxWidth = 600,
        )

        adapter.reportPlaybackProgress(session, playbackState())
        adapter.logout(session)

        assertEquals("/reverse/emby/Items/item-e/Images/Primary", Url(image.url).encodedPath)
        assertEquals("emby-secret", image.headers["X-Emby-Token"])
        assertEquals("/reverse/emby/Sessions/Playing/Progress", pathOf(transport.requests[0]))
        assertEquals("emby-secret", transport.requests[0].headers["X-Emby-Token"])
        assertEquals("/reverse/emby/Sessions/Logout", pathOf(transport.requests[1]))
    }

    @Test
    fun `Emby 直放与开始停止报告使用独立契约`() = runBlocking {
        val transport = RecordingMediaServerTransport(
            response(
                """{
                    "PlaySessionId":"emby-play",
                    "MediaSources":[{
                      "Id":"emby-source","Container":"mp4","SupportsDirectPlay":true,
                      "RequiredHttpHeaders":{"Referer":"https://upstream.example/source"}
                    }]
                }""".trimIndent(),
            ),
            response("", 204),
            response("", 204),
        )
        val adapter = EmbyApiAdapter(transport)
        val session = session(MediaServerVendor.EMBY, "emby-secret").copy(
            apiBaseUrl = "https://media.example.test/reverse/emby",
        )

        val plan = adapter.preparePlayback(session, MediaServerPlaybackRequest("item-e"))
        adapter.reportPlaybackStarted(session, playbackState().copy(itemId = "item-e"))
        adapter.reportPlaybackStopped(
            session,
            playbackState().copy(itemId = "item-e", positionMs = 9_000),
            failed = true,
        )

        assertEquals("/reverse/emby/Videos/item-e/stream.mp4", Url(plan.url).encodedPath)
        assertEquals("emby-secret", plan.headers["X-Emby-Token"])
        assertEquals("https://upstream.example/source", plan.headers["Referer"])
        assertEquals("/reverse/emby/Sessions/Playing", pathOf(transport.requests[1]))
        assertEquals("/reverse/emby/Sessions/Playing/Stopped", pathOf(transport.requests[2]))
        assertTrue(requireNotNull(transport.requests[2].body).contains("\"Failed\":true"))
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

    private fun playbackState(): MediaServerPlaybackState = MediaServerPlaybackState(
        itemId = "item-1",
        mediaSourceId = "source-1",
        playSessionId = "play-1",
        playMethod = MediaServerPlayMethod.DIRECT_PLAY,
        positionMs = 1_234,
        audioStreamIndex = 1,
        subtitleStreamIndex = 2,
        isPaused = false,
        isMuted = false,
    )

    private fun pathOf(request: MediaServerHttpRequest): String = Url(request.url).encodedPath

    private fun response(body: String, statusCode: Int = 200): MediaServerHttpResponse =
        MediaServerHttpResponse(statusCode, body)
}

private class RecordingMediaServerTransport(
    vararg responses: MediaServerHttpResponse,
) : MediaServerTransport {
    private val remaining = ArrayDeque(responses.toList())
    val requests = mutableListOf<MediaServerHttpRequest>()

    override suspend fun execute(request: MediaServerHttpRequest): MediaServerHttpResponse {
        requests += request
        return remaining.removeFirstOrNull() ?: error("测试响应队列已耗尽: ${request.operation}")
    }
}
