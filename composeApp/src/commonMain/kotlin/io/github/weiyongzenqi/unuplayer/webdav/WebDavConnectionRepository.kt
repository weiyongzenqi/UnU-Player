package io.github.weiyongzenqi.unuplayer.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.security.CredentialCipher
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabase

/** WebDAV 连接持久化接口；生产环境使用同一份 SQLDelight 用户数据库。 */
interface WebDavConnectionStore {
    suspend fun loadAll(): List<WebDavConnection>
    suspend fun replaceAll(connections: List<WebDavConnection>)
}

/**
 * WebDAV 连接仓库。
 *
 * 连接凭据不再写入 Settings/Java Preferences，而是和播放记录、刮削库统一保存在
 * `unu_playback.db`。仓库用互斥锁保护读改写，避免两个 UI 操作互相覆盖。
 */
class WebDavConnectionRepository(
    private val store: WebDavConnectionStore,
    private val credentialCipher: CredentialCipher,
) {
    constructor(database: UnuDatabase, credentialCipher: CredentialCipher) :
        this(SqlDelightWebDavConnectionStore(database), credentialCipher)

    private val mutationMutex = Mutex()

    suspend fun loadAll(): List<WebDavConnection> = withContext(Dispatchers.Default) {
        mutationMutex.withLock { loadDecodedLocked().exposed }
    }

    /** Android 独立播放器按 mediaKey 中的连接 id 重建认证头，避免 Authorization 进入 Intent。 */
    suspend fun playbackHeaders(connectionId: String, playUrl: String): Map<String, String> {
        val connection = loadAll().firstOrNull { it.id == connectionId }
            ?: error("找不到播放记录对应的 WebDAV 连接")
        check(!connection.credentialUnavailable) { "WebDAV 凭据已失效" }
        // 任一 origin 解析失败(null=地址畸形)即判不同源: 否则两个畸形地址 null==null 会骗过校验,
        // 理论上可能把凭据发往用户未配置的目标。
        val connectionOrigin = urlOrigin(connection.baseUrl)
        check(connectionOrigin != null && connectionOrigin == urlOrigin(playUrl)) {
            "播放地址与 WebDAV 连接不同源"
        }
        return WebDavClient(
            createHttpClient(),
            connection.baseUrl,
            connection.username,
            connection.password,
        ).playHeaders()
    }

    suspend fun save(connections: List<WebDavConnection>, allowCleartext: Boolean = false) {
        withContext(Dispatchers.Default) {
            mutationMutex.withLock {
                val current = loadDecodedLocked()
                val validated = connections.map { it.validatedForMutation(allowCleartext) }
                store.replaceAll(validated.map {
                    it.encodedForStorage(current.storedById, current.exposedById)
                })
            }
        }
    }

    suspend fun add(
        conn: WebDavConnection,
        allowCleartext: Boolean = false,
    ): List<WebDavConnection> = withContext(Dispatchers.Default) {
        mutationMutex.withLock {
            require(!conn.credentialUnavailable) { "凭据失效的连接不能直接保存" }
            val current = loadDecodedLocked()
            val normalizedConnection = conn.validatedForMutation(allowCleartext)
            val updated = current.exposed.toMutableList().apply {
                removeAll { it.id == normalizedConnection.id }
                add(normalizedConnection)
            }
            store.replaceAll(updated.map {
                it.encodedForStorage(current.storedById, current.exposedById)
            })
            updated
        }
    }

    suspend fun remove(id: String): List<WebDavConnection> = withContext(Dispatchers.Default) {
        mutationMutex.withLock {
            val current = loadDecodedLocked()
            val exposed = current.exposed.filterNot { it.id == id }
            val stored = current.stored.filterNot { it.id == id }
            store.replaceAll(stored)
            exposed
        }
    }

    suspend fun update(
        conn: WebDavConnection,
        allowCleartext: Boolean = false,
    ): List<WebDavConnection> = withContext(Dispatchers.Default) {
        mutationMutex.withLock {
            require(!conn.credentialUnavailable) { "凭据失效的连接必须重新输入后才能更新" }
            val current = loadDecodedLocked()
            val normalizedConnection = conn.validatedForMutation(allowCleartext)
            val updated = current.exposed.toMutableList()
            val index = updated.indexOfFirst { it.id == normalizedConnection.id }
            if (index >= 0) {
                updated[index] = normalizedConnection
                store.replaceAll(updated.map {
                    it.encodedForStorage(current.storedById, current.exposedById)
                })
            }
            updated
        }
    }

    /**
     * 解密对外视图，并把旧明文密码、URL userInfo 和非规范字段一次性迁移回密文存储。
     * 解不开的密文原样保留；对外只暴露空密码 + credentialUnavailable，禁止静默匿名请求。
     */
    private suspend fun loadDecodedLocked(): DecodedConnections {
        val raw = store.loadAll()
        val exposed = ArrayList<WebDavConnection>(raw.size)
        val stored = ArrayList<WebDavConnection>(raw.size)
        raw.forEach { rawConnection ->
            val normalized = rawConnection.normalized()
            if (normalized.password.isEmpty()) {
                exposed += normalized.copy(credentialUnavailable = false)
                stored += normalized.copy(credentialUnavailable = false)
            } else if (credentialCipher.isProtected(normalized.password)) {
                try {
                    exposed += normalized.copy(
                        password = credentialCipher.unprotect(passwordPurpose(normalized.id), normalized.password),
                        credentialUnavailable = false,
                    )
                    stored += normalized.copy(credentialUnavailable = false)
                } catch (_: Throwable) {
                    exposed += normalized.copy(password = "", credentialUnavailable = true)
                    stored += normalized.copy(credentialUnavailable = false)
                }
            } else {
                exposed += normalized.copy(credentialUnavailable = false)
                stored += normalized.copy(
                    password = credentialCipher.protect(passwordPurpose(normalized.id), normalized.password),
                    credentialUnavailable = false,
                )
            }
        }
        if (stored != raw) store.replaceAll(stored)
        return DecodedConnections(exposed = exposed, stored = stored)
    }

    private fun WebDavConnection.encodedForStorage(
        existingById: Map<String, WebDavConnection>,
        existingExposedById: Map<String, WebDavConnection>,
    ): WebDavConnection {
        val normalized = normalized()
        if (normalized.credentialUnavailable) {
            return requireNotNull(existingById[normalized.id]) { "找不到需保留的失效凭据" }
        }
        val existing = existingExposedById[normalized.id]
        val existingStored = existingById[normalized.id]
        val protectedPassword = if (
            existing != null && !existing.credentialUnavailable && existing.password == normalized.password
        ) {
            existingStored?.password.orEmpty()
        } else {
            normalized.password.takeIf { it.isNotEmpty() }?.let {
                credentialCipher.protect(passwordPurpose(normalized.id), it)
            }.orEmpty()
        }
        return normalized.copy(
            password = protectedPassword,
            credentialUnavailable = false,
        )
    }

    private fun passwordPurpose(id: String): String = "webdav:$id:password"

    private data class DecodedConnections(
        val exposed: List<WebDavConnection>,
        val stored: List<WebDavConnection>,
    ) {
        val storedById: Map<String, WebDavConnection> = stored.associateBy { it.id }
        val exposedById: Map<String, WebDavConnection> = exposed.associateBy { it.id }
    }
}

