package io.github.weiyongzenqi.unuplayer.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPlacement
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.swing.JOptionPane
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.gl.DesktopRenderBackend
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.core.player.PlayerConfig
import io.github.weiyongzenqi.unuplayer.platform.DesktopAppDirectories
import io.github.weiyongzenqi.unuplayer.platform.DesktopWindowPreferences
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import io.github.weiyongzenqi.unuplayer.platform.WindowsAppMutex
import io.github.weiyongzenqi.unuplayer.ui.App
import io.github.weiyongzenqi.unuplayer.ui.player.DesktopPlayerScreen
import io.github.weiyongzenqi.unuplayer.ui.theme.UnUTheme

/**
 * Windows 桌面端入口。
 *
 * 通过 [DesktopAppGraph] 装配桌面依赖并注入 commonMain 的 [App]。点视频开独立播放窗口运行
 * [DesktopPlayerScreen](libmpv JNA render API)。
 *
 * 阶段进度:
 * - WebDAV 浏览 / 设置: 已可用; 本地浏览: java.io.File 实现; 海报墙与播放记录使用 JDBC SQLite
 * - 播放器: 本地 Windows 默认 Direct3D 合成；libmpv sw 输出支持 copy-back 硬解
 *
 * 设置持久化统一写入应用数据目录，日志写入用户选择的本地目录。
 */
fun main() {
    val appMutex = WindowsAppMutex.acquire() ?: run {
        // acquire() 返回 null 仅代表已有实例在运行(非 Windows 返回无句柄实例, 创建失败直接抛错)。
        // 静默 return 会让用户双击后"没反应", 故退出前同步弹一次模态提示。
        notifyAlreadyRunning()
        return
    }
    appMutex.use { runDesktopApplication() }
}

/**
 * 已有实例在运行时的一次性提示。[JOptionPane.showMessageDialog] 为模态框, 在进程退出前同步显示完毕;
 * 此时本进程未持有任何互斥量句柄(acquire 内部对已存在的句柄已 CloseHandle), 提示期间无句柄泄漏。
 * runCatching 兜底: 无显示环境(如烟测)下弹窗失败也照常退出。
 */
private fun notifyAlreadyRunning() {
    runCatching {
        JOptionPane.showMessageDialog(
            null,
            "UnU-Player 已在运行中",
            "UnU Player",
            JOptionPane.INFORMATION_MESSAGE,
        )
    }
}

