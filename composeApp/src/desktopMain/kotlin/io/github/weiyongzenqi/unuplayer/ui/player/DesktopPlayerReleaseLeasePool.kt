package io.github.weiyongzenqi.unuplayer.ui.player

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 为每个桌面播放器会话预留独立的释放 FIFO。
 *
 * native render 若永久阻塞，只会占住自己的 daemon worker；进程级许可上限阻止继续创建
 * 无法保证最终释放的播放器实例。许可必须在 native engine 创建前取得。
 */
class DesktopPlayerReleaseLeasePool(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = java.lang.Object()
    private var closed = false
    private var activeLeases = 0
    private var nextLeaseId = 0L

    init {
        require(capacity > 0)
    }

    fun tryAcquire(): DesktopPlayerReleaseLease? = synchronized(lock) {
        if (closed || activeLeases >= capacity) return@synchronized null
        activeLeases++
        DesktopPlayerReleaseLease(this, ++nextLeaseId)
    }

    fun closeAndAwait(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0L)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        synchronized(lock) {
            closed = true
            while (activeLeases > 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                val millis = remaining / 1_000_000L
                val nanos = (remaining % 1_000_000L).toInt()
                lock.wait(millis, nanos)
            }
            return true
        }
    }

    internal fun releaseLease() {
        synchronized(lock) {
            check(activeLeases > 0) { "桌面播放器释放许可计数失衡" }
            activeLeases--
            lock.notifyAll()
        }
    }

    internal fun activeLeaseCount(): Int = synchronized(lock) { activeLeases }

    private companion object {
        const val DEFAULT_CAPACITY = 2
    }
}

class DesktopPlayerReleaseLease internal constructor(
    private val pool: DesktopPlayerReleaseLeasePool,
    leaseId: Long,
) {
    private enum class State { ACQUIRED, CLAIMED, TERMINAL_SUBMITTED, RELEASED }

    private val lock = Any()
    private var state = State.ACQUIRED
    private var childReservations = 0
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(RELEASE_QUEUE_CAPACITY),
        { task -> Thread(task, "unu-player-release-$leaseId").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** 由实际进入组合的播放页认领；重复组合幂等。 */
    fun claim(): Boolean = synchronized(lock) {
        when (state) {
            State.ACQUIRED -> {
                state = State.CLAIMED
                true
            }
            State.CLAIMED -> true
            State.TERMINAL_SUBMITTED, State.RELEASED -> false
        }
    }

    /** 在创建 render worker 前预留其清理位置，防止同一会话反复 retry 形成无界 FIFO。 */
    fun tryReserveChildRelease(): DesktopPlayerChildReleaseReservation? = synchronized(lock) {
        if (state != State.CLAIMED || childReservations >= MAX_CHILD_RELEASES) return@synchronized null
        childReservations++
        DesktopPlayerChildReleaseReservation(this)
    }

    /** 同一会话的 worker generation 清理按 FIFO 排队，不归还会话许可。 */
    fun submit(task: () -> Unit) {
        synchronized(lock) {
            check(state == State.CLAIMED) { "桌面播放器释放许可不接受新的非终态任务" }
            executor.execute(task)
        }
    }

    /** 终态任务排在本会话全部子资源清理之后，完成后才归还进程级许可。 */
    fun submitTerminal(task: () -> Unit) {
        synchronized(lock) {
            check(state == State.CLAIMED) { "桌面播放器释放许可已提交终态任务" }
            state = State.TERMINAL_SUBMITTED
            executor.execute {
                try {
                    task()
                } finally {
                    executor.shutdown()
                    releaseToPool()
                }
            }
        }
    }

    /** 会话尚未进入组合就被新请求替换时，不启动 worker，直接归还许可。 */
    fun releaseIfUnclaimed(): Boolean {
        val released = synchronized(lock) {
            if (state != State.ACQUIRED) return@synchronized false
            state = State.RELEASED
            true
        }
        if (released) {
            executor.shutdown()
            pool.releaseLease()
        }
        return released
    }

    private fun releaseToPool() {
        val released = synchronized(lock) {
            if (state == State.RELEASED) return@synchronized false
            check(state == State.TERMINAL_SUBMITTED)
            check(childReservations == 0) { "桌面播放器子资源释放许可尚未归还" }
            state = State.RELEASED
            true
        }
        if (released) pool.releaseLease()
    }

    internal fun submitChildRelease(task: () -> Unit) {
        synchronized(lock) {
            check(state == State.CLAIMED) { "桌面播放器会话已进入终态释放" }
            executor.execute {
                try {
                    task()
                } finally {
                    releaseChildReservation()
                }
            }
        }
    }

    internal fun releaseChildReservation() {
        synchronized(lock) {
            check(childReservations > 0) { "桌面播放器子资源释放许可计数失衡" }
            childReservations--
        }
    }

    private companion object {
        const val MAX_CHILD_RELEASES = 2
        /** 最坏为一个子清理运行、两个子清理排队，终态任务仍必须有一个槽位。 */
        const val RELEASE_QUEUE_CAPACITY = 3
    }
}

class DesktopPlayerChildReleaseReservation internal constructor(
    private val lease: DesktopPlayerReleaseLease,
) {
    private enum class State { RESERVED, SUBMITTED, RELEASED }

    private val lock = Any()
    private var state = State.RESERVED

    fun submit(task: () -> Unit) {
        synchronized(lock) {
            check(state == State.RESERVED) { "桌面播放器子资源释放许可已消费" }
            state = State.SUBMITTED
        }
        lease.submitChildRelease(task)
    }

    /** 当前 worker 由会话终态任务直接接管时，在同一 FIFO worker 内执行并归还子许可。 */
    fun runInline(task: () -> Unit) {
        synchronized(lock) {
            check(state == State.RESERVED) { "桌面播放器子资源释放许可已消费" }
            state = State.SUBMITTED
        }
        try {
            task()
        } finally {
            releaseSubmitted()
        }
    }

    fun releaseUnused(): Boolean {
        val released = synchronized(lock) {
            if (state != State.RESERVED) return@synchronized false
            state = State.RELEASED
            true
        }
        if (released) lease.releaseChildReservation()
        return released
    }

    private fun releaseSubmitted() {
        val released = synchronized(lock) {
            check(state == State.SUBMITTED)
            state = State.RELEASED
            true
        }
        if (released) lease.releaseChildReservation()
    }
}
