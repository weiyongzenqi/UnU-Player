package io.github.weiyongzenqi.unuplayer.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.media.AnimePlaybackContext
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.player.HttpRedirectPolicy
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepositoryProvider
import io.github.weiyongzenqi.unuplayer.ui.SettingsWriteFailureDialog
import io.github.weiyongzenqi.unuplayer.domain.SettingsLoadState
import io.github.weiyongzenqi.unuplayer.domain.toDanmakuConfig
import io.github.weiyongzenqi.unuplayer.platform.AndroidStorage
import io.github.weiyongzenqi.unuplayer.platform.AndroidAppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import io.github.weiyongzenqi.unuplayer.ui.DisclaimerScreen
import io.github.weiyongzenqi.unuplayer.ui.SettingsLoadErrorScreen
import io.github.weiyongzenqi.unuplayer.ui.SettingsLoadingScreen
import io.github.weiyongzenqi.unuplayer.ui.player.PlayerScreen
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchConfig
import io.github.weiyongzenqi.unuplayer.danmaku.source.parseDanmakuMatchOrder
import io.github.weiyongzenqi.unuplayer.core.media.SiblingSubtitleLoader
import io.github.weiyongzenqi.unuplayer.danmaku.source.ManualMatchCacheRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepositoryProvider
import io.github.weiyongzenqi.unuplayer.webdav.setSharedHttpClientTlsInsecure
import io.github.weiyongzenqi.unuplayer.ui.theme.UnUTheme
import io.github.weiyongzenqi.unuplayer.mediaserver.AndroidMediaServerClientIdentityProvider
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerConnectionRepositoryProvider
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerConnectionService
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerPlaybackLocator
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerPlaybackRequest
import io.github.weiyongzenqi.unuplayer.mediaserver.historyMediaKey
import io.github.weiyongzenqi.unuplayer.playback.sync.PlaybackSyncTriggerProvider
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepositoryImpl
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideIdentity
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideJson
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideSettings
import io.github.weiyongzenqi.unuplayer.library.diffUpdate
import io.github.weiyongzenqi.unuplayer.library.withOverride
import io.github.weiyongzenqi.unuplayer.domain.bangumiEndpoints

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
 * 普通来源入参包含 URL、标题、contentUri、mediaKey 和来源类型。媒体服务器入参只包含连接 ID、
 * item ID、标题与非秘密续播位置；真实 URL、认证头、PlaySessionId 均在本 Activity 内从加密仓库重建。
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
        val directUrl = intent?.getStringExtra(EXTRA_URL)
        val mediaServerConnectionId = intent?.getStringExtra(EXTRA_MEDIA_SERVER_CONNECTION_ID)
            ?.takeIf { it.isNotBlank() }
        val mediaServerItemId = intent?.getStringExtra(EXTRA_MEDIA_SERVER_ITEM_ID)
            ?.takeIf { it.isNotBlank() }
        if (!isPlayerLaunchLocatorValid(directUrl, mediaServerConnectionId, mediaServerItemId)) {
            finish()
            return
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: ""
        val directContentUri = intent?.getStringExtra(EXTRA_CONTENT_URI)
        val directMediaKey = intent?.getStringExtra(EXTRA_MEDIA_KEY)
        val directSourceKind = parseSourceKind(intent?.getStringExtra(EXTRA_SOURCE_KIND), directMediaKey, directContentUri)
        // 三元组(刮削番剧跨库续播锚点): Intent getLongExtra 无法区分 0 与 absent, 用 hasExtra 守卫
        val directTmdbId = intent?.getLongExtra(EXTRA_TMDB_ID, 0L)?.takeIf { intent?.hasExtra(EXTRA_TMDB_ID) == true }
        val directSeasonNumber = intent?.getLongExtra(EXTRA_SEASON_NUMBER, 0L)?.takeIf { intent?.hasExtra(EXTRA_SEASON_NUMBER) == true }
        val directEpisodeNumber = intent?.getLongExtra(EXTRA_EPISODE_NUMBER, 0L)?.takeIf { intent?.hasExtra(EXTRA_EPISODE_NUMBER) == true }
        val directAnimeContext = intent?.getStringExtra(EXTRA_ANIME_SERIES_TITLE)?.let { seriesTitle ->
            AnimePlaybackContext(
                seriesTitle = seriesTitle.take(MAX_ANIME_CONTEXT_TEXT_LENGTH),
                episodeTitle = intent?.getStringExtra(EXTRA_ANIME_EPISODE_TITLE)
                    ?.take(MAX_ANIME_CONTEXT_TEXT_LENGTH),
                episodeDescription = intent?.getStringExtra(EXTRA_ANIME_EPISODE_DESCRIPTION)
                    ?.take(MAX_ANIME_CONTEXT_DESCRIPTION_LENGTH),
                bangumiSubjectId = intent?.getLongExtra(EXTRA_BANGUMI_SUBJECT_ID, 0L)
                    ?.takeIf { intent?.hasExtra(EXTRA_BANGUMI_SUBJECT_ID) == true },
                bangumiEpisodeOffset = intent?.getLongExtra(EXTRA_BANGUMI_EPISODE_OFFSET, 0L) ?: 0L,
            )
        }
        val mediaServerStartPositionMs = intent?.getLongExtra(EXTRA_MEDIA_SERVER_START_POSITION_MS, 0L)
            ?.coerceAtLeast(0L) ?: 0L

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
        // 节目专属弹幕设置覆盖仓库(进程级单例): 刮削番剧按 tmdb 键读写本部稀疏覆盖。
        val scrapedRepo = ScrapedLibraryRepositoryImpl.get(applicationContext)
        // 覆盖身份键: 有 tmdbId(刮削番剧)走本部覆盖主路径; null(ANCHOR/非刮削/外部拉起)完全维持写全局行为。
        val overrideKey = directTmdbId?.let { ShowOverrideIdentity.tmdb(it) }
        val mediaServerService = if (mediaServerConnectionId != null) {
            MediaServerConnectionService(
                repository = MediaServerConnectionRepositoryProvider.get(applicationContext),
                clientIdentityProvider = AndroidMediaServerClientIdentityProvider(storage, BuildConfig.VERSION_NAME),
                logger = appLogger,
            )
        } else null
        val credentialLoadState = MutableStateFlow<PlaybackCredentialLoadState>(PlaybackCredentialLoadState.Loading)
        var playbackLoadJob: Job? = null

        fun reloadPlaybackCredentials() {
            playbackLoadJob?.cancel()
            playbackLoadJob = appScope.launch {
                credentialLoadState.value = PlaybackCredentialLoadState.Loading
                try {
                    // init-only TLS 决策必须等设置真正可用；设置失败页恢复后本协程再继续。
                    settingsRepo.loadState.first { it == SettingsLoadState.Loaded }
                    setSharedHttpClientTlsInsecure(settingsRepo.state.value.allowTlsInsecure)
                    val playback = if (mediaServerConnectionId != null && mediaServerItemId != null) {
                        val prepared = withContext(Dispatchers.IO) {
                            requireNotNull(mediaServerService).openPlayback(
                                mediaServerConnectionId,
                                MediaServerPlaybackRequest(
                                    itemId = mediaServerItemId,
                                    startPositionMs = mediaServerStartPositionMs,
                                ),
                            )
                        }
                        val plan = prepared.plan
                        check(plan.connectionId == mediaServerConnectionId && plan.itemId == mediaServerItemId) {
                            "媒体服务器播放计划与启动定位不匹配"
                        }
                        PreparedPlayerPlayback(
                            url = plan.url,
                            headers = plan.headers,
                            httpRedirectPolicy = HttpRedirectPolicy.DENY,
                            contentUri = null,
                            mediaKey = plan.historyMediaKey,
                            sourceKind = plan.vendor.sourceKind,
                            initialPositionMs = plan.initialPositionMs,
                            mediaServerPlayback = prepared,
                            // 媒体服务器暂不进刮削表,movie 类自动排除,三元组 null
                            tmdbId = null,
                            seasonNumber = null,
                            episodeNumber = null,
                            animeContext = null,
                        )
                    } else {
                        val url = requireNotNull(directUrl)
                        val connectionId = webDavConnectionId(directMediaKey)
                        val webDavRequest = if (connectionId == null) {
                            null
                        } else {
                            withContext(Dispatchers.IO) {
                                webDavConnRepo.preparePlayback(connectionId, url)
                            }
                        }
                        PreparedPlayerPlayback(
                            url = webDavRequest?.url ?: url,
                            recordUrl = url,
                            headers = webDavRequest?.headers.orEmpty(),
                            httpRedirectPolicy = webDavRequest?.redirectPolicy ?: HttpRedirectPolicy.FOLLOW,
                            contentUri = directContentUri,
                            mediaKey = directMediaKey,
                            sourceKind = directSourceKind,
                            // 三元组从 Intent 读取(刮削路径)/null(外部 Intent)
                            tmdbId = directTmdbId,
                            seasonNumber = directSeasonNumber,
                            episodeNumber = directEpisodeNumber,
                            animeContext = directAnimeContext,
                        )
                    }
                    credentialLoadState.value = PlaybackCredentialLoadState.Ready(playback)
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
                        val playback = destination.playback
                        val isMediaServerPlayback = playback.mediaServerPlayback != null
                        // 节目专属弹幕覆盖内存态(本播放会话): 初值空=全跟随全局。每次播放开新 Activity, remember 无需媒体 key。
                        var currentOverride by remember { mutableStateOf(ShowOverrideSettings()) }
                        // 启动加载一次本部覆盖(有身份键才读); 读到即填 currentOverride, 触发重组刷新有效配置。
                        LaunchedEffect(overrideKey) {
                            val key = overrideKey ?: return@LaunchedEffect
                            scrapedRepo.getShowOverrideJson(key)?.let { raw ->
                                ShowOverrideJson.decode(raw)?.let { decoded ->
                                    // 仅空态才赋值: DB 异常慢时, 防止晚到的旧加载结果盖掉用户已调整的新值(窗口极小, 防御性)
                                    if (currentOverride.isEmpty()) currentOverride = decoded
                                }
                            }
                        }
                        // 全局弹幕配置(随设置重组重算); 实际传给播放页的是叠加本部覆盖后的有效配置。
                        val globalCfg = settings.toDanmakuConfig()
                        PlayerScreen(
                            playUrl = playback.url,
                            recordUrl = playback.recordUrl,
                            playTitle = title,
                            contentUri = playback.contentUri,
                            mediaKey = playback.mediaKey,
                            playSourceKind = playback.sourceKind,
                            // 三元组(刮削番剧跨库续播锚点)
                            tmdbId = playback.tmdbId,
                            seasonNumber = playback.seasonNumber,
                            episodeNumber = playback.episodeNumber,
                            animeContext = playback.animeContext,
                            bangumiEndpoints = settings.bangumiEndpoints(),
                            initialPositionMs = playback.initialPositionMs,
                            mediaServerPlayback = playback.mediaServerPlayback,
                            // 媒体服务器弹幕识别与其它来源一致: 哈希经无重定向安全变体拉取(computeHash),
                            // 文件名匹配用条目标题, 手动匹配缓存按哈希命中。
                            recognizeAnime = settings.recognizeAnime,
                            animePortraitPlaybackEnabled = settings.animePortraitPlaybackEnabled,
                            animePortraitCommentsHiddenByDefault = settings.animePortraitCommentsHiddenByDefault,
                            hdrMode = settings.hdrMode,
                            longPressSpeed = settings.longPressSpeed,
                            hwdec = settings.hwdec,
                            audioOutput = settings.audioOutput,
                            cacheSize = settings.cacheSize,
                            cacheSecs = settings.cacheSecs,
                            allowTlsInsecure = settings.allowTlsInsecure,
                            playHeaders = playback.headers,
                            httpRedirectPolicy = playback.httpRedirectPolicy,
                            appLogger = appLogger,
                            // 日志开关关闭时压低 mpv 消息级别: AAR native 把 mpv 消息直写 logcat
                            // (绕过 AppLogger 脱敏), v/info 级含完整播放 URL。开关开启属知情诊断。
                            logLevel = if (settings.enableLogs) settings.logLevel else "warn",
                            subtitleFont = settings.subtitleFont,
                            subtitleFontDir = settings.subtitleFontDir,
                            // 字幕样式/选轨偏好按本部有效值传: 覆盖非 null 用覆盖, 否则全局; currentOverride 重组随之更新。
                            subtitleScale = currentOverride.subtitleScale ?: settings.subtitleScale,
                            subtitleColor = settings.subtitleColor,
                            subtitleBorderSize = currentOverride.subtitleBorderSize ?: settings.subtitleBorderSize,
                            subtitleBold = currentOverride.subtitleBold ?: settings.subtitleBold,
                            subtitleStyleOverride = settings.subtitleStyleOverride,
                            defaultSubtitleTrackPattern = currentOverride.defaultSubtitleTrackPattern ?: settings.defaultSubtitleTrackPattern,
                            defaultAudioTrackPattern = currentOverride.defaultAudioTrackPattern ?: settings.defaultAudioTrackPattern,
                            speedPresets = settings.speedPresets,
                            predictiveBack = settings.predictiveBack,
                            danmakuConfig = globalCfg.withOverride(currentOverride),
                            onDanmakuConfigChange = { cfg ->
                                if (overrideKey != null) {
                                    // enabled 总开关跟随全局(设计: 开关全局/样式本部): 变动即写全局, 不进覆盖。
                                    if (cfg.enabled != globalCfg.enabled) {
                                        appScope.launch { settingsRepo.update { it.copy(danmakuEnabled = cfg.enabled) } }
                                    }
                                    // 样式字段差分写入本部覆盖(自动创建), 不动全局。old=变动前有效配置; 无样式变动不写。
                                    val old = globalCfg.withOverride(currentOverride)
                                    val updated = currentOverride.diffUpdate(old, cfg)
                                    if (updated != currentOverride) {
                                        currentOverride = updated
                                        appScope.launch {
                                            scrapedRepo.upsertShowOverride(
                                                overrideKey,
                                                ShowOverrideJson.encode(updated),
                                                System.currentTimeMillis(),
                                            )
                                        }
                                    }
                                } else {
                                    // 无节目身份: 原样写全局设置(DanmakuConfig -> SettingsState 各字段)。
                                    appScope.launch { settingsRepo.update { it.copy(
                                        danmakuEnabled = cfg.enabled,
                                        danmakuOpacity = cfg.opacity,
                                        danmakuFontSize = cfg.fontSize,
                                        danmakuDisplayArea = cfg.displayArea,
                                        danmakuSpeedMultiplier = cfg.speedMultiplier,
                                        danmakuStrokeWidth = cfg.strokeWidth,
                                        danmakuTimeOffsetSec = cfg.timeOffsetSec,
                                        danmakuEngine = cfg.engineType.name,
                                        danmakuMaxOnScreen = cfg.maxOnScreen,
                                    ) } }
                                }
                            },
                            danmakuShowMatchToast = settings.danmakuShowMatchToast,
                            onDanmakuMatchToastChange = { v ->
                                appScope.launch { settingsRepo.update { it.copy(danmakuShowMatchToast = v) } }
                            },
                            perfMonitorOverlay = settings.perfMonitorOverlay,
                            dandanplayAppId = settings.dandanplayAppId,
                            dandanplayAppSecret = settings.dandanplayAppSecret,
                            dandanplayUseProxy = settings.dandanplayUseProxy,
                            danmakuMatchConfig = DanmakuMatchConfig(
                                tmdbIdMatchPattern = settings.tmdbIdMatchPattern,
                                matchOrder = parseDanmakuMatchOrder(
                                    currentOverride.danmakuMatchPriority ?: settings.danmakuMatchPriority,
                                ),
                            ),
                            onLoadManualMatch = { hash -> manualMatchCacheRepo.load(hash) },
                            onSaveManualMatch = { hash, entry -> manualMatchCacheRepo.save(hash, entry) },
                            siblingSubtitleLoader = subtitleLoader.takeUnless { isMediaServerPlayback },
                            autoLoadSiblingSubtitle = settings.autoLoadSiblingSubtitle,
                            subtitleLanguagePreference = settings.subtitleLanguagePreference,
                            danmakuAutoManualMatch = settings.danmakuAutoManualMatch,
                            onReloadPlayback = ::reloadPlaybackCredentials,
                            onBack = { finish() },
                        )
                    }
                }
                SettingsWriteFailureDialog(settingsRepo)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // P2: 退出播放后防抖推送(进程级 scope, 不随 Activity 销毁)。best-effort, 失败仅 WARN。
        runCatching {
            val trigger = PlaybackSyncTriggerProvider.get(applicationContext, AndroidAppLogger.get(applicationContext))
            trigger.scheduleDebouncedPush(SettingsRepositoryProvider.get(applicationContext).state.value)
        }
        // 取消本 Activity 的协程(设置收集 job), 防泄漏。AppLogger 是进程单例, 不在此关闭。
        if (::appScope.isInitialized) appScope.cancel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val directUrl = intent.getStringExtra(EXTRA_URL)
        val connectionId = intent.getStringExtra(EXTRA_MEDIA_SERVER_CONNECTION_ID)?.takeIf { it.isNotBlank() }
        val itemId = intent.getStringExtra(EXTRA_MEDIA_SERVER_ITEM_ID)?.takeIf { it.isNotBlank() }
        if (!isPlayerLaunchLocatorValid(directUrl, connectionId, itemId)) return

        // CLEAR_TOP + SINGLE_TOP 把所有外部/应用内请求收敛到当前播放器实例。
        // 播放依赖含 init-only 选项和来源专属服务，完整重建 Activity 可保证旧引擎先走统一释放链，
        // 新 onCreate 再只读取最新 Intent，不把两份媒体请求并存在同一 Compose 树中。
        setIntent(intent)
        recreate()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CONTENT_URI = "content_uri"
        private const val EXTRA_MEDIA_KEY = "media_key"
        private const val EXTRA_SOURCE_KIND = "source_kind"
        private const val EXTRA_MEDIA_SERVER_CONNECTION_ID = "media_server_connection_id"
        private const val EXTRA_MEDIA_SERVER_ITEM_ID = "media_server_item_id"
        private const val EXTRA_MEDIA_SERVER_START_POSITION_MS = "media_server_start_position_ms"
        private const val EXTRA_TMDB_ID = "tmdb_id"
        private const val EXTRA_SEASON_NUMBER = "season_number"
        private const val EXTRA_EPISODE_NUMBER = "episode_number"
        private const val EXTRA_ANIME_SERIES_TITLE = "anime_series_title"
        private const val EXTRA_ANIME_EPISODE_TITLE = "anime_episode_title"
        private const val EXTRA_ANIME_EPISODE_DESCRIPTION = "anime_episode_description"
        private const val EXTRA_BANGUMI_SUBJECT_ID = "bangumi_subject_id"
        private const val EXTRA_BANGUMI_EPISODE_OFFSET = "bangumi_episode_offset"

        /**
         * @param title 媒体标题/文件名(本地 content:// 仍用它做展示和文件名匹配回落)
         * @param contentUri 原始 content://(本地视频算弹幕哈希用；引擎每次 load 时另开
         *   fdclose://，哈希仍通过 ContentResolver 读前 16MB)。非 content 传 null
         * @param mediaKey 播放记录稳定 key(source 层算的导航位置; 外部拉起传 null, PlayerScreen fallback)
         * @param tmdbId TMDB ID(刮削番剧跨库续播锚点)。非刮削路径为 null
         * @param seasonNumber 季号(刮削番剧跨库续播锚点)。非刮削路径为 null
         * @param episodeNumber 集号(刮削番剧跨库续播锚点)。非刮削路径为 null
         */
        fun newIntent(
            context: Context,
            url: String,
            title: String = "",
            contentUri: String? = null,
            mediaKey: String? = null,
            sourceKind: MediaSourceKind? = null,
            tmdbId: Long? = null,
            seasonNumber: Long? = null,
            episodeNumber: Long? = null,
            animeContext: AnimePlaybackContext? = null,
        ): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                if (contentUri != null) {
                    putExtra(EXTRA_CONTENT_URI, contentUri)
                    // 外部 content URI 读权限随 Intent grant 给本 Activity(同应用内 FileProvider 自带权限, 加 flag 无害)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (mediaKey != null) putExtra(EXTRA_MEDIA_KEY, mediaKey)
                if (sourceKind != null) putExtra(EXTRA_SOURCE_KIND, sourceKind.name)
                if (tmdbId != null) putExtra(EXTRA_TMDB_ID, tmdbId)
                if (seasonNumber != null) putExtra(EXTRA_SEASON_NUMBER, seasonNumber)
                if (episodeNumber != null) putExtra(EXTRA_EPISODE_NUMBER, episodeNumber)
                animeContext?.let { context ->
                    putExtra(EXTRA_ANIME_SERIES_TITLE, context.seriesTitle.take(MAX_ANIME_CONTEXT_TEXT_LENGTH))
                    context.episodeTitle?.let {
                        putExtra(EXTRA_ANIME_EPISODE_TITLE, it.take(MAX_ANIME_CONTEXT_TEXT_LENGTH))
                    }
                    context.episodeDescription?.let {
                        putExtra(
                            EXTRA_ANIME_EPISODE_DESCRIPTION,
                            it.take(MAX_ANIME_CONTEXT_DESCRIPTION_LENGTH),
                        )
                    }
                    context.bangumiSubjectId?.let { putExtra(EXTRA_BANGUMI_SUBJECT_ID, it) }
                    putExtra(EXTRA_BANGUMI_EPISODE_OFFSET, context.bangumiEpisodeOffset)
                }
            }

        private const val MAX_ANIME_CONTEXT_TEXT_LENGTH = 1024
        private const val MAX_ANIME_CONTEXT_DESCRIPTION_LENGTH = 8192

        /** 只写入非秘密定位字段；不接受 URL/header/mediaKey/PlaySessionId。 */
        fun newMediaServerIntent(context: Context, locator: MediaServerPlaybackLocator): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_TITLE, locator.title)
                putExtra(EXTRA_MEDIA_SERVER_CONNECTION_ID, locator.connectionId)
                putExtra(EXTRA_MEDIA_SERVER_ITEM_ID, locator.itemId)
                putExtra(EXTRA_MEDIA_SERVER_START_POSITION_MS, locator.startPositionMs)
            }

        private fun webDavConnectionId(mediaKey: String?): String? {
            if (mediaKey?.startsWith(WEBDAV_MEDIA_KEY_PREFIX) != true) return null
            return mediaKey.removePrefix(WEBDAV_MEDIA_KEY_PREFIX).substringBefore(':').takeIf { it.isNotEmpty() }
        }

        private fun parseSourceKind(
            rawSourceKind: String?,
            mediaKey: String?,
            contentUri: String?,
        ): MediaSourceKind = rawSourceKind
            ?.let { raw -> runCatching { MediaSourceKind.valueOf(raw) }.getOrNull() }
            ?: when {
                mediaKey?.startsWith(WEBDAV_MEDIA_KEY_PREFIX) == true -> MediaSourceKind.WEBDAV
                contentUri != null -> MediaSourceKind.LOCAL
                else -> MediaSourceKind.EXTERNAL
            }

        private const val WEBDAV_MEDIA_KEY_PREFIX = "webdav:"
    }
}

internal fun isPlayerLaunchLocatorValid(
    directUrl: String?,
    mediaServerConnectionId: String?,
    mediaServerItemId: String?,
): Boolean {
    val hasAnyMediaServerExtra = mediaServerConnectionId != null || mediaServerItemId != null
    return if (hasAnyMediaServerExtra) {
        mediaServerConnectionId != null && mediaServerItemId != null
    } else {
        directUrl != null
    }
}
