package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.core.security.CredentialCipher
import io.github.weiyongzenqi.unuplayer.core.security.PROTECTED_CREDENTIAL_PREFIX
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * preparePlaybackSession 组 [MediaServerDanmakuHint] 测试。
 *
 * 覆盖: EPISODE 二跳查系列 Tmdb / MOVIE 直取 / detail 失败 hint=null 且 plan 仍返回 / 401 重试路径仍工作 /
 * toString 不泄漏 token/url。hint 字段全可空, 失败不影响播放是核心安全契约。
 */
class MediaServerDanmakuHintTest {

    @Test
    fun `EPISODE 二跳查系列 detail 取系列级 Tmdb 并带季集号`() = runBlocking {
        val api = HintTestApi()
        // 第一次 getItemDetail(episode) -> SeriesId=series-1, IndexNumber=3, ParentIndexNumber=1
        // 第二次 getItemDetail(series-1) -> ProviderIds={"Tmdb":"285574"}
        api.detailSequence.addLast(HintTestDetail(
            id = "episode-1", kind = MediaServerItemKind.EPISODE,
            seriesId = "series-1", indexNumber = 3, parentIndexNumber = 1, seriesName = "义妹生活",
        ))
        api.detailSequence.addLast(HintTestDetail(
            id = "series-1", kind = MediaServerItemKind.SERIES,
            providerIds = mapOf("Tmdb" to "285574"),
        ))

        val prepared = openPlayback(api, MediaServerPlaybackRequest(itemId = "episode-1"))

        val hint = prepared.plan.danmakuHint
        assertEquals(285574L, hint?.tmdbId)
        assertEquals(1, hint?.seasonNumber)
        assertEquals(3, hint?.episodeNumber)
        assertEquals("义妹生活", hint?.seriesName)
        // 两次 getItemDetail 调用(episode + series 二跳)
        assertEquals(2, api.detailCalls.size)
        assertEquals("episode-1", api.detailCalls[0])
        assertEquals("series-1", api.detailCalls[1])
    }

    @Test
    fun `MOVIE 直取自身 ProviderIds Tmdb 不二跳`() = runBlocking {
        val api = HintTestApi()
        api.detailSequence.addLast(HintTestDetail(
            id = "movie-1", kind = MediaServerItemKind.MOVIE,
            providerIds = mapOf("Tmdb" to "999999"),
        ))

        val prepared = openPlayback(api, MediaServerPlaybackRequest(itemId = "movie-1"))

        assertEquals(999999L, prepared.plan.danmakuHint?.tmdbId)
        assertEquals(1, api.detailCalls.size)
        assertEquals("movie-1", api.detailCalls.single())
    }

    @Test
    fun `EPISODE seriesId null 不二跳且 tmdbId null`() = runBlocking {
        val api = HintTestApi()
        api.detailSequence.addLast(HintTestDetail(
            id = "episode-2", kind = MediaServerItemKind.EPISODE,
            seriesId = null, indexNumber = 5, parentIndexNumber = 2, seriesName = "某番",
        ))

        val prepared = openPlayback(api, MediaServerPlaybackRequest(itemId = "episode-2"))

        assertNull(prepared.plan.danmakuHint?.tmdbId)
        assertEquals(2, prepared.plan.danmakuHint?.seasonNumber)
        assertEquals(5, prepared.plan.danmakuHint?.episodeNumber)
        assertEquals(1, api.detailCalls.size)
    }

    @Test
    fun `detail 失败 hint null 且 plan 仍返回播放不受影响`() = runBlocking {
        val api = HintTestApi()
        api.detailFailure = true

        val prepared = openPlayback(api, MediaServerPlaybackRequest(itemId = "episode-1"))

        assertNull(prepared.plan.danmakuHint)
        assertEquals("episode-1", prepared.plan.itemId)
        assertEquals("source-1", prepared.plan.mediaSourceId)
        // preparePlayback 仍调用, detail 失败被 runSuspendCatching 吞掉
        assertEquals(1, api.prepareCalls)
        assertEquals(1, api.detailCalls.size)
    }

