package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

fun interface MediaServerClientIdentityProvider {
    suspend fun get(): MediaServerClientIdentity
}

class MediaServerConnectionService(
    private val repository: MediaServerConnectionRepository,
    private val clientIdentityProvider: MediaServerClientIdentityProvider,
    private val apiFactory: (MediaServerVendor) -> MediaServerApi = MediaServerApiFactory::create,
    private val connectionIdFactory: () -> String = ::randomMediaServerConnectionId,
    /** 仅用于 best-effort logout 失败告警(A-05); 不记 URL/token。 */
    private val logger: AppLogger? = null,
    private val removeLogoutTimeoutMs: Long = DEFAULT_REMOVE_LOGOUT_TIMEOUT_MS,
) {
    init {
        require(removeLogoutTimeoutMs > 0L) { "删除连接的注销超时必须大于 0" }
    }

    suspend fun listConnections(): List<MediaServerConnectionSummary> = repository.loadSummaries()

    suspend fun connect(
        vendor: MediaServerVendor,
        name: String,
        baseUrl: String,
        username: String,
        password: String,
        allowCleartext: Boolean = false,
    ): MediaServerConnectionSummary {
        require(name.isNotBlank()) { "连接名称不能为空" }
        val client = clientIdentityProvider.get()
        val api = apiFactory(vendor)
        require(api.vendor == vendor) { "媒体服务器 API 类型不匹配" }
        val session = api.authenticate(
            baseUrl = baseUrl,
            username = username,
            password = password,
            client = client,
            allowCleartext = allowCleartext,
        )
        check(session.vendor == vendor) { "媒体服务器会话类型不匹配" }
        val connection = MediaServerConnection(
            id = connectionIdFactory(),
            vendor = vendor,
            name = name.trim(),
            baseUrl = session.apiBaseUrl,
            serverId = session.serverId,
            serverVersion = session.serverVersion,
            userId = session.userId,
            username = session.username,
            accessToken = session.accessToken,
            deviceId = client.deviceId,
        )
        try {
            repository.add(connection, allowCleartext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            runSuspendCatching { api.logout(session) }
            throw error
        }
        return connection.toSummary()
    }

    suspend fun remove(connectionId: String): List<MediaServerConnectionSummary> {
        // A-05: 删除前 best-effort logout, 注销服务端会话 token(否则 token 在服务端永久残留,
        // 401 文案引导"删除重加"会系统性累积僵尸会话)。
        // best-effort 而非强制前置: 服务端不可达(外出时局域网服务器/已停机)或凭据已解密失败时,
        // 删除必须仍然成功——用户意图优先, 残留 token 靠服务端会话超时回收兜底。
        // 会话构造照现有 requireSession 路径(匿名探测复核服务器身份后再释放 token, 防地址变更发错服务)。
        val logoutResult = withTimeoutOrNull(removeLogoutTimeoutMs) {
            runSuspendCatching {
                val connection = repository.loadAll().firstOrNull { it.id == connectionId }
                if (connection != null && !connection.credentialUnavailable) {
                    val api = apiFactory(connection.vendor)
                    val publicInfo = api.getPublicInfo(
                        baseUrl = connection.baseUrl,
                        allowCleartext = connection.baseUrl.startsWith("http://", ignoreCase = true),
                    )
                    val session = repository.createSession(connectionId, publicInfo, clientIdentityProvider.get())
                    api.logout(session)
                }
            }
        }
        if (logoutResult == null) {
            logger?.appEvent(
                "media-server",
                "删除连接前注销服务端会话超时, 继续删除",
                LogLevel.WARN,
            )
        } else logoutResult.onFailure { error ->
            // runSuspendCatching 已让协程取消继续上抛, 到这里只有业务失败: 记 WARN 不阻断删除(不记 URL/token)。
            logger?.appEvent(
                "media-server",
                "删除连接前注销服务端会话失败, 继续删除: ${error::class.simpleName ?: "未知错误"}",
                LogLevel.WARN,
            )
        }
        repository.remove(connectionId)
        return repository.loadSummaries()
    }

    suspend fun openCatalog(connectionId: String): MediaCatalogSource {
        return openStoredCatalog(connectionId)
    }

    /**
     * 在播放器边界内重新探测服务器、释放加密 token 并生成播放计划与报告器。
     * 调用方只应通过安全 locator 进入本方法，不能从 Intent 传入 URL/header/session。
     */
    suspend fun openPlayback(
        connectionId: String,
        request: MediaServerPlaybackRequest,
    ): MediaServerPreparedPlayback = openStoredCatalog(connectionId).preparePlaybackSession(request)

    private suspend fun openStoredCatalog(connectionId: String): StoredMediaServerCatalogSource {
        val summary = repository.loadSummaries().firstOrNull { it.id == connectionId }
            ?: error("找不到媒体服务器连接")
        check(!summary.credentialUnavailable) { "媒体服务器凭据已失效" }
        return StoredMediaServerCatalogSource(
            connection = summary,
            repository = repository,
            client = clientIdentityProvider.get(),
            api = apiFactory(summary.vendor),
        )
    }
}

private const val DEFAULT_REMOVE_LOGOUT_TIMEOUT_MS = 5_000L

private fun randomMediaServerConnectionId(): String {
    val bytes = Random.nextBytes(16)
    val alphabet = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { value ->
            val unsigned = value.toInt() and 0xff
            append(alphabet[unsigned ushr 4])
            append(alphabet[unsigned and 0x0f])
        }
    }
}