private fun urlOrigin(value: String): String? {
    val trimmed = value.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return null
    val authorityStart = schemeEnd + 3
    val authorityEnd = sequenceOf(
        trimmed.indexOf('/', authorityStart),
        trimmed.indexOf('?', authorityStart),
        trimmed.indexOf('#', authorityStart),
    ).filter { it >= 0 }.minOrNull() ?: trimmed.length
    val authority = trimmed.substring(authorityStart, authorityEnd)
    if (authority.isEmpty() || '@' in authority) return null
    return trimmed.substring(0, schemeEnd).lowercase() + "://" + authority.lowercase()
}

private fun WebDavConnection.normalized(): WebDavConnection {
    val sanitizedUrl = sanitizeUrlUserInfo(baseUrl)
    return copy(
        name = name.trim(),
        baseUrl = sanitizedUrl.baseUrl.trimEnd('/'),
        username = username.trim().ifEmpty { sanitizedUrl.username.orEmpty() },
        password = password.ifEmpty { sanitizedUrl.password.orEmpty() },
    )
}

private fun WebDavConnection.validatedForMutation(allowCleartext: Boolean): WebDavConnection {
    val validation = validateWebDavBaseUrl(baseUrl)
    require(validation.isValid) { validation.errorMessage ?: "WebDAV 服务器地址无效" }
    require(!validation.requiresCleartextConfirmation || allowCleartext) {
        "HTTP WebDAV 必须经过用户明确授权"
    }
    return copy(baseUrl = requireNotNull(validation.normalizedUrl)).normalized()
}

