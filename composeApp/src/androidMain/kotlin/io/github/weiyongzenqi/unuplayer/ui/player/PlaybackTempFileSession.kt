package io.github.weiyongzenqi.unuplayer.ui.player

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 只跟踪当前播放器会话创建的临时文件和正在写入的租约。 */
internal class PlaybackTempFileSession(
    private val cacheDir: File,
    sessionId: Long = nextSessionId.incrementAndGet(),
    private val cleanupWaitMillis: Long = TimeUnit.SECONDS.toMillis(CLEANUP_WAIT_SECONDS),
) {
    private val lock = Any()
    private val token = sessionId.toString(36)
    private val ownedFiles = LinkedHashSet<File>()
    private val activeFiles = LinkedHashSet<File>()
    private var nextFileId = 0L
    private var detached = false
    private var detachLatch: CountDownLatch? = null
    private var cleanupCompleted = false
    private var lateCleanupScheduler: (() -> Unit)? = null

    fun newFile(prefix: String, extension: String): PlaybackTempFileLease = synchronized(lock) {
        check(!detached) { "播放器临时文件会话已关闭" }
        val safeExtension = extension.lowercase().filter(Char::isLetterOrDigit).take(10).ifEmpty { "tmp" }
        val file = File(cacheDir, "${prefix}_${token}_${nextFileId++}.$safeExtension")
        ownedFiles += file
        activeFiles += file
        PlaybackTempFileLease(file) { complete(file) }
    }

    /** onDispose 只创建等待任务；真正等待慢 Provider 与列目录都在后台生命周期 worker。 */
    fun detachCleanupTask(): (() -> List<File>)? = synchronized(lock) {
        if (detached) return null
        detached = true
        val latch = CountDownLatch(activeFiles.size).also { detachLatch = it }
        val sessionFiles = ownedFiles.toList()
        return {
            latch.await(cleanupWaitMillis, TimeUnit.MILLISECONDS)
            synchronized(lock) {
                sessionFiles.filterNot(activeFiles::contains).also { completed ->
                    ownedFiles.removeAll(completed.toSet())
                    cleanupCompleted = true
                    if (activeFiles.isEmpty()) lateCleanupScheduler = null
                }
            }
        }
    }

    /** 首次清理超时后，最后一个迟到 lease 结束时安排一次后台重试。 */
    fun setLateCleanupScheduler(schedule: () -> Unit) {
        val shouldSchedule = synchronized(lock) {
            lateCleanupScheduler = schedule
            detached && cleanupCompleted && activeFiles.isEmpty()
        }
        if (shouldSchedule) {
            synchronized(lock) { lateCleanupScheduler = null }
            schedule()
        }
    }

    private fun complete(file: File) {
        var schedule: (() -> Unit)? = null
        synchronized(lock) {
            if (activeFiles.remove(file)) {
                detachLatch?.countDown()
                if (detached && cleanupCompleted && activeFiles.isEmpty()) {
                    schedule = lateCleanupScheduler
                    lateCleanupScheduler = null
                }
            }
        }
        schedule?.invoke()
    }

    private companion object {
        const val CLEANUP_WAIT_SECONDS = 30L
        val nextSessionId = AtomicLong(System.currentTimeMillis())
    }
}

internal class PlaybackTempFileLease internal constructor(
    val file: File,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private var closed = false

    override fun close() = synchronized(this) {
        if (!closed) {
            closed = true
            onClose()
        }
    }
}

/** 串行化周期进度写与退出最终写，防止离页取消竞态把最终位置覆盖回旧值。 */
internal class PlaybackRecordWriteGate {
    private val lock = Any()
    private var closed = false

    fun submitIfOpen(submit: () -> Unit): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        submit()
        true
    }

    fun closeAndSubmit(submit: () -> Unit): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        closed = true
        submit()
        true
    }
}
