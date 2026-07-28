package io.github.weiyongzenqi.unuplayer.mediaserver

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import io.github.weiyongzenqi.unuplayer.core.security.DesktopCredentialCipher
import io.github.weiyongzenqi.unuplayer.core.security.PROTECTED_CREDENTIAL_PREFIX
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabase
import io.github.weiyongzenqi.unuplayer.playback.configuredDesktopDataSource
import io.github.weiyongzenqi.unuplayer.playback.ensureCurrentDesktopSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MediaServerConnectionRepositoryTest {

    @Test
    fun `连接 token 加密持久化且未变化时复用原密文`() = runBlocking {
        withDatabase("media-server-encryption") { database ->
            val repository = MediaServerConnectionRepository(database, DesktopCredentialCipher())
            val original = connection("first", "token-first")

            assertEquals(listOf(original), repository.add(original))
            val firstCiphertext = database.persistedToken("first")
            assertTrue(firstCiphertext.startsWith(PROTECTED_CREDENTIAL_PREFIX))
            assertFalse(firstCiphertext.contains("token-first"))

            val renamed = original.copy(name = "更新后的名称")
            assertEquals(listOf(renamed), repository.update(renamed))
            assertEquals(firstCiphertext, database.persistedToken("first"))

            val renewed = renamed.copy(accessToken = "token-renewed")
            assertEquals(listOf(renewed), repository.update(renewed))
            val renewedCiphertext = database.persistedToken("first")
            assertNotEquals(firstCiphertext, renewedCiphertext)
            assertFalse(renewedCiphertext.contains("token-renewed"))

            val reloaded = MediaServerConnectionRepository(database, DesktopCredentialCipher()).loadAll()
            assertEquals(listOf(renewed), reloaded)
        }
    }

    @Test
    fun `历史明文自动迁移且损坏密文保留为凭据失效`() = runBlocking {
        withDatabase("media-server-migration") { database ->
            val brokenCiphertext = PROTECTED_CREDENTIAL_PREFIX + "broken"
            database.insertRaw(connection("legacy", "legacy-token"))
            database.insertRaw(connection("broken", brokenCiphertext), sortOrder = 1)
            val repository = MediaServerConnectionRepository(database, DesktopCredentialCipher())

            val loaded = repository.loadAll()

            assertEquals("legacy-token", loaded[0].accessToken)
            assertFalse(loaded[0].credentialUnavailable)
            assertEquals("", loaded[1].accessToken)
            assertTrue(loaded[1].credentialUnavailable)
            assertTrue(database.persistedToken("legacy").startsWith(PROTECTED_CREDENTIAL_PREFIX))
            assertEquals(brokenCiphertext, database.persistedToken("broken"))

            val renamedBroken = loaded[1].copy(name = "损坏但保留")
            repository.update(renamedBroken)
            assertEquals(brokenCiphertext, database.persistedToken("broken"))
            assertFailsWith<IllegalArgumentException> {
                repository.update(renamedBroken.copy(serverId = "another-server"))
            }
        }
    }

    @Test
    fun `并发新增由同一事务锁串行且不会丢更新`() = runBlocking {
        val store = InMemoryMediaServerConnectionStore()
        val repository = MediaServerConnectionRepository(store, DesktopCredentialCipher())

        listOf(
            async(Dispatchers.Default) { repository.add(connection("first", "token-first")) },
            async(Dispatchers.Default) { repository.add(connection("second", "token-second")) },
        ).awaitAll()

        val loaded = repository.loadAll()
        assertEquals(2, loaded.size)
        assertEquals(setOf("first", "second"), loaded.map { it.id }.toSet())
        assertEquals(1, store.maxConcurrentReplacements)
    }

    @Test
    fun `HTTP 连接保存默认拒绝且失败不改原快照`() = runBlocking {
        val store = InMemoryMediaServerConnectionStore()
        val repository = MediaServerConnectionRepository(store, DesktopCredentialCipher())
        val secure = connection("secure", "secure-token")
        val cleartext = connection("cleartext", "cleartext-token").copy(
            baseUrl = "http://192.168.1.20:8096/",
        )

        repository.add(secure)
        assertFailsWith<IllegalArgumentException> { repository.add(cleartext) }
        assertEquals(listOf(secure), repository.loadAll())

        val expected = cleartext.copy(baseUrl = "http://192.168.1.20:8096")
        assertEquals(
            setOf(secure, expected),
            repository.add(cleartext, allowCleartext = true).toSet(),
        )
    }

    @Test
    fun `会话只在厂商地址与服务器身份全部匹配时释放 token`() = runBlocking {
        val repository = MediaServerConnectionRepository(
            InMemoryMediaServerConnectionStore(),
            DesktopCredentialCipher(),
        )
        val saved = connection("bound", "bound-secret").copy(
            baseUrl = "https://MEDIA.example.test/reverse/jellyfin/",
            serverId = "server-bound",
        )
        val normalized = saved.copy(baseUrl = "https://MEDIA.example.test/reverse/jellyfin")
        repository.add(saved)
        val client = clientIdentity()
        val matching = publicInfo(
            vendor = MediaServerVendor.JELLYFIN,
            serverId = "server-bound",
            apiBaseUrl = "https://media.example.test/reverse/jellyfin",
        )

        val session = repository.createSession("bound", matching, client)

        assertEquals("bound", session.connectionId)
        assertEquals("bound-secret", session.accessToken)
        assertEquals(normalized.baseUrl, session.apiBaseUrl)
        assertEquals("12.0.0", session.serverVersion)

        assertFailsWith<IllegalStateException> {
            repository.createSession(
                "bound",
                matching,
                client.copy(deviceId = "another-device"),
            )
        }

        listOf(
            matching.copy(vendor = MediaServerVendor.EMBY),
            matching.copy(serverId = "another-server"),
            matching.copy(apiBaseUrl = "https://attacker.example.test/reverse/jellyfin"),
            matching.copy(apiBaseUrl = "https://media.example.test/other/jellyfin"),
        ).forEach { mismatched ->
            val error = assertFailsWith<IllegalStateException> {
                repository.createSession("bound", mismatched, client)
            }
            assertFalse(error.message.orEmpty().contains("bound-secret"))
        }
    }

    @Test
    fun `连接文本输出不包含 token 或地址查询凭据`() {
        val text = connection("string", "to-string-secret").copy(
            baseUrl = "https://media.example.test?api_key=url-secret",
        ).toString()

        assertFalse(text.contains("to-string-secret"))
        assertFalse(text.contains("url-secret"))
    }

    private fun connection(id: String, accessToken: String): MediaServerConnection =
        MediaServerConnection(
            id = id,
            vendor = MediaServerVendor.JELLYFIN,
            name = "连接-$id",
            baseUrl = "https://media.example.test/$id",
            serverId = "server-$id",
            serverVersion = "10.11.11",
            userId = "user-$id",
            username = "username-$id",
            accessToken = accessToken,
            deviceId = "device-installation",
        )

    private fun publicInfo(
        vendor: MediaServerVendor,
        serverId: String,
        apiBaseUrl: String,
    ): MediaServerPublicInfo = MediaServerPublicInfo(
        vendor = vendor,
        serverId = serverId,
        serverName = "测试服务器",
        version = "12.0.0",
        productName = "Jellyfin Server",
        apiBaseUrl = apiBaseUrl,
    )

    private fun clientIdentity() = MediaServerClientIdentity(
        clientName = "UnU Player",
        clientVersion = "0.1.2",
        deviceName = "Android",
        deviceId = "device-installation",
    )

    private suspend fun withDatabase(
        name: String,
        block: suspend (UnuDatabase) -> Unit,
    ) {
        val directory = Files.createTempDirectory("unu-$name-")
        val databaseFile = directory.resolve("connections.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${databaseFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            block(UnuDatabase(driver))
        } finally {
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun UnuDatabase.insertRaw(connection: MediaServerConnection, sortOrder: Long = 0) {
        mediaServerQueries.insert(
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
            sort_order = sortOrder,
        )
    }

    private fun UnuDatabase.persistedToken(id: String): String =
        mediaServerQueries.listAll {
                storedId, _, _, _, _, _, _, _, accessToken, _, _,
            ->
            storedId to accessToken
        }.executeAsList().single { it.first == id }.second

    private class InMemoryMediaServerConnectionStore : MediaServerConnectionStore {
        private var connections = emptyList<MediaServerConnection>()
        private var activeReplacements = 0
        var maxConcurrentReplacements = 0
            private set

        override suspend fun loadAll(): List<MediaServerConnection> = connections

        override suspend fun replaceAll(connections: List<MediaServerConnection>) {
            activeReplacements++
            maxConcurrentReplacements = maxOf(maxConcurrentReplacements, activeReplacements)
            try {
                yield()
                this.connections = connections
            } finally {
                activeReplacements--
            }
        }
    }
}