    @Test
    fun `EPISODE 二跳失败 tmdbId null 但季集号仍保留`() = runBlocking {
        val api = HintTestApi()
        // episode detail 成功(seriesId=series-x, IndexNumber=4, ParentIndexNumber=2)
        api.detailSequence.addLast(HintTestDetail(
            id = "episode-3", kind = MediaServerItemKind.EPISODE,
            seriesId = "series-x", indexNumber = 4, parentIndexNumber = 2,
        ))
        // 二跳 series detail 抛异常 -> runSuspendCatching 吞掉 -> seriesTmdb=null
        api.detailSequence.addLast(HintTestDetail(id = "series-x", kind = MediaServerItemKind.SERIES, fail = true))

        val prepared = openPlayback(api, MediaServerPlaybackRequest(itemId = "episode-3"))

        assertNull(prepared.plan.danmakuHint?.tmdbId)
        assertEquals(2, prepared.plan.danmakuHint?.seasonNumber)
        assertEquals(4, prepared.plan.danmakuHint?.episodeNumber)
    }

    @Test
    fun `preparePlayback 401 重建会话后仍组 hint`() = runBlocking {
        val api = HintTestApi()
        api.prepareFailureStatusCodes.addLast(401) // 首次 preparePlayback 401
        api.detailSequence.addLast(HintTestDetail(
            id = "movie-1", kind = MediaServerItemKind.MOVIE,
            providerIds = mapOf("Tmdb" to "888888"),
        ))

        val prepared = openPlayback(api, MediaServerPlaybackRequest(itemId = "movie-1"))

        assertEquals(888888L, prepared.plan.danmakuHint?.tmdbId)
        // preparePlayback 调了 2 次(首次 401, 重建 session 后重试); detail 1 次(重建后 block 重跑)
        assertEquals(2, api.prepareCalls)
        assertEquals(2, api.publicInfoCalls)
    }

    @Test
    fun `plan toString 不泄漏 token url 且 hint 原样列出`() = runBlocking {
        val api = HintTestApi()
        api.detailSequence.addLast(HintTestDetail(
            id = "movie-1", kind = MediaServerItemKind.MOVIE,
            providerIds = mapOf("Tmdb" to "123456"), seriesName = "某番",
        ))

        val prepared = openPlayback(api, MediaServerPlaybackRequest(itemId = "movie-1"))
        val text = prepared.toString()

        assertFalse(text.contains("session-secret"))
        assertFalse(text.contains("play-session-secret"))
        // hint 是非秘密元数据, toString 应原样列出
        assertTrue(text.contains("123456") || text.contains("danmakuHint="))
        assertFalse(prepared.plan.toString().contains("play-session-secret"))
    }

    @Test
    fun `credentialFreeUrlOrNull 等既有安全断言不回归`() {
        // 既有安全函数: 含 token 的 URL 返回 null; 干净 URL 原样返回
        assertNull(credentialFreeUrlOrNull("/video?api_key=secret", "secret"))
        assertNull(credentialFreeUrlOrNull("/video?token=secret", "secret"))
        assertEquals("/video", credentialFreeUrlOrNull("/video", "secret"))
        assertEquals("/video?x=1", credentialFreeUrlOrNull("/video?x=1", "secret"))
    }

    private suspend fun openPlayback(api: MediaServerApi, request: MediaServerPlaybackRequest): MediaServerPreparedPlayback {
        val repository = MediaServerConnectionRepository(
            HintTestConnectionStore(),
            HintTestCredentialCipher(),
        )
        val service = MediaServerConnectionService(
            repository = repository,
            clientIdentityProvider = MediaServerClientIdentityProvider { testClient() },
            apiFactory = { api },
            connectionIdFactory = { "connection-hint" },
        )
        val summary = service.connect(
            vendor = MediaServerVendor.JELLYFIN,
            name = "家庭媒体库",
            baseUrl = "https://media.example.test/reverse/jellyfin",
            username = "alice",
            password = "password-secret",
        )
        return service.openPlayback(summary.id, request)
    }

    private fun testClient(): MediaServerClientIdentity = MediaServerClientIdentity(
        clientName = "UnU Player",
        clientVersion = "0.1.2",
        deviceName = "Android",
        deviceId = "installation-device",
    )
}

/** 测试用 [MediaServerApi] 桩: preparePlayback 固定 plan, getItemDetail 按 [detailSequence] 出队。 */
private class HintTestApi : MediaServerApi {
    override val vendor: MediaServerVendor = MediaServerVendor.JELLYFIN
    val detailCalls = mutableListOf<String>()
    val detailSequence = ArrayDeque<HintTestDetail>()
    var detailFailure: Boolean = false
    var prepareCalls = 0
    var publicInfoCalls = 0
    val prepareFailureStatusCodes = ArrayDeque<Int>()

    override suspend fun getPublicInfo(baseUrl: String, allowCleartext: Boolean): MediaServerPublicInfo {
        publicInfoCalls++
        return MediaServerPublicInfo(
            vendor = vendor,
            serverId = "server-1",
            serverName = "Jellyfin",
            version = "10.11.11",
            productName = "Jellyfin Server",
            apiBaseUrl = baseUrl.trimEnd('/'),
        )
    }

