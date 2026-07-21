package io.github.weiyongzenqi.unuplayer.core.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CR-070/PF-007: 验证 [AndroidPlayerLifecycleTasks.runSerialized] 不再 `runBlocking` 阻塞线程。
 *
 * 旧实现 `recordAdmission.submit { runBlocking { task() } }` 在 record 单 worker 线程上
 * `runBlocking` 死等 task 内 `withContext(IO)` 的结果, 慢 upsert 会阻塞 record worker,
 * 后续周期 updatePosition 排队等待。新实现用 `Mutex.withLock` suspend 语义, task 挂起期间
 * 等待协程让出线程, 不阻塞。
 *
 * 注意: [AndroidPlayerLifecycleTasks] 是单例 object, [runSerializedMutex] 为 object 级状态。
 * 测试间通过 `join`/`await` 保证 Mutex/Admission 释放, 不残留。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPlayerLifecycleRunSerializedTest {

    @Test
    fun `runSerialized 串行化并发任务`() = runBlocking {
        val order = mutableListOf<Int>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = launch(Dispatchers.Default) {
            AndroidPlayerLifecycleTasks.runSerialized(null, "first") {
                order += 1
                firstStarted.complete(Unit)
                releaseFirst.await()
                order += 2
            }
        }
        firstStarted.await()

        val second = launch(Dispatchers.Default) {
            AndroidPlayerLifecycleTasks.runSerialized(null, "second") {
                order += 3
            }
        }
        delay(100)
        // 第二个应等第一个释放 Mutex, 尚未进入 task
        assertFalse(second.isCompleted, "second 应等 first 释放 Mutex")
        assertEquals(listOf(1), order, "second 不应先于 first 完成执行")

        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun `runSerialized 不阻塞线程 - task 挂起期间其他协程可在同线程运行`() = runBlocking {
        // 用单线程 dispatcher 验证: 旧 runBlocking 实现下, first 的 runBlocking 会阻塞该线程,
        // second 无法运行; 新 Mutex.withLock 实现下, first 在 releaseFirst.await() 挂起让出线程,
        // second 能运行到等 Mutex(不进 task)。
        val singleThread = newSingleThreadContext("test-runSerialized-nonblocking")
        try {
            val order = mutableListOf<String>()
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()

            val first = async(singleThread) {
                AndroidPlayerLifecycleTasks.runSerialized(null, "first") {
                    firstStarted.complete(Unit)
                    order += "first-start"
                    releaseFirst.await()  // task 内挂起, 不阻塞线程
                    order += "first-end"
                }
            }
            firstStarted.await()

            val second = async(singleThread) {
                order += "second-before-lock"
                AndroidPlayerLifecycleTasks.runSerialized(null, "second") {
                    order += "second-in-lock"
                }
            }

            delay(200)
            // 关键断言: first 挂起期间, second 应已运行到等 Mutex
            // 若 runBlocking, 线程被 first 占住, second 无法执行, order 不含 "second-before-lock"
            assertTrue(
                order.contains("second-before-lock"),
                "second 应在 first 挂起时运行到等 Mutex, 实际 order=$order",
            )
            assertFalse(
                order.contains("second-in-lock"),
                "second 不应在 first 持锁时进入 task, 实际 order=$order",
            )
            assertFalse(
                order.contains("first-end"),
                "first 不应在 releaseFirst 完成前结束, 实际 order=$order",
            )

            releaseFirst.complete(Unit)
            first.await()
            second.await()
            assertEquals(
                listOf("first-start", "second-before-lock", "first-end", "second-in-lock"),
                order,
            )
        } finally {
            singleThread.close()
        }
    }

    @Test
    fun `runSerialized task 抛异常时正常传播且不残留 Mutex`() = runBlocking {
        val firstFailed = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default) {
            try {
                AndroidPlayerLifecycleTasks.runSerialized(null, "throws") {
                    firstFailed.complete(Unit)
                    throw IllegalStateException("expected")
                }
            } catch (e: IllegalStateException) {
                // 预期
            }
        }
        firstFailed.await()
        first.join()

        // Mutex 应已释放, 后续 runSerialized 能正常执行
        val result = AndroidPlayerLifecycleTasks.runSerialized(null, "after-failure") {
            "ok"
        }
        assertEquals("ok", result)
    }
}
