package io.github.weiyongzenqi.unuplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.domain.SettingsLoadState
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.StartupHome
import io.github.weiyongzenqi.unuplayer.library.MediaSourceFactory
import io.github.weiyongzenqi.unuplayer.library.PosterWallScanCoordinator
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.local.LocalDirectoryRepository
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.ui.browser.WebDavBrowserScreen
import io.github.weiyongzenqi.unuplayer.ui.local.LocalBrowserScreen
import io.github.weiyongzenqi.unuplayer.ui.posterwall.AnimeScreen
import io.github.weiyongzenqi.unuplayer.ui.settings.SettingsScreen
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository

/** 主导航 tab。 */
enum class UnUTab { WEBDAV, ANIME, LOCAL, SETTINGS }

/** App 依赖(平台侧注入)。 */
class AppDependencies(
    val webDavRepository: WebDavConnectionRepository,
    val settingsRepository: SettingsRepository,
    val localDirectoryRepository: LocalDirectoryRepository,
    /** 日志器(平台侧, 默认关闭; 设置开启后写选定目录)。null=平台不支持。 */
    val appLogger: AppLogger? = null,
    /** 播放记录仓库(浏览列表"已播放进度"披露式查询用)。null=平台不支持/降级不显示进度。 */
    val playbackRepository: PlaybackRecordRepository? = null,
    /** 刮削库仓库(海报墙用)。null=平台不支持, 海报墙 tab 降级提示。 */
    val scrapedRepository: ScrapedLibraryRepository? = null,
    /** 媒体来源工厂(海报墙扫描/图片/播放按 library 重建 MediaSource)。null=平台不支持。 */
    val mediaSourceFactory: MediaSourceFactory? = null,
    /** 海报墙扫描协调器(进程级, 跨 tab 保持扫描状态/阻塞重复触发)。null=平台不支持。 */
    val posterWallScanCoordinator: PosterWallScanCoordinator? = null,
)

/**
 * UnU-Player 首页(跨平台)。
 *
 * 导航布局由平台 [HomeNavShell] 决定: Android 底部 NavigationBar; 桌面据 settings.desktopLayout
 * 选侧边栏(SIDEBAR)或顶部 tab(TOP_TABS)。内容区 [HomeTabs] 共用, 状态保持(SaveableStateHolder)。
 *
 * 首次启动先弹免责声明(强制阅读 3 秒并同意, 见 [DisclaimerScreen]), 同意持久化后不再弹出;
 * 回访用户等 SettingsRepository 加载完成后直接进首页(避免声明一闪)。
 *
 * 播放器: Android 走独立 PlayerActivity([onPlay] 拉起); 桌面阶段5 接入(本次 onPlay 占位)。
 */
