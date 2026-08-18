package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 在线刮削调度优先级：后台批量 < 详情页自动任务 < 用户手动操作。 */
internal enum class ScrapeTaskPriority {
    BACKGROUND,
    INTERACTIVE,
    MANUAL,
}
/** 高优先级任务接管同一番剧时，用独立取消原因让批量任务能计入已处理并继续其余条目。 */
internal class ScrapePreemptedException : CancellationException("在线刮削已由更高优先级任务接管")

/**
 * 进程级单番剧刮削仲裁器。
 *
 * 不同番剧互不阻塞；同一 `(libraryId, showPath)` 只允许一个写任务。高优先级任务会原子替换
 * 低优先级所有权，再通知旧任务取消网络/图片工作。租约带独立 token，旧任务迟到的 finally
 * 不会误删新任务所有权。
 */
internal class ScrapeTaskArbiter {
    private data class ActiveTask(
        val priority: ScrapeTaskPriority,
        val token: Any,
        val preempted: CompletableDeferred<Unit>,
        val completed: CompletableDeferred<Unit>,
    )

    private val mutex = Mutex()
    private val activeTasks = mutableMapOf<String, ActiveTask>()

    suspend fun acquire(key: String, priority: ScrapeTaskPriority): Lease? {
        while (true) {
            var completionToAwait: CompletableDeferred<Unit>? = null
            val lease = mutex.withLock {
                val current = activeTasks[key]
                if (current != null && priority <= current.priority) return null

                if (current != null && !current.completed.isCompleted) {
                    current.preempted.complete(Unit)
                    completionToAwait = current.completed
                    return@withLock null
                }

                val token = Any()
                val signal = CompletableDeferred<Unit>()
                val completed = CompletableDeferred<Unit>()
                activeTasks[key] = ActiveTask(priority, token, signal, completed)
                Lease(key, token, signal, completed)
            }
            if (lease != null) return lease

            // 旧任务的不可取消清理可能包含 native/文件收尾，必须锁外等待，避免阻塞其他番剧。
            completionToAwait?.await()
        }
    }

    inner class Lease internal constructor(
        private val key: String,
        private val token: Any,
        private val preempted: CompletableDeferred<Unit>,
        private val completed: CompletableDeferred<Unit>,
    ) {
        /**
         * 仅自动刮削需要可抢占执行；手动路径只使用租约互斥，不会被较低优先级任务接管。
         */
        suspend fun <T> runPreemptible(block: suspend () -> T): T = coroutineScope {
            val work = async { block() }
            try {
                select {
                    work.onAwait { it }
                    preempted.onAwait { throw ScrapePreemptedException() }
                }
            } finally {
                withContext(NonCancellable) {
                    work.cancelAndJoin()
                    completed.complete(Unit)
                }
            }
        }

        suspend fun release() {
            // 手动租约不调用 runPreemptible；先完成交接信号再获取仲裁锁，
            // 避免等待中的高优先级 acquire 形成死锁。
            completed.complete(Unit)
            withContext(NonCancellable) {
                mutex.withLock {
                    if (activeTasks[key]?.token === token) activeTasks.remove(key)
                }
            }
        }
    }
}
