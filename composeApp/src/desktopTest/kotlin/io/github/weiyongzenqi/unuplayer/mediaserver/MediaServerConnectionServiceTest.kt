package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.core.security.CredentialCipher
import io.github.weiyongzenqi.unuplayer.core.security.PROTECTED_CREDENTIAL_PREFIX
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaServerConnectionServiceTest {

    @Test
    fun `连接服务只暴露摘要且目录源先复核服务器身份再释放 token`() = runBlocking {
        val store = ServiceTestConnectionStore()
        val repository = MediaServerConnectionRepository(store, ServiceTestCredentialCipher())
        val api = ServiceTestMediaServerApi()
        val service = MediaServerConnectionService(
            repository = repository,
            clientIdentityProvider = MediaServerClientIdentityProvider { testClient() },
            apiFactory = { api },
            connectionIdFactory = { "connection-1" },
        )

        val summary = service.connect(
            vendor = MediaServerVendor.JELLYFIN,
            name = "家庭媒体库",
            baseUrl = "https://media.example.test/reverse/jellyfin",
            username = "alice",
            password = "password-secret",
        )

        assertEquals("connection-1", summary.id)
        assertEquals("家庭媒体库", summary.name)
        assertEquals("alice", summary.username)
        assertFalse(summary.toString().contains("session-secret"))
        assertFalse(summary.toString().contains("media.example.test"))
        assertTrue(store.records.single().accessToken.startsWith(PROTECTED_CREDENTIAL_PREFIX))

        val catalog = service.openCatalog(summary.id)
        val libraries = catalog.listLibraries()

        assertEquals("library-1", libraries.single().id)
        assertEquals(listOf("authenticate", "public-info", "list-libraries"), api.operations)
        assertEquals("connection-1", api.librarySession?.connectionId)
        assertEquals("session-secret", api.librarySession?.accessToken)

        val remaining = service.remove(summary.id)
        assertTrue(remaining.isEmpty())
        assertTrue(repository.loadAll().isEmpty())
        // A-05: 删除先 best-effort logout(匿名探测复核身份 + 注销), 服务端不留僵尸 token。
        assertEquals(
            listOf("authenticate", "public-info", "list-libraries", "public-info", "logout"),
            api.operations,
        )
        assertEquals("session-secret", api.logoutSession?.accessToken)
    }

    @Test
    fun `注销会话失败不阻断连接删除 A-05`() = runBlocking {
        val repository = MediaServerConnectionRepository(
            ServiceTestConnectionStore(),
            ServiceTestCredentialCipher(),
        )
        val api = ServiceTestMediaServerApi()
        val service = MediaServerConnectionService(
            repository = repository,
            clientIdentityProvider = MediaServerClientIdentityProvider { testClient() },
            apiFactory = { api },
            connectionIdFactory = { "connection-remove-fail" },
        )
        val summary = service.connect(
            vendor = MediaServerVendor.JELLYFIN,
            name = "家庭媒体库",
            baseUrl = "https://media.example.test/reverse/jellyfin",
            username = "alice",
            password = "password-secret",
        )
        // 服务端不可达/报错时 logout 失败, 但用户删除意图优先: 本地连接必须删掉。
        api.logoutFailureStatusCodes.addLast(503)

        val remaining = service.remove(summary.id)

        assertTrue(remaining.isEmpty())
        assertTrue(repository.loadAll().isEmpty())
        assertTrue(api.operations.contains("logout"))
    }

    @Test
    fun `注销会话悬挂超过 deadline 仍完成本地删除 A-05`() = runBlocking {
        val repository = MediaServerConnectionRepository(
            ServiceTestConnectionStore(),
            ServiceTestCredentialCipher(),
        )
        val api = ServiceTestMediaServerApi()
        val service = MediaServerConnectionService(
            repository = repository,
            clientIdentityProvider = MediaServerClientIdentityProvider { testClient() },
            apiFactory = { api },
            connectionIdFactory = { "connection-remove-timeout" },
            removeLogoutTimeoutMs = 25L,
        )
        val summary = service.connect(
            vendor = MediaServerVendor.JELLYFIN,
            name = "家庭媒体库",
            baseUrl = "https://media.example.test/reverse/jellyfin",
            username = "alice",
            password = "password-secret",
        )
        api.suspendLogout = true

        val remaining = withTimeout(1_000L) { service.remove(summary.id) }

        assertTrue(remaining.isEmpty())
        assertTrue(repository.loadAll().isEmpty())
        assertTrue(api.operations.contains("logout"))
    }

    @Test
    fun `服务器身份已变更时删除跳过注销仍成功 A-05`() = runBlocking {
        val repository = MediaServerConnectionRepository(
            ServiceTestConnectionStore(),
            ServiceTestCredentialCipher(),
        )
        val api = ServiceTestMediaServerApi()
        val service = MediaServerConnectionService(
            repository = repository,
            clientIdentityProvider = MediaServerClientIdentityProvider { testClient() },
            apiFactory = { api },
            connectionIdFactory = { "connection-remove-identity" },
        )
        val summary = service.connect(
            vendor = MediaServerVendor.JELLYFIN,
            name = "家庭媒体库",
            baseUrl = "https://media.example.test/reverse/jellyfin",
            username = "alice",
            password = "password-secret",
        )
        // 地址复用为新服务器: createSession 身份复核失败 -> 不向陌生服务器发 token, 删除照常。
        api.publicServerId = "replaced-server"

        val remaining = service.remove(summary.id)

        assertTrue(remaining.isEmpty())
        assertTrue(repository.loadAll().isEmpty())
        assertEquals(null, api.logoutSession)
    }

    @Test
    fun `目录源发现服务器身份变化时不会向 API 释放保存 token`() = runBlocking {
        val repository = MediaServerConnectionRepository(
            ServiceTestConnectionStore(),
            ServiceTestCredentialCipher(),
        )
        val api = ServiceTestMediaServerApi()
        val service = MediaServerConnectionService(
            repository = repository,
            clientIdentityProvider = MediaServerClientIdentityProvider { testClient() },
            apiFactory = { api },
            connectionIdFactory = { "connection-2" },
        )
        val summary = service.connect(
            vendor = MediaServerVendor.JELLYFIN,
            name = "家庭媒体库",
            baseUrl = "https://media.example.test/reverse/jellyfin",
            username = "alice",
            password = "password-secret",
        )
        api.publicServerId = "replaced-server"

        val catalog = service.openCatalog(summary.id)
        assertFailsWith<IllegalStateException> { catalog.listLibraries() }

        assertEquals(listOf("authenticate", "public-info"), api.operations)
        assertEquals(null, api.librarySession)
    }

    @Test
    fun `目录源图片引用不暴露凭据且下载前校验归属`() = runBlocking {
        val repository = MediaServerConnectionRepository(
            ServiceTestConnectionStore(),
            ServiceTestCredentialCipher(),
        )
        val api = ServiceTestMediaServerApi()
        val service = MediaServerConnectionService(
            repository = repository,
            clientIdentityProvider = MediaServerClientIdentityProvider { testClient() },
            apiFactory = { api },
            connectionIdFactory = { "connection-image" },
        )
        val summary = service.connect(
            vendor = MediaServerVendor.JELLYFIN,
            name = "家庭媒体库",
            baseUrl = "https://media.example.test/reverse/jellyfin",
            username = "alice",
            password = "password-secret",
        )
        var downloadedRequest: MediaServerImageRequest? = null
        val catalog = StoredMediaServerCatalogSource(
            connection = summary,
            repository = repository,
            client = testClient(),
            api = api,
            imageDownloader = MediaServerImageDownloader { request, _ ->
                downloadedRequest = request
                true
            },
        )
        val reference = catalog.imageReference(
            itemId = "item-1",
            imageTag = "tag-1",
            maxWidth = 160,
            maxHeight = 240,
        )

        assertFalse(reference.toString().contains("session-secret"))
        assertTrue(catalog.downloadImage(reference, PlatformFile("unused.part")))
        assertTrue(requireNotNull(downloadedRequest).headers["Authorization"].orEmpty().contains("session-secret"))
        assertFailsWith<IllegalArgumentException> {
            catalog.downloadImage(reference.copy(cacheKey = "tampered"), PlatformFile("unused.part"))
        }
        Unit
    }

    @Test
    fun `播放器按安全定位重建计划和报告会话`() = runBlocking {
        val repository = MediaServerConnectionRepository(
            ServiceTestConnectionStore(),
            ServiceTestCredentialCipher(),
        )
        val api = ServiceTestMediaServerApi()
        val service = MediaServerConnectionService(
            repository = repository,
            clientIdentityProvider = MediaServerClientIdentityProvider { testClient() },
            apiFactory = { api },
            connectionIdFactory = { "connection-playback" },
        )
        val summary = service.connect(
            vendor = MediaServerVendor.JELLYFIN,
            name = "家庭媒体库",
            baseUrl = "https://media.example.test/reverse/jellyfin",
            username = "alice",
            password = "password-secret",
        )

        val prepared = service.openPlayback(
            summary.id,
            MediaServerPlaybackRequest(itemId = "episode-1", startPositionMs = 12_345L),
        )
        prepared.reporter.reportStarted(
            MediaServerPlaybackState(
                itemId = prepared.plan.itemId,
                mediaSourceId = prepared.plan.mediaSourceId,
                playSessionId = prepared.plan.playSessionId,
                playMethod = prepared.plan.playMethod,
                positionMs = prepared.plan.initialPositionMs,
                isPaused = false,
                isMuted = false,
            ),
        )

        assertEquals("episode-1", prepared.plan.itemId)
        assertEquals(12_345L, prepared.plan.initialPositionMs)
        assertEquals("session-secret", api.playbackSession?.accessToken)
        // preparePlaybackSession 组弹幕 hint 会再调一次 getItemDetail(MOVIE 默认, 不二跳)。
        assertEquals(
            listOf("authenticate", "public-info", "prepare-playback", "item-detail", "report-started"),
            api.operations,
        )
        assertFalse(prepared.toString().contains("session-secret"))
        assertFalse(prepared.toString().contains("play-session-secret"))
    }

    @Test
    fun `目录源对 401 丢弃缓存会话并按仓库凭据重建一次`() = runBlocking {
        val repository = MediaServerConnectionRepository(
            ServiceTestConnectionStore(),
            ServiceTestCredentialCipher(),
        )
        val api = ServiceTestMediaServerApi()
        val service = MediaServerConnectionService(
            repository = repository,
            clientIdentityProvider = MediaServerClientIdentityProvider { testClient() },
            apiFactory = { api },
            connectionIdFactory = { "connection-retry" },
        )
        val summary = service.connect(
            vendor = MediaServerVendor.JELLYFIN,
            name = "家庭媒体库",
            baseUrl = "https://media.example.test/reverse/jellyfin",
            username = "alice",
            password = "password-secret",
        )
        val catalog = service.openCatalog(summary.id)
        api.libraryFailureStatusCodes.addLast(401)

        val libraries = catalog.listLibraries()

        assertEquals("library-1", libraries.single().id)
        // 第一次 401 后必须重新匿名探测 + 重建会话再试一次，而不是死抱失效会话。
        assertEquals(
            listOf("authenticate", "public-info", "list-libraries", "public-info", "list-libraries"),
            api.operations,
        )
    }

    @Test
    fun `重建会话后仍 401 上抛且非 401 错误不触发重试`() = runBlocking {
        val repository = MediaServerConnectionRepository(
            ServiceTestConnectionStore(),
            ServiceTestCredentialCipher(),
        )
        val api = ServiceTestMediaServerApi()
        val service = MediaServerConnectionService(
            repository = repository,
            clientIdentityProvider = MediaServerClientIdentityProvider { testClient() },
            apiFactory = { api },
            connectionIdFactory = { "connection-retry-fail" },
        )
        val summary = service.connect(
            vendor = MediaServerVendor.JELLYFIN,
            name = "家庭媒体库",
            baseUrl = "https://media.example.test/reverse/jellyfin",
            username = "alice",
            password = "password-secret",
        )
        val catalog = service.openCatalog(summary.id)

        api.libraryFailureStatusCodes.addLast(401)
        api.libraryFailureStatusCodes.addLast(401)
        val doubleUnauthorized = assertFailsWith<MediaServerHttpException> { catalog.listLibraries() }
        assertEquals(401, doubleUnauthorized.statusCode)
        assertEquals(2, api.operations.count { it == "list-libraries" })

        api.operations.clear()
        api.libraryFailureStatusCodes.addLast(503)
        val serverError = assertFailsWith<MediaServerHttpException> { catalog.listLibraries() }
        assertEquals(503, serverError.statusCode)
        assertEquals(listOf("list-libraries"), api.operations)
    }

    private fun testClient(): MediaServerClientIdentity = MediaServerClientIdentity(
        clientName = "UnU Player",
        clientVersion = "0.1.2",
        deviceName = "Android",
        deviceId = "installation-device",
    )
}

