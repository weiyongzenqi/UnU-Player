package io.github.weiyongzenqi.unuplayer.core.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.coroutineContext

private fun AppLogger?.safeLifecycleLog(message: String, level: LogLevel) {
    runCatching { this?.appEvent("lifecycle", message, level) }
}

/** 单 worker 有界队列；三个生产实例分别承载 native、记录和文件清理。 */
internal class AndroidPlayerLifecycleTaskQueue(
    private val executor: ExecutorService,
    private val criticalOverflowCapacity: Int = 64,
) {
    private class LifecycleRunnable(
        val critical: Boolean,
        private val logger: AppLogger?,
        private val description: String,
        private val task: () -> Unit,
        private val onDropped: () -> Unit,
        private val onFinished: () -> Unit,
    ) : Runnable {
        private val dropPublished = AtomicBoolean(false)

        override fun run() {
            try {
                runCatching(task).onFailure { error ->
                    logger.safeLifecycleLog(
                        "$description 失败: ${error.javaClass.simpleName}: ${error.message}",
                        LogLevel.ERROR,
                    )
                }
            } finally {
                runCatching(onFinished)
            }
        }

        fun dropped() {
            if (dropPublished.compareAndSet(false, true)) runCatching(onDropped)
        }
    }

    private inner class ExecutorRunnable(val lifecycle: LifecycleRunnable) : Runnable {
        override fun run() {
            try {
                lifecycle.run()
            } finally {
                drainCriticalOverflow()
            }
        }
    }

    private val submissionLock = Any()
    private val criticalAdmissionLock = ReentrantLock(true)
    private val criticalOverflow = ArrayDeque<LifecycleRunnable>(criticalOverflowCapacity)
    private var criticalWaiters = 0

    fun submit(
        logger: AppLogger?,
        description: String,
        critical: Boolean = false,
        onDropped: () -> Unit = {},
        task: () -> Unit,
    ): Boolean {
        val runnable = LifecycleRunnable(critical, logger, description, task, onDropped) {}
        val accepted = enqueue(runnable)
        if (!accepted) {
            runnable.dropped()
            logger.safeLifecycleLog("$description 未能进入有界释放队列", LogLevel.ERROR)
        }
        return accepted
    }

    internal fun submitCriticalBlocking(
        logger: AppLogger?,
        description: String,
        shouldContinue: () -> Boolean = { true },
        admissionReserved: Boolean = false,
        onFinished: () -> Unit = {},
        task: () -> Unit,
    ) {
        val runnable = LifecycleRunnable(true, logger, description, task, {}, onFinished)
        if (!admissionReserved) reserveCriticalAdmission()
        try {
            criticalAdmissionLock.withLock {
                while (true) {
                    if (!shouldContinue()) throw CancellationException("$description 的提交已取消")
                    if (enqueue(runnable)) return
                    if (executor.isShutdown) {
                        runnable.dropped()
                        throw RejectedExecutionException("$description 的生命周期队列已关闭")
                    }
                    LockSupport.parkNanos(CRITICAL_RETRY_DELAY_NANOS)
                }
            }
        } finally {
            releaseCriticalAdmission()
        }
    }

    internal fun reserveCriticalAdmission() {
        synchronized(submissionLock) { criticalWaiters++ }
    }

    internal fun releaseCriticalAdmission() {
        synchronized(submissionLock) {
            check(criticalWaiters > 0) { "关键任务 admission 计数失衡" }
            criticalWaiters--
        }
    }

    suspend fun submitCriticalAwait(logger: AppLogger?, description: String, task: () -> Unit) {
        val callerContext = coroutineContext
        withContext(Dispatchers.IO) {
            submitCriticalBlocking(logger, description, shouldContinue = { callerContext.isActive }, task = task)
        }
    }

    private fun enqueue(runnable: LifecycleRunnable): Boolean = synchronized(submissionLock) {
        if (!runnable.critical && criticalWaiters > 0) return@synchronized false
        if (criticalOverflow.isNotEmpty()) {
            return@synchronized if (runnable.critical && criticalOverflow.size < criticalOverflowCapacity) {
                criticalOverflow.addLast(runnable)
                true
            } else {
                false
            }
        }

        if (tryExecute(runnable)) return@synchronized true
        if (!runnable.critical || executor.isShutdown) return@synchronized false

        val pool = executor as? ThreadPoolExecutor
        val dropped = pool?.queue?.firstOrNull { it is ExecutorRunnable && !it.lifecycle.critical }
        if (dropped is ExecutorRunnable && pool.remove(dropped)) {
            dropped.lifecycle.dropped()
            if (tryExecute(runnable)) return@synchronized true
        }

        if (criticalOverflow.size < criticalOverflowCapacity) {
            criticalOverflow.addLast(runnable)
            true
        } else {
            false
        }
    }

    private fun tryExecute(task: LifecycleRunnable): Boolean = try {
        executor.execute(ExecutorRunnable(task))
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private fun drainCriticalOverflow() {
        synchronized(submissionLock) {
            val next = criticalOverflow.pollFirst() ?: return
            if (!tryExecute(next)) criticalOverflow.addFirst(next)
        }
    }

    internal fun shutdownAndAwait(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (synchronized(submissionLock) { criticalOverflow.isNotEmpty() }) {
            if (System.nanoTime() >= deadline) return false
            LockSupport.parkNanos(CRITICAL_RETRY_DELAY_NANOS)
        }
        executor.shutdown()
        val remaining = (deadline - System.nanoTime()).coerceAtLeast(0L)
        return executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)
    }

    private companion object {
        const val CRITICAL_RETRY_DELAY_NANOS = 5_000_000L
    }
}