    override suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String,
        client: MediaServerClientIdentity,
        allowCleartext: Boolean,
    ): MediaServerSession = MediaServerSession(
        vendor = vendor,
        apiBaseUrl = baseUrl.trimEnd('/'),
        serverId = "server-1",
        serverVersion = "10.11.11",
        userId = "user-1",
        username = username,
        accessToken = "session-secret",
        client = client,
    )

    override suspend fun listLibraries(session: MediaServerSession): List<MediaServerLibrary> = error("测试未使用")

    override suspend fun listItems(
        session: MediaServerSession,
        query: MediaServerItemsQuery,
    ): MediaServerPage<MediaServerItem> = error("测试未使用")

    override suspend fun getItemDetail(session: MediaServerSession, itemId: String): MediaServerItemDetail {
        detailCalls += itemId
        if (detailFailure) throw MediaServerHttpException("item-detail", 500)
        val d = detailSequence.removeFirstOrNull() ?: error("测试 detail 序列耗尽: $itemId")
        if (d.fail) throw MediaServerHttpException("item-detail", 500)
        return MediaServerItemDetail(
            id = d.id,
            kind = d.kind,
            providerIds = d.providerIds,
            seriesId = d.seriesId,
            seasonId = d.seasonId,
            seriesName = d.seriesName,
            indexNumber = d.indexNumber,
            parentIndexNumber = d.parentIndexNumber,
        )
    }

    override suspend fun getPlaybackInfo(session: MediaServerSession, request: MediaServerPlaybackRequest): MediaServerPlaybackInfo =
        error("测试未使用")

    override suspend fun preparePlayback(session: MediaServerSession, request: MediaServerPlaybackRequest): MediaServerPlaybackPlan {
        prepareCalls++
        prepareFailureStatusCodes.removeFirstOrNull()?.let { statusCode ->
            throw MediaServerHttpException("prepare-playback", statusCode)
        }
        return MediaServerPlaybackPlan(
            vendor = vendor,
            connectionId = requireNotNull(session.connectionId),
            serverId = session.serverId,
            userId = session.userId,
            itemId = request.itemId,
            mediaSourceId = "source-1",
            playSessionId = "play-session-secret",
            playMethod = MediaServerPlayMethod.DIRECT_PLAY,
            url = "https://media.example.test/Videos/${request.itemId}/stream.mp4?PlaySessionId=play-session-secret",
            headers = mapOf("Authorization" to "MediaBrowser Token=${session.accessToken}"),
            externalSubtitles = emptyList(),
            defaultSubtitleStreamIndex = null,
            initialPositionMs = request.startPositionMs,
        )
    }

    override fun imageRequest(
        session: MediaServerSession,
        itemId: String,
        imageType: MediaServerImageType,
        imageIndex: Int?,
        imageTag: String?,
        maxWidth: Int?,
        maxHeight: Int?,
    ): MediaServerImageRequest = error("测试未使用")

    override suspend fun reportPlaybackStarted(session: MediaServerSession, state: MediaServerPlaybackState) = Unit
    override suspend fun reportPlaybackProgress(session: MediaServerSession, state: MediaServerPlaybackState) = Unit
    override suspend fun reportPlaybackStopped(session: MediaServerSession, state: MediaServerPlaybackState, failed: Boolean) = Unit
    override suspend fun logout(session: MediaServerSession) = Unit
}

private data class HintTestDetail(
    val id: String,
    val kind: MediaServerItemKind,
    val providerIds: Map<String, String> = emptyMap(),
    val seriesId: String? = null,
    val seasonId: String? = null,
    val seriesName: String? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val fail: Boolean = false,
)

private class HintTestConnectionStore : MediaServerConnectionStore {
    var records = emptyList<MediaServerConnection>()
    override suspend fun loadAll(): List<MediaServerConnection> = records
    override suspend fun replaceAll(connections: List<MediaServerConnection>) { records = connections }
}

private class HintTestCredentialCipher : CredentialCipher {
    override fun protect(purpose: String, plaintext: String): String =
        PROTECTED_CREDENTIAL_PREFIX + purpose + " " + plaintext
    override fun unprotect(purpose: String, protectedValue: String): String {
        val payload = protectedValue.removePrefix(PROTECTED_CREDENTIAL_PREFIX)
        require(payload.substringBefore(' ') == purpose) { "purpose 不匹配" }
        return payload.substringAfter(' ')
    }
}