private class ServiceTestMediaServerApi : MediaServerApi {
    override val vendor: MediaServerVendor = MediaServerVendor.JELLYFIN
    val operations = mutableListOf<String>()
    var publicServerId: String = "server-1"
    var librarySession: MediaServerSession? = null
    var playbackSession: MediaServerSession? = null

    override suspend fun getPublicInfo(baseUrl: String, allowCleartext: Boolean): MediaServerPublicInfo {
        operations += "public-info"
        return MediaServerPublicInfo(
            vendor = vendor,
            serverId = publicServerId,
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
    ): MediaServerSession {
        operations += "authenticate"
        return MediaServerSession(
            vendor = vendor,
            apiBaseUrl = baseUrl.trimEnd('/'),
            serverId = "server-1",
            serverVersion = "10.11.11",
            userId = "user-1",
            username = username,
            accessToken = "session-secret",
            client = client,
        )
    }

    override suspend fun listLibraries(session: MediaServerSession): List<MediaServerLibrary> {
        operations += "list-libraries"
        librarySession = session
        libraryFailureStatusCodes.removeFirstOrNull()?.let { statusCode ->
            throw MediaServerHttpException("list-libraries", statusCode)
        }
        return listOf(MediaServerLibrary("library-1", "动画", "tvshows", null))
    }

    /** 每次 listLibraries 调用弹出一个状态码并以该状态失败；耗尽后恢复成功。 */
    val libraryFailureStatusCodes = ArrayDeque<Int>()

    override suspend fun listItems(
        session: MediaServerSession,
        query: MediaServerItemsQuery,
    ): MediaServerPage<MediaServerItem> = error("测试未使用")

    override suspend fun getItemDetail(
        session: MediaServerSession,
        itemId: String,
    ): MediaServerItemDetail {
        operations += "item-detail"
        itemDetailFailureStatusCodes.removeFirstOrNull()?.let { statusCode ->
            throw MediaServerHttpException("item-detail", statusCode)
        }
        // 默认返回 MOVIE 直取形态(不触发 EPISODE 二跳), 让调用方按需 copy 成 EPISODE。
        return MediaServerItemDetail(
            id = itemId,
            kind = MediaServerItemKind.MOVIE,
            providerIds = detailProviderIds,
            seriesId = detailSeriesId,
            seriesName = detailSeriesName,
            indexNumber = detailIndexNumber,
            parentIndexNumber = detailParentIndexNumber,
        )
    }

    /** 控制 getItemDetail 抛出的状态码(每次调用弹一个); 耗尽后恢复成功。 */
    val itemDetailFailureStatusCodes = ArrayDeque<Int>()
    var detailProviderIds: Map<String, String> = emptyMap()
    var detailSeriesId: String? = null
    var detailSeriesName: String? = null
    var detailIndexNumber: Int? = null
    var detailParentIndexNumber: Int? = null

    override suspend fun getPlaybackInfo(
        session: MediaServerSession,
        request: MediaServerPlaybackRequest,
    ): MediaServerPlaybackInfo = error("测试未使用")

    override suspend fun preparePlayback(
        session: MediaServerSession,
        request: MediaServerPlaybackRequest,
    ): MediaServerPlaybackPlan {
        operations += "prepare-playback"
        playbackSession = session
        return MediaServerPlaybackPlan(
            vendor = vendor,
            connectionId = requireNotNull(session.connectionId),
            itemId = request.itemId,
            mediaSourceId = "source-1",
            playSessionId = "play-session-secret",
            playMethod = MediaServerPlayMethod.DIRECT_PLAY,
            url = "https://media.example.test/Videos/${request.itemId}/stream.mp4?PlaySessionId=play-session-secret",
            headers = mapOf("Authorization" to "MediaBrowser Token=${session.accessToken}"),
            externalSubtitles = emptyList(),
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
    ): MediaServerImageRequest = buildImageRequest(
        session = session,
        itemId = itemId,
        imageType = imageType,
        imageIndex = imageIndex,
        imageTag = imageTag,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        authenticationHeaders = mapOf("Authorization" to "MediaBrowser Token=${session.accessToken}"),
    )

    override suspend fun reportPlaybackStarted(
        session: MediaServerSession,
        state: MediaServerPlaybackState,
    ) {
        operations += "report-started"
    }

    override suspend fun reportPlaybackProgress(
        session: MediaServerSession,
        state: MediaServerPlaybackState,
    ) = Unit

    override suspend fun reportPlaybackStopped(
        session: MediaServerSession,
        state: MediaServerPlaybackState,
        failed: Boolean,
    ) = Unit

    var logoutSession: MediaServerSession? = null

    /** 每次 logout 调用弹出一个状态码并以该状态失败；耗尽后恢复成功。 */
    val logoutFailureStatusCodes = ArrayDeque<Int>()
    var suspendLogout = false

    override suspend fun logout(session: MediaServerSession) {
        operations += "logout"
        logoutSession = session
        if (suspendLogout) awaitCancellation()
        logoutFailureStatusCodes.removeFirstOrNull()?.let { statusCode ->
            throw MediaServerHttpException("logout", statusCode)
        }
    }
}

private class ServiceTestConnectionStore : MediaServerConnectionStore {
    var records = emptyList<MediaServerConnection>()

    override suspend fun loadAll(): List<MediaServerConnection> = records

    override suspend fun replaceAll(connections: List<MediaServerConnection>) {
        records = connections
    }
}

private class ServiceTestCredentialCipher : CredentialCipher {
    override fun protect(purpose: String, plaintext: String): String =
        PROTECTED_CREDENTIAL_PREFIX + purpose + "\u0000" + plaintext

    override fun unprotect(purpose: String, protectedValue: String): String {
        val payload = protectedValue.removePrefix(PROTECTED_CREDENTIAL_PREFIX)
        val storedPurpose = payload.substringBefore('\u0000')
        require(storedPurpose == purpose) { "purpose 不匹配" }
        return payload.substringAfter('\u0000')
    }
}
