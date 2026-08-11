package io.github.weiyongzenqi.unuplayer.smb

import io.github.weiyongzenqi.unuplayer.core.security.CredentialCipher
import io.github.weiyongzenqi.unuplayer.core.security.PROTECTED_CREDENTIAL_PREFIX
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmbConnectionRepositoryTest {
    @Test
    fun `新增连接只存密文且读取恢复明文`() = runBlocking {
        val store = FakeStore()
        val repository = SmbConnectionRepository(store, FakeCipher())

        val connection = sample("one", "secret")
        repository.add(connection)

        assertTrue(store.connections.single().password.startsWith(PROTECTED_CREDENTIAL_PREFIX))
        assertFalse(store.connections.single().password.contains("secret"))
        assertEquals(connection, repository.loadAll().single())
        assertFalse(connection.toString().contains("secret"))
        assertFalse(connection.toString().contains(connection.username))
    }

    @Test
    fun `删除其他连接时损坏密文原样保留`() = runBlocking {
        val broken = PROTECTED_CREDENTIAL_PREFIX + "broken"
        val store = FakeStore(mutableListOf(sample("broken", broken), sample("remove", "plain")))
        val repository = SmbConnectionRepository(store, FakeCipher())

        val loaded = repository.loadAll()
        assertTrue(loaded.first { it.id == "broken" }.credentialUnavailable)
        repository.remove("remove")

        assertEquals(broken, store.connections.single().password)
        assertTrue(repository.loadAll().single().credentialUnavailable)
    }

    @Test
    fun `添加和编辑其他连接时损坏密文原样保留`() = runBlocking {
        val broken = PROTECTED_CREDENTIAL_PREFIX + "broken"
        val store = FakeStore(mutableListOf(sample("broken", broken), sample("edit", "old")))
        val repository = SmbConnectionRepository(store, FakeCipher())

        repository.add(sample("added", "new"))
        repository.update(sample("edit", "updated"))

        assertEquals(broken, store.connections.first { it.id == "broken" }.password)
        assertTrue(repository.loadAll().first { it.id == "broken" }.credentialUnavailable)
        assertEquals("updated", repository.loadAll().first { it.id == "edit" }.password)
    }

    @Test
    fun `重新输入密码可修复损坏凭据`() = runBlocking {
        val store = FakeStore(mutableListOf(sample("broken", PROTECTED_CREDENTIAL_PREFIX + "broken")))
        val repository = SmbConnectionRepository(store, FakeCipher())
        val unavailable = repository.loadAll().single()

        repository.update(unavailable.copy(password = "replacement", credentialUnavailable = false))

        assertFalse(repository.loadAll().single().credentialUnavailable)
        assertEquals("replacement", repository.loadAll().single().password)
        assertFalse(store.connections.single().password.contains("replacement"))
    }

    private fun sample(id: String, password: String) = SmbConnection(
        id = id,
        name = "NAS",
        host = "192.0.2.1",
        share = "media",
        username = "viewer",
        password = password,
    )

    private class FakeStore(
        var connections: MutableList<SmbConnection> = mutableListOf(),
    ) : SmbConnectionStore {
        override suspend fun loadAll(): List<SmbConnection> = connections.toList()

        override suspend fun replaceAll(connections: List<SmbConnection>) {
            this.connections = connections.toMutableList()
        }
    }

    private class FakeCipher : CredentialCipher {
        override fun protect(purpose: String, plaintext: String): String =
            PROTECTED_CREDENTIAL_PREFIX + plaintext.reversed()

        override fun unprotect(purpose: String, protectedValue: String): String {
            if (protectedValue == PROTECTED_CREDENTIAL_PREFIX + "broken") error("broken")
            return protectedValue.removePrefix(PROTECTED_CREDENTIAL_PREFIX).reversed()
        }
    }
}
