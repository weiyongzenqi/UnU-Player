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
    fun `默认进入影视源并可持久化新选择`() = runBlocking {
        val storage = InMemoryStorage()
        val repository = SettingsRepositoryImpl(storage, this, EncryptedSecretStorage(storage, DesktopCredentialCipher()))

        repository.awaitLoaded()
        assertEquals(StartupHome.MEDIA_SOURCE, repository.state.value.startupHome)

        repository.update { it.copy(startupHome = StartupHome.ANIME) }
        assertEquals("ANIME", storage.getString("startupHome"))
    }

    @Test
    fun `非法持久化值安全回退影视源`() = runBlocking {
        val storage = InMemoryStorage().apply { putString("startupHome", "UNKNOWN") }
        val repository = SettingsRepositoryImpl(storage, this, EncryptedSecretStorage(storage, DesktopCredentialCipher()))

        repository.awaitLoaded()

        assertEquals(StartupHome.MEDIA_SOURCE, repository.state.value.startupHome)
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
    fun `番剧首页不可用时只临时回退影视源`() {
        assertEquals(UnUTab.MEDIA_SOURCE, resolveStartupTab(StartupHome.MEDIA_SOURCE, animeAvailable = true))
        assertEquals(UnUTab.ANIME, resolveStartupTab(StartupHome.ANIME, animeAvailable = true))
        assertEquals(UnUTab.MEDIA_SOURCE, resolveStartupTab(StartupHome.ANIME, animeAvailable = false))
        // 最近播放不受 animeAvailable 影响
        assertEquals(UnUTab.RECENT, resolveStartupTab(StartupHome.RECENT, animeAvailable = true))
        assertEquals(UnUTab.RECENT, resolveStartupTab(StartupHome.RECENT, animeAvailable = false))
    }

    @Test
    fun `最近播放首页可持久化`() = runBlocking {
        val storage = InMemoryStorage()
        val repository = SettingsRepositoryImpl(storage, this, EncryptedSecretStorage(storage, DesktopCredentialCipher()))

        repository.awaitLoaded()
        repository.update { it.copy(startupHome = StartupHome.RECENT) }
        assertEquals("RECENT", storage.getString("startupHome"))
        assertEquals(StartupHome.RECENT, repository.state.value.startupHome)
    }

    @Test
    fun `老版本持久化值兼容回退影视源`() = runBlocking {
        // 老版本枚举值 "WEBDAV" / "LOCAL" 在新枚举 {MEDIA_SOURCE, ANIME, RECENT} 下 valueOf 失败,
        // 由 runCatching + getOrDefault 兜底回 MEDIA_SOURCE, 无需额外迁移代码。
        listOf("WEBDAV", "LOCAL").forEach { legacy ->
            val storage = InMemoryStorage().apply { putString("startupHome", legacy) }
            val repository = SettingsRepositoryImpl(storage, this, EncryptedSecretStorage(storage, DesktopCredentialCipher()))
            repository.awaitLoaded()
            assertEquals(StartupHome.MEDIA_SOURCE, repository.state.value.startupHome, "老值 $legacy 应回退 MEDIA_SOURCE")
        }
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
