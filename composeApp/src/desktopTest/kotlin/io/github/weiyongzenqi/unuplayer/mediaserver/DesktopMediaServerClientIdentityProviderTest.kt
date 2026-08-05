package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.core.platform.Storage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopMediaServerClientIdentityProviderTest {

    @Test
    fun `并发读取复用同一 Windows 设备身份`() = runBlocking {
        val storage = InMemoryStorage()
        val providers = List(4) {
            DesktopMediaServerClientIdentityProvider(storage, "0.1.6")
        }

        val identities = coroutineScope {
            providers.map { provider -> async { provider.get() } }.awaitAll()
        }

        assertEquals(1, identities.map { it.deviceId }.distinct().size)
        assertTrue(identities.all { it.clientName == "UnU Player" })
        assertTrue(identities.all { it.clientVersion == "0.1.6" })
        assertTrue(identities.all { it.deviceName == "Windows" })
    }

    private class InMemoryStorage : Storage {
        private val values = mutableMapOf<String, Any>()

        override suspend fun getString(key: String, default: String?): String? =
            synchronized(values) { values[key] as? String ?: default }

        override suspend fun putString(key: String, value: String) {
            synchronized(values) { values[key] = value }
        }

        override suspend fun getBoolean(key: String, default: Boolean): Boolean = default
        override suspend fun putBoolean(key: String, value: Boolean) = Unit
        override suspend fun getInt(key: String, default: Int): Int = default
        override suspend fun putInt(key: String, value: Int) = Unit
        override suspend fun remove(key: String) {
            synchronized(values) { values.remove(key) }
        }
    }
}
