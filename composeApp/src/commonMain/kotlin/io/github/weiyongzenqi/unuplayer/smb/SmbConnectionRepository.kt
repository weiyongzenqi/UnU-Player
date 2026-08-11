package io.github.weiyongzenqi.unuplayer.smb

import io.github.weiyongzenqi.unuplayer.core.security.CredentialCipher
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** SMB 连接持久化接口，便于 host 测试和 Android SQLDelight 实现复用。 */
interface SmbConnectionStore {
    suspend fun loadAll(): List<SmbConnection>
    suspend fun replaceAll(connections: List<SmbConnection>)
}

/**
 * SMB 连接仓库。
 *
 * password 使用与 WebDAV 相同的 CredentialCipher，外部播放器只通过 connection id 重建连接，
 * 不把密码、Authorization 或 smb URL userInfo 放入 Intent、SavedState、播放记录和日志。
 */
class SmbConnectionRepository(
    private val store: SmbConnectionStore,
    private val credentialCipher: CredentialCipher,
) {
    constructor(database: UnuDatabase, credentialCipher: CredentialCipher) :
        this(SqlDelightSmbConnectionStore(database), credentialCipher)

    private val mutationMutex = Mutex()

    suspend fun loadAll(): List<SmbConnection> = withContext(Dispatchers.IO) {
        mutationMutex.withLock { loadDecodedLocked().exposed }
    }

    suspend fun add(connection: SmbConnection): List<SmbConnection> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            require(!connection.credentialUnavailable) { "凭据失效的 SMB 连接不能直接保存" }
            val current = loadDecodedLocked()
            val normalized = connection.validated()
            val updated = current.exposed.toMutableList().apply {
                removeAll { it.id == normalized.id }
                add(normalized)
            }
            store.replaceAll(updated.map { it.encodeForStorage(current) })
            updated
        }
    }

    suspend fun update(connection: SmbConnection): List<SmbConnection> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            require(!connection.credentialUnavailable) { "凭据失效的 SMB 连接必须重新输入密码" }
            val current = loadDecodedLocked()
            val normalized = connection.validated()
            val updated = current.exposed.toMutableList()
            val index = updated.indexOfFirst { it.id == normalized.id }
            if (index >= 0) {
                updated[index] = normalized
                store.replaceAll(updated.map { it.encodeForStorage(current) })
            }
            updated
        }
    }

    suspend fun remove(id: String): List<SmbConnection> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val current = loadDecodedLocked()
            val exposed = current.exposed.filterNot { it.id == id }
            store.replaceAll(exposed.map { it.encodeForStorage(current) })
            exposed
        }
    }

    private suspend fun loadDecodedLocked(): DecodedConnections {
        val raw = store.loadAll()
        val exposed = ArrayList<SmbConnection>(raw.size)
        val stored = ArrayList<SmbConnection>(raw.size)
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
        return DecodedConnections(exposed, stored)
    }

    private fun SmbConnection.encodeForStorage(current: DecodedConnections): SmbConnection {
        val normalized = normalized()
        if (normalized.credentialUnavailable) {
            return requireNotNull(current.storedById[normalized.id]) { "找不到需保留的失效 SMB 凭据" }
        }
        val existing = current.exposedById[normalized.id]
        val existingStored = current.storedById[normalized.id]
        val protectedPassword = if (
            existing != null && !existing.credentialUnavailable && existing.password == normalized.password
        ) {
            existingStored?.password.orEmpty()
        } else {
            normalized.password.takeIf { it.isNotEmpty() }?.let {
                credentialCipher.protect(passwordPurpose(normalized.id), it)
            }.orEmpty()
        }
        return normalized.copy(password = protectedPassword, credentialUnavailable = false)
    }

    private fun passwordPurpose(id: String): String = "smb:$id:password"

    private data class DecodedConnections(
        val exposed: List<SmbConnection>,
        val stored: List<SmbConnection>,
    ) {
        val exposedById = exposed.associateBy { it.id }
        val storedById = stored.associateBy { it.id }
    }
}

private fun SmbConnection.normalized(): SmbConnection = copy(
    name = name.trim(),
    host = host.trim(),
    share = share.trim().trim('/').replace('/', '\\'),
    username = username.trim(),
    domain = domain.trim(),
    port = port,
)

private fun SmbConnection.validated(): SmbConnection {
    val normalized = normalized()
    require(normalized.id.isNotBlank()) { "SMB 连接 ID 不能为空" }
    require(normalized.name.isNotBlank()) { "SMB 连接名称不能为空" }
    require(normalized.host.isNotBlank() && normalized.host.none { it.isWhitespace() || it in "/\\@" }) {
        "SMB 主机地址无效"
    }
    require(normalized.port in 1..65535) { "SMB 端口无效" }
    require(normalized.share.isNotBlank() && normalized.share.none { it in "\\/:?*\"<>|" }) {
        "SMB 共享名无效"
    }
    require(normalized.username.isNotBlank()) { "SMB 用户名不能为空" }
    return normalized
}

private class SqlDelightSmbConnectionStore(
    private val database: UnuDatabase,
) : SmbConnectionStore {
    private val queries get() = database.smbQueries

    override suspend fun loadAll(): List<SmbConnection> = withContext(Dispatchers.IO) {
        queries.listAll { id, name, host, port, shareName, username, password, domain, requireEncryption, _ ->
            SmbConnection(
                id = id,
                name = name,
                host = host,
                port = port.toInt(),
                share = shareName,
                username = username,
                password = password,
                domain = domain,
                requireEncryption = requireEncryption != 0L,
            )
        }.executeAsList()
    }

    override suspend fun replaceAll(connections: List<SmbConnection>) = withContext(Dispatchers.IO) {
        database.transaction {
            queries.deleteAll()
            connections.forEachIndexed { index, connection ->
                queries.insert(
                    id = connection.id,
                    name = connection.name,
                    host = connection.host,
                    port = connection.port.toLong(),
                    share_name = connection.share,
                    username = connection.username,
                    password = connection.password,
                    domain = connection.domain,
                    require_encryption = if (connection.requireEncryption) 1L else 0L,
                    sort_order = index.toLong(),
                )
            }
        }
    }
}
