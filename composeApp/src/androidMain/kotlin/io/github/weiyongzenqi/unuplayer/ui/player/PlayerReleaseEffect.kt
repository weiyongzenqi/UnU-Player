package io.github.weiyongzenqi.unuplayer.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import io.github.weiyongzenqi.unuplayer.core.player.AndroidPlayerLifecycleTasks
import io.github.weiyongzenqi.unuplayer.core.player.AndroidPlayerSessionCloseLease
import io.github.weiyongzenqi.unuplayer.core.player.MpvPlayerEngine
import io.github.weiyongzenqi.unuplayer.core.player.PlaybackStatus
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.playback.nextPlaybackWriteTimestamp

/**
 * 播放器退出协调器：onDispose 只发布状态并提交预留任务，所有可能阻塞的工作留在后台队列。
 * 关闭许可通过 State 读取，避免 DisposableEffect 捕获初始化阶段的 null 快照。
 */
@Composable
internal fun PlayerReleaseEffect(
    engine: MpvPlayerEngine,
    appLogger: AppLogger?,
    recordRepo: PlaybackRecordRepository,
    recordKey: String,
    tempFileSession: PlaybackTempFileSession,
    recordWriteGate: PlaybackRecordWriteGate,
    sessionCloseLease: State<AndroidPlayerSessionCloseLease?>,
    onFinalizePlayback: (suspend (positionMs: Long, durationMs: Long, failed: Boolean) -> Unit)? = null,
    /** 引擎归零(EOF 后 mpv 卸载文件)时的最终写回退快照: (positionMs, durationMs)。 */
    lastValidPlayback: () -> Pair<Long, Long> = { 0L to 0L },
) {
    DisposableEffect(Unit) {
        onDispose {
            val rawPos = engine.position.value
            val rawDur = engine.state.value.durationMs
            val (snapshotPos, snapshotDur) = lastValidPlayback()
            val finalPos = if (rawPos > 0L) rawPos else snapshotPos
            val finalDur = if (rawDur > 0L) rawDur else snapshotDur
            val finalFailed = engine.state.value.status == PlaybackStatus.ERROR
            val destroyTask = engine.captureReleaseTask() ?: return@onDispose
            val closeLease = sessionCloseLease.value
            if (closeLease == null) {
                // 初始化仍在等待关闭许可，MPVLib 尚未创建；这里只完成无 native 的状态收口。
                runCatching(destroyTask)
                return@onDispose
            }
            val cleanupTask = tempFileSession.detachCleanupTask()
            if (cleanupTask != null) {
                tempFileSession.setLateCleanupScheduler {
                    val accepted = AndroidPlayerLifecycleTasks.submitCleanup(appLogger, "迟到字幕清理") {
                        cleanupTask().forEach { file ->
                            if (file.exists() && !file.delete()) {
                                runCatching {
                                    appLogger?.appEvent("player", "删除迟到字幕失败: ${file.name}", LogLevel.WARN)
                                }
                            }
                        }
                    }
                    if (!accepted) {
                        runCatching {
                            appLogger?.appEvent("player", "迟到字幕清理未能进入有界队列", LogLevel.ERROR)
                        }
                    }
                }
            }

            val completed = if (finalDur > 0 &&
                (finalPos.toDouble() / finalDur >= 0.9 || finalPos >= finalDur - 15_000)
            ) 1L else 0L
            val finalProgress = if (finalDur > 0) {
                (finalPos.toDouble() / finalDur).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val finishedAt = nextPlaybackWriteTimestamp()
            val finalRecordAccepted = recordWriteGate.closeAndSubmit {
                val accepted = closeLease.submitRecord(appLogger, "最终播放记录") {
                    runBlocking {
                        val failures = runPlaybackFinalizers(
                            // B-01: 只有本会话观察到正位置才允许最终写。duration 可能早于续播 seek 的
                            // time-pos 到达，不能作为“已开始播放”的证据；SQL 另有零不覆盖非零的第二道守卫。
                            // 远端 Stopped 不受影响，服务端会话仍必须关闭。
                            finishLocal = if (shouldPersistFinalPlayback(finalPos)) {
                                {
                                    recordRepo.finishPlayback(
                                        recordKey,
                                        finalPos,
                                        finalDur,
                                        finalProgress,
                                        completed,
                                        finishedAt,
                                    )
                                }
                            } else {
                                null
                            },
                            finishRemote = onFinalizePlayback?.let { finalize ->
                                { finalize(finalPos, finalDur, finalFailed) }
                            },
                        )
                        failures.local?.let { error ->
                            appLogger?.appEvent(
                                "player",
                                "保存最终播放记录失败: ${error.javaClass.simpleName}: ${error.message}",
                                LogLevel.ERROR,
                            )
                        }
                        failures.remote?.let { error ->
                            appLogger?.appEvent(
                                "media-server",
                                "发送最终播放状态失败: ${error.javaClass.simpleName}",
                                LogLevel.WARN,
                            )
                        }
                    }
                }
                if (!accepted) {
                    runCatching {
                        appLogger?.appEvent("player", "最终播放记录未能进入有界队列", LogLevel.ERROR)
                    }
                }
            }
            if (!finalRecordAccepted) {
                runCatching {
                    appLogger?.appEvent("player", "最终播放记录 gate 已关闭，跳过重复提交", LogLevel.WARN)
                }
            }

            val releaseAccepted = closeLease.submitNative(appLogger, "播放器退出") {
                runCatching(destroyTask).onFailure { error ->
                    runCatching {
                        appLogger?.appEvent(
                            "engine",
                            "destroy 失败: ${error.javaClass.simpleName}: ${error.message}",
                            LogLevel.ERROR,
                        )
                    }
                }
                val cleanupAccepted = closeLease.submitCleanup(appLogger, "会话临时字幕清理") {
                    cleanupTask?.invoke().orEmpty().forEach { file ->
                        val deleted = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
                        if (!deleted) {
                            runCatching {
                                appLogger?.appEvent(
                                    "player",
                                    "删除会话临时字幕失败: ${file.name}",
                                    LogLevel.WARN,
                                )
                            }
                        }
                    }
                }
                if (!cleanupAccepted) {
                    runCatching {
                        appLogger?.appEvent("player", "会话临时字幕清理未能进入预留队列", LogLevel.ERROR)
                    }
                }
            }
            if (!releaseAccepted) {
                closeLease.releaseUnusedReservations()
                runCatching {
                    appLogger?.appEvent("engine", "播放器退出未能进入预留 native 队列", LogLevel.ERROR)
                }
            }
        }
    }
}

internal data class PlaybackFinalizationFailures(
    val local: Throwable?,
    val remote: Throwable?,
)

/**
 * 远端 Stopped 上限(A-08)。服务端单次上报正常 <2s, 8s 足够容忍弱网, 又远小于 OkHttp 默认
 * 超时链(~75s): 不限时的话一次卡死的上报会独占进程级 record 单 worker 队列 75s, 阻塞后续会话最终写。
 */
internal const val REMOTE_FINALIZE_TIMEOUT_MS = 8_000L

/** 远端 Stopped 超时即失败(A-08): 调用方按 remote 失败记 WARN(只记异常名, 不含 URL/token)。 */
private class RemoteFinalizeTimeoutException : RuntimeException("media server finalize timed out")

/**
 * 本地最终写与远端 Stopped 相互隔离，任一失败都不能跳过另一项。
 *
 * [finishLocal] 为 null 表示调用方守卫决定跳过本地最终写(B-01: 未观察到有效播放, 零位置会覆盖
 * 已有续播点); 远端 Stopped 仍照常执行(服务端会话必须关闭)。
 *
 * A-08: 远端上报限时 [remoteTimeoutMs](默认 [REMOTE_FINALIZE_TIMEOUT_MS])。必须用 withTimeoutOrNull
 * 而非 withTimeout: 后者抛 TimeoutCancellationException, 它是 CancellationException, 会被
 * runSuspendCatching 当协程取消向上重抛(见 CoroutineResult 注释), 取消整个 record 任务 = 语义错误。
 * 超时转 [RemoteFinalizeTimeoutException] 走普通失败路径, 不阻塞队列后续会话。
 */
internal suspend fun runPlaybackFinalizers(
    finishLocal: (suspend () -> Unit)?,
    finishRemote: (suspend () -> Unit)?,
    remoteTimeoutMs: Long = REMOTE_FINALIZE_TIMEOUT_MS,
): PlaybackFinalizationFailures {
    val localFailure = finishLocal?.let { task -> runSuspendCatching { task() }.exceptionOrNull() }
    val remoteFailure = finishRemote?.let { task ->
        runSuspendCatching {
            val completed = withTimeoutOrNull(remoteTimeoutMs) {
                task()
                true
            }
            if (completed == null) throw RemoteFinalizeTimeoutException()
        }.exceptionOrNull()
    }
    return PlaybackFinalizationFailures(localFailure, remoteFailure)
}

/**
 * B-01 守卫判据：只有本会话观察到正播放位置才执行本地 finishPlayback。
 * duration 可在续播 seek 完成前先到达，不能用于证明用户确实从头开始观看。
 */
internal fun shouldPersistFinalPlayback(finalPos: Long): Boolean = finalPos > 0L
