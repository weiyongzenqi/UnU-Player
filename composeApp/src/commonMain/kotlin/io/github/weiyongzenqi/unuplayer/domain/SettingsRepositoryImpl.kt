package io.github.weiyongzenqi.unuplayer.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.weiyongzenqi.unuplayer.core.platform.Storage
import io.github.weiyongzenqi.unuplayer.core.platform.StorageBatch
import io.github.weiyongzenqi.unuplayer.core.platform.StorageSnapshot
import io.github.weiyongzenqi.unuplayer.core.player.HdrMode
import io.github.weiyongzenqi.unuplayer.core.security.CredentialProtectionException
import io.github.weiyongzenqi.unuplayer.core.security.SecretStorage
import io.github.weiyongzenqi.unuplayer.core.security.EncryptedSecretStorage
import io.github.weiyongzenqi.unuplayer.danmaku.model.toSupportedDanmakuEngineType
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchMethod
import io.github.weiyongzenqi.unuplayer.library.EpisodeThumbPositionMode
import io.github.weiyongzenqi.unuplayer.library.PosterWallSort
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSourcePreset

/**
 * SettingsRepository 的通用实现: 用 Storage 持久化, StateFlow 响应式。
 *
 * 放 commonMain(只用 Storage 接口 + 协程, 不碰平台 API), 各平台共享。
 * 异步初始化: 先用默认值, init 时从 Storage 异步读取后更新 _state。
 *
 * 竞态保护: update() 会 await loadComplete, 确保加载完成后再改, 避免
 * "启动瞬间改设置 → loadSettings() 用旧快照覆盖刚改的值"(P1-14 异步化引入)。
 */
