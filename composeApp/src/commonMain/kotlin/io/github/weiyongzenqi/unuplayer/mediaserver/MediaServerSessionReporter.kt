package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 串行化播放报告，并确保 start/stop 这类非幂等请求最多尝试一次（失败后可重试，成功后置位）。
 *
 * @param sessionRefresher E-P2-2: 401(会话 token 失效)时按本次失败会话重建并返回新会话; null=无刷新能力。
 *   由构造方(MediaServerCatalogSource)注入仓库层的 createSession 重建逻辑。
 */
class MediaServerSessionReporter(
    private val api: MediaServerApi,
    session: MediaServerSession,
    private val sessionRefresher: suspend (MediaServerSession) -> MediaServerSession? = { null },
) {
    private val mutex = Mutex()
    /** E-P2-2: session 可变, 401 刷新后替换, 后续上报用新会话。 */
    @Volatile private var session = session
    private var startAttempted = false
    private var started = false
    private var stopAttempted = false

    suspend fun reportStarted(state: MediaServerPlaybackState): Boolean = mutex.withLock {
        if (startAttempted || stopAttempted) return@withLock false
        // E-P2-1: 成功后置位, 失败不置 startAttempted——之前提前置位使失败后整场哑火无重试。
        withUnauthorizedRetry { api.reportPlaybackStarted(session, state) }
        startAttempted = true
        started = true
        true
    }

    /** 调度协程因播放错误重启时复用已成功公告的会话，不重复发送非幂等 Started。 */
    internal suspend fun hasActiveStartedSession(): Boolean = mutex.withLock {
        started && !stopAttempted
    }

    suspend fun reportProgress(state: MediaServerPlaybackState): Boolean = mutex.withLock {
        if (!started || stopAttempted) return@withLock false
        withUnauthorizedRetry { api.reportPlaybackProgress(session, state) }
        true
    }

    suspend fun reportStopped(
        state: MediaServerPlaybackState,
        failed: Boolean = false,
    ): Boolean = mutex.withLock {
        if (stopAttempted) return@withLock false
        stopAttempted = true
        withUnauthorizedRetry { api.reportPlaybackStopped(session, state, failed) }
        true
    }

    /** E-P2-2: 401 时经 [sessionRefresher] 重建会话重试一次; 仍失败/无刷新能力则原样抛给调用方。 */
    private suspend fun withUnauthorizedRetry(block: suspend () -> Unit) {
        val failedSession = session
        try {
            block()
        } catch (error: MediaServerHttpException) {
            if (error.statusCode != UNAUTHORIZED_STATUS) throw error
            val refreshed = sessionRefresher(failedSession) ?: throw error
            session = refreshed
            block()
        }
    }
}

/**
 * 播放报告调度策略。自身不创建 Scope/线程，生命周期由调用方协程持有：
 * Started 成功后，播放中按固定间隔报告；暂停态由 UI 在状态变化时调用 [reportNow] 即时报告。
 */
internal class MediaServerPlaybackReportCoordinator(
    private val reporter: MediaServerSessionReporter,
    private val intervalMillis: Long = DEFAULT_PROGRESS_INTERVAL_MILLIS,
    private val maxStartedAttempts: Int = DEFAULT_MAX_STARTED_ATTEMPTS,
    private val maxStartedRetryDelayMillis: Long = DEFAULT_MAX_STARTED_RETRY_DELAY_MILLIS,
    private val awaitInterval: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(intervalMillis > 0L) { "播放进度上报间隔必须大于 0" }
        require(maxStartedAttempts > 0) { "Started 上报次数上限必须大于 0" }
        require(maxStartedRetryDelayMillis > 0L) { "Started 重试间隔上限必须大于 0" }
    }

    suspend fun runPeriodic(
        currentState: () -> MediaServerPlaybackState,
        onFailure: (Throwable) -> Unit = {},
        startedState: (() -> MediaServerPlaybackState)? = null,
        shouldStop: () -> Boolean = { false },
    ) {
        // Started 失败后有限重试，避免服务持续不可用时在页面内永久请求。
        var announced = reporter.hasActiveStartedSession()
        var attempt = 0
        var retryDelayMillis = intervalMillis.coerceAtMost(maxStartedRetryDelayMillis)
        while (!announced && attempt < maxStartedAttempts && !shouldStop()) {
            attempt++
            val started = runSuspendCatching { reporter.reportStarted((startedState ?: currentState)()) }
            started.exceptionOrNull()?.let { error -> runSuspendCatching { onFailure(error) } }
            if (started.getOrDefault(false)) {
                announced = true
            } else if (attempt < maxStartedAttempts && !shouldStop()) {
                awaitInterval(retryDelayMillis)
                retryDelayMillis = doubledDelayCapped(retryDelayMillis, maxStartedRetryDelayMillis)
            }
        }
        if (!announced || shouldStop()) return

        while (!shouldStop()) {
            awaitInterval(intervalMillis)
            if (shouldStop()) return
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

private fun doubledDelayCapped(value: Long, cap: Long): Long =
    if (value >= cap || value > Long.MAX_VALUE / 2L) cap else (value * 2L).coerceAtMost(cap)

private const val DEFAULT_PROGRESS_INTERVAL_MILLIS = 10_000L
private const val DEFAULT_MAX_STARTED_ATTEMPTS = 5
private const val DEFAULT_MAX_STARTED_RETRY_DELAY_MILLIS = 60_000L
