package io.github.weiyongzenqi.unuplayer.local

import io.github.weiyongzenqi.unuplayer.core.platform.Storage
import io.github.weiyongzenqi.unuplayer.core.platform.StorageBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopLocalDirectoryRepositoryTest {
    @Test
    fun `并发新增不会由后写覆盖先写`() = runBlocking {
        val storage = DelayedStorage()
        val repository = DesktopLocalDirectoryRepository(storage)

        coroutineScope {
            (0 until 40).map { index ->
                async(Dispatchers.Default) { repository.add("C:\\media-$index") }
            }.awaitAll()
        }

        val directories = repository.loadAll()
        assertEquals(40, directories.size)
        assertEquals(40, directories.map { it.uri }.toSet().size)
    }

    @Test
    fun `持久化失败时保留旧快照`() = runBlocking {
        val storage = DelayedStorage()
        val repository = DesktopLocalDirectoryRepository(storage)
        repository.add("C:\\existing")
        storage.failNextWrite = true

        assertFailsWith<IllegalStateException> { repository.add("C:\\not-committed") }

        assertEquals(listOf("C:\\existing"), repository.loadAll().map { it.uri })
    }

    private class DelayedStorage : Storage {
        private val values = mutableMapOf<String, Any>()
        var failNextWrite = false

        override suspend fun getString(key: String, default: String?): String? {
            delay(1)
            return values[key] as? String ?: default
        }

        override suspend fun putString(key: String, value: String) {
            delay(1)
            if (failNextWrite) {
                failNextWrite = false
                throw IllegalStateException("injected write failure")
            }
            values[key] = value
        }

        override suspend fun getBoolean(key: String, default: Boolean): Boolean =
            values[key] as? Boolean ?: default

        override suspend fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }

        override suspend fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default

        override suspend fun putInt(key: String, value: Int) {
            values[key] = value
        }

        override suspend fun remove(key: String) {
            values.remove(key)
        }

        override suspend fun edit(block: StorageBatch.() -> Unit) = super.edit(block)
    }
}
