package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.core.security.CredentialCipher
import io.github.weiyongzenqi.unuplayer.core.security.CredentialProtectionException
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabase
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class MediaServerConnection(
    val id: String,
    val vendor: MediaServerVendor,
    val name: String,
    val baseUrl: String,
    val serverId: String,
    val serverVersion: String?,
    val userId: String,
    val username: String,
    val accessToken: String,
    val deviceId: String,
    val credentialUnavailable: Boolean = false,
) {
    override fun toString(): String =
        "MediaServerConnection(id=$id, vendor=$vendor, name=$name, " +
            "baseUrl=<redacted>, serverId=$serverId, " +
            "serverVersion=$serverVersion, userId=$userId, username=$username, " +
            "accessToken=<redacted>, deviceId=$deviceId, " +
            "credentialUnavailable=$credentialUnavailable)"
}

data class MediaServerConnectionSummary(
    val id: String,
    val vendor: MediaServerVendor,
    val name: String,
    val baseUrl: String,
    val serverId: String,
    val serverVersion: String?,
    val username: String,
    val credentialUnavailable: Boolean,
) {
    override fun toString(): String =
        "MediaServerConnectionSummary(id=$id, vendor=$vendor, name=$name, baseUrl=<redacted>, " +
            "serverId=$serverId, serverVersion=$serverVersion, username=$username, " +
            "credentialUnavailable=$credentialUnavailable)"
}

internal interface MediaServerConnectionStore {
    suspend fun loadAll(): List<MediaServerConnection>
    suspend fun replaceAll(connections: List<MediaServerConnection>)
}