private fun runDesktopApplication() {
    DesktopAppDirectories.configureNativeTempDirectories()
    val renderBackend = DesktopRenderBackend.configureBeforeCompose()
    val smokeUrl = System.getenv("UNU_PLAYER_SMOKE_URL")?.takeIf { it.isNotBlank() }
    val graph = DesktopAppGraph()
    graph.scope.launch {
        // 日志目录/等级统一由 DesktopAppGraph 的常驻 collect 维护(见该类 init), 此处不再重复设置,
        // 避免双处维护。设置加载完成前的启动日志用默认等级/不落盘(可接受); 渲染后端事件仍在加载后记录一次。
        graph.settingsRepository.awaitLoaded()
        graph.appLogger.appEvent(
            "render",
            "backend requested=${renderBackend.requestedApi} reason=${renderBackend.reason} remoteSession=${renderBackend.remoteSession}",
            LogLevel.INFO,
        )
    }
    val windowPreferences = DesktopWindowPreferences()
    val initialMainWindowBounds = windowPreferences.loadMain().ensureVisibleOnCurrentScreens()

    try {
        application(exitProcessOnExit = false) {
        System.getenv("UNU_APP_AUTO_EXIT_MS")?.toLongOrNull()?.takeIf { it > 0 }?.let { autoExitMs ->
            LaunchedEffect(autoExitMs) {
                delay(autoExitMs)
                exitApplication()
            }
        }

        // 播放请求(开独立播放窗口)；UNU_PLAYER_SMOKE_URL 仅供自动化烟测。
        var playing by remember {
            mutableStateOf(
                smokeUrl?.let {
                    PlayableMedia(
                        url = it,
                        title = "软件播放烟测",
                        sourceKind = MediaSourceKind.LOCAL,
                    )
                },
            )
        }
        val settings by graph.settingsRepository.state.collectAsState()
        val mainWindowState = rememberWindowState(
            placement = if (initialMainWindowBounds.maximized) {
                WindowPlacement.Maximized
            } else {
                WindowPlacement.Floating
            },
            position = initialMainWindowBounds.toWindowPosition(),
            width = initialMainWindowBounds.width.dp,
            height = initialMainWindowBounds.height.dp,
        )
        val mainWindowTracker = remember { DesktopWindowStateTracker(initialMainWindowBounds) }
        // 应用图标(任务栏/alt-tab/标题栏 logo); classpath 资源 desktopApp/src/main/resources/icon.png
        // useResource/loadImageBitmap 在 Compose 1.11 标记弃用, 但跨模块 compose resources 不可靠
        // (JetBrains compose-multiplatform#4327), 桌面窗口图标暂用 classpath 资源加载, 后续视官方支持再迁移。
        @Suppress("DEPRECATION")
        val appBitmap = useResource("icon.png") { loadImageBitmap(it) }
        val appIcon = remember(appBitmap) { BitmapPainter(appBitmap) }
        PersistWindowState(mainWindowState, mainWindowTracker, windowPreferences::saveMain)

        var showCloseDialog by remember { mutableStateOf(false) }
        var closeDialogInitialBackground by remember { mutableStateOf(false) }

        val persistMainNow: () -> Unit = {
            runCatching {
                windowPreferences.saveMain(mainWindowTracker.capture(mainWindowState.toObservation()))
            }.onFailure { error ->
                graph.appLogger.appEvent(
                    "window",
                    "保存主窗口状态失败: ${error.javaClass.simpleName}: ${error.message}",
                    LogLevel.WARN,
                )
            }
            Unit
        }
        val exitNow: () -> Unit = {
            persistMainNow()
            exitApplication()
        }
        val minimizeToBackground: () -> Unit = {
            persistMainNow()
            mainWindowState.isMinimized = true
        }
        val requestMainClose: () -> Unit = {
            if (settings.desktopClosePrompt) {
                closeDialogInitialBackground = settings.desktopRunInBackground
                showCloseDialog = true
            } else if (settings.desktopRunInBackground) {
                minimizeToBackground()
            } else {
                exitNow()
            }
        }

        // 主窗口(首页)
        Window(
            onCloseRequest = requestMainClose,
            title = "UnU Player",
            icon = appIcon,
            state = mainWindowState,
            undecorated = true,
        ) {
            UnUTheme(dynamicColor = settings.dynamicColor, darkTheme = settings.darkTheme) {
                Column(Modifier.fillMaxSize()) {
                    DesktopWindowTitleBar(
                        title = "UnU Player",
                        icon = appBitmap,
                        state = mainWindowState,
                        onClose = requestMainClose,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        App(
                            dependencies = graph.dependencies,
                            onPlay = { media ->
                                graph.appLogger.appEvent("app", "桌面播放 ${media.title}", LogLevel.INFO)
                                playing = media
                            },
                            // 免责声明拒绝属于明确退出，不应用后台模式拦截。
                            onExitApp = exitNow,
                        )
                    }
                }
                if (showCloseDialog) {
                    DesktopCloseDialog(
                        initialRunInBackground = closeDialogInitialBackground,
                        onDismiss = { showCloseDialog = false },
                        onConfirm = { runInBackground, dontAskAgain ->
                            showCloseDialog = false
                            graph.scope.launch {
                                runSuspendCatching {
                                    graph.settingsRepository.update {
                                        it.copy(
                                            desktopRunInBackground = runInBackground,
                                            desktopClosePrompt = !dontAskAgain,
                                        )
                                    }
                                }.onFailure { error ->
                                    graph.appLogger.appEvent(
                                        "window",
                                        "保存关闭行为失败: ${error.javaClass.simpleName}: ${error.message}",
                                        LogLevel.WARN,
                                    )
                                }
                                if (runInBackground) minimizeToBackground() else exitNow()
                            }
                        },
                    )
                }
            }
        }

        // 播放窗口(独立, 关闭回首页)
        playing?.let { media ->
            val initialPlayerWindowBounds = remember {
                windowPreferences.loadPlayer().ensureVisibleOnCurrentScreens()
            }
            val playerWindowState = rememberWindowState(
                placement = if (initialPlayerWindowBounds.maximized) {
                    WindowPlacement.Maximized
                } else {
                    WindowPlacement.Floating
                },
                position = initialPlayerWindowBounds.toWindowPosition(),
                width = initialPlayerWindowBounds.width.dp,
                height = initialPlayerWindowBounds.height.dp,
            )
            val playerWindowTracker = remember { DesktopWindowStateTracker(initialPlayerWindowBounds) }
            PersistWindowState(playerWindowState, playerWindowTracker, windowPreferences::savePlayer)
            val closePlayer: () -> Unit = {
                runCatching {
                    windowPreferences.savePlayer(playerWindowTracker.capture(playerWindowState.toObservation()))
                }.onFailure { error ->
                    graph.appLogger.appEvent(
                        "window",
                        "保存播放器窗口状态失败: ${error.javaClass.simpleName}: ${error.message}",
                        LogLevel.WARN,
                    )
                }
                playing = null
            }
            var borderlessFullscreen by remember(media.url) { mutableStateOf(false) }
            var previousPlacement by remember(media.url) { mutableStateOf(WindowPlacement.Floating) }
            val toggleBorderlessFullscreen = {
                if (borderlessFullscreen) {
                    borderlessFullscreen = false
                    playerWindowState.placement = previousPlacement
                } else {
                    previousPlacement = playerWindowState.placement.takeUnless {
                        it == WindowPlacement.Fullscreen
                    } ?: WindowPlacement.Floating
                    borderlessFullscreen = true
                    playerWindowState.placement = WindowPlacement.Fullscreen
                }
            }
            Window(
                onCloseRequest = closePlayer,
                title = media.title.ifBlank { "UnU Player" },
                icon = appIcon,
                state = playerWindowState,
                undecorated = true,
            ) {
                UnUTheme(dynamicColor = settings.dynamicColor, darkTheme = settings.darkTheme) {
                    Column(Modifier.fillMaxSize()) {
                        if (!borderlessFullscreen) {
                            DesktopWindowTitleBar(
                                title = media.title.ifBlank { "UnU Player" },
                                icon = appBitmap,
                                state = playerWindowState,
                                onClose = closePlayer,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            DesktopPlayerScreen(
                                media = media,
                                config = PlayerConfig(
                                    hwdec = settings.hwdec,
                                    audioOutput = settings.audioOutput,
                                    hdrMode = settings.hdrMode,
                                    cacheSize = settings.cacheSize,
                                    cacheSecs = settings.cacheSecs,
                                    httpHeaders = media.headers,
                                    allowTlsInsecure = settings.allowTlsInsecure,
                                    logLevel = if (settings.enableLogs) settings.logLevel else "",
                                    subAuto = if (settings.autoLoadSiblingSubtitle) "fuzzy" else "no",
                                ),
                                settingsRepository = graph.settingsRepository,
                                webDavRepository = graph.webDavRepository,
                                manualMatchCacheRepository = graph.manualMatchCacheRepository,
                                playbackRepository = graph.playbackRepository,
                                logger = graph.appLogger,
                                releaseExecutor = graph::submitPlayerRelease,
                                recordExecutor = graph::submitPlayerRecord,
                                isFullscreen = borderlessFullscreen,
                                onToggleFullscreen = toggleBorderlessFullscreen,
                                onEscape = {
                                    if (borderlessFullscreen) toggleBorderlessFullscreen() else closePlayer()
                                },
                                onClose = closePlayer,
                            )
                        }
                    }
                }
            }
        }
        }
    } finally {
        graph.close()
    }
}