class SettingsRepositoryImpl(
    private val storage: Storage,
    private val scope: CoroutineScope,
    private val secretStorage: SecretStorage,
) : SettingsRepository {

    private val _state = MutableStateFlow(SettingsState())
    override val state = _state.asStateFlow()
    private val _loadState = MutableStateFlow<SettingsLoadState>(SettingsLoadState.Loading)
    override val loadState = _loadState.asStateFlow()
    private val _writeFailure = MutableStateFlow<SettingsWriteFailure?>(null)
    override val writeFailure = _writeFailure.asStateFlow()

    /** 加载完成信号。update() await 它, 保证不在 load 覆盖前写入。 */
    private val loadComplete = CompletableDeferred<Unit>()
    private val updateMutex = Mutex()
    private val loadMutex = Mutex()
    private var appSecretLoadFailed = false
    private var pendingSettings: SettingsState? = null

    /**
     * 默认值翻转迁移未落库标记(见 [migrateFlippedDefaults]): 加载时置位, 首次设置保存的
     * 同一事务写迁移键后清除。读写均在 updateMutex 保护内(load 双锁 / save 调用方持锁)。
     */
    private var flipDefaultsMigrationPending = false

    init {
        // 异步从 Storage 加载, 不阻塞主线程(P1-14 修复 runBlocking)
        scope.launch {
            try {
                loadFromStorage()
            } finally {
                loadComplete.complete(Unit)
            }
        }
    }

    override suspend fun update(transform: (SettingsState) -> SettingsState) {
        // 等加载完成, 避免与 init 的 loadSettings() 赋值竞态(覆盖丢失修改)
        loadComplete.await()
        // 加载失败不落盘(P3⑫): 此时 _state 是默认值, 直接写会用 ~80 键默认值覆盖用户设置。
        // 静默返回(本类无 logger 注入, 禁 println); 由 UI 引导 retryLoad()/useDefaultsAfterLoadFailure()
        // 恢复可写态后再写。retryLoad/useDefaults 不经 update(), 免责声明仅在 Loaded 态经 update(), 均不受挡。
        updateMutex.withLock {
            if (_loadState.value != SettingsLoadState.Loaded) return@withLock
            val old = _state.value
            val transformed = transform(old)
            val new = transformed.copy(
                bangumiDataSource = transformed.bangumiDataSource.normalizedForUse(),
            )
            try {
                saveSettings(old, new)
                _state.value = new
                pendingSettings = null
                _writeFailure.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                pendingSettings = new
                publishWriteFailure(error)
            }
        }
    }

    override suspend fun retryLastUpdate() {
        loadComplete.await()
        updateMutex.withLock {
            if (_loadState.value != SettingsLoadState.Loaded) return@withLock
            val target = pendingSettings ?: return@withLock
            val old = _state.value
            try {
                saveSettings(old, target)
                _state.value = target
                pendingSettings = null
                _writeFailure.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                publishWriteFailure(error)
            }
        }
    }

    override suspend fun dismissWriteFailure() {
        updateMutex.withLock {
            pendingSettings = null
            _writeFailure.value = null
        }
    }

    private fun publishWriteFailure(error: Throwable) {
        val errorType = error::class.simpleName ?: "未知错误"
        _writeFailure.value = SettingsWriteFailure(
            message = "设置保存失败（$errorType），原设置已保留",
            retryAvailable = pendingSettings != null,
        )
    }

    override suspend fun awaitLoaded() = loadComplete.await()

    override suspend fun retryLoad() {
        loadFromStorage()
    }

    override suspend fun useDefaultsAfterLoadFailure() {
        updateMutex.withLock {
            if (_loadState.value !is SettingsLoadState.Failed) return@withLock
            if (appSecretLoadFailed) {
                try {
                    secretStorage.remove(DANDANPLAY_APP_SECRET_KEY)
                    appSecretLoadFailed = false
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    val errorType = error::class.simpleName ?: "未知错误"
                    _loadState.value = SettingsLoadState.Failed("安全凭据清理失败（$errorType）")
                    return@withLock
                }
            }
            _state.value = SettingsState()
            _loadState.value = SettingsLoadState.Loaded
        }
    }

    private suspend fun loadFromStorage() {
        loadMutex.withLock {
            updateMutex.withLock {
                val previousLoadState = _loadState.value
                val previousAppSecretLoadFailed = appSecretLoadFailed
                _loadState.value = SettingsLoadState.Loading
                appSecretLoadFailed = false
                try {
                    val loaded = loadSettings()
                    _state.value = loaded
                    _loadState.value = SettingsLoadState.Loaded
                } catch (error: CancellationException) {
                    appSecretLoadFailed = previousAppSecretLoadFailed
                    _loadState.value = previousLoadState
                    throw error
                } catch (error: Throwable) {
                    _state.value = SettingsState()
                    val errorType = error::class.simpleName ?: "未知错误"
                    _loadState.value = SettingsLoadState.Failed("设置读取失败（$errorType）")
                }
            }
        }
    }

    private suspend fun loadSettings(): SettingsState {
        val snapshot = storage.readSnapshot()
        val settings = loadMainSettings(snapshot).copy(
            bangumiDataSource = loadBangumiSourceSettings(snapshot),
        )
        clearLegacyTmdbCredentials(snapshot)
        return migrateFlippedDefaults(settings, snapshot)
    }

    /**
     * 默认值翻转迁移(v0.2.1, 2026-08-26 用户决策): 详情页季度海报默认开启, 弹幕同屏上限默认
     * 自动(0=5000 硬上限)。设置保存是全量写入, 老库早已把旧默认固化成普通存储值, 仅改读取
     * default 只对新装机生效。加载时先在内存翻转"仍等于旧默认"的值; 迁移标记随首次设置保存
     * 的同一事务落库(writeSettingsToBatch 尾部)——不为迁移单独 edit(保持"一次更新只请求一次
     * 批量事务"语义), 也保证用户此后显式改回的值不再被二次翻转(标记已写入即不重跑)。
     */
    private suspend fun migrateFlippedDefaults(settings: SettingsState, snapshot: StorageSnapshot?): SettingsState {
        val alreadyMigrated = if (snapshot != null) {
            snapshot.getBoolean(FLIPPED_DEFAULTS_MIGRATION_KEY, false)
        } else {
            storage.getBoolean(FLIPPED_DEFAULTS_MIGRATION_KEY, false)
        }
        if (alreadyMigrated) return settings
        flipDefaultsMigrationPending = true
        return settings.copy(
            danmakuMaxOnScreen = if (settings.danmakuMaxOnScreen == 150) 0 else settings.danmakuMaxOnScreen,
            posterWallDetailUseSeasonPoster = true,
        )
    }

    /** 弹幕/弹弹/在线刮削凭证设置分块(loadMainSettings 已接近 JVM 64 KiB 单方法上限, 拆出可再容纳新设置)。 */
    private data class DanmakuSettingsPart(
        val appId: String,
        val appSecret: String,
        val useProxy: Boolean,
        val hashFallback: Boolean,
        val enabled: Boolean,
        val engine: String,
        val showMatchToast: Boolean,
        val autoManualMatch: Boolean,
        val opacity: Float,
        val fontSize: Float,
        val displayArea: Float,
        val speedMultiplier: Float,
        val maxOnScreen: Int,
        val strokeWidth: Float,
        val timeOffsetSec: Double,
    )

    private suspend fun readDanmakuSettingsPart(snapshot: StorageSnapshot?): DanmakuSettingsPart {
        suspend fun readString(key: String, default: String? = null): String? =
            if (snapshot != null) snapshot.getString(key, default) else storage.getString(key, default)
        suspend fun readBoolean(key: String, default: Boolean = false): Boolean =
            if (snapshot != null) snapshot.getBoolean(key, default) else storage.getBoolean(key, default)
        return DanmakuSettingsPart(
            appId = readString("dandanplayAppId", "") ?: "",
            appSecret = loadDandanplayAppSecret(snapshot),
            useProxy = readBoolean("dandanplayUseProxy", true),
            hashFallback = readBoolean("danmakuHashFallback", true),
            enabled = readBoolean("danmakuEnabled", true),
            engine = readString("danmakuEngine", "ATLAS").toSupportedDanmakuEngineType().name,
            showMatchToast = readBoolean("danmakuShowMatchToast", false),
            autoManualMatch = readBoolean("danmakuAutoManualMatch", true),
            opacity = readString("danmakuOpacity", "1.0")?.toFloatOrNull() ?: 1.0f,
            fontSize = readString("danmakuFontSize", "0")?.toFloatOrNull() ?: 0f,
            displayArea = readString("danmakuDisplayArea", "1.0")?.toFloatOrNull() ?: 1.0f,
            speedMultiplier = readString("danmakuSpeedMultiplier", "1.0")?.toFloatOrNull() ?: 1.0f,
            maxOnScreen = readString("danmakuMaxOnScreen", "0")?.toIntOrNull() ?: 0,
            strokeWidth = readString("danmakuStrokeWidth", "2.0")?.toFloatOrNull() ?: 2.0f,
            timeOffsetSec = readString("danmakuTimeOffsetSec", "0.0")?.toDoubleOrNull() ?: 0.0,
        )
    }

    private data class UiSettingsPart(
        val predictiveBack: Boolean,
        val animePortraitPlaybackEnabled: Boolean,
        val animePortraitCommentsHiddenByDefault: Boolean,
        val playbackEndBehavior: PlaybackEndBehavior,
        val dynamicColor: Boolean,
        val darkTheme: Boolean,
        val desktopLayout: DesktopLayout,
        val startupHome: StartupHome,
        val desktopRunInBackground: Boolean,
        val desktopClosePrompt: Boolean,
        val desktopGpuRendering: Boolean,
        val enableLogs: Boolean,
        val logLevel: String,
        val appLogLevel: String,
        val logDirUri: String?,
        val allowTlsInsecure: Boolean,
        val scheduleSearchHistory: List<String>,
        val scheduleHideTheatrical: Boolean,
    )

    private suspend fun readUiSettingsPart(snapshot: StorageSnapshot?): UiSettingsPart {
        suspend fun readString(key: String, default: String? = null): String? =
            if (snapshot != null) snapshot.getString(key, default) else storage.getString(key, default)
        suspend fun readBoolean(key: String, default: Boolean = false): Boolean =
            if (snapshot != null) snapshot.getBoolean(key, default) else storage.getBoolean(key, default)
        val playbackEndBehavior = readString("playbackEndBehavior", PlaybackEndBehavior.AUTO_NEXT.name)
            ?.let { runCatching { PlaybackEndBehavior.valueOf(it) }.getOrNull() }
            ?: PlaybackEndBehavior.AUTO_NEXT
        return UiSettingsPart(
            predictiveBack = readBoolean("predictiveBack", true),
            animePortraitPlaybackEnabled = readBoolean("animePortraitPlaybackEnabled", true),
            animePortraitCommentsHiddenByDefault = readBoolean("animePortraitCommentsHiddenByDefault", false),
            playbackEndBehavior = playbackEndBehavior,
            dynamicColor = readBoolean("dynamicColor", true),
            darkTheme = readBoolean("darkTheme", true),
            desktopLayout = readString("desktopLayout", DesktopLayout.SIDEBAR.name)
                ?.let { runCatching { DesktopLayout.valueOf(it) }.getOrNull() }
                ?: DesktopLayout.SIDEBAR,
            startupHome = readString("startupHome", StartupHome.MEDIA_SOURCE.name)
                ?.let { runCatching { StartupHome.valueOf(it) }.getOrNull() }
                ?: StartupHome.MEDIA_SOURCE,
            desktopRunInBackground = readBoolean("desktopRunInBackground", false),
            desktopClosePrompt = readBoolean("desktopClosePrompt", true),
            desktopGpuRendering = readBoolean(DESKTOP_GPU_RENDERING_KEY, false),
            enableLogs = readBoolean("enableLogs", false),
            logLevel = readString("logLevel", "info") ?: "info",
            appLogLevel = readString("appLogLevel", "info") ?: "info",
            logDirUri = readString("logDirUri", null),
            allowTlsInsecure = readBoolean("allowTlsInsecure", false),
            scheduleSearchHistory = decodeScheduleSearchHistory(readString("scheduleSearchHistory", null)),
            scheduleHideTheatrical = readBoolean("scheduleHideTheatrical", false),
        )
    }

    /**
     * 弹幕匹配方式优先级迁移(2026-08-14): 新键 danmakuMatchPriority 存过(含空串 = 用户显式
     * 全部禁用)则以存储值为准, 不得回落; 从未存过才按旧开关状态构造
     * (旧 tmdbIdQuickMatch=false → 剔除 TMDB_PATH; 旧 danmakuHashFallback=false → 剔除 HASH)。
     */
    private fun migrateDanmakuMatchPriority(
        stored: String?,
        legacyTmdbIdQuickMatch: Boolean,
        legacyHashFallback: Boolean,
    ): List<String> {
        if (stored != null) {
            return stored.split(',').map { it.trim() }.filter { it.isNotBlank() }
        }
        return DEFAULT_DANMAKU_MATCH_PRIORITY.filter { name ->
            when (name) {
                DanmakuMatchMethod.TMDB_PATH.name -> legacyTmdbIdQuickMatch
                DanmakuMatchMethod.HASH.name -> legacyHashFallback
                else -> true
            }
        }
    }

    /** 保持主设置读取状态机与独立 Bangumi 数据源设置解耦，避免 JVM 单方法字节码超过 64 KiB。 */
    private suspend fun loadMainSettings(snapshot: StorageSnapshot?): SettingsState {
        suspend fun readString(key: String, default: String? = null): String? =
            if (snapshot != null) snapshot.getString(key, default) else storage.getString(key, default)
        suspend fun readBoolean(key: String, default: Boolean = false): Boolean =
            if (snapshot != null) snapshot.getBoolean(key, default) else storage.getBoolean(key, default)
        suspend fun readInt(key: String, default: Int = 0): Int =
            if (snapshot != null) snapshot.getInt(key, default) else storage.getInt(key, default)

        // 弹幕/弹弹字段分块读取, 防本方法字节码超 64 KiB(见 readDanmakuSettingsPart)
        val danmaku = readDanmakuSettingsPart(snapshot)
        val ui = readUiSettingsPart(snapshot)

        return SettingsState(
            recognizeAnime = readBoolean("recognizeAnime", true),
            hwdec = readString("hwdec", defaultHwdec()) ?: defaultHwdec(),
            audioOutput = readString("audioOutput", defaultAudioOutput()) ?: defaultAudioOutput(),
            hdrMode = readString("hdrMode", "AUTO").let { stored ->
                runCatching { HdrMode.valueOf(stored ?: "AUTO") }.getOrDefault(HdrMode.AUTO)
            },
            cacheSize = readInt("cacheSize", 32),
            cacheSecs = readInt("cacheSecs", 20),
            perfMonitorOverlay = readBoolean("perfMonitorOverlay", false),
            longPressSpeed = readString("longPressSpeed", "2")?.toFloatOrNull() ?: 2f,
            subtitleFont = readString("subtitleFont", "") ?: "",
            subtitleFontDir = readString("subtitleFontDir", null),
            subtitleScale = readString("subtitleScale", "1.0")?.toFloatOrNull() ?: 1.0f,
            subtitleColor = readString("subtitleColor", "#FFFFFFFF") ?: "#FFFFFFFF",
            subtitleBorderSize = readString("subtitleBorderSize", "2.0")?.toFloatOrNull() ?: 2.0f,
            subtitleBold = readBoolean("subtitleBold", false),
            subtitleStyleOverride = readString("subtitleStyleOverride", "force") ?: "force",
            autoLoadSiblingSubtitle = readBoolean("autoLoadSiblingSubtitle", true),
            subtitleLanguagePreference = readString("subtitleLanguagePreference", "sc") ?: "sc",
            defaultSubtitleTrackPattern = readString("defaultSubtitleTrackPattern", DEFAULT_SUBTITLE_TRACK_PATTERN)
                ?: DEFAULT_SUBTITLE_TRACK_PATTERN,
            defaultAudioTrackPattern = readString("defaultAudioTrackPattern", DEFAULT_AUDIO_TRACK_PATTERN)
                ?: DEFAULT_AUDIO_TRACK_PATTERN,
            predictiveBack = ui.predictiveBack,
            animePortraitPlaybackEnabled = ui.animePortraitPlaybackEnabled,
            animePortraitCommentsHiddenByDefault = ui.animePortraitCommentsHiddenByDefault,
            playbackEndBehavior = ui.playbackEndBehavior,
            dynamicColor = ui.dynamicColor,
            darkTheme = ui.darkTheme,
            desktopLayout = ui.desktopLayout,
            startupHome = ui.startupHome,
            desktopRunInBackground = ui.desktopRunInBackground,
            desktopClosePrompt = ui.desktopClosePrompt,
            desktopGpuRendering = ui.desktopGpuRendering,
            enableLogs = ui.enableLogs,
            logLevel = ui.logLevel,
            appLogLevel = ui.appLogLevel,
            logDirUri = ui.logDirUri,
            allowTlsInsecure = ui.allowTlsInsecure,
            webdavDefaultConnectionId = readString("webdavDefaultConnectionId", null),
            webdavDefaultDirectory = readString("webdavDefaultDirectory", "/") ?: "/",
            playbackSyncEnabled = readBoolean("playbackSyncEnabled", false),
            playbackSyncConnectionId = readString("playbackSyncConnectionId", null),
            playbackAutoSync = readBoolean("playbackAutoSync", true),
            webdavSortPreset = WebDavSortPreset.fromValue(readString("webdavSortPreset", "default")),
            webdavShowBreadcrumb = readBoolean("webdavShowBreadcrumb", true),
            webdavAutoEnterSeasonFolder = readBoolean("webdavAutoEnterSeasonFolder", false),
            webdavSeasonFolderPattern = readString("webdavSeasonFolderPattern", "Season*") ?: "Season*",
            scrapeTriggerMode = readString("scrapeTriggerMode", ScrapeTriggerMode.LAZY.name)
                ?.let { runCatching { ScrapeTriggerMode.valueOf(it) }.getOrNull()?.name }
                ?: ScrapeTriggerMode.LAZY.name,
            scrapeConcurrency = (readString("scrapeConcurrency", "1")?.toIntOrNull() ?: 1).coerceIn(1, 4),
            scrapeUniqueAutoApply = readBoolean("scrapeUniqueAutoApply", true),
            bgmIdQuickMatch = readBoolean("bgmIdQuickMatch", true),
            bgmIdMatchPattern = readString("bgmIdMatchPattern", "bgm(id)?[=-](\\d+)") ?: "bgm(id)?[=-](\\d+)",
            danmakuMatchPriority = migrateDanmakuMatchPriority(
                stored = readString("danmakuMatchPriority", null),
                legacyTmdbIdQuickMatch = readBoolean("tmdbIdQuickMatch", true),
                legacyHashFallback = danmaku.hashFallback,
            ),
            tmdbIdMatchPattern = readString("tmdbIdMatchPattern", "tmdb(id)?[=-](\\d+)") ?: "tmdb(id)?[=-](\\d+)",
            episodeOffsetEnabled = readBoolean("episodeOffsetEnabled", false),
            dandanplayAppId = danmaku.appId,
            dandanplayAppSecret = danmaku.appSecret,
            dandanplayUseProxy = danmaku.useProxy,
            aniRssBaseUrl = readString("aniRss.baseUrl", "") ?: "",
            aniRssCleartextConfirmed = readBoolean("aniRss.cleartextConfirmed", false),
            scheduleSearchHistory = ui.scheduleSearchHistory,
            scheduleHideTheatrical = ui.scheduleHideTheatrical,
            danmakuEnabled = danmaku.enabled,
            danmakuEngine = danmaku.engine,
            danmakuShowMatchToast = danmaku.showMatchToast,
            danmakuAutoManualMatch = danmaku.autoManualMatch,
            danmakuOpacity = danmaku.opacity,
            danmakuFontSize = danmaku.fontSize,
            danmakuDisplayArea = danmaku.displayArea,
            danmakuSpeedMultiplier = danmaku.speedMultiplier,
            danmakuMaxOnScreen = danmaku.maxOnScreen,
            danmakuStrokeWidth = danmaku.strokeWidth,
            danmakuTimeOffsetSec = danmaku.timeOffsetSec,
            webdavEnableSearch = readBoolean("webdavEnableSearch", true),
            webdavSearchScope = WebDavSearchScope.fromValue(readString("webdavSearchScope", "current_with_depth")),
            webdavSearchDepthLimit = readInt("webdavSearchDepthLimit", 3),
            webdavSearchTargets = WebDavSearchTarget.fromValues(
                readString("webdavSearchTargets", "folder,video")?.split(",").orEmpty()
            ),
            webdavSearchTimeout = WebDavSearchTimeout.fromSeconds(readInt("webdavSearchTimeout", 30)),
            webdavSearchRequestInterval = readInt("webdavSearchRequestInterval", 100),
            webdavSearchMaxResults = readInt("webdavSearchMaxResults", 500),
            posterWallEnabled = readBoolean("posterWallEnabled", true),
            posterWallDefaultLibraryId = readString("posterWallDefaultLibraryId", null)?.toLongOrNull(),
            posterWallScanRequestIntervalMs = readInt("posterWallScanRequestIntervalMs", 100),
            posterWallScanConcurrency = readInt("posterWallScanConcurrency", 2),
            posterWallScanDepth = readInt("posterWallScanDepth", 6),
            posterWallScanTimeoutSeconds = readInt("posterWallScanTimeoutSeconds", 600),
            posterWallPosterColumnsPortrait = readInt("posterWallPosterColumnsPortrait", 3),
            posterWallPosterColumnsLandscape = readInt("posterWallPosterColumnsLandscape", 5),
            posterWallGroupByQuarter = readBoolean("posterWallGroupByQuarter", true),
            posterWallSortBy = readString("posterWallSortBy", "QUARTER").let { stored ->
                runCatching { PosterWallSort.valueOf(stored ?: "QUARTER") }
                    .getOrDefault(PosterWallSort.QUARTER)
            },
            posterWallShowEpisodeThumb = readBoolean("posterWallShowEpisodeThumb", true),
            posterWallAutoEpisodeThumb = readBoolean("posterWallAutoEpisodeThumb", false),
            posterWallEpisodeThumbPositionMode = readString("posterWallEpisodeThumbPositionMode", "PERCENT").let { stored ->
                runCatching { EpisodeThumbPositionMode.valueOf(stored ?: "PERCENT") }
                    .getOrDefault(EpisodeThumbPositionMode.PERCENT)
            },
            posterWallEpisodeThumbAtPercent = readInt("posterWallEpisodeThumbAtPercent", 10),
            posterWallEpisodeThumbAtSeconds = readInt("posterWallEpisodeThumbAtSeconds", 30),
            posterWallDetailUseSeasonPoster = readBoolean("posterWallDetailUseSeasonPoster", true),
            posterWallBadgeShowSeason1 = readBoolean("posterWallBadgeShowSeason1", true),
            posterWallImageCacheSizeMb = readInt("posterWallImageCacheSizeMb", 200),
            posterWallWalAutoCheckpoint = readBoolean("posterWallWalAutoCheckpoint", true),
            disclaimerAccepted = readBoolean("disclaimerAccepted", false),
        )
    }

    /**
     * AppSecret 从普通设置快照剥离。旧版本明文只在安全存储写入成功后删除，避免迁移失败丢凭据。
     */
    private suspend fun loadDandanplayAppSecret(snapshot: StorageSnapshot?): String {
        val protectedSecret = try {
            secretStorage.getString(DANDANPLAY_APP_SECRET_KEY)
        } catch (error: CredentialProtectionException) {
            appSecretLoadFailed = true
            throw error
        }
        val legacySecret = if (snapshot != null) {
            snapshot.getString(LEGACY_DANDANPLAY_APP_SECRET_KEY, null)
        } else {
            storage.getString(LEGACY_DANDANPLAY_APP_SECRET_KEY, null)
        }
        if (protectedSecret != null) {
            if (legacySecret != null) storage.remove(LEGACY_DANDANPLAY_APP_SECRET_KEY)
            return protectedSecret
        }

        if (!legacySecret.isNullOrEmpty()) {
            secretStorage.putString(DANDANPLAY_APP_SECRET_KEY, legacySecret)
        }
        if (legacySecret != null) storage.remove(LEGACY_DANDANPLAY_APP_SECRET_KEY)
        return legacySecret.orEmpty()
    }

    /** Gateway 接管后，删除旧版本可能遗留在普通设置或 SecretStorage 中的 TMDB 官方令牌。 */
    private suspend fun clearLegacyTmdbCredentials(snapshot: StorageSnapshot?) {
        val migrationCompleted = if (snapshot != null) {
            snapshot.getBoolean(TMDB_GATEWAY_CREDENTIAL_MIGRATION_KEY, false)
        } else {
            storage.getBoolean(TMDB_GATEWAY_CREDENTIAL_MIGRATION_KEY, false)
        }
        if (migrationCompleted) return

        var cleanupSucceeded = true
        try {
            secretStorage.remove(LEGACY_TMDB_ACCESS_TOKEN_KEY)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            cleanupSucceeded = false
        }
        try {
            storage.remove(LEGACY_TMDB_ACCESS_TOKEN_KEY)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            cleanupSucceeded = false
        }
        if (cleanupSucceeded) {
            try {
                storage.putBoolean(TMDB_GATEWAY_CREDENTIAL_MIGRATION_KEY, true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // 标记失败时不阻断设置加载，下次启动会幂等重试删除。
            }
        }
    }

    private suspend fun loadBangumiSourceSettings(snapshot: StorageSnapshot?): BangumiDataSourceSettings {
        suspend fun readString(key: String, default: String): String =
            (if (snapshot != null) snapshot.getString(key, default) else storage.getString(key, default)) ?: default

        // 默认 GATEWAY(自建网关); bangumi.lol 预设已退役, 旧存量选择迁移到 GATEWAY; 其余未知值回落 GATEWAY
        val preset = readString("bangumiSourcePreset", BangumiSourcePreset.GATEWAY.name).let { stored ->
            if (stored == "BANGUMI_LOL") {
                BangumiSourcePreset.GATEWAY
            } else {
                runCatching { BangumiSourcePreset.valueOf(stored) }.getOrDefault(BangumiSourcePreset.GATEWAY)
            }
        }
        val settings = BangumiDataSourceSettings(
            preset = preset,
            customSiteBaseUrl = readString("bangumiCustomSiteBaseUrl", "https://bgm.tv"),
            customApiBaseUrl = readString("bangumiCustomApiBaseUrl", "https://api.bgm.tv"),
            customNextApiBaseUrl = readString("bangumiCustomNextApiBaseUrl", "https://next.bgm.tv/p1"),
            customImageBaseUrl = readString("bangumiCustomImageBaseUrl", "https://lain.bgm.tv"),
            thirdPartyDisclaimerAcceptedFor = readString("bangumiThirdPartyDisclaimerAcceptedFor", "")
                .take(MAX_BANGUMI_ACCEPTANCE_IDENTITY_LENGTH),
        )
        return settings.normalizedForUse()
    }

    private suspend fun saveSettings(old: SettingsState, s: SettingsState) {
        val secretChanged = old.dandanplayAppSecret != s.dandanplayAppSecret
        val preparedSecret = if (secretChanged && secretStorage is EncryptedSecretStorage) {
            secretStorage.prepareMutationFor(
                targetStorage = storage,
                key = DANDANPLAY_APP_SECRET_KEY,
                value = s.dandanplayAppSecret.takeIf { it.isNotEmpty() },
            )
        } else {
            null
        }

        if (preparedSecret != null) {
            storage.edit {
                preparedSecret.applyTo(this)
                writeSettingsToBatch(s)
            }
            return
        }

        try {
            if (secretChanged) updateSecret(s.dandanplayAppSecret)
            storage.edit { writeSettingsToBatch(s) }
            flipDefaultsMigrationPending = false
        } catch (error: Throwable) {
            if (secretChanged) {
                val restoreFailure = withContext(NonCancellable) {
                    try {
                        updateSecret(old.dandanplayAppSecret)
                        null
                    } catch (restoreError: Throwable) {
                        restoreError
                    }
                }
                if (restoreFailure != null) error.addSuppressed(restoreFailure)
            }
            throw error
        }
    }

    private suspend fun updateSecret(value: String) {
        if (value.isEmpty()) {
            secretStorage.remove(DANDANPLAY_APP_SECRET_KEY)
        } else {
            secretStorage.putString(DANDANPLAY_APP_SECRET_KEY, value)
        }
    }

    private fun StorageBatch.writeSettingsToBatch(s: SettingsState) {
            putBoolean("recognizeAnime", s.recognizeAnime)
            putString("bangumiSourcePreset", s.bangumiDataSource.preset.name)
            putString("bangumiCustomSiteBaseUrl", s.bangumiDataSource.customSiteBaseUrl)
            putString("bangumiCustomApiBaseUrl", s.bangumiDataSource.customApiBaseUrl)
            putString("bangumiCustomNextApiBaseUrl", s.bangumiDataSource.customNextApiBaseUrl)
            putString("bangumiCustomImageBaseUrl", s.bangumiDataSource.customImageBaseUrl)
            putString("bangumiThirdPartyDisclaimerAcceptedFor", s.bangumiDataSource.thirdPartyDisclaimerAcceptedFor)
            putString("hwdec", s.hwdec)
            putString("audioOutput", s.audioOutput)
            putString("hdrMode", s.hdrMode.name)
            putInt("cacheSize", s.cacheSize)
            putInt("cacheSecs", s.cacheSecs)
            putBoolean("perfMonitorOverlay", s.perfMonitorOverlay)
            putString("longPressSpeed", s.longPressSpeed.toString())
            putString("subtitleFont", s.subtitleFont)
            val fontDir = s.subtitleFontDir
            if (fontDir != null) putString("subtitleFontDir", fontDir) else remove("subtitleFontDir")
            putString("subtitleScale", s.subtitleScale.toString())
            putString("subtitleColor", s.subtitleColor)
            putString("subtitleBorderSize", s.subtitleBorderSize.toString())
            putBoolean("subtitleBold", s.subtitleBold)
            putString("subtitleStyleOverride", s.subtitleStyleOverride)
            putBoolean("autoLoadSiblingSubtitle", s.autoLoadSiblingSubtitle)
            putString("subtitleLanguagePreference", s.subtitleLanguagePreference)
            putString("defaultSubtitleTrackPattern", s.defaultSubtitleTrackPattern)
            putString("defaultAudioTrackPattern", s.defaultAudioTrackPattern)
            putBoolean("predictiveBack", s.predictiveBack)
            putBoolean("animePortraitPlaybackEnabled", s.animePortraitPlaybackEnabled)
            putBoolean("animePortraitCommentsHiddenByDefault", s.animePortraitCommentsHiddenByDefault)
            putString("playbackEndBehavior", s.playbackEndBehavior.name)
            putBoolean("dynamicColor", s.dynamicColor)
            putBoolean("darkTheme", s.darkTheme)
            putString("desktopLayout", s.desktopLayout.name)
            putString("startupHome", s.startupHome.name)
            putBoolean("desktopRunInBackground", s.desktopRunInBackground)
            putBoolean("desktopClosePrompt", s.desktopClosePrompt)
            putBoolean(DESKTOP_GPU_RENDERING_KEY, s.desktopGpuRendering)
            putBoolean("enableLogs", s.enableLogs)
            putString("logLevel", s.logLevel)
            putString("appLogLevel", s.appLogLevel)
            val dir = s.logDirUri
            if (dir != null) putString("logDirUri", dir) else remove("logDirUri")
            putBoolean("allowTlsInsecure", s.allowTlsInsecure)
            val connId = s.webdavDefaultConnectionId
            if (connId != null) putString("webdavDefaultConnectionId", connId)
            else remove("webdavDefaultConnectionId")
            putBoolean("playbackSyncEnabled", s.playbackSyncEnabled)
            putBoolean("playbackAutoSync", s.playbackAutoSync)
            val syncConnId = s.playbackSyncConnectionId
            if (syncConnId != null) putString("playbackSyncConnectionId", syncConnId)
            else remove("playbackSyncConnectionId")
            putString("webdavDefaultDirectory", s.webdavDefaultDirectory)
            putString("webdavSortPreset", s.webdavSortPreset.value)
            putBoolean("webdavShowBreadcrumb", s.webdavShowBreadcrumb)
            putBoolean("webdavAutoEnterSeasonFolder", s.webdavAutoEnterSeasonFolder)
            putString("webdavSeasonFolderPattern", s.webdavSeasonFolderPattern)
            putString("scrapeTriggerMode", s.scrapeTriggerMode)
            putString("scrapeConcurrency", s.scrapeConcurrency.coerceIn(1, 4).toString())
            putBoolean("scrapeUniqueAutoApply", s.scrapeUniqueAutoApply)
            putBoolean("bgmIdQuickMatch", s.bgmIdQuickMatch)
            putString("bgmIdMatchPattern", s.bgmIdMatchPattern)
            putString("danmakuMatchPriority", s.danmakuMatchPriority.joinToString(","))
            putString("tmdbIdMatchPattern", s.tmdbIdMatchPattern)
            putBoolean("episodeOffsetEnabled", s.episodeOffsetEnabled)
            putString("dandanplayAppId", s.dandanplayAppId)
            remove(LEGACY_DANDANPLAY_APP_SECRET_KEY)
            putBoolean("dandanplayUseProxy", s.dandanplayUseProxy)
            putString("aniRss.baseUrl", s.aniRssBaseUrl)
            putBoolean("aniRss.cleartextConfirmed", s.aniRssCleartextConfirmed)
            putString("scheduleSearchHistory", encodeScheduleSearchHistory(s.scheduleSearchHistory))
            putBoolean("scheduleHideTheatrical", s.scheduleHideTheatrical)
            putBoolean("danmakuEnabled", s.danmakuEnabled)
            putString("danmakuEngine", s.danmakuEngine)
            putBoolean("danmakuShowMatchToast", s.danmakuShowMatchToast)
            putBoolean("danmakuAutoManualMatch", s.danmakuAutoManualMatch)
            putString("danmakuOpacity", s.danmakuOpacity.toString())
            putString("danmakuFontSize", s.danmakuFontSize.toString())
            putString("danmakuDisplayArea", s.danmakuDisplayArea.toString())
            putString("danmakuSpeedMultiplier", s.danmakuSpeedMultiplier.toString())
            putString("danmakuMaxOnScreen", s.danmakuMaxOnScreen.toString())
            putString("danmakuStrokeWidth", s.danmakuStrokeWidth.toString())
            putString("danmakuTimeOffsetSec", s.danmakuTimeOffsetSec.toString())
            putBoolean("webdavEnableSearch", s.webdavEnableSearch)
            putString("webdavSearchScope", s.webdavSearchScope.value)
            putInt("webdavSearchDepthLimit", s.webdavSearchDepthLimit)
            putString("webdavSearchTargets", s.webdavSearchTargets.joinToString(",") { it.value })
            putInt("webdavSearchTimeout", s.webdavSearchTimeout.seconds)
            putInt("webdavSearchRequestInterval", s.webdavSearchRequestInterval)
            putInt("webdavSearchMaxResults", s.webdavSearchMaxResults)
            putBoolean("posterWallEnabled", s.posterWallEnabled)
            val pwLibId = s.posterWallDefaultLibraryId
            if (pwLibId != null) putString("posterWallDefaultLibraryId", pwLibId.toString())
            else remove("posterWallDefaultLibraryId")
            putInt("posterWallScanRequestIntervalMs", s.posterWallScanRequestIntervalMs)
            putInt("posterWallScanConcurrency", s.posterWallScanConcurrency)
            putInt("posterWallScanDepth", s.posterWallScanDepth)
            putInt("posterWallScanTimeoutSeconds", s.posterWallScanTimeoutSeconds)
            putInt("posterWallPosterColumnsPortrait", s.posterWallPosterColumnsPortrait)
            putInt("posterWallPosterColumnsLandscape", s.posterWallPosterColumnsLandscape)
            putBoolean("posterWallGroupByQuarter", s.posterWallGroupByQuarter)
            putString("posterWallSortBy", s.posterWallSortBy.name)
            putBoolean("posterWallShowEpisodeThumb", s.posterWallShowEpisodeThumb)
            putBoolean("posterWallAutoEpisodeThumb", s.posterWallAutoEpisodeThumb)
            putString("posterWallEpisodeThumbPositionMode", s.posterWallEpisodeThumbPositionMode.name)
            putInt("posterWallEpisodeThumbAtPercent", s.posterWallEpisodeThumbAtPercent)
            putInt("posterWallEpisodeThumbAtSeconds", s.posterWallEpisodeThumbAtSeconds)
            putBoolean("posterWallDetailUseSeasonPoster", s.posterWallDetailUseSeasonPoster)
            putBoolean("posterWallBadgeShowSeason1", s.posterWallBadgeShowSeason1)
            putInt("posterWallImageCacheSizeMb", s.posterWallImageCacheSizeMb)
            putBoolean("posterWallWalAutoCheckpoint", s.posterWallWalAutoCheckpoint)
            putBoolean("disclaimerAccepted", s.disclaimerAccepted)
            // 默认值翻转迁移标记: 落库后下次启动不再重跑(见 migrateFlippedDefaults)
            if (flipDefaultsMigrationPending) {
                putBoolean(FLIPPED_DEFAULTS_MIGRATION_KEY, true)
            }
    }

    private companion object {
        const val MAX_BANGUMI_ACCEPTANCE_IDENTITY_LENGTH = 4096
        const val DANDANPLAY_APP_SECRET_KEY = "dandanplayAppSecret"
        const val LEGACY_DANDANPLAY_APP_SECRET_KEY = "dandanplayAppSecret"
        const val LEGACY_TMDB_ACCESS_TOKEN_KEY = "tmdbAccessToken"
        const val TMDB_GATEWAY_CREDENTIAL_MIGRATION_KEY = "tmdbGatewayCredentialMigrationCompleted"
        const val FLIPPED_DEFAULTS_MIGRATION_KEY = "flippedDefaultsMigrationV021"
    }
}

internal fun encodeScheduleSearchHistory(history: List<String>): String =
    history.asSequence()
        .map { it.replace('\n', ' ').replace('\r', ' ').trim().take(120) }
        .filter(String::isNotEmpty)
        .distinctBy { it.lowercase() }
        .take(12)
        .joinToString("\n")

internal fun decodeScheduleSearchHistory(raw: String?): List<String> =
    raw.orEmpty().lineSequence()
        .map { it.trim().take(120) }
        .filter(String::isNotEmpty)
        .distinctBy { it.lowercase() }
        .take(12)
        .toList()
