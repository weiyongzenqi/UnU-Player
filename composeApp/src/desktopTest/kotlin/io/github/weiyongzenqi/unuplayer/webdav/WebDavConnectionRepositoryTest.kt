package io.github.weiyongzenqi.unuplayer.webdav

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteDataSource
import io.github.weiyongzenqi.unuplayer.core.security.DesktopCredentialCipher
import io.github.weiyongzenqi.unuplayer.core.security.PROTECTED_CREDENTIAL_PREFIX
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabase
import io.github.weiyongzenqi.unuplayer.playback.configuredDesktopDataSource
import io.github.weiyongzenqi.unuplayer.playback.ensureCurrentDesktopSchema
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebDavConnectionRepositoryTest {

    @Test
    fun `历史 URL userInfo 会迁移且损坏密文不会降级为匿名连接`() = runBlocking {
        val directory = Files.createTempDirectory("unu-webdav-security-migration-")
        val databaseFile = directory.resolve("connections.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${databaseFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val database = UnuDatabase(driver)
            val repository = WebDavConnectionRepository(database, DesktopCredentialCipher())
            database.webdavQueries.insert(
                id = "legacy-userinfo",
                name = "旧连接",
                base_url = "https://legacy-user:legacy%20pass@example.invalid/dav",
                username = "",
                password = "",
                sort_order = 0,
            )
            val brokenCiphertext = PROTECTED_CREDENTIAL_PREFIX + "not-valid-dpapi"
            database.webdavQueries.insert(
                id = "broken",
                name = "损坏连接",
                base_url = "https://example.invalid/broken",
                username = "broken-user",
                password = brokenCiphertext,
                sort_order = 1,
            )

            val loaded = repository.loadAll()

            assertEquals("https://example.invalid/dav", loaded[0].baseUrl)
            assertEquals("legacy-user", loaded[0].username)
            assertEquals("legacy pass", loaded[0].password)
            assertFalse(loaded[0].credentialUnavailable)
            assertEquals("", loaded[1].password)
            assertTrue(loaded[1].credentialUnavailable)
            assertFalse(loaded[1].toString().contains("broken-user-password"))
            assertEquals(
                brokenCiphertext,
                database.webdavQueries.listAll { id, _, _, _, password, _ -> id to password }
                    .executeAsList().single { it.first == "broken" }.second,
            )
            assertTrue(database.persistedPasswords().first().startsWith(PROTECTED_CREDENTIAL_PREFIX))
        } finally {
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `连接地址和非密码字段会统一规范化`() = runBlocking {
        val directory = Files.createTempDirectory("unu-webdav-normalization-")
        val databaseFile = directory.resolve("connections.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${databaseFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val database = UnuDatabase(driver)
            val repository = WebDavConnectionRepository(database, DesktopCredentialCipher())

            database.webdavQueries.insert(
                id = "legacy",
                name = "  主服务器  ",
                base_url = "https://example.invalid/dav\n",
                username = "  legacy-user\t",
                password = " secret ",
                sort_order = 0,
            )

            assertEquals(
                listOf(
                    WebDavConnection(
                        id = "legacy",
                        name = "主服务器",
                        baseUrl = "https://example.invalid/dav",
                        username = "legacy-user",
                        password = " secret ",
                    ),
                ),
                repository.loadAll(),
            )
            assertTrue(database.persistedPasswords().single().startsWith(PROTECTED_CREDENTIAL_PREFIX))
            assertFalse(database.persistedPasswords().single().contains(" secret "))

            val saved = connection("saved", "  保存连接  ").copy(
                baseUrl = " https://example.invalid/saved/\n",
                username = " saved-user ",
                password = " saved-secret ",
            )
            repository.save(listOf(saved))
            assertEquals(listOf("https://example.invalid/saved"), database.persistedBaseUrls())

            val added = connection("added", "  新增连接  ").copy(
                baseUrl = "https://example.invalid/added/\r\n",
                username = " added-user ",
                password = " added-secret ",
            )
            assertEquals(
                listOf(saved.normalizedForAssertion(), added.normalizedForAssertion()),
                repository.add(added),
            )
            assertEquals(
                listOf("https://example.invalid/saved", "https://example.invalid/added"),
                database.persistedBaseUrls(),
            )

            val updated = added.copy(
                name = "  更新连接  ",
                baseUrl = "\thttps://example.invalid/updated///\n",
                username = " updated-user\r\n",
                password = " updated-secret ",
            )
            assertEquals(
                listOf(saved.normalizedForAssertion(), updated.normalizedForAssertion()),
                repository.update(updated),
            )
            assertEquals(
                listOf("https://example.invalid/saved", "https://example.invalid/updated"),
                database.persistedBaseUrls(),
            )
        } finally {
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `WebDAV 连接按顺序持久化到统一数据库并支持增删改`() = runBlocking {
        val directory = Files.createTempDirectory("unu-webdav-database-")
        val databaseFile = directory.resolve("connections.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${databaseFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val database = UnuDatabase(driver)
            val repository = WebDavConnectionRepository(database, DesktopCredentialCipher())
            val first = connection("first", "主服务器")
            val second = connection("second", "备用服务器")

            assertEquals(listOf(first), repository.add(first))
            val firstCiphertext = database.persistedPassword("first")
            assertEquals(listOf(first, second), repository.add(second))
            assertEquals(firstCiphertext, database.persistedPassword("first"))

            assertFailsWith<IllegalStateException> {
                repository.playbackHeaders(first.id, "https://attacker.invalid/video.mkv")
            }

            val updated = first.copy(name = "主服务器（更新）", password = "new-password")
            assertEquals(listOf(updated, second), repository.update(updated))
            assertEquals(listOf(updated, second), repository.loadAll())
            assertTrue(database.persistedPasswords().all { it.startsWith(PROTECTED_CREDENTIAL_PREFIX) })
            assertTrue(database.persistedPasswords().none { it.contains("password") })

            assertEquals(listOf(updated), repository.remove(second.id))
            assertEquals(listOf(updated), repository.loadAll())
            assertFalse(
                WebDavConnection(
                    id = "string",
                    name = "测试",
                    baseUrl = "https://url-user:url-pass@example.invalid/dav",
                    username = "user",
                    password = "to-string-canary",
                ).toString().contains("to-string-canary"),
            )
            assertFalse(
                WebDavConnection(
                    id = "string",
                    name = "测试",
                    baseUrl = "https://url-user:url-pass@example.invalid/dav",
                    username = "user",
                    password = "to-string-canary",
                ).toString().contains("url-user:url-pass"),
            )
        } finally {
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `HTTP 连接新增时必须明确授权`() = runBlocking {
        val store = InMemoryWebDavConnectionStore()
        val repository = WebDavConnectionRepository(store, DesktopCredentialCipher())
        val cleartext = connection("http", "明文服务器").copy(
            baseUrl = " http://192.168.1.20:8080/dav/ ",
        )
        val normalized = cleartext.copy(baseUrl = "http://192.168.1.20:8080/dav")

        assertFailsWith<IllegalArgumentException> { repository.add(cleartext) }
        assertTrue(store.loadAll().isEmpty())

        assertEquals(
            listOf(normalized),
            repository.add(cleartext, allowCleartext = true),
        )
        assertEquals(listOf(normalized), repository.loadAll())
    }

    @Test
    fun `HTTP 连接批量保存默认拒绝且不修改原快照`() = runBlocking {
        val store = InMemoryWebDavConnectionStore()
        val repository = WebDavConnectionRepository(store, DesktopCredentialCipher())
        val original = connection("https", "加密服务器")
        val cleartext = connection("http", "明文服务器").copy(
            baseUrl = "http://192.168.1.20/dav",
        )

        repository.add(original)
        assertFailsWith<IllegalArgumentException> { repository.save(listOf(cleartext)) }
        assertEquals(listOf(original), repository.loadAll())

        repository.save(listOf(cleartext), allowCleartext = true)
        assertEquals(listOf(cleartext), repository.loadAll())
    }

    @Test
    fun `HTTP 连接更新时必须再次明确授权`() = runBlocking {
        val store = InMemoryWebDavConnectionStore()
        val repository = WebDavConnectionRepository(store, DesktopCredentialCipher())
        val cleartext = connection("http", "明文服务器").copy(
            baseUrl = "http://192.168.1.20/dav",
        )
        val updated = cleartext.copy(name = "更新后的明文服务器")

        repository.add(cleartext, allowCleartext = true)
        assertFailsWith<IllegalArgumentException> { repository.update(updated) }
        assertEquals(listOf(cleartext), repository.loadAll())

        assertEquals(
            listOf(updated),
            repository.update(updated, allowCleartext = true),
        )
    }

    @Test
    fun `非法 WebDAV 地址不能保存`() = runBlocking {
        val store = InMemoryWebDavConnectionStore()
        val repository = WebDavConnectionRepository(store, DesktopCredentialCipher())
        val original = connection("https", "加密服务器")
        repository.add(original)

        listOf(
            "ftp://example.invalid/dav",
            "https://",
            "https://user:secret@example.invalid/dav",
            "https://example.invalid/dav?token=value",
            "https://example.invalid/dav#directory",
        ).forEach { invalidUrl ->
            assertFailsWith<IllegalArgumentException> {
                repository.add(connection("invalid", "非法地址").copy(baseUrl = invalidUrl))
            }
        }
        assertEquals(listOf(original), repository.loadAll())
    }

    private fun connection(id: String, name: String) = WebDavConnection(
        id = id,
        name = name,
        baseUrl = "https://example.invalid/$id",
        username = "user-$id",
        password = "password-$id",
    )

    private fun WebDavConnection.normalizedForAssertion() = copy(
        name = name.trim(),
        baseUrl = baseUrl.trim().trimEnd('/'),
        username = username.trim(),
    )

    private fun UnuDatabase.persistedBaseUrls(): List<String> =
        webdavQueries.listAll { _, _, baseUrl, _, _, _ -> baseUrl }.executeAsList()

    private fun UnuDatabase.persistedPasswords(): List<String> =
        webdavQueries.listAll { _, _, _, _, password, _ -> password }.executeAsList()

    private fun UnuDatabase.persistedPassword(id: String): String =
        webdavQueries.listAll { storedId, _, _, _, password, _ -> storedId to password }
            .executeAsList().single { it.first == id }.second

    private class InMemoryWebDavConnectionStore : WebDavConnectionStore {
        private var connections = emptyList<WebDavConnection>()

        override suspend fun loadAll(): List<WebDavConnection> = connections

        override suspend fun replaceAll(connections: List<WebDavConnection>) {
            this.connections = connections
        }
    }
}
