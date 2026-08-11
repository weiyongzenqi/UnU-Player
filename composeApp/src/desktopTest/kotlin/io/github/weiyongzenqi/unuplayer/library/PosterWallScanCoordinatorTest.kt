package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.domain.SettingsState
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class PosterWallScanCoordinatorTest {
    @Test
    fun `扫描在创建媒体源前发布状态并在停止收尾后完成`() = runBlocking {
        val factory = BlockingMediaSourceFactory()
        val coordinator = PosterWallScanCoordinator(unusedRepository(), factory)
        val library = LibraryConfig(
            id = 7,
            name = "coordinator-test",
            sourceKind = MediaSourceKind.LOCAL,
            connectionId = null,
            localUri = "content://test",
            rootPath = "/",
            scanDepth = 2,
            lastScannedAt = null,
            createdAt = 0,
        )

        try {
            coordinator.startScan(library, SettingsState(), force = false)
            assertTrue(coordinator.state.value.isScanning)
            assertEquals("正在准备增量扫描...", coordinator.state.value.status)
            withTimeout(2_000) { factory.started.await() }

            coordinator.stopScan()
            assertTrue(coordinator.state.value.isScanning)
            assertEquals("正在停止扫描...", coordinator.state.value.status)
            withTimeout(2_000) {
                while (coordinator.state.value.isScanning) delay(10)
            }
            assertEquals("已停止", coordinator.state.value.status)
        } finally {
            coordinator.close()
        }
    }

    private class BlockingMediaSourceFactory : MediaSourceFactory {
        val started = CompletableDeferred<Unit>()

        override suspend fun create(library: LibraryConfig): io.github.weiyongzenqi.unuplayer.core.media.MediaSource? {
            started.complete(Unit)
            awaitCancellation()
        }

        override suspend fun credentialsToken(library: LibraryConfig): String? = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun unusedRepository(): ScrapedLibraryRepository = Proxy.newProxyInstance(
        ScrapedLibraryRepository::class.java.classLoader,
        arrayOf(ScrapedLibraryRepository::class.java),
    ) { _, method, _ ->
        error("扫描停止前不应调用 repository.${method.name}")
    } as ScrapedLibraryRepository
}
