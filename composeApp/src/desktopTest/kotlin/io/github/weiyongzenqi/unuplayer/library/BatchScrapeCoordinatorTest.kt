package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchScrapeCoordinatorTest {
    @Test
    fun `批量任务停止后保留进度和最终状态`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val coordinator = BatchScrapeCoordinator(unusedFactory())
        val scraper = object : BatchScraper {
            override suspend fun scrapePendingInCoordinator(
                library: LibraryConfig,
                anchorOnly: Boolean,
                concurrency: Int,
                hashProvider: (suspend (String) -> Pair<Long, String>?)?,
                cooldownMs: Long,
                onProgress: suspend (Int, Int, String) -> Unit,
            ): Int {
                started.complete(Unit)
                onProgress(1, 3, "正在处理测试番剧")
                awaitCancellation()
            }
        }
        val library = LibraryConfig(
            id = 11,
            name = "batch-test",
            sourceKind = MediaSourceKind.LOCAL,
            connectionId = null,
            localUri = "content://test",
            rootPath = "/",
            scanDepth = 2,
            lastScannedAt = null,
            createdAt = 0,
        )

        try {
            assertTrue(
                coordinator.start(
                    library = library,
                    scraper = scraper,
                    anchorOnly = false,
                    concurrency = 1,
                    reason = BatchScrapeReason.MANUAL,
                ),
            )
            withTimeout(2_000) { started.await() }
            withTimeout(2_000) {
                while (coordinator.state.value.completed != 1) delay(10)
            }
            coordinator.stop()
            withTimeout(2_000) {
                while (coordinator.state.value.isRunning) delay(10)
            }
            assertFalse(coordinator.state.value.isRunning)
            assertTrue(coordinator.state.value.status.startsWith("已停止批量补刮"))
            assertTrue(coordinator.state.value.completed == 1)
            assertTrue(coordinator.state.value.total == 3)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `停止后可立即启动新任务且旧任务不覆盖新状态`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val coordinator = BatchScrapeCoordinator(unusedFactory())
        val firstScraper = suspendingScraper(firstStarted, 1, 3, "旧任务")
        val secondScraper = object : BatchScraper {
            override suspend fun scrapePendingInCoordinator(
                library: LibraryConfig,
                anchorOnly: Boolean,
                concurrency: Int,
                hashProvider: (suspend (String) -> Pair<Long, String>?)?,
                cooldownMs: Long,
                onProgress: suspend (Int, Int, String) -> Unit,
            ): Int {
                secondStarted.complete(Unit)
                onProgress(2, 2, "新任务")
                return 2
            }
        }

        try {
            assertTrue(coordinator.start(library(11), firstScraper, false, 1, BatchScrapeReason.MANUAL))
            withTimeout(2_000) { firstStarted.await() }
            coordinator.stop()
            withTimeout(2_000) {
                while (coordinator.state.value.isRunning) delay(10)
            }
            assertTrue(coordinator.start(library(12), secondScraper, false, 1, BatchScrapeReason.MANUAL))
            withTimeout(2_000) { secondStarted.await() }
            withTimeout(2_000) {
                while (coordinator.state.value.isRunning) delay(10)
            }
            assertEquals(12L, coordinator.state.value.libraryId)
            assertEquals(2, coordinator.state.value.successful)
            assertTrue(coordinator.state.value.status.startsWith("批量补刮完成"))
        } finally {
            coordinator.close()
        }
    }

    private fun suspendingScraper(
        started: CompletableDeferred<Unit>,
        completed: Int,
        total: Int,
        title: String,
    ): BatchScraper = object : BatchScraper {
        override suspend fun scrapePendingInCoordinator(
            library: LibraryConfig,
            anchorOnly: Boolean,
            concurrency: Int,
            hashProvider: (suspend (String) -> Pair<Long, String>?)?,
            cooldownMs: Long,
            onProgress: suspend (Int, Int, String) -> Unit,
        ): Int {
            started.complete(Unit)
            onProgress(completed, total, title)
            awaitCancellation()
        }
    }

    private fun library(id: Long) = LibraryConfig(
        id = id,
        name = "batch-test-$id",
        sourceKind = MediaSourceKind.LOCAL,
        connectionId = null,
        localUri = "content://test/$id",
        rootPath = "/",
        scanDepth = 2,
        lastScannedAt = null,
        createdAt = 0,
    )

    private fun unusedFactory(): MediaSourceFactory = object : MediaSourceFactory {
        override suspend fun create(library: LibraryConfig): MediaSource? = null

        override suspend fun credentialsToken(library: LibraryConfig): String? = null
    }
}