class MediaServerConnectionRepository internal constructor(
    private val store: MediaServerConnectionStore,
    private val credentialCipher: CredentialCipher,
) {
    constructor(database: UnuDatabase, credentialCipher: CredentialCipher) :
        this(SqlDelightMediaServerConnectionStore(database), credentialCipher)

    private val mutationMutex = Mutex()

    suspend fun loadAll(): List<MediaServerConnection> = withContext(Dispatchers.Default) {
        mutationMutex.withLock { loadDecodedLocked().exposed }
    }

    suspend fun loadSummaries(): List<MediaServerConnectionSummary> = withContext(Dispatchers.Default) {
        mutationMutex.withLock { loadDecodedLocked().exposed.map(MediaServerConnection::toSummary) }
    }

    suspend fun add(
        connection: MediaServerConnection,
        allowCleartext: Boolean = false,
    ): List<MediaServerConnection> = withContext(Dispatchers.Default) {
        mutationMutex.withLock {
            require(!connection.credentialUnavailable) { "凭据失效的连接不能直接保存" }
            val normalized = connection.validatedForMutation(allowCleartext)
            val current = loadDecodedLocked()
            require(current.exposed.none { it.id == normalized.id }) { "媒体服务器连接 ID 已存在" }
            val updated = current.exposed + normalized
            store.replaceAll(updated.map { candidate ->
                candidate.encodedForStorage(current.storedById, current.exposedById)
            })
            updated
        }
    }

    suspend fun update(
        connection: MediaServerConnection,
        allowCleartext: Boolean = false,
    ): List<MediaServerConnection> = withContext(Dispatchers.Default) {
        mutationMutex.withLock {
            val normalized = if (connection.credentialUnavailable) {
                connection.validatedMetadataForMutation(allowCleartext)
            } else {
                connection.validatedForMutation(allowCleartext)
            }
            val current = loadDecodedLocked()
            val index = current.exposed.indexOfFirst { it.id == normalized.id }
            require(index >= 0) { "找不到要更新的媒体服务器连接" }
            if (connection.credentialUnavailable) {
                require(normalized.hasSameCredentialIdentity(current.exposed[index])) {
                    "凭据失效时不能修改服务器或用户身份"
                }
            }
            val updated = current.exposed.toMutableList().apply { this[index] = normalized }
            store.replaceAll(updated.map { candidate ->
                candidate.encodedForStorage(current.storedById, current.exposedById)
            })
            updated
        }
    }

    suspend fun remove(id: String): List<MediaServerConnection> = withContext(Dispatchers.Default) {
        mutationMutex.withLock {
            val current = loadDecodedLocked()
            require(current.exposed.any { it.id == id }) { "找不到要删除的媒体服务器连接" }
            val exposed = current.exposed.filterNot { it.id == id }
            store.replaceAll(current.stored.filterNot { it.id == id })
            exposed
        }
    }

    /** 匿名探测结果与保存记录完全匹配后才释放 token，避免地址变更时把凭据发给错误服务。 */
    suspend fun createSession(
        connectionId: String,
        publicInfo: MediaServerPublicInfo,
        client: MediaServerClientIdentity,
    ): MediaServerSession = withContext(Dispatchers.Default) {
        mutationMutex.withLock {
            val connection = loadDecodedLocked().exposed.firstOrNull { it.id == connectionId }
                ?: error("找不到媒体服务器连接")
            check(!connection.credentialUnavailable) { "媒体服务器凭据已失效" }
            check(connection.vendor == publicInfo.vendor) { "媒体服务器类型与保存记录不匹配" }
            check(connection.serverId == publicInfo.serverId) { "媒体服务器身份与保存记录不匹配" }
            check(connection.deviceId == client.deviceId) { "媒体服务器设备身份与保存记录不匹配" }
            check(mediaServerBaseUrlsMatch(connection.baseUrl, publicInfo.apiBaseUrl, connection.vendor)) {
                "媒体服务器地址与保存记录不匹配"
            }
            MediaServerSession(
                vendor = connection.vendor,
                connectionId = connection.id,
                apiBaseUrl = connection.baseUrl,
                serverId = connection.serverId,
                serverVersion = publicInfo.version,
                userId = connection.userId,
                username = connection.username,
                accessToken = connection.accessToken,
                client = client,
            )
        }
    }

    private suspend fun loadDecodedLocked(): DecodedConnections {
        val raw = store.loadAll()
        val exposed = ArrayList<MediaServerConnection>(raw.size)
        val stored = ArrayList<MediaServerConnection>(raw.size)
        raw.forEach { rawConnection ->
            val normalized = rawConnection.normalizedStoredRecord()
            if (!credentialCipher.isProtected(normalized.accessToken)) {
                require(normalized.accessToken.isNotBlank()) { "媒体服务器 token 记录不能为空" }
                exposed += normalized.copy(credentialUnavailable = false)
                stored += normalized.copy(
                    accessToken = protectToken(normalized.id, normalized.accessToken),
                    credentialUnavailable = false,
                )
            } else {
                val token = try {
                    credentialCipher.unprotect(tokenPurpose(normalized.id), normalized.accessToken)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (token.isNullOrBlank()) {
                    exposed += normalized.copy(accessToken = "", credentialUnavailable = true)
                } else {
                    exposed += normalized.copy(accessToken = token, credentialUnavailable = false)
                }
                stored += normalized.copy(credentialUnavailable = false)
            }
        }
        if (stored != raw) store.replaceAll(stored)
        return DecodedConnections(exposed = exposed, stored = stored)
    }

    private fun MediaServerConnection.encodedForStorage(
        existingStoredById: Map<String, MediaServerConnection>,
        existingExposedById: Map<String, MediaServerConnection>,
    ): MediaServerConnection {
        if (credentialUnavailable) {
            val existing = requireNotNull(existingStoredById[id]) { "找不到需保留的失效凭据" }
            return copy(accessToken = existing.accessToken, credentialUnavailable = false)
        }
        val existingExposed = existingExposedById[id]
        val existingStored = existingStoredById[id]
        val protectedToken = if (
            existingExposed != null && !existingExposed.credentialUnavailable &&
            existingExposed.accessToken == accessToken
        ) {
            existingStored?.accessToken ?: protectToken(id, accessToken)
        } else {
            protectToken(id, accessToken)
        }
        return copy(accessToken = protectedToken, credentialUnavailable = false)
    }

    private fun protectToken(id: String, token: String): String {
        require(token.isNotBlank()) { "媒体服务器 token 不能为空" }
        return try {
            credentialCipher.protect(tokenPurpose(id), token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw CredentialProtectionException("媒体服务器凭据无法保存，请稍后重试", error)
        }
    }

    private fun tokenPurpose(id: String): String = "media-server:$id:access-token"

    private data class DecodedConnections(
        val exposed: List<MediaServerConnection>,
        val stored: List<MediaServerConnection>,
    ) {
        val exposedById = exposed.associateBy { it.id }
        val storedById = stored.associateBy { it.id }
    }
}

internal fun MediaServerConnection.toSummary(): MediaServerConnectionSummary =
    MediaServerConnectionSummary(
        id = id,
        vendor = vendor,
        name = name,
        baseUrl = baseUrl,
        serverId = serverId,
        serverVersion = serverVersion,
        username = username,
        credentialUnavailable = credentialUnavailable,
    )

private fun MediaServerConnection.validatedForMutation(allowCleartext: Boolean): MediaServerConnection {
    require(accessToken.isNotBlank()) { "媒体服务器 token 不能为空" }
    return validatedMetadataForMutation(allowCleartext).copy(credentialUnavailable = false)
}

private fun MediaServerConnection.validatedMetadataForMutation(allowCleartext: Boolean): MediaServerConnection {
    require(id.isNotBlank()) { "连接 ID 不能为空" }
    require(name.isNotBlank()) { "连接名称不能为空" }
    require(serverId.isNotBlank()) { "服务器 ID 不能为空" }
    require(userId.isNotBlank()) { "用户 ID 不能为空" }
    require(username.isNotBlank()) { "用户名不能为空" }
    require(deviceId.isNotBlank()) { "设备 ID 不能为空" }
    val validation = validateMediaServerBaseUrl(baseUrl, vendor)
    require(validation.isValid) { validation.errorMessage ?: "媒体服务器地址无效" }
    require(!validation.requiresCleartextConfirmation || allowCleartext) {
        "HTTP 媒体服务器必须经过用户明确授权"
    }
    return copy(
        id = id.trim(),
        name = name.trim(),
        baseUrl = requireNotNull(validation.normalizedApiBaseUrl),
        serverId = serverId.trim(),
        serverVersion = serverVersion?.trim()?.takeIf { it.isNotEmpty() },
        userId = userId.trim(),
        username = username.trim(),
        deviceId = deviceId.trim(),
    )
}

private fun MediaServerConnection.normalizedStoredRecord(): MediaServerConnection {
    val validation = validateMediaServerBaseUrl(baseUrl, vendor)
    require(validation.isValid) { "媒体服务器连接记录地址无效" }
    return copy(
        id = id.trim(),
        name = name.trim(),
        baseUrl = requireNotNull(validation.normalizedApiBaseUrl),
        serverId = serverId.trim(),
        serverVersion = serverVersion?.trim()?.takeIf { it.isNotEmpty() },
        userId = userId.trim(),
        username = username.trim(),
        deviceId = deviceId.trim(),
        credentialUnavailable = false,
    )
}

private fun MediaServerConnection.hasSameCredentialIdentity(other: MediaServerConnection): Boolean =
    vendor == other.vendor &&
        baseUrl == other.baseUrl &&
        serverId == other.serverId &&
        userId == other.userId &&
        deviceId == other.deviceId

private fun mediaServerBaseUrlsMatch(
    first: String,
    second: String,
    vendor: MediaServerVendor,
): Boolean {
    val firstNormalized = validateMediaServerBaseUrl(first, vendor).normalizedApiBaseUrl ?: return false
    val secondNormalized = validateMediaServerBaseUrl(second, vendor).normalizedApiBaseUrl ?: return false
    val firstUrl = runCatching { Url(firstNormalized) }.getOrNull() ?: return false
    val secondUrl = runCatching { Url(secondNormalized) }.getOrNull() ?: return false
    return mediaServerUrlsHaveSameOrigin(firstUrl, secondUrl) &&
        firstUrl.encodedPath.trimEnd('/') == secondUrl.encodedPath.trimEnd('/')
}

private class SqlDelightMediaServerConnectionStore(
    private val database: UnuDatabase,
) : MediaServerConnectionStore {
    private val queries get() = database.mediaServerQueries

    override suspend fun loadAll(): List<MediaServerConnection> = withContext(Dispatchers.Default) {
        queries.listAll {
                id, vendor, name, baseUrl, serverId, serverVersion, userId, username,
                accessToken, deviceId, _,
            ->
            MediaServerConnection(
                id = id,
                vendor = vendor.toMediaServerVendor(),
                name = name,
                baseUrl = baseUrl,
                serverId = serverId,
                serverVersion = serverVersion,
                userId = userId,
                username = username,
                accessToken = accessToken,
                deviceId = deviceId,
            )
        }.executeAsList()
    }

    override suspend fun replaceAll(connections: List<MediaServerConnection>) = withContext(Dispatchers.Default) {
        database.transaction {
            queries.deleteAll()
            connections.forEachIndexed { index, connection ->
                queries.insert(
                    id = connection.id,
                    vendor = connection.vendor.name,
                    name = connection.name,
                    base_url = connection.baseUrl,
                    server_id = connection.serverId,
                    server_version = connection.serverVersion,
                    user_id = connection.userId,
                    username = connection.username,
                    access_token = connection.accessToken,
                    device_id = connection.deviceId,
                    sort_order = index.toLong(),
                )
            }
        }
    }
}

private fun String.toMediaServerVendor(): MediaServerVendor =
    MediaServerVendor.entries.firstOrNull { it.name == this }
        ?: throw IllegalStateException("媒体服务器连接类型无效")
