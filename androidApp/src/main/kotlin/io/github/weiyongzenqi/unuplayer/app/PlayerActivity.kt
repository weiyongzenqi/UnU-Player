package io.github.weiyongzenqi.unuplayer.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepositoryProvider
import io.github.weiyongzenqi.unuplayer.platform.AndroidStorage
import io.github.weiyongzenqi.unuplayer.platform.AndroidAppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import io.github.weiyongzenqi.unuplayer.ui.DisclaimerScreen
import io.github.weiyongzenqi.unuplayer.ui.SettingsLoadErrorScreen
import io.github.weiyongzenqi.unuplayer.ui.SettingsLoadingScreen
import io.github.weiyongzenqi.unuplayer.ui.player.PlayerScreen
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchConfig
import io.github.weiyongzenqi.unuplayer.core.media.SiblingSubtitleLoader
import io.github.weiyongzenqi.unuplayer.danmaku.source.ManualMatchCacheRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepositoryProvider
import io.github.weiyongzenqi.unuplayer.webdav.setSharedHttpClientTlsInsecure
import io.github.weiyongzenqi.unuplayer.ui.theme.UnUTheme

/**
 * 播放器独立 Activity。
 *
 * 与首页(MainActivity)分离, 目的: 退出时走系统级跨 Activity 预测返回动画。
 * 首页 Activity 始终竖屏、永不被旋转, 故退出播放器时系统直接淡入竖屏首页,
 * 不再出现"露出横屏首页 + 旋转回竖屏"的割裂闪烁, 也无需黑幕遮罩。
 *
 * 方向由 PlayerScreen 按视频尺寸动态锁定(横屏视频锁横屏, 竖屏视频锁竖屏);
 * finish() 后系统自动回到竖屏首页, 无需手动恢复方向。
 *
 * 入参(Intent)只包含 URL、标题、contentUri 和 mediaKey。WebDAV 认证按 mediaKey 中的连接 id
 * 从加密仓库重新加载，Authorization 不进入 Intent/Bundle。
 * 播放设置(hwdec/HDR/字幕/倍速等)经进程级单例 SettingsRepository(SettingsRepositoryProvider)读取,
 * 与首页共享同一仓库实例, 播放器内的设置更新不会被首页陈旧 state 还原。
 *
 * 首次启动免责声明: 设置加载成功或用户明确使用默认设置后，若 disclaimerAccepted=false，先弹 DisclaimerScreen
 * (与首页共享同一 DataStore), 不同意 finish() 绝不进入播放。外部 Intent 直接拉起本 Activity
 * 时也先过此关, 不给绕过路径。
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var appScope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: ""
        val contentUri = intent?.getStringExtra(EXTRA_CONTENT_URI)
        val mediaKey = intent?.getStringExtra(EXTRA_MEDIA_KEY)

        val storage = AndroidStorage(applicationContext)
        appScope = MainScope()
        val appLogger = AndroidAppLogger.get(applicationContext)
        appLogger.appEvent("app", "PlayerActivity title=$title", LogLevel.INFO)
        // 进程级单例(P1 修复): 与 MainActivity 共用同一设置仓库, 播放器内改的设置不被首页陈旧 state 还原。
        val settingsRepo = SettingsRepositoryProvider.get(applicationContext)
        // 手动匹配弹幕 per-file 记忆缓存(Storage 存 JSON map; 仿 WebDavConnectionRepository)
        val manualMatchCacheRepo = ManualMatchCacheRepository(storage)
        // 同目录外挂字幕加载器: 从 mediaKey/contentUri 重建列目录能力(WebDAV 用连接仓库, 本地用 SAF parentFile)
        // WebDAV 连接仓库同为进程级单例(B10 修复): 与首页共享实例锁, 迁移写与编辑并发不再丢更新。
        val webDavConnRepo = WebDavConnectionRepositoryProvider.get(applicationContext)
        val subtitleLoader = SiblingSubtitleLoader(applicationContext, webDavConnRepo)
        val credentialLoadState = MutableStateFlow<PlaybackCredentialLoadState>(PlaybackCredentialLoadState.Loading)

        fun reloadPlaybackCredentials() {
            appScope.launch {
                credentialLoadState.value = PlaybackCredentialLoadState.Loading
                try {
                    val connectionId = webDavConnectionId(mediaKey)
                    val headers = if (connectionId == null) {
                        emptyMap()
                    } else {
                        withContext(Dispatchers.IO) {
                            webDavConnRepo.playbackHeaders(connectionId, url)
                        }
                    }
                    credentialLoadState.value = PlaybackCredentialLoadState.Ready(headers)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    val errorType = error::class.simpleName ?: "未知错误"
                    credentialLoadState.value = PlaybackCredentialLoadState.Failed(
                        "播放凭据读取失败（$errorType）",
                    )
                }
            }
        }
        reloadPlaybackCredentials()
        // 设置驱动日志目录: 开启且选了目录 → 写; 否则不写。随设置变化更新。
        appScope.launch {
            settingsRepo.state.collect { s ->
                appLogger.setDirectory(if (s.enableLogs) s.logDirUri else null)
                appLogger.setAppLogLevel(runCatching { LogLevel.valueOf(s.appLogLevel.uppercase()) }.getOrDefault(LogLevel.INFO))
                // B12: TLS 降级开关同步到进程级共享 HTTP 客户端(WebDAV 列目录/弹弹play 匹配/字幕下载)。
                setSharedHttpClientTlsInsecure(s.allowTlsInsecure)
            }
        }

        // setContent 立即建立加载/错误 UI；只有读取成功或用户明确接受默认值后才构造 PlayerScreen。
        // 这样 init-only 的 HDR/解码设置不会在 Storage 读取失败时误用默认值启动。
        setContent {
            val settings by settingsRepo.state.collectAsState()
            val settingsLoadState by settingsRepo.loadState.collectAsState()
            val currentCredentialLoadState by credentialLoadState.collectAsState()
            val scope = rememberCoroutineScope()
            UnUTheme(
                dynamicColor = settings.dynamicColor,
                darkTheme = settings.darkTheme,
            ) {
                when (val destination = resolvePlayerStartupDestination(
                    settingsLoadState = settingsLoadState,
                    credentialLoadState = currentCredentialLoadState,
                    disclaimerAccepted = settings.disclaimerAccepted,
                )) {
                    PlayerStartupDestination.Loading -> SettingsLoadingScreen()
                    is PlayerStartupDestination.SettingsFailed -> {
                        SettingsLoadErrorScreen(
                            message = destination.message,
                            onRetry = {
                                scope.launch { settingsRepo.retryLoad() }
                            },
                            onUseDefaults = {
                                scope.launch { settingsRepo.useDefaultsAfterLoadFailure() }
                            },
                        )
                    }

                    is PlayerStartupDestination.CredentialsFailed -> SettingsLoadErrorScreen(
                        message = destination.message,
                        onRetry = ::reloadPlaybackCredentials,
                        title = "播放凭据加载失败",
                        recoveryHint = "为避免把失效凭据当作匿名请求，修复或重新添加连接后再重试。",
                    )

                    PlayerStartupDestination.Disclaimer -> {
                        // 首次启动免责声明闸门: 未同意则强制阅读 3 秒并同意, 不同意 finish() 无法播放。
                        // 与 MainActivity 共享同一 DataStore(unu_settings), 任一处同意即全局生效;
                        // 外部 Intent 直接拉起本 Activity 时也先过此关, 不给绕过路径。
                        DisclaimerScreen(
                            onAgree = {
                                scope.launch {
                                    settingsRepo.update { it.copy(disclaimerAccepted = true) }
                                }
                            },
                            // 不同意 = 关闭播放器, 回到调用方(首页或外部应用), 绝不进入播放
                            onDisagree = { finish() },
                        )
                    }

                    is PlayerStartupDestination.Player -> {
                        PlayerScreen(
                            playUrl = url,
                            playTitle = title,
                            contentUri = contentUri,
                            mediaKey = mediaKey,
                            recognizeAnime = settings.recognizeAnime,
                            hdrMode = settings.hdrMode,
                            longPressSpeed = settings.longPressSpeed,
                            hwdec = settings.hwdec,
                            audioOutput = settings.audioOutput,
                            cacheSize = settings.cacheSize,
                            cacheSecs = settings.cacheSecs,
                            allowTlsInsecure = settings.allowTlsInsecure,
                            playHeaders = destination.headers,
                            appLogger = appLogger,
                            logLevel = settings.logLevel,
                            subtitleFont = settings.subtitleFont,
                            subtitleFontDir = settings.subtitleFontDir,
                            subtitleScale = settings.subtitleScale,
                            subtitleColor = settings.subtitleColor,
                            subtitleBorderSize = settings.subtitleBorderSize,
                            subtitleBold = settings.subtitleBold,
                            subtitleStyleOverride = settings.subtitleStyleOverride,
                            defaultSubtitleTrackPattern = settings.defaultSubtitleTrackPattern,
                            defaultAudioTrackPattern = settings.defaultAudioTrackPattern,
                            speedPresets = settings.speedPresets,
                            predictiveBack = settings.predictiveBack,
                            danmakuConfig = DanmakuConfig(
                                enabled = settings.danmakuEnabled,
                                opacity = settings.danmakuOpacity,
                                fontSize = settings.danmakuFontSize,
                                displayArea = settings.danmakuDisplayArea,
                                speedMultiplier = settings.danmakuSpeedMultiplier,
                                engineType = when (settings.danmakuEngine) {
                                    "BITMAP" -> DanmakuEngineType.BITMAP
                                    "ATLAS" -> DanmakuEngineType.ATLAS
                                    else -> DanmakuEngineType.COMPOSE
                                },
                                maxOnScreen = settings.danmakuMaxOnScreen,
                            ),
                            onDanmakuConfigChange = { cfg ->
                                // 弹幕页设置写回全局设置(DanmakuConfig -> SettingsState 各字段)
                                appScope.launch { settingsRepo.update { it.copy(
                                    danmakuEnabled = cfg.enabled,
                                    danmakuOpacity = cfg.opacity,
                                    danmakuFontSize = cfg.fontSize,
                                    danmakuDisplayArea = cfg.displayArea,
                                    danmakuSpeedMultiplier = cfg.speedMultiplier,
                                    danmakuEngine = cfg.engineType.name,
                                    danmakuMaxOnScreen = cfg.maxOnScreen,
                                ) } }
                            },
                            danmakuShowMatchToast = settings.danmakuShowMatchToast,
                            onDanmakuMatchToastChange = { v ->
                                appScope.launch { settingsRepo.update { it.copy(danmakuShowMatchToast = v) } }
                            },
                            dandanplayAppId = settings.dandanplayAppId,
                            dandanplayAppSecret = settings.dandanplayAppSecret,
                            dandanplayUseProxy = settings.dandanplayUseProxy,
                            danmakuMatchConfig = DanmakuMatchConfig(
                                settings.tmdbIdQuickMatch,
                                settings.tmdbIdMatchPattern,
                                settings.danmakuHashFallback,
                            ),
                            onLoadManualMatch = { hash -> manualMatchCacheRepo.load(hash) },
                            onSaveManualMatch = { hash, entry -> manualMatchCacheRepo.save(hash, entry) },
                            siblingSubtitleLoader = subtitleLoader,
                            autoLoadSiblingSubtitle = settings.autoLoadSiblingSubtitle,
                            subtitleLanguagePreference = settings.subtitleLanguagePreference,
                            danmakuAutoManualMatch = settings.danmakuAutoManualMatch,
                            onBack = { finish() },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 取消本 Activity 的协程(设置收集 job), 防泄漏。AppLogger 是进程单例, 不在此关闭。
        if (::appScope.isInitialized) appScope.cancel()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CONTENT_URI = "content_uri"
        private const val EXTRA_MEDIA_KEY = "media_key"

        /**
         * @param title 媒体标题/文件名(本地 content:// 仍用它做展示和文件名匹配回落)
         * @param contentUri 原始 content://(本地视频算弹幕哈希用；引擎每次 load 时另开
         *   fdclose://，哈希仍通过 ContentResolver 读前 16MB)。非 content 传 null
         * @param mediaKey 播放记录稳定 key(source 层算的导航位置; 外部拉起传 null, PlayerScreen fallback)
         */
        fun newIntent(context: Context, url: String, title: String = "", contentUri: String? = null, mediaKey: String? = null): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                if (contentUri != null) {
                    putExtra(EXTRA_CONTENT_URI, contentUri)
                    // 外部 content URI 读权限随 Intent grant 给本 Activity(同应用内 FileProvider 自带权限, 加 flag 无害)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (mediaKey != null) putExtra(EXTRA_MEDIA_KEY, mediaKey)
            }

        private fun webDavConnectionId(mediaKey: String?): String? {
            if (mediaKey?.startsWith(WEBDAV_MEDIA_KEY_PREFIX) != true) return null
            return mediaKey.removePrefix(WEBDAV_MEDIA_KEY_PREFIX).substringBefore(':').takeIf { it.isNotEmpty() }
        }

        private const val WEBDAV_MEDIA_KEY_PREFIX = "webdav:"
    }
}