internal class CriticalTaskAdmission(
    private val target: AndroidPlayerLifecycleTaskQueue,
    name: String,
    capacity: Int = 64,
) {
    /** 与 executor 的 1 running + queue capacity 完全一致。 */
    private val permits = Semaphore(capacity + 1)
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(capacity),
        { task -> Thread(task, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun submit(
        logger: AppLogger?,
        description: String,
        onRejected: () -> Unit = {},
        task: () -> Unit,
    ): Boolean {
        if (!permits.tryAcquire()) {
            runCatching(onRejected)
            logger.safeLifecycleLog("$description 未能进入有界关键 admission 队列", LogLevel.ERROR)
            return false
        }
        return submitWithReservedPermit(logger, description, onRejected, task)
    }

    suspend fun reserve(): CriticalTaskReservation {
        permits.acquire()
        return CriticalTaskReservation(this)
    }

    internal fun submitReserved(
        logger: AppLogger?,
        description: String,
        onRejected: () -> Unit = {},
        task: () -> Unit,
    ): Boolean = submitWithReservedPermit(logger, description, onRejected, task)

    internal fun releaseReservedPermit() {
        permits.release()
    }

    private fun submitWithReservedPermit(
        logger: AppLogger?,
        description: String,
        onRejected: () -> Unit,
        task: () -> Unit,
    ): Boolean {
        val permitReleased = AtomicBoolean(false)
        val releasePermit = {
            if (permitReleased.compareAndSet(false, true)) permits.release()
        }
        target.reserveCriticalAdmission()
        return try {
            executor.execute {
                try {
                    target.submitCriticalBlocking(
                        logger,
                        description,
                        admissionReserved = true,
                        onFinished = releasePermit,
                        task = task,
                    )
                } catch (error: Throwable) {
                    releasePermit()
                    logger.safeLifecycleLog(
                        "$description 未能进入关键任务队列: ${error.javaClass.simpleName}: ${error.message}",
                        LogLevel.ERROR,
                    )
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            target.releaseCriticalAdmission()
            releasePermit()
            runCatching(onRejected)
            logger.safeLifecycleLog("$description 未能进入有界关键 admission 队列", LogLevel.ERROR)
            false
        }
    }

    internal fun shutdownAndAwait(timeoutMs: Long): Boolean {
        executor.shutdown()
        return executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
    }
}

internal class CriticalTaskReservation internal constructor(
    private val admission: CriticalTaskAdmission,
) {
    private val consumed = AtomicBoolean(false)

    fun submit(logger: AppLogger?, description: String, task: () -> Unit): Boolean {
        check(consumed.compareAndSet(false, true)) { "$description 的预留关闭许可已消费" }
        return admission.submitReserved(logger, description, task = task)
    }

    fun releaseUnused() {
        if (consumed.compareAndSet(false, true)) admission.releaseReservedPermit()
    }
}

/** 播放前预留三类最终任务容量，保证已创建 native 的会话不会在退出时因队列饱和而丢失。 */
internal class AndroidPlayerSessionCloseLease internal constructor(
    private val native: CriticalTaskReservation,
    private val record: CriticalTaskReservation,
    private val cleanup: CriticalTaskReservation,
) {
    fun submitNative(logger: AppLogger?, description: String, task: () -> Unit): Boolean =
        native.submit(logger, description, task)

    fun submitRecord(logger: AppLogger?, description: String, task: () -> Unit): Boolean =
        record.submit(logger, description, task)

    fun submitCleanup(logger: AppLogger?, description: String, task: () -> Unit): Boolean =
        cleanup.submit(logger, description, task)

    fun releaseUnusedReservations() {
        native.releaseUnused()
        record.releaseUnused()
        cleanup.releaseUnused()
    }
}

internal object AndroidPlayerLifecycleTasks {
    private const val QUEUE_CAPACITY = 64
    private fun createQueue(name: String) = AndroidPlayerLifecycleTaskQueue(
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(QUEUE_CAPACITY),
            { task -> Thread(task, name).apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        ),
    )

    private val nativeQueue = createQueue("unu-player-native-lifecycle")
    private val recordQueue = createQueue("unu-player-record-lifecycle")
    private val cleanupQueue = createQueue("unu-player-cleanup-lifecycle")
    private val nativeAdmission = CriticalTaskAdmission(nativeQueue, "unu-player-native-admission")
    private val recordAdmission = CriticalTaskAdmission(recordQueue, "unu-player-record-admission")
    private val cleanupAdmission = CriticalTaskAdmission(cleanupQueue, "unu-player-cleanup-admission")

    /** 挂起只发生在 Compose 的可取消初始化协程中；取得全部许可前不得创建 MPVLib。 */
    suspend fun acquireSessionCloseLease(): AndroidPlayerSessionCloseLease {
        val reservations = ArrayList<CriticalTaskReservation>(3)
        return try {
            val native = nativeAdmission.reserve().also(reservations::add)
            val record = recordAdmission.reserve().also(reservations::add)
            val cleanup = cleanupAdmission.reserve().also(reservations::add)
            AndroidPlayerSessionCloseLease(native, record, cleanup)
        } catch (error: Throwable) {
            reservations.forEach(CriticalTaskReservation::releaseUnused)
            throw error
        }
    }

    fun submit(
        logger: AppLogger?,
        description: String,
        onDropped: () -> Unit = {},
        task: () -> Unit,
    ): Boolean = nativeQueue.submit(logger, description, onDropped = onDropped, task = task)

    /** 关键提交由每类独立的有界单 worker admission 保序，不在调用线程等待目标槽位。 */
    fun submitCritical(logger: AppLogger?, description: String, task: () -> Unit): Boolean =
        submitCriticalTo(nativeAdmission, logger, description, task)

    fun submitRecord(logger: AppLogger?, description: String, task: () -> Unit): Boolean =
        recordQueue.submit(logger, description, task = task)

    fun submitRecordCritical(logger: AppLogger?, description: String, task: () -> Unit): Boolean =
        submitCriticalTo(recordAdmission, logger, description, task)

    /** 文件清理独立于记录队列，慢 Provider 不得阻塞新会话续播。 */
    fun submitCleanup(logger: AppLogger?, description: String, task: () -> Unit): Boolean =
        submitCriticalTo(cleanupAdmission, logger, description, task)

    internal fun submitCriticalTo(
        admission: CriticalTaskAdmission,
        logger: AppLogger?,
        description: String,
        task: () -> Unit,
    ): Boolean = admission.submit(logger, description, task = task)

    /**
     * 跨播放器会话严格 FIFO 串行化短任务(upsert/updateDanmaku/getByMediaKey)。
     *
     * CR-070/PF-007: 改用 [Mutex.withLock] suspend 语义, 不再 [runBlocking]:
     * - 旧实现 `recordAdmission.submit { runBlocking { task() } }` 在 record 单 worker 线程上
     *   `runBlocking` 死等 task 内 `withContext(IO)` 的结果, 慢 upsert(SQLite busy 5s)会阻塞
     *   record worker, 后续 `submitRecord`(周期 updatePosition) 排队等待, 最坏 `recordWriteGate`
     *   10s 周期写被跳过; admission 队列饱和后 `RejectedExecutionException` 被 `runSuspendCatching`
     *   吞掉, 静默丢进度。`runBlocking` 在单 worker 上是背压放大器。
     * - 新实现: [runSerializedMutex] 保证串行化(suspend, 不阻塞线程), 慢 task 期间等待协程挂起让出;
     *   [runSerializedAdmission] 保留有界性(满即抛 [RejectedExecutionException], 对应原 onRejected 语义)。
     * - 不再经过 recordQueue/recordAdmission: 与 `submitRecord`(周期 updatePosition)/
     *   `lease.submitRecord`(finishPlayback) 不再共享 FIFO。数据一致性由 SQL 层保证:
     *     · upsert/updatePosition/finishPlayback 都有 `last_played_at < :new` 单调守卫, 乱序不回退;
     *     · upsert 弹幕字段用 COALESCE(:new, 旧值), 防止陈旧 existing 覆盖 updateDanmaku 非空值;
     *     · updateDanmaku 只写弹幕字段, 与 updatePosition/finishPlayback 字段无重叠。
     * - PERF-004 保持: lease 仍走 recordAdmission.reserve()/submitRecord, runSerialized 不再消费
     *   该 permit 池, lease 可用性反而改善; recordWriteGate/关闭许可/三类 worker 有界性不变。
     */
    suspend fun <T> runSerialized(logger: AppLogger?, description: String, task: suspend () -> T): T {
        if (!runSerializedAdmission.tryAcquire()) {
            logger.safeLifecycleLog("$description 的 runSerialized admission 已满", LogLevel.ERROR)
            throw RejectedExecutionException("$description 的 runSerialized admission 已满")
        }
        try {
            return runSerializedMutex.withLock { task() }
        } finally {
            runSerializedAdmission.release()
        }
    }

    private val runSerializedMutex = Mutex()
    private val runSerializedAdmission = Semaphore(RUN_SERIALIZED_ADMISSION_CAPACITY)

    private const val RUN_SERIALIZED_ADMISSION_CAPACITY = 16
}
