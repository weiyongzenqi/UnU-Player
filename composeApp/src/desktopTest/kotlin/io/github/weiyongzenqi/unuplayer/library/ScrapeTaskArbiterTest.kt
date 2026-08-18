package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScrapeTaskArbiterTest {

    @Test
    fun `前台任务抢占同番剧后台任务且旧租约不删除新所有权`() = runBlocking {
        val arbiter = ScrapeTaskArbiter()
        val background = assertNotNull(arbiter.acquire("1\u0000show", ScrapeTaskPriority.BACKGROUND))
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        val backgroundResult = async {
            runCatching {
                background.runPreemptible {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupStarted.complete(Unit)
                            cleanupRelease.await()
                        }
                    }
                }
            }
        }

        started.await()
        val interactiveAcquire = async {
            arbiter.acquire("1\u0000show", ScrapeTaskPriority.INTERACTIVE)
        }
        cleanupStarted.await()
        assertFalse(interactiveAcquire.isCompleted, "新任务必须等待旧任务完成取消收尾")
        cleanupRelease.complete(Unit)
        val interactive = assertNotNull(interactiveAcquire.await())
        assertIs<ScrapePreemptedException>(backgroundResult.await().exceptionOrNull())

        background.release()
        assertNull(arbiter.acquire("1\u0000show", ScrapeTaskPriority.BACKGROUND))
        interactive.release()
        val nextBackground = assertNotNull(arbiter.acquire("1\u0000show", ScrapeTaskPriority.BACKGROUND))
        nextBackground.release()
    }

    @Test
    fun `同优先级任务不互相抢占而手动任务可接管自动任务`() = runBlocking {
        val arbiter = ScrapeTaskArbiter()
        val interactive = assertNotNull(
            arbiter.acquire("1\u0000show", ScrapeTaskPriority.INTERACTIVE),
        )
        assertNull(arbiter.acquire("1\u0000show", ScrapeTaskPriority.INTERACTIVE))

        val started = CompletableDeferred<Unit>()
        val interactiveResult = async {
            runCatching {
                interactive.runPreemptible {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        started.await()
        val manual = assertNotNull(arbiter.acquire("1\u0000show", ScrapeTaskPriority.MANUAL))
        assertIs<ScrapePreemptedException>(interactiveResult.await().exceptionOrNull())

        interactive.release()
        manual.release()
    }

    @Test
    fun `前台番剧与其他番剧的后台任务可并行持有`() = runBlocking {
        val arbiter = ScrapeTaskArbiter()
        val background = assertNotNull(arbiter.acquire("1\u0000show-a", ScrapeTaskPriority.BACKGROUND))
        val interactive = assertNotNull(arbiter.acquire("1\u0000show-b", ScrapeTaskPriority.INTERACTIVE))

        background.release()
        interactive.release()
    }

    @Test
    fun `同番剧旧任务清理挂起时其他番剧仍可获取租约`() = runBlocking {
        val arbiter = ScrapeTaskArbiter()
        val background = assertNotNull(arbiter.acquire("1\u0000show-a", ScrapeTaskPriority.BACKGROUND))
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        val backgroundResult = async {
            runCatching {
                background.runPreemptible {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupStarted.complete(Unit)
                            cleanupRelease.await()
                        }
                    }
                }
            }
        }

        started.await()
        val takeover = async {
            arbiter.acquire("1\u0000show-a", ScrapeTaskPriority.INTERACTIVE)
        }
        cleanupStarted.await()
        try {
            val otherShow = withTimeout(5_000L) {
                arbiter.acquire("1\u0000show-b", ScrapeTaskPriority.BACKGROUND)
            }
            assertNotNull(otherShow).release()
        } finally {
            cleanupRelease.complete(Unit)
        }

        val interactive = assertNotNull(takeover.await())
        assertIs<ScrapePreemptedException>(backgroundResult.await().exceptionOrNull())
        background.release()
        interactive.release()
    }
}
