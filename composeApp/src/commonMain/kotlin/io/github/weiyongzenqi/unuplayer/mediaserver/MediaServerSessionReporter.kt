package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 串行化播放报告，并确保 start/stop 这类非幂等请求最多尝试一次。 */
class MediaServerSessionReporter(
    private val api: MediaServerApi,
    private val session: MediaServerSession,
) {
    private val mutex = Mutex()
    private var startAttempted = false
    private var started = false
    private var stopAttempted = false

    suspend fun reportStarted(state: MediaServerPlaybackState): Boolean = mutex.withLock {
        if (startAttempted || stopAttempted) return@withLock false
        startAttempted = true
        api.reportPlaybackStarted(session, state)
        started = true
        true
    }

    suspend fun reportProgress(state: MediaServerPlaybackState): Boolean = mutex.withLock {
        if (!started || stopAttempted) return@withLock false
        api.reportPlaybackProgress(session, state)
        true
    }

    suspend fun reportStopped(
        state: MediaServerPlaybackState,
        failed: Boolean = false,
    ): Boolean = mutex.withLock {
        if (stopAttempted) return@withLock false
        stopAttempted = true
        api.reportPlaybackStopped(session, state, failed)
        true
    }
}

/**
 * 播放报告调度策略。自身不创建 Scope/线程，生命周期由调用方协程持有：
 * Started 成功后，播放中按固定间隔报告；暂停态由 UI 在状态变化时调用 [reportNow] 即时报告。
 */
internal class MediaServerPlaybackReportCoordinator(
    private val reporter: MediaServerSessionReporter,
    private val intervalMillis: Long = DEFAULT_PROGRESS_INTERVAL_MILLIS,
    private val awaitInterval: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(intervalMillis > 0L) { "播放进度上报间隔必须大于 0" }
    }

    suspend fun runPeriodic(
        currentState: () -> MediaServerPlaybackState,
        onFailure: (Throwable) -> Unit = {},
        startedState: (() -> MediaServerPlaybackState)? = null,
    ) {
        val started = runSuspendCatching { reporter.reportStarted((startedState ?: currentState)()) }
        started.exceptionOrNull()?.let { error -> runSuspendCatching { onFailure(error) } }
        if (!started.getOrDefault(false)) return

        while (true) {
            awaitInterval(intervalMillis)
            val state = currentState()
            if (!state.isPaused) {
                reportNow(state).exceptionOrNull()?.let { error -> runSuspendCatching { onFailure(error) } }
            }
        }
    }

    suspend fun reportNow(state: MediaServerPlaybackState): Result<Boolean> =
        runSuspendCatching { reporter.reportProgress(state) }

    suspend fun reportStopped(
        state: MediaServerPlaybackState,
        failed: Boolean = false,
    ): Result<Boolean> = runSuspendCatching { reporter.reportStopped(state, failed) }
}

private const val DEFAULT_PROGRESS_INTERVAL_MILLIS = 10_000L
