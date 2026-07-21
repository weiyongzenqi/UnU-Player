package io.github.weiyongzenqi.unuplayer.ui

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import io.github.weiyongzenqi.unuplayer.core.platform.Storage
import io.github.weiyongzenqi.unuplayer.core.security.DesktopCredentialCipher
import io.github.weiyongzenqi.unuplayer.core.security.EncryptedSecretStorage
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepositoryImpl
import io.github.weiyongzenqi.unuplayer.domain.StartupHome

class StartupHomeTest {

    @Test
    fun `旧用户默认进入 WebDAV 并可持久化新选择`() = runBlocking {
        val storage = InMemoryStorage()
        val repository = SettingsRepositoryImpl(storage, this, EncryptedSecretStorage(storage, DesktopCredentialCipher()))

        repository.awaitLoaded()
        assertEquals(StartupHome.WEBDAV, repository.state.value.startupHome)

        repository.update { it.copy(startupHome = StartupHome.LOCAL) }
        assertEquals("LOCAL", storage.getString("startupHome"))
    }

    @Test
    fun `非法持久化值安全回退 WebDAV`() = runBlocking {
        val storage = InMemoryStorage().apply { putString("startupHome", "UNKNOWN") }
        val repository = SettingsRepositoryImpl(storage, this, EncryptedSecretStorage(storage, DesktopCredentialCipher()))

        repository.awaitLoaded()

        assertEquals(StartupHome.WEBDAV, repository.state.value.startupHome)
    }

    @Test
    fun `桌面后台与关闭提示设置可持久化`() = runBlocking {
        val storage = InMemoryStorage()
        val repository = SettingsRepositoryImpl(storage, this, EncryptedSecretStorage(storage, DesktopCredentialCipher()))

        repository.awaitLoaded()
        assertEquals(false, repository.state.value.desktopRunInBackground)
        assertEquals(true, repository.state.value.desktopClosePrompt)

        repository.update {
            it.copy(desktopRunInBackground = true, desktopClosePrompt = false)
        }

        assertEquals(true, storage.getBoolean("desktopRunInBackground", false))
        assertEquals(false, storage.getBoolean("desktopClosePrompt", true))
    }

    @Test
    fun `番剧首页不可用时只临时回退 WebDAV`() {
        assertEquals(UnUTab.ANIME, resolveStartupTab(StartupHome.ANIME, animeAvailable = true))
        assertEquals(UnUTab.WEBDAV, resolveStartupTab(StartupHome.ANIME, animeAvailable = false))
        assertEquals(UnUTab.LOCAL, resolveStartupTab(StartupHome.LOCAL, animeAvailable = false))
    }

    private class InMemoryStorage : Storage {
        private val values = mutableMapOf<String, Any>()

        override suspend fun getString(key: String, default: String?): String? = values[key] as? String ?: default
        override suspend fun putString(key: String, value: String) { values[key] = value }
        override suspend fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
        override suspend fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override suspend fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default
        override suspend fun putInt(key: String, value: Int) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }
}
