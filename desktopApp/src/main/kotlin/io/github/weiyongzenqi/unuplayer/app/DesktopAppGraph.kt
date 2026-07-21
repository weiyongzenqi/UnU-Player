package io.github.weiyongzenqi.unuplayer.app

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepositoryImpl
import io.github.weiyongzenqi.unuplayer.core.security.DesktopCredentialCipher
import io.github.weiyongzenqi.unuplayer.core.security.EncryptedSecretStorage
import io.github.weiyongzenqi.unuplayer.danmaku.source.ManualMatchCacheRepository
import io.github.weiyongzenqi.unuplayer.library.DesktopMediaSourceFactory
import io.github.weiyongzenqi.unuplayer.library.PosterWallScanCoordinator
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepositoryImpl
import io.github.weiyongzenqi.unuplayer.local.DesktopLocalDirectoryRepository
import io.github.weiyongzenqi.unuplayer.core.platform.AppNotif
import io.github.weiyongzenqi.unuplayer.platform.DesktopAppLogger
import io.github.weiyongzenqi.unuplayer.platform.DesktopStorage
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepositoryImpl
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabaseProvider
import io.github.weiyongzenqi.unuplayer.ui.AppDependencies
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.closeSharedHttpClient
import io.github.weiyongzenqi.unuplayer.webdav.setSharedHttpClientTlsInsecure
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Windows 进程级依赖图。不能在 Compose `application {}` 内裸创建，否则每次重组都会复制 scope、
 * 日志 collector 和扫描协调器，导致同一数据库出现多个后台扫描任务。
 */
class DesktopAppGraph : AutoCloseable {
    val scope = MainScope()
    val storage = DesktopStorage()
    val credentialCipher = DesktopCredentialCipher()
    val appLogger = DesktopAppLogger()
    val settingsRepository = SettingsRepositoryImpl(
        storage,
        scope,
        EncryptedSecretStorage(storage, credentialCipher),
    )
    val manualMatchCacheRepository = ManualMatchCacheRepository(storage)
    val webDavRepository = WebDavConnectionRepository(UnuDatabaseProvider.get(), credentialCipher)
    val playbackRepository = PlaybackRecordRepositoryImpl.get()
    val scrapedRepository = ScrapedLibraryRepositoryImpl.get()
    val mediaSourceFactory = DesktopMediaSourceFactory(webDavRepository)
    val scanCoordinator = PosterWallScanCoordinator(scrapedRepository, mediaSourceFactory)
    val dependencies = AppDependencies(
        webDavRepository = webDavRepository,
        settingsRepository = settingsRepository,
        localDirectoryRepository = DesktopLocalDirectoryRepository(storage),
        appLogger = appLogger,
        playbackRepository = playbackRepository,
        scrapedRepository = scrapedRepository,
        mediaSourceFactory = mediaSourceFactory,
        posterWallScanCoordinator = scanCoordinator,
    )

    private val closed = AtomicBoolean(false)
    private val playerReleaseExecutor = Executors.newSingleThreadExecutor { task ->
        // native destroy 极端情况下可能永久阻塞；daemon 是最终兜底，正常关闭仍会先有界等待。
        Thread(task, "unu-player-release").apply { isDaemon = true }
    }
    // CR-066: 播放记录写(DB, 可阻塞 5s+ WAL checkpoint)与 native destroy 分离到独立有界池,
    // 避免 runBlocking finishPlayback 阻塞单线程 releaseExecutor 导致 destroy 队列背压、
    // 最坏 close() awaitTermination(10s) 超时 shutdownNow 强制中断 destroy -> native 句柄泄漏。
    // 2 worker: 不同 mediaKey 的记录写并发; 队列无界但播放窗口销毁时通常只 1 个任务, 不会堆积。
    private val playerRecordExecutor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "unu-player-record").apply { isDaemon = true }
    }

    init {
        AppNotif.setLogger(appLogger)
        scope.launch {
            settingsRepository.state.collect { settings ->
                appLogger.setDirectory(if (settings.enableLogs) settings.logDirUri else null)
                appLogger.setAppLogLevel(
                    runCatching { LogLevel.valueOf(settings.appLogLevel.uppercase()) }
                        .getOrDefault(LogLevel.INFO),
                )
                // B12: TLS 降级开关同步到进程级共享 HTTP 客户端(WebDAV 列目录/弹弹play 匹配/字幕下载)。
                setSharedHttpClientTlsInsecure(settings.allowTlsInsecure)
            }
        }
    }

    /**
     * 播放窗口销毁时提交最终记录写入与 native 释放。应用退出会先等待这里清空，再关闭 SQLite。
     */
    fun submitPlayerRelease(task: () -> Unit) {
        try {
            playerReleaseExecutor.execute(task)
        } catch (_: RejectedExecutionException) {
            // 仅可能发生在应用关闭边界；同步完成比遗留 native 句柄或丢最终记录更安全。
            task()
        }
    }

    /**
     * 提交播放记录最终写入(DB IO, 可阻塞)。与 [submitPlayerRelease] 分离, 使 native destroy
     * 不被 SQLite busy 拖累; close() 时 [playerRecordExecutor] 在 SQLite 关闭前 awaitTermination。
     */
    fun submitPlayerRecord(task: () -> Unit) {
        try {
            playerRecordExecutor.execute(task)
        } catch (_: RejectedExecutionException) {
            task()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scanCoordinator.close()
        playerReleaseExecutor.shutdown()
        // CR-066: 先等 release(native destroy) 完成或超时, 再等 record(DB 写); 二者独立但都须在 DB close 前结束。
        playerRecordExecutor.shutdown()
        var interrupted = false
        try {
            if (!playerReleaseExecutor.awaitTermination(PLAYER_RELEASE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                appLogger.appEvent(
                    "lifecycle",
                    "播放器释放超过 ${PLAYER_RELEASE_SHUTDOWN_TIMEOUT_SECONDS}s，强制结束后台释放队列",
                    LogLevel.WARN,
                )
                playerReleaseExecutor.shutdownNow()
                playerReleaseExecutor.awaitTermination(FORCED_SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)
            }
            if (!playerRecordExecutor.awaitTermination(PLAYER_RECORD_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                appLogger.appEvent(
                    "lifecycle",
                    "播放记录写入超过 ${PLAYER_RECORD_SHUTDOWN_TIMEOUT_SECONDS}s，强制结束后台记录队列",
                    LogLevel.WARN,
                )
                playerRecordExecutor.shutdownNow()
                playerRecordExecutor.awaitTermination(FORCED_SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            interrupted = true
            playerReleaseExecutor.shutdownNow()
            playerRecordExecutor.shutdownNow()
        }
        runCatching { closeSharedHttpClient() }
        runCatching { UnuDatabaseProvider.checkpointTruncate() }
        runCatching { UnuDatabaseProvider.close() }
        AppNotif.setLogger(null)
        runCatching { appLogger.shutdown() }
        scope.cancel()
        if (interrupted) Thread.currentThread().interrupt()
    }

    private companion object {
        const val PLAYER_RELEASE_SHUTDOWN_TIMEOUT_SECONDS = 10L
        const val PLAYER_RECORD_SHUTDOWN_TIMEOUT_SECONDS = 10L
        const val FORCED_SHUTDOWN_GRACE_SECONDS = 2L
    }
}
