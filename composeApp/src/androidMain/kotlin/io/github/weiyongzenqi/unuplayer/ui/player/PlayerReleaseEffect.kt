package io.github.weiyongzenqi.unuplayer.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import kotlinx.coroutines.runBlocking
import io.github.weiyongzenqi.unuplayer.core.player.AndroidPlayerLifecycleTasks
import io.github.weiyongzenqi.unuplayer.core.player.AndroidPlayerSessionCloseLease
import io.github.weiyongzenqi.unuplayer.core.player.MpvPlayerEngine
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
) {
    DisposableEffect(Unit) {
        onDispose {
            val finalPos = engine.position.value
            val finalDur = engine.state.value.durationMs
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
                    runCatching {
                        runBlocking {
                            recordRepo.finishPlayback(
                                recordKey,
                                finalPos,
                                finalDur,
                                finalProgress,
                                completed,
                                finishedAt,
                            )
                        }
                    }.onFailure { error ->
                        appLogger?.appEvent(
                            "player",
                            "保存最终播放记录失败: ${error.javaClass.simpleName}: ${error.message}",
                            LogLevel.ERROR,
                        )
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
