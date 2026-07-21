package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class MediaSourceCacheTest {

    @Test
    fun `同一配置只创建一次且关闭幂等`(): Unit = runBlocking {
        val created = mutableListOf<FakeMediaSource>()
        val cache = MediaSourceCache(FakeFactory {
            FakeMediaSource("source-${created.size}").also(created::add)
        })
        val library = library()

        assertEquals("source-0", cache.withSource(library) { it.displayName })
        assertEquals("source-0", cache.withSource(library) { it.displayName })
        assertEquals(1, created.size)

        cache.close()
        cache.close()

        assertEquals(1, created.single().closeCount)
        assertNull(cache.withSource(library) { it.displayName })
    }

    @Test
    fun `同一库来源配置变化会关闭旧实例并创建新实例`(): Unit = runBlocking {
        val created = mutableListOf<FakeMediaSource>()
        val cache = MediaSourceCache(FakeFactory {
            FakeMediaSource("source-${created.size}").also(created::add)
        })
        val original = library(rootPath = "/old")
        val updated = original.copy(rootPath = "/new")

        assertEquals("source-0", cache.withSource(original) { it.displayName })
        assertEquals("source-1", cache.withSource(updated) { it.displayName })

        assertEquals(1, created[0].closeCount)
        assertEquals(0, created[1].closeCount)
        cache.close()
        assertEquals(1, created[1].closeCount)
    }

    @Test
    fun `并发请求同一库仍为单实例`(): Unit = runBlocking {
        val createEntered = CompletableDeferred<Unit>()
        val allowCreate = CompletableDeferred<Unit>()
        var createCount = 0
        val source = FakeMediaSource("shared")
        val cache = MediaSourceCache(FakeFactory {
            createCount++
            createEntered.complete(Unit)
            allowCreate.await()
            source
        })
        val library = library()

        val names = coroutineScope {
            val requests = List(4) { async { cache.withSource(library) { it.displayName } } }
            createEntered.await()
            allowCreate.complete(Unit)
            requests.awaitAll()
        }

        assertEquals(List(4) { "shared" }, names)
        assertEquals(1, createCount)
        cache.close()
        assertEquals(1, source.closeCount)
    }

    @Test
    fun `关闭缓存会等待活跃租用结束再释放`(): Unit = runBlocking {
        val blockEntered = CompletableDeferred<Unit>()
        val allowBlockExit = CompletableDeferred<Unit>()
        val source = FakeMediaSource("active")
        val cache = MediaSourceCache(FakeFactory { source })
        val library = library()

        val active = async {
            cache.withSource(library) {
                blockEntered.complete(Unit)
                allowBlockExit.await()
                assertFalse(source.isClosed)
                "done"
            }
        }
        blockEntered.await()

        cache.close()
        assertFalse(source.isClosed)
        assertNull(cache.withSource(library) { "unexpected" })

        allowBlockExit.complete(Unit)
        assertEquals("done", active.await())
        assertTrue(source.isClosed)
        assertEquals(1, source.closeCount)
    }

    @Test
    fun `取消活跃租用仍会释放已关闭缓存中的实例`(): Unit = runBlocking {
        val blockEntered = CompletableDeferred<Unit>()
        val source = FakeMediaSource("cancelled")
        val cache = MediaSourceCache(FakeFactory { source })
        val library = library()
        val active = launch {
            cache.withSource(library) {
                blockEntered.complete(Unit)
                awaitCancellation()
            }
        }
        blockEntered.await()

        cache.close()
        assertFalse(source.isClosed)
        active.cancelAndJoin()

        assertTrue(source.isClosed)
        assertEquals(1, source.closeCount)
    }

    @Test
    fun `创建失败不会保留旧配置实例且可重试`(): Unit = runBlocking {
        val originalSource = FakeMediaSource("old")
        val recoveredSource = FakeMediaSource("new")
        var call = 0
        val cache = MediaSourceCache(FakeFactory {
            when (call++) {
                0 -> originalSource
                1 -> error("create failed")
                else -> recoveredSource
            }
        })
        val original = library(rootPath = "/old")
        val updated = original.copy(rootPath = "/new")

        assertEquals("old", cache.withSource(original) { it.displayName })
        assertFailsWith<IllegalStateException> {
            cache.withSource(updated) { it.displayName }
        }
        assertEquals(1, originalSource.closeCount)

        assertEquals("new", cache.withSource(updated) { it.displayName })
        cache.invalidate(updated.id)
        assertEquals(1, recoveredSource.closeCount)
    }

    @Test
    fun `同连接凭据指纹变化会关闭旧实例并创建新实例`(): Unit = runBlocking {
        val created = mutableListOf<FakeMediaSource>()
        var token: String? = "token-a"
        // TTL=0: 每次租用都重验指纹, 测试不依赖真实时间窗口
        val cache = MediaSourceCache(
            factory = FakeFactory(
                createBlock = { FakeMediaSource("source-${created.size}").also(created::add) },
                credentialsTokenBlock = { token },
            ),
            credentialsTokenTtl = Duration.ZERO,
        )
        val library = library()

        assertEquals("source-0", cache.withSource(library) { it.displayName })
        token = "token-b"   // 模拟密码被编辑: 库配置不变, 仅凭据指纹变化
        assertEquals("source-1", cache.withSource(library) { it.displayName })

        assertEquals(1, created[0].closeCount)   // 旧源按退役机制恰好关闭一次
        assertEquals(0, created[1].closeCount)
        cache.close()
        assertEquals(1, created[1].closeCount)
    }

    private fun library(rootPath: String = "/media") = LibraryConfig(
        id = 7L,
        name = "测试库",
        sourceKind = MediaSourceKind.LOCAL,
        connectionId = null,
        localUri = rootPath,
        rootPath = rootPath,
        scanDepth = 3,
        lastScannedAt = null,
        createdAt = 1L,
    )

    private class FakeFactory(
        private val credentialsTokenBlock: suspend (LibraryConfig) -> String? = { null },
        private val createBlock: suspend (LibraryConfig) -> MediaSource?,
    ) : MediaSourceFactory {
        override suspend fun create(library: LibraryConfig): MediaSource? = createBlock(library)
        override suspend fun credentialsToken(library: LibraryConfig): String? = credentialsTokenBlock(library)
    }

    private class FakeMediaSource(
        override val displayName: String,
    ) : MediaSource {
        override val kind: MediaSourceKind = MediaSourceKind.LOCAL
        var closeCount: Int = 0
            private set
        val isClosed: Boolean get() = closeCount > 0

        override suspend fun listFolder(path: String): List<MediaEntry> = emptyList()
        override suspend fun resolvePlayMedia(entry: MediaEntry): PlayableMedia = error("未用于本测试")
        override suspend fun testConnection(): Boolean = true
        override fun close() {
            closeCount++
        }
    }
}