/** 历史版本若保存了 https://user:pass@host，只迁移字段并确保 URL 不再落盘或进入错误文本。 */
private fun sanitizeUrlUserInfo(value: String): SanitizedUrl {
    val trimmed = value.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return SanitizedUrl(trimmed)
    val authorityStart = schemeEnd + 3
    val authorityEnd = trimmed.indexOf('/', authorityStart).let { if (it < 0) trimmed.length else it }
    val at = trimmed.lastIndexOf('@', authorityEnd - 1)
    if (at < authorityStart) return SanitizedUrl(trimmed)

    val userInfo = trimmed.substring(authorityStart, at)
    val separator = userInfo.indexOf(':')
    val username = decodeUserInfo(userInfo.substring(0, separator.takeIf { it >= 0 } ?: userInfo.length))
    val password = separator.takeIf { it >= 0 }?.let { decodeUserInfo(userInfo.substring(it + 1)) }
    return SanitizedUrl(
        baseUrl = trimmed.removeRange(authorityStart, at + 1),
        username = username,
        password = password,
    )
}

private fun decodeUserInfo(value: String): String {
    val output = StringBuilder(value.length)
    val bytes = ArrayList<Byte>()
    fun flushBytes() {
        if (bytes.isEmpty()) return
        output.append(ByteArray(bytes.size) { bytes[it] }.decodeToString())
        bytes.clear()
    }
    var index = 0
    while (index < value.length) {
        if (value[index] == '%' && index + 2 < value.length) {
            val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (decoded != null) {
                bytes += decoded.toByte()
                index += 3
                continue
            }
        }
        flushBytes()
        output.append(value[index])
        index++
    }
    flushBytes()
    return output.toString()
}

private data class SanitizedUrl(
    val baseUrl: String,
    val username: String? = null,
    val password: String? = null,
)

private class SqlDelightWebDavConnectionStore(
    private val database: UnuDatabase,
) : WebDavConnectionStore {
    private val queries get() = database.webdavQueries

    override suspend fun loadAll(): List<WebDavConnection> = withContext(Dispatchers.Default) {
        queries.listAll { id, name, baseUrl, username, password, _ ->
            WebDavConnection(
                id = id,
                name = name,
                baseUrl = baseUrl,
                username = username,
                password = password,
            )
        }.executeAsList()
    }

    override suspend fun replaceAll(connections: List<WebDavConnection>) = withContext(Dispatchers.Default) {
        database.transaction {
            queries.deleteAll()
            connections.forEachIndexed { index, connection ->
                queries.insert(
                    id = connection.id,
                    name = connection.name,
                    base_url = connection.baseUrl,
                    username = connection.username,
                    password = connection.password,
                    sort_order = index.toLong(),
                )
            }
        }
    }
}