@Composable
fun App(
    dependencies: AppDependencies,
    /** 拉起播放器(平台侧注入)。Android 启动 PlayerActivity; 桌面阶段5 接入。 */
    onPlay: (PlayableMedia) -> Unit,
    /** 退出应用(免责声明"不同意"时调)。Android finishAffinity; 桌面 exitApplication。 */
    onExitApp: () -> Unit,
) {
    val settings by dependencies.settingsRepository.state.collectAsState()
    val loadState by dependencies.settingsRepository.loadState.collectAsState()
    val scope = rememberCoroutineScope()

    when (val currentLoadState = loadState) {
        // 加载中: 纯背景, 不渲染任何 UI
        SettingsLoadState.Loading -> {
            SettingsLoadingScreen()
        }

        is SettingsLoadState.Failed -> {
            SettingsLoadErrorScreen(
                message = currentLoadState.message,
                onRetry = {
                    scope.launch { dependencies.settingsRepository.retryLoad() }
                },
                onUseDefaults = {
                    scope.launch { dependencies.settingsRepository.useDefaultsAfterLoadFailure() }
                },
            )
        }

        SettingsLoadState.Loaded -> {
            if (!settings.disclaimerAccepted) {
                // 首次启动: 强制阅读免责声明 3 秒并同意
                DisclaimerScreen(
                    onAgree = {
                        scope.launch {
                            dependencies.settingsRepository.update { it.copy(disclaimerAccepted = true) }
                        }
                    },
                    onDisagree = onExitApp,
                )
            } else {
                // 主页: 平台导航壳 + 共用内容区
                val animeAvailable = settings.posterWallEnabled &&
                    dependencies.scrapedRepository != null &&
                    dependencies.mediaSourceFactory != null &&
                    dependencies.posterWallScanCoordinator != null
                var selectedTab by rememberSaveable {
                    mutableStateOf(resolveStartupTab(settings.startupHome, animeAvailable))
                }
                HomeNavShell(
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    desktopLayout = settings.desktopLayout,
                ) { padding ->
                    HomeTabs(
                        selectedTab = selectedTab,
                        onPlay = onPlay,
                        dependencies = dependencies,
                        posterWallEnabled = settings.posterWallEnabled,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }
            }
        }
    }
}

internal fun resolveStartupTab(startupHome: StartupHome, animeAvailable: Boolean): UnUTab = when (startupHome) {
    StartupHome.WEBDAV -> UnUTab.WEBDAV
    StartupHome.ANIME -> if (animeAvailable) UnUTab.ANIME else UnUTab.WEBDAV
    StartupHome.LOCAL -> UnUTab.LOCAL
}

/**
 * tab 内容容器: 按选中 tab 渲染对应页面。
 *
 * 用 SaveableStateHolder 包裹: 切走 tab 时保留各 tab 的 rememberSaveable 状态
 * (路径栈/选中连接等), 切回来恢复到上次所在页面, 而非每次回首页。
 */
@Composable
private fun HomeTabs(
    selectedTab: UnUTab,
    onPlay: (PlayableMedia) -> Unit,
    dependencies: AppDependencies,
    posterWallEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val holder = rememberSaveableStateHolder()
    Box(modifier) {
        holder.SaveableStateProvider(selectedTab.name) {
            when (selectedTab) {
                UnUTab.WEBDAV -> WebDavBrowserScreen(
                    onPlay = onPlay,
                    repository = dependencies.webDavRepository,
                    settingsRepository = dependencies.settingsRepository,
                    playbackRepository = dependencies.playbackRepository,
                )
                UnUTab.ANIME -> {
                    val scrapedRepo = dependencies.scrapedRepository
                    val factory = dependencies.mediaSourceFactory
                    val coordinator = dependencies.posterWallScanCoordinator
                    if (!posterWallEnabled) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("海报墙已关闭，可在设置中开启")
                        }
                    } else if (scrapedRepo != null && factory != null && coordinator != null) {
                        AnimeScreen(
                            onPlay = onPlay,
                            scrapedRepo = scrapedRepo,
                            mediaSourceFactory = factory,
                            scanCoordinator = coordinator,
                            webDavRepo = dependencies.webDavRepository,
                            localDirRepo = dependencies.localDirectoryRepository,
                            settingsRepo = dependencies.settingsRepository,
                            playbackRepo = dependencies.playbackRepository,
                        )
                    } else {
                        // 平台不支持: 降级提示
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("海报墙不可用")
                        }
                    }
                }
                UnUTab.LOCAL -> LocalBrowserScreen(
                    onPlay = onPlay,
                    repository = dependencies.localDirectoryRepository,
                )
                UnUTab.SETTINGS -> SettingsScreen(
                    onBack = null,
                    repository = dependencies.settingsRepository,
                    webDavRepository = dependencies.webDavRepository,
                    scrapedRepository = dependencies.scrapedRepository,
                    posterWallScanCoordinator = dependencies.posterWallScanCoordinator,
                    appLogger = dependencies.appLogger,
                    onPlay = onPlay,
                )
            }
        }
    }
}
