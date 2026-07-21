package io.github.weiyongzenqi.unuplayer.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import io.github.weiyongzenqi.unuplayer.core.platform.Storage
import io.github.weiyongzenqi.unuplayer.core.platform.StorageBatch
import io.github.weiyongzenqi.unuplayer.core.platform.StorageSnapshot
import io.github.weiyongzenqi.unuplayer.core.security.DesktopCredentialCipher
import io.github.weiyongzenqi.unuplayer.core.security.EncryptedSecretStorage
import io.github.weiyongzenqi.unuplayer.core.security.PROTECTED_CREDENTIAL_PREFIX
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsRepositoryImplTest {

    @Test
    fun `设置加载只读取一次平台快照`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = SnapshotStorage()
            val repository = repository(storage, scope)
            repository.awaitLoaded()

            assertEquals(1, storage.snapshotReadCount)
            assertEquals(1, storage.fieldReadCount, "安全凭据保持独立存储读取，普通设置不得逐字段读取")
            assertFalse(repository.state.value.darkTheme)
            assertEquals(64, repository.state.value.cacheSize)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `一次设置更新只请求一次批量事务`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = InMemoryStorage()
            val repository = repository(storage, scope)
            repository.awaitLoaded()

            repository.update { it.copy(cacheSize = 64) }

            assertEquals(1, storage.editCallCount)
            assertEquals(64, repository.state.value.cacheSize)
            assertEquals(64, storage.getInt("cacheSize"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `并发修改不同字段不会丢失`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = InMemoryStorage(delayFirstEdit = true)
            val repository = repository(storage, scope)
            repository.awaitLoaded()

            val first = async {
                repository.update { it.copy(darkTheme = false) }
            }
            withTimeout(2_000) { storage.firstEditStarted.await() }
            val second = async {
                repository.update { it.copy(cacheSize = 64) }
            }
            first.await()
            second.await()

            assertFalse(repository.state.value.darkTheme)
            assertEquals(64, repository.state.value.cacheSize)

            val reloaded = repository(storage, scope)
            reloaded.awaitLoaded()
            assertFalse(reloaded.state.value.darkTheme)
            assertEquals(64, reloaded.state.value.cacheSize)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `桌面GPU渲染开关可在启动前持久恢复`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = InMemoryStorage(initialValues = mapOf(DESKTOP_GPU_RENDERING_KEY to true))
            val repository = repository(storage, scope)
            repository.awaitLoaded()

            assertTrue(repository.state.value.desktopGpuRendering)
            repository.update { it.copy(desktopGpuRendering = false) }

            val reloaded = repository(storage, scope)
            reloaded.awaitLoaded()
            assertFalse(reloaded.state.value.desktopGpuRendering)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `首次读取异常会结束等待并暴露错误`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = InMemoryStorage(failFirstRead = true)
            val repository = repository(storage, scope)

            withTimeout(2_000) { repository.awaitLoaded() }

            val failed = repository.loadState.value
            assertTrue(failed is SettingsLoadState.Failed)
            assertEquals(
                "设置读取失败（IllegalStateException）",
                failed.message,
            )
            assertEquals(SettingsState().darkTheme, repository.state.value.darkTheme)
            assertEquals(SettingsState().cacheSize, repository.state.value.cacheSize)

            repository.useDefaultsAfterLoadFailure()
            assertEquals(SettingsLoadState.Loaded, repository.loadState.value)
            assertEquals(0, storage.editCallCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `加载失败后可重试并恢复持久化设置`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = InMemoryStorage(
                initialValues = mapOf(
                    "darkTheme" to false,
                    "cacheSize" to 64,
                ),
                failFirstRead = true,
            )
            val repository = repository(storage, scope)
            withTimeout(2_000) { repository.awaitLoaded() }
            assertTrue(repository.loadState.value is SettingsLoadState.Failed)

            repository.retryLoad()

            assertEquals(SettingsLoadState.Loaded, repository.loadState.value)
            assertFalse(repository.state.value.darkTheme)
            assertEquals(64, repository.state.value.cacheSize)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `加载失败后update不落盘_避免默认值覆盖用户设置`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = InMemoryStorage(
                initialValues = mapOf("cacheSize" to 64),
                failFirstRead = true,
            )
            val repository = repository(storage, scope)
            withTimeout(2_000) { repository.awaitLoaded() }
            assertTrue(repository.loadState.value is SettingsLoadState.Failed)

            // P3⑫: 加载失败态下 update() 应静默返回, 不写存储(否则 ~80 键默认值覆盖用户设置)
            repository.update { it.copy(cacheSize = 99) }
            assertEquals(0, storage.editCallCount, "Failed 态 update() 不得触发存储写入")
            assertEquals(64, storage.getInt("cacheSize"), "用户原有设置不得被默认值覆盖")

            // 恢复路径不受 guard 影响: useDefaults 切换为 Loaded 后 update 正常落盘
            repository.useDefaultsAfterLoadFailure()
            assertEquals(SettingsLoadState.Loaded, repository.loadState.value)
            repository.update { it.copy(cacheSize = 48) }
            assertEquals(1, storage.editCallCount)
            assertEquals(48, repository.state.value.cacheSize)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `旧版 AppSecret 首次加载后迁移为密文且更新不回写明文`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = InMemoryStorage(
                initialValues = mapOf("dandanplayAppSecret" to "旧版-secret-秘密"),
            )
            val repository = repository(storage, scope)
            repository.awaitLoaded()

            assertEquals("旧版-secret-秘密", repository.state.value.dandanplayAppSecret)
            assertEquals(null, storage.raw("dandanplayAppSecret"))
            assertTrue((storage.raw("credential.dandanplayAppSecret") as String).startsWith(PROTECTED_CREDENTIAL_PREFIX))

            repository.update { it.copy(dandanplayAppSecret = "更新-secret") }

            assertEquals(null, storage.raw("dandanplayAppSecret"))
            assertFalse((storage.raw("credential.dandanplayAppSecret") as String).contains("更新-secret"))
            val reloaded = repository(storage, scope)
            reloaded.awaitLoaded()
            assertEquals("更新-secret", reloaded.state.value.dandanplayAppSecret)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `AppSecret 密文损坏后明确使用默认值会清除坏密文`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = InMemoryStorage(
                initialValues = mapOf(
                    "credential.dandanplayAppSecret" to PROTECTED_CREDENTIAL_PREFIX + "invalid-dpapi",
                ),
            )
            val repository = repository(storage, scope)
            repository.awaitLoaded()

            assertTrue(repository.loadState.value is SettingsLoadState.Failed)
            repository.useDefaultsAfterLoadFailure()

            assertEquals(SettingsLoadState.Loaded, repository.loadState.value)
            assertEquals(null, storage.raw("credential.dandanplayAppSecret"))
            val reloaded = repository(storage, scope)
            reloaded.awaitLoaded()
            assertEquals(SettingsLoadState.Loaded, reloaded.loadState.value)
        } finally {
            scope.cancel()
        }
    }

    private fun repository(storage: Storage, scope: CoroutineScope) = SettingsRepositoryImpl(
        storage,
        scope,
        EncryptedSecretStorage(storage, DesktopCredentialCipher()),
    )

    private class InMemoryStorage(
        private val delayFirstEdit: Boolean = false,
        initialValues: Map<String, Any> = emptyMap(),
        failFirstRead: Boolean = false,
    ) : Storage {
        private val values = initialValues.toMutableMap()
        private var firstEditDelayed = false
        private var shouldFailRead = failFirstRead

        var editCallCount: Int = 0
            private set

        val firstEditStarted = CompletableDeferred<Unit>()

        fun raw(key: String): Any? = values[key]

        override suspend fun getString(key: String, default: String?): String? {
            failReadIfRequested()
            return values[key] as? String ?: default
        }

        override suspend fun putString(key: String, value: String) {
            values[key] = value
        }

        override suspend fun getBoolean(key: String, default: Boolean): Boolean {
            failReadIfRequested()
            return values[key] as? Boolean ?: default
        }

        override suspend fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }

        override suspend fun getInt(key: String, default: Int): Int {
            failReadIfRequested()
            return values[key] as? Int ?: default
        }

        override suspend fun putInt(key: String, value: Int) {
            values[key] = value
        }

        override suspend fun remove(key: String) {
            values.remove(key)
        }

        override suspend fun edit(block: StorageBatch.() -> Unit) {
            editCallCount += 1
            if (delayFirstEdit && !firstEditDelayed) {
                firstEditDelayed = true
                firstEditStarted.complete(Unit)
                delay(100)
            }
            super<Storage>.edit(block)
        }

        private fun failReadIfRequested() {
            if (!shouldFailRead) return
            shouldFailRead = false
            throw IllegalStateException("模拟设置读取失败")
        }
    }

    private class SnapshotStorage : Storage {
        var snapshotReadCount = 0
            private set
        var fieldReadCount = 0
            private set

        override suspend fun readSnapshot(): StorageSnapshot {
            snapshotReadCount++
            return StorageSnapshot(
                mapOf(
                    "darkTheme" to false,
                    "cacheSize" to 64,
                ),
            )
        }

        override suspend fun getString(key: String, default: String?): String? {
            fieldReadCount++
            return default
        }

        override suspend fun getBoolean(key: String, default: Boolean): Boolean {
            fieldReadCount++
            return default
        }

        override suspend fun getInt(key: String, default: Int): Int {
            fieldReadCount++
            return default
        }

        override suspend fun putString(key: String, value: String) = Unit
        override suspend fun putBoolean(key: String, value: Boolean) = Unit
        override suspend fun putInt(key: String, value: Int) = Unit
        override suspend fun remove(key: String) = Unit
    }
}
