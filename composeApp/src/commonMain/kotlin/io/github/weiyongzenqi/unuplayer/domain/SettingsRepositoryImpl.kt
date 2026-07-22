package io.github.weiyongzenqi.unuplayer.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.weiyongzenqi.unuplayer.core.platform.Storage
import io.github.weiyongzenqi.unuplayer.core.platform.StorageSnapshot
import io.github.weiyongzenqi.unuplayer.core.player.HdrMode
import io.github.weiyongzenqi.unuplayer.core.security.CredentialProtectionException
import io.github.weiyongzenqi.unuplayer.core.security.SecretStorage
import io.github.weiyongzenqi.unuplayer.library.PosterWallSort

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

    /** 加载完成信号。update() await 它, 保证不在 load 覆盖前写入。 */
    private val loadComplete = CompletableDeferred<Unit>()
    private val updateMutex = Mutex()
    private val loadMutex = Mutex()
    private var appSecretLoadFailed = false

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
        if (_loadState.value is SettingsLoadState.Failed) return
        updateMutex.withLock {
            val old = _state.value
            val new = transform(old)
            saveSettings(old, new)
            _state.value = new
        }
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
            _loadState.value = SettingsLoadState.Loading
            appSecretLoadFailed = false
            try {
                val loaded = loadSettings()
                updateMutex.withLock {
                    _state.value = loaded
                }
                _loadState.value = SettingsLoadState.Loaded
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                updateMutex.withLock {
                    _state.value = SettingsState()
                }
                val errorType = error::class.simpleName ?: "未知错误"
                _loadState.value = SettingsLoadState.Failed("设置读取失败（$errorType）")
            }
        }
    }

    private suspend fun loadSettings(): SettingsState {
        val snapshot = storage.readSnapshot()
        suspend fun readString(key: String, default: String? = null): String? =
            if (snapshot != null) snapshot.getString(key, default) else storage.getString(key, default)
        suspend fun readBoolean(key: String, default: Boolean = false): Boolean =
            if (snapshot != null) snapshot.getBoolean(key, default) else storage.getBoolean(key, default)
        suspend fun readInt(key: String, default: Int = 0): Int =
            if (snapshot != null) snapshot.getInt(key, default) else storage.getInt(key, default)

        return SettingsState(
            recognizeAnime = readBoolean("recognizeAnime", true),
            hwdec = readString("hwdec", defaultHwdec()) ?: defaultHwdec(),
            audioOutput = readString("audioOutput", defaultAudioOutput()) ?: defaultAudioOutput(),
            hdrMode = readString("hdrMode", "AUTO").let { stored ->
                runCatching { HdrMode.valueOf(stored ?: "AUTO") }.getOrDefault(HdrMode.AUTO)
            },
            cacheSize = readInt("cacheSize", 32),
            cacheSecs = readInt("cacheSecs", 20),
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
            predictiveBack = readBoolean("predictiveBack", true),
            dynamicColor = readBoolean("dynamicColor", true),
            darkTheme = readBoolean("darkTheme", true),
            desktopLayout = readString("desktopLayout", "SIDEBAR").let { stored ->
                runCatching { DesktopLayout.valueOf(stored ?: "SIDEBAR") }.getOrDefault(DesktopLayout.SIDEBAR)
            },
            startupHome = readString("startupHome", StartupHome.MEDIA_SOURCE.name).let { stored ->
                runCatching { StartupHome.valueOf(stored ?: StartupHome.MEDIA_SOURCE.name) }
                    .getOrDefault(StartupHome.MEDIA_SOURCE)
            },
            desktopRunInBackground = readBoolean("desktopRunInBackground", false),
            desktopClosePrompt = readBoolean("desktopClosePrompt", true),
            desktopGpuRendering = readBoolean(DESKTOP_GPU_RENDERING_KEY, false),
            enableLogs = readBoolean("enableLogs", false),
            logLevel = readString("logLevel", "info") ?: "info",
            appLogLevel = readString("appLogLevel", "info") ?: "info",
            logDirUri = readString("logDirUri", null),
            allowTlsInsecure = readBoolean("allowTlsInsecure", false),
            webdavDefaultConnectionId = readString("webdavDefaultConnectionId", null),
            webdavDefaultDirectory = readString("webdavDefaultDirectory", "/") ?: "/",
            webdavSortPreset = WebDavSortPreset.fromValue(readString("webdavSortPreset", "default")),
            webdavShowBreadcrumb = readBoolean("webdavShowBreadcrumb", true),
            webdavAutoEnterSeasonFolder = readBoolean("webdavAutoEnterSeasonFolder", false),
            webdavSeasonFolderPattern = readString("webdavSeasonFolderPattern", "Season*") ?: "Season*",
            bgmIdQuickMatch = readBoolean("bgmIdQuickMatch", false),
            bgmIdMatchPattern = readString("bgmIdMatchPattern", "bgm(id)?[=-](\\d+)") ?: "bgm(id)?[=-](\\d+)",
            tmdbIdQuickMatch = readBoolean("tmdbIdQuickMatch", false),
            tmdbIdMatchPattern = readString("tmdbIdMatchPattern", "tmdb(id)?[=-](\\d+)") ?: "tmdb(id)?[=-](\\d+)",
            episodeOffsetEnabled = readBoolean("episodeOffsetEnabled", false),
            dandanplayAppId = readString("dandanplayAppId", "") ?: "",
            dandanplayAppSecret = loadDandanplayAppSecret(snapshot),
            dandanplayUseProxy = readBoolean("dandanplayUseProxy", true),
            danmakuHashFallback = readBoolean("danmakuHashFallback", true),
            danmakuEnabled = readBoolean("danmakuEnabled", true),
            danmakuEngine = readString("danmakuEngine", "ATLAS") ?: "ATLAS",
            danmakuShowMatchToast = readBoolean("danmakuShowMatchToast", false),
            danmakuAutoManualMatch = readBoolean("danmakuAutoManualMatch", true),
            danmakuOpacity = readString("danmakuOpacity", "1.0")?.toFloatOrNull() ?: 1.0f,
            danmakuFontSize = readString("danmakuFontSize", "0")?.toFloatOrNull() ?: 0f,
            danmakuDisplayArea = readString("danmakuDisplayArea", "1.0")?.toFloatOrNull() ?: 1.0f,
            danmakuSpeedMultiplier = readString("danmakuSpeedMultiplier", "1.0")?.toFloatOrNull() ?: 1.0f,
            danmakuMaxOnScreen = readString("danmakuMaxOnScreen", "150")?.toIntOrNull() ?: 150,
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
            posterWallDetailUseSeasonPoster = readBoolean("posterWallDetailUseSeasonPoster", false),
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

    private suspend fun saveSettings(old: SettingsState, s: SettingsState) {
        if (old.dandanplayAppSecret != s.dandanplayAppSecret) {
            if (s.dandanplayAppSecret.isEmpty()) {
                secretStorage.remove(DANDANPLAY_APP_SECRET_KEY)
            } else {
                secretStorage.putString(DANDANPLAY_APP_SECRET_KEY, s.dandanplayAppSecret)
            }
        }
        storage.edit {
            putBoolean("recognizeAnime", s.recognizeAnime)
            putString("hwdec", s.hwdec)
            putString("audioOutput", s.audioOutput)
            putString("hdrMode", s.hdrMode.name)
            putInt("cacheSize", s.cacheSize)
            putInt("cacheSecs", s.cacheSecs)
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
            putString("webdavDefaultDirectory", s.webdavDefaultDirectory)
            putString("webdavSortPreset", s.webdavSortPreset.value)
            putBoolean("webdavShowBreadcrumb", s.webdavShowBreadcrumb)
            putBoolean("webdavAutoEnterSeasonFolder", s.webdavAutoEnterSeasonFolder)
            putString("webdavSeasonFolderPattern", s.webdavSeasonFolderPattern)
            putBoolean("bgmIdQuickMatch", s.bgmIdQuickMatch)
            putString("bgmIdMatchPattern", s.bgmIdMatchPattern)
            putBoolean("tmdbIdQuickMatch", s.tmdbIdQuickMatch)
            putString("tmdbIdMatchPattern", s.tmdbIdMatchPattern)
            putBoolean("episodeOffsetEnabled", s.episodeOffsetEnabled)
            putString("dandanplayAppId", s.dandanplayAppId)
            remove(LEGACY_DANDANPLAY_APP_SECRET_KEY)
            putBoolean("dandanplayUseProxy", s.dandanplayUseProxy)
            putBoolean("danmakuHashFallback", s.danmakuHashFallback)
            putBoolean("danmakuEnabled", s.danmakuEnabled)
            putString("danmakuEngine", s.danmakuEngine)
            putBoolean("danmakuShowMatchToast", s.danmakuShowMatchToast)
            putBoolean("danmakuAutoManualMatch", s.danmakuAutoManualMatch)
            putString("danmakuOpacity", s.danmakuOpacity.toString())
            putString("danmakuFontSize", s.danmakuFontSize.toString())
            putString("danmakuDisplayArea", s.danmakuDisplayArea.toString())
            putString("danmakuSpeedMultiplier", s.danmakuSpeedMultiplier.toString())
            putString("danmakuMaxOnScreen", s.danmakuMaxOnScreen.toString())
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
            putBoolean("posterWallDetailUseSeasonPoster", s.posterWallDetailUseSeasonPoster)
            putBoolean("posterWallBadgeShowSeason1", s.posterWallBadgeShowSeason1)
            putInt("posterWallImageCacheSizeMb", s.posterWallImageCacheSizeMb)
            putBoolean("posterWallWalAutoCheckpoint", s.posterWallWalAutoCheckpoint)
            putBoolean("disclaimerAccepted", s.disclaimerAccepted)
        }
    }

    private companion object {
        const val DANDANPLAY_APP_SECRET_KEY = "dandanplayAppSecret"
        const val LEGACY_DANDANPLAY_APP_SECRET_KEY = "dandanplayAppSecret"
    }
}
