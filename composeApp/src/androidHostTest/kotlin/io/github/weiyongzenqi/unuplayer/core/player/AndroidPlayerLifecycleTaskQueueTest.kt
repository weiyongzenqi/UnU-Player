package io.github.weiyongzenqi.unuplayer.core.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidPlayerLifecycleTaskQueueTest {

    @Test
    fun `任务严格串行且单个失败不阻止后续释放`() {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "lifecycle-test") }
        val queue = AndroidPlayerLifecycleTaskQueue(executor)
        val events = Collections.synchronizedList(mutableListOf<Int>())
        val threads = Collections.synchronizedSet(mutableSetOf<String>())
        val completed = CountDownLatch(3)

        queue.submit(null, "first") {
            threads += Thread.currentThread().name
            events += 1
            completed.countDown()
        }
        queue.submit(null, "second") {
            threads += Thread.currentThread().name
            events += 2
            completed.countDown()
            error("expected")
        }
        queue.submit(null, "third") {
            threads += Thread.currentThread().name
            events += 3
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(queue.shutdownAndAwait(2_000))
        assertEquals(listOf(1, 2, 3), events)
        assertEquals(setOf("lifecycle-test"), threads)
    }

    @Test
    fun `关键退出任务会替换已排队的非关键任务`() {
        val executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
        )
        val queue = AndroidPlayerLifecycleTaskQueue(executor)
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val criticalCompleted = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val normalDropped = AtomicInteger(0)

        queue.submit(null, "running") {
            workerStarted.countDown()
            releaseWorker.await(2, TimeUnit.SECONDS)
            events += "running"
        }
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS))
        assertTrue(
            queue.submit(null, "normal", onDropped = { normalDropped.incrementAndGet() }) {
                events += "normal"
            },
        )
        assertTrue(
            queue.submit(null, "critical", critical = true) {
                events += "critical"
                criticalCompleted.countDown()
            },
        )

        releaseWorker.countDown()
        assertTrue(criticalCompleted.await(2, TimeUnit.SECONDS))
        assertTrue(queue.shutdownAndAwait(2_000))
        assertEquals(listOf("running", "critical"), events)
        assertEquals(1, normalDropped.get())
    }

    @Test
    fun `关键 overflow 存在时新任务不会越过旧任务`() {
        val executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
        )
        val queue = AndroidPlayerLifecycleTaskQueue(executor, criticalOverflowCapacity = 4)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val completed = CountDownLatch(4)
        val events = Collections.synchronizedList(mutableListOf<String>())

        queue.submit(null, "first", critical = true) {
            events += "first"
            releaseFirst.await(2, TimeUnit.SECONDS)
            completed.countDown()
        }
        queue.submit(null, "second", critical = true) {
            events += "second"
            secondStarted.countDown()
            releaseSecond.await(2, TimeUnit.SECONDS)
            completed.countDown()
        }
        queue.submit(null, "third", critical = true) {
            events += "third"
            completed.countDown()
        }

        releaseFirst.countDown()
        assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
        assertTrue(queue.submit(null, "fourth", critical = true) {
            events += "fourth"
            completed.countDown()
        })
        releaseSecond.countDown()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(queue.shutdownAndAwait(2_000))
        assertEquals(listOf("first", "second", "third", "fourth"), events)
    }

    @Test
    fun `关键 overflow 饱和时挂起提交会等待容量而不是永久丢失`() = runBlocking {
        val executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
        )
        val queue = AndroidPlayerLifecycleTaskQueue(executor, criticalOverflowCapacity = 1)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(4)

        queue.submit(null, "first", critical = true) {
            firstStarted.countDown()
            releaseFirst.await(2, TimeUnit.SECONDS)
            completed.countDown()
        }
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        queue.submit(null, "second", critical = true) { completed.countDown() }
        queue.submit(null, "third", critical = true) { completed.countDown() }

        val waitingSubmit = launch(Dispatchers.Default) {
            queue.submitCriticalAwait(null, "fourth") { completed.countDown() }
        }
        delay(25)
        assertFalse(waitingSubmit.isCompleted)
        val normalDropped = AtomicInteger(0)
        assertFalse(queue.submit(null, "late-normal", onDropped = { normalDropped.incrementAndGet() }) {})
        assertEquals(1, normalDropped.get())

        releaseFirst.countDown()
        withTimeout(2_000) { waitingSubmit.join() }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(queue.shutdownAndAwait(2_000))
    }

    @Test
    fun `等待 admission 的协程取消后不会遗留关键任务`() = runBlocking {
        val executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
        )
        val queue = AndroidPlayerLifecycleTaskQueue(executor, criticalOverflowCapacity = 1)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(3)
        val cancelledTaskRan = AtomicBoolean(false)

        queue.submit(null, "first", critical = true) {
            firstStarted.countDown()
            releaseFirst.await(2, TimeUnit.SECONDS)
            completed.countDown()
        }
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        queue.submit(null, "second", critical = true) { completed.countDown() }
        queue.submit(null, "third", critical = true) { completed.countDown() }

        val waitingSubmit = launch(Dispatchers.Default) {
            queue.submitCriticalAwait(null, "cancelled") { cancelledTaskRan.set(true) }
        }
        delay(25)
        waitingSubmit.cancelAndJoin()

        releaseFirst.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(queue.shutdownAndAwait(2_000))
        assertFalse(cancelledTaskRan.get())
    }

    @Test
    fun `actor 尚未启动时预登记的关键任务也会阻止普通任务越过`() {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "admission-test") }
        val queue = AndroidPlayerLifecycleTaskQueue(executor)
        val criticalCompleted = CountDownLatch(1)
        val normalDropped = AtomicInteger(0)

        queue.reserveCriticalAdmission()
        assertFalse(queue.submit(null, "normal", onDropped = { normalDropped.incrementAndGet() }) {})
        queue.submitCriticalBlocking(null, "critical", admissionReserved = true) {
            criticalCompleted.countDown()
        }

        assertTrue(criticalCompleted.await(2, TimeUnit.SECONDS))
        assertTrue(queue.shutdownAndAwait(2_000))
        assertEquals(1, normalDropped.get())
    }

    @Test
    fun `有界 admission 饱和时立即拒绝且已接收任务保持 FIFO`() {
        val executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
        )
        val queue = AndroidPlayerLifecycleTaskQueue(executor, criticalOverflowCapacity = 1)
        val admission = CriticalTaskAdmission(queue, "bounded-admission-test", capacity = 1)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(5)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val rejected = AtomicInteger(0)

        queue.submit(null, "running", critical = true) {
            events += "running"
            firstStarted.countDown()
            releaseFirst.await(2, TimeUnit.SECONDS)
            completed.countDown()
        }
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        queue.submit(null, "queued", critical = true) { events += "queued"; completed.countDown() }
        queue.submit(null, "overflow", critical = true) { events += "overflow"; completed.countDown() }

        assertTrue(admission.submit(null, "admitted-1") { events += "admitted-1"; completed.countDown() })
        assertTrue(admission.submit(null, "admitted-2") { events += "admitted-2"; completed.countDown() })
        assertFalse(admission.submit(null, "rejected", onRejected = { rejected.incrementAndGet() }) {})

        releaseFirst.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(admission.shutdownAndAwait(2_000))
        assertTrue(queue.shutdownAndAwait(2_000))
        assertEquals(listOf("running", "queued", "overflow", "admitted-1", "admitted-2"), events)
        assertEquals(1, rejected.get())
    }

    @Test
    fun `会话预留许可不会被后续普通关键任务挤掉`() = runBlocking {
        val executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(4),
        )
        val queue = AndroidPlayerLifecycleTaskQueue(executor, criticalOverflowCapacity = 4)
        val admission = CriticalTaskAdmission(queue, "reserved-admission-test", capacity = 1)
        val reservation = admission.reserve()
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val events = Collections.synchronizedList(mutableListOf<String>())

        queue.submit(null, "blocker", critical = true) {
            blockerStarted.countDown()
            releaseBlocker.await(2, TimeUnit.SECONDS)
        }
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS))
        assertTrue(admission.submit(null, "normal") { events += "normal"; completed.countDown() })
        delay(25)
        assertFalse(admission.submit(null, "rejected") {})
        assertTrue(reservation.submit(null, "reserved") { events += "reserved"; completed.countDown() })

        releaseBlocker.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(admission.shutdownAndAwait(2_000))
        assertTrue(queue.shutdownAndAwait(2_000))
        assertEquals(listOf("normal", "reserved"), events)
    }

    @Test
    fun `等待预留许可的协程取消后不会泄漏容量`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val queue = AndroidPlayerLifecycleTaskQueue(executor)
        val admission = CriticalTaskAdmission(queue, "reservation-cancel-test", capacity = 1)
        val first = admission.reserve()
        val second = admission.reserve()
        val waiting = launch { admission.reserve().releaseUnused() }

        delay(25)
        assertFalse(waiting.isCompleted)
        waiting.cancelAndJoin()
        first.releaseUnused()
        second.releaseUnused()

        val next = withTimeout(2_000) { admission.reserve() }
        next.releaseUnused()
        assertTrue(admission.shutdownAndAwait(2_000))
        assertTrue(queue.shutdownAndAwait(2_000))
    }

    @Test
    fun `日志器异常不会传播到生命周期提交调用者`() {
        val executor = Executors.newSingleThreadExecutor()
        executor.shutdown()
        val queue = AndroidPlayerLifecycleTaskQueue(executor)

        assertFalse(queue.submit(ThrowingLogger, "rejected") {})
    }

    @Test
    fun `生产关键提交 API 会把 admission 拒绝返回给调用者`() {
        val executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
        )
        val queue = AndroidPlayerLifecycleTaskQueue(executor, criticalOverflowCapacity = 1)
        val admission = CriticalTaskAdmission(queue, "production-submit-test", capacity = 1)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)

        queue.submit(null, "running", critical = true) {
            firstStarted.countDown()
            releaseFirst.await(2, TimeUnit.SECONDS)
        }
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        queue.submit(null, "queued", critical = true) {}
        queue.submit(null, "overflow", critical = true) {}

        assertTrue(AndroidPlayerLifecycleTasks.submitCriticalTo(admission, null, "admitted-1") {})
        assertTrue(AndroidPlayerLifecycleTasks.submitCriticalTo(admission, null, "admitted-2") {})
        assertFalse(AndroidPlayerLifecycleTasks.submitCriticalTo(admission, null, "rejected") {})

        releaseFirst.countDown()
        assertTrue(admission.shutdownAndAwait(2_000))
        assertTrue(queue.shutdownAndAwait(2_000))
    }

    private object ThrowingLogger : AppLogger {
        override fun setDirectory(path: String?) = Unit
        override fun setAppLogLevel(level: LogLevel) = Unit
        override fun log(level: String, prefix: String, text: String) = error("logger failed")
        override fun appEvent(tag: String, message: String, level: LogLevel) = error("logger failed")
        override suspend fun clearLogs(): Long = 0L
        override suspend fun logsSize(): Long = 0L
    }
}
