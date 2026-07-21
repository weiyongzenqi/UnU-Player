package io.github.weiyongzenqi.unuplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.github.weiyongzenqi.unuplayer.core.media.DesktopSiblingSubtitleLoader
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.core.player.DesktopMpvPlayerEngine
import io.github.weiyongzenqi.unuplayer.core.player.PlaybackStatus
import io.github.weiyongzenqi.unuplayer.core.player.PlayerConfig
import io.github.weiyongzenqi.unuplayer.core.player.MediaInfo
import io.github.weiyongzenqi.unuplayer.core.player.PlayerState
import io.github.weiyongzenqi.unuplayer.core.player.TrackList
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType
import io.github.weiyongzenqi.unuplayer.danmaku.render.DanmakuLayer
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchConfig
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchMethod
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchResult
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatcher
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayApi
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayProxyConfig
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplaySourceProvider
import io.github.weiyongzenqi.unuplayer.danmaku.source.ManualMatchCacheEntry
import io.github.weiyongzenqi.unuplayer.danmaku.source.ManualMatchCacheRepository
import io.github.weiyongzenqi.unuplayer.danmaku.source.calcDanmakuHash
import io.github.weiyongzenqi.unuplayer.danmaku.source.remoteHashForUrl
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.domain.EpisodeNumberExtractor
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.playback.nextPlaybackWriteTimestamp
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Windows 播放页。当前保留既有桌面控制条设计，同时接通稳定 mediaKey、续播和播放进度写回。
 * 更完整的 Android 等价控制层（轨道、字幕、倍速、弹幕、技术信息）后续复用 common UI 补齐。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun DesktopPlayerScreen(
    media: PlayableMedia,
    config: PlayerConfig,
    settingsRepository: SettingsRepository,
    webDavRepository: WebDavConnectionRepository,
    manualMatchCacheRepository: ManualMatchCacheRepository,
    playbackRepository: PlaybackRecordRepository?,
    logger: AppLogger?,
    releaseExecutor: (task: () -> Unit) -> Unit = { task ->
        // 默认参数仅测试路径命中; 生产由 desktopApp 注入 graph 进程级单例执行器(graph::submitPlayerRelease),
        // 不受此默认值影响。测试路径用 daemon=true: 进程退出时残留的释放任务不得拖住 JVM。
        Thread(task, "unu-player-release").apply {
            isDaemon = true
            start()
        }
    },
    // CR-066: 播放记录最终写(DB, 可阻塞)独立提交, 不阻塞 releaseExecutor 的 native destroy。
    // 生产由 desktopApp 注入 graph::submitPlayerRecord; 测试默认 daemon thread。
    recordExecutor: (task: () -> Unit) -> Unit = { task ->
        Thread(task, "unu-player-record").apply {
            isDaemon = true
            start()
        }
    },
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit,
    onEscape: () -> Unit,
    onClose: () -> Unit,
) {
    var engine by remember(media.url) { mutableStateOf<DesktopMpvPlayerEngine?>(null) }
    var initError by remember(media.url) { mutableStateOf<String?>(null) }
    val defaultState = remember { MutableStateFlow(PlayerState()) }
    val defaultPos = remember { MutableStateFlow(0L) }
    val defaultMediaInfo = remember { MutableStateFlow<MediaInfo?>(null) }
    val defaultTracks = remember { MutableStateFlow(TrackList(emptyList(), emptyList(), emptyList())) }
    val state by (engine?.state ?: defaultState).collectAsState()
    val mediaInfo by (engine?.mediaInfo ?: defaultMediaInfo).collectAsState()
    val tracks by (engine?.tracks ?: defaultTracks).collectAsState()
    val settings by settingsRepository.state.collectAsState()
    val latestMediaUrl by rememberUpdatedState(media.url)
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val recordKey = media.mediaKey ?: media.contentUri ?: media.url
    val siblingSubtitleLoader = remember(media.url, webDavRepository) {
        DesktopSiblingSubtitleLoader(webDavRepository)
    }
    var resumeReady by remember(media.url) { mutableStateOf(false) }
    var retryToken by remember(media.url) { mutableLongStateOf(0L) }
    var controlsVisible by remember(media.url) { mutableStateOf(true) }
    var controlsPinned by remember(media.url) { mutableStateOf(false) }
    var controlsInteraction by remember(media.url) { mutableLongStateOf(0L) }
    var showInfoPanel by remember(media.url) { mutableStateOf(false) }
    var showSettingsSheet by remember(media.url) { mutableStateOf(false) }
    var rightKeyPressed by remember(media.url) { mutableStateOf(false) }
    var rightKeySpeedActive by remember(media.url) { mutableStateOf(false) }
    var rightKeyPreviousRate by remember(media.url) { mutableFloatStateOf(1f) }
    var rightKeyLongPressJob by remember(media.url) { mutableStateOf<Job?>(null) }
    var subtitleScale by remember(media.url) { mutableFloatStateOf(settings.subtitleScale) }
    var subtitleBorder by remember(media.url) { mutableFloatStateOf(settings.subtitleBorderSize) }
    var subtitleBold by remember(media.url) { mutableStateOf(settings.subtitleBold) }
    var automaticTracksApplied by remember(media.url, retryToken) { mutableStateOf(false) }
    var userPickedSubtitle by remember(media.url) { mutableStateOf(false) }
    var siblingSubtitleCandidates by remember(media.url) {
        mutableStateOf<List<DesktopSiblingSubtitleLoader.Candidate>>(emptyList())
    }
    var showSiblingSubtitleDialog by remember(media.url) { mutableStateOf(false) }
    val danmakuConfig = DanmakuConfig(
        enabled = settings.danmakuEnabled,
        opacity = settings.danmakuOpacity,
        fontSize = settings.danmakuFontSize,
        displayArea = settings.danmakuDisplayArea,
        speedMultiplier = settings.danmakuSpeedMultiplier,
        engineType = runCatching { DanmakuEngineType.valueOf(settings.danmakuEngine) }
            .getOrDefault(DanmakuEngineType.COMPOSE),
        maxOnScreen = settings.danmakuMaxOnScreen,
    )
    val dandanplayApi = remember(
        settings.dandanplayAppId,
        settings.dandanplayAppSecret,
        settings.dandanplayUseProxy,
    ) {
        when {
            settings.dandanplayUseProxy -> DandanplayApi(
                baseUrl = DandanplayProxyConfig.proxyUrl(),
                proxyApiKey = DandanplayProxyConfig.apiKey(),
            )
            settings.dandanplayAppId.isNotBlank() -> DandanplayApi(
                settings.dandanplayAppId,
                settings.dandanplayAppSecret,
            )
            else -> null
        }
    }
    var danmakuEntries by remember(media.url) { mutableStateOf<List<DanmakuEntry>>(emptyList()) }
    var currentEpisodeTitle by remember(media.url) { mutableStateOf("") }
    var matchToast by remember(media.url) { mutableStateOf<String?>(null) }
    var showManualMatchDialog by remember(media.url) { mutableStateOf(false) }

    fun updateDanmakuConfig(updated: DanmakuConfig) {
        scope.launch {
            settingsRepository.update {
                it.copy(
                    danmakuEnabled = updated.enabled,
                    danmakuOpacity = updated.opacity,
                    danmakuFontSize = updated.fontSize,
                    danmakuDisplayArea = updated.displayArea,
                    danmakuSpeedMultiplier = updated.speedMultiplier,
                    danmakuEngine = updated.engineType.name,
                    danmakuMaxOnScreen = updated.maxOnScreen,
                )
            }
        }
    }

    suspend fun computeDanmakuHash(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        when {
            media.url.startsWith("http", ignoreCase = true) -> remoteHashForUrl(
                media.url,
                media.headers["Authorization"].orEmpty(),
            )
            else -> runCatching {
                val file = if (media.url.startsWith("file:", ignoreCase = true)) {
                    File(java.net.URI(media.url))
                } else {
                    File(media.url)
                }
                file.takeIf { it.isFile }?.let { it.length() to calcDanmakuHash(it.absolutePath) }
            }.getOrNull()
        }
    }

    fun buildRecord(
        pos: Long,
        dur: Long,
        completed: Long,
        existing: PlaybackRecord? = null,
    ): PlaybackRecord {
        val progress = if (dur > 0) (pos.toDouble() / dur).coerceIn(0.0, 1.0) else 0.0
        return PlaybackRecord(
            id = 0,
            media_key = recordKey,
            source_kind = media.sourceKind.name,
            url = media.url,
            content_uri = media.contentUri,
            title = media.title.ifBlank { media.url.substringAfterLast('/') },
            position_ms = pos,
            duration_ms = dur,
            watch_progress = progress,
            is_completed = completed,
            danmaku_episode_id = existing?.danmaku_episode_id,
            danmaku_anime_id = existing?.danmaku_anime_id,
            danmaku_anime_title = existing?.danmaku_anime_title,
            danmaku_episode_title = existing?.danmaku_episode_title,
            danmaku_match_method = existing?.danmaku_match_method,
            last_played_at = nextPlaybackWriteTimestamp(existing?.last_played_at ?: Long.MIN_VALUE),
            sync_status = existing?.sync_status ?: 0,
            sync_version = existing?.sync_version ?: 0,
        )
    }

    // native 初始化和 DLL 加载可能阻塞，始终放 IO；render context 仍由视频 Canvas 首帧创建。
    LaunchedEffect(media.url, retryToken) {
        initError = null
        resumeReady = false
        val existing = engine
        if (existing != null) {
            existing.load(media.url)
            return@LaunchedEffect
        }

        var created: DesktopMpvPlayerEngine? = null
        try {
            val readyEngine = withContext(Dispatchers.IO) {
                DesktopMpvPlayerEngine(logger).also {
                    created = it
                    it.init(config)
                    it.load(media.url)
                }
            }
            currentCoroutineContext().ensureActive()
            engine = readyEngine
            created = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            initError = error.message ?: "播放器初始化失败"
            logger?.appEvent("player", "桌面播放器初始化失败: ${error.javaClass.simpleName}")
        } finally {
            created?.let { abandoned ->
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { abandoned.destroy() }
                }
            }
        }
    }

    LaunchedEffect(engine, config.hwdec) {
        engine?.setHardwareDecoding(config.hwdec)
    }

    // 初始 pause=yes：FILE_LOADED 后先恢复进度，再开始播放，避免从头播放和 seek 竞争。
    LaunchedEffect(engine, recordKey, retryToken) {
        val currentEngine = engine ?: return@LaunchedEffect
        resumeReady = false
        val readyState = withTimeoutOrNull(30_000) {
            currentEngine.state.first {
                it.status == PlaybackStatus.READY || it.status == PlaybackStatus.ERROR
            }
        }
        if (readyState == null) {
            initError = "媒体加载超时"
            return@LaunchedEffect
        }
        if (currentEngine.state.value.status == PlaybackStatus.ERROR) return@LaunchedEffect

        val record = playbackRepository?.getByMediaKey(recordKey)
        if (record != null && record.is_completed == 0L && record.position_ms > 5_000) {
            var attempts = 0
            while (attempts < 50) {
                val durationReady = currentEngine.state.value.durationMs > 0
                val videoReady = currentEngine.mediaInfo.value?.width?.let { it > 0 } == true
                if (durationReady && (videoReady || attempts >= 30)) break
                delay(100)
                attempts++
            }
            currentEngine.seekTo(record.position_ms)
            logger?.appEvent("player", "桌面续播 seek=${record.position_ms}ms")
        }
        resumeReady = true
        currentEngine.play()
    }

    LaunchedEffect(controlsVisible, controlsPinned, controlsInteraction) {
        if (controlsVisible && !controlsPinned) {
            delay(5_000)
            controlsVisible = false
            showInfoPanel = false
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(
        engine,
        subtitleScale,
        subtitleBorder,
        subtitleBold,
        settings.subtitleFont,
        settings.subtitleFontDir,
        settings.subtitleColor,
        settings.subtitleStyleOverride,
    ) {
        engine?.applySubtitleStyle(
            font = settings.subtitleFont,
            fontDir = settings.subtitleFontDir,
            scale = subtitleScale,
            color = settings.subtitleColor,
            borderSize = subtitleBorder,
            bold = subtitleBold,
            styleOverride = settings.subtitleStyleOverride,
        )
    }

    LaunchedEffect(engine, tracks, resumeReady, retryToken) {
        val currentEngine = engine ?: return@LaunchedEffect
        if (!resumeReady || automaticTracksApplied) return@LaunchedEffect
        settings.defaultAudioTrackPattern.takeIf { it.isNotBlank() }?.let { pattern ->
            tracks.audio.firstOrNull { it.matchesTrackPattern(pattern) }?.let { currentEngine.setAudioTrack(it.id) }
        }
        settings.defaultSubtitleTrackPattern.takeIf { it.isNotBlank() }?.let { pattern ->
            tracks.subtitle.firstOrNull { it.matchesTrackPattern(pattern) }
                ?.let { currentEngine.setSubtitleTrack(it.id) }
        }
        automaticTracksApplied = true
    }

    // mpv 的 sub-auto=fuzzy 先尝试本地同目录字幕；轨道稳定后仍为空时，应用 loader
    // 再按简繁偏好补载本地/WebDAV 同名字幕，避免重复加入。
    LaunchedEffect(
        engine,
        media.url,
        retryToken,
        settings.autoLoadSiblingSubtitle,
        settings.subtitleLanguagePreference,
    ) {
        if (!settings.autoLoadSiblingSubtitle) return@LaunchedEffect
        val currentEngine = engine ?: return@LaunchedEffect
        val targetUrl = media.url
        val readyState = withTimeoutOrNull(30_000) {
            currentEngine.state.first {
                it.status == PlaybackStatus.READY || it.status == PlaybackStatus.PAUSED ||
                    it.status == PlaybackStatus.PLAYING || it.status == PlaybackStatus.ERROR
            }
        } ?: return@LaunchedEffect
        if (readyState.status == PlaybackStatus.ERROR) return@LaunchedEffect

        var attempts = 0
        while (currentEngine.state.value.status != PlaybackStatus.ERROR && attempts < 60) {
            val info = currentEngine.mediaInfo.value
            val durationReady = currentEngine.state.value.durationMs > 0
            if (durationReady && info != null && info.width > 0) break
            if (durationReady && attempts >= 30) break
            delay(100)
            attempts++
        }
        if (currentEngine.state.value.status == PlaybackStatus.ERROR) return@LaunchedEffect
        delay(150)
        if (currentEngine.tracks.value.subtitle.isNotEmpty() || userPickedSubtitle) return@LaunchedEffect

        try {
            val candidate = withContext(Dispatchers.IO) {
                siblingSubtitleLoader.listCandidates(media, settings.subtitleLanguagePreference).firstOrNull()
            } ?: return@LaunchedEffect
            val localFile = withContext(Dispatchers.IO) {
                siblingSubtitleLoader.materialize(candidate)
            } ?: return@LaunchedEffect
            if (
                latestMediaUrl != targetUrl || engine !== currentEngine || userPickedSubtitle ||
                currentEngine.tracks.value.subtitle.isNotEmpty()
            ) {
                return@LaunchedEffect
            }
            currentEngine.addExternalSubtitle(
                localFile.absolutePath,
                title = candidate.displayName.substringBeforeLast('.'),
            )
            logger?.appEvent("player", "自动加载同目录字幕 ${candidate.displayName}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger?.appEvent(
                "player",
                "自动加载同目录字幕失败: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    LaunchedEffect(matchToast) {
        if (matchToast != null) {
            delay(2_000)
            matchToast = null
        }
    }

    // 与 Android 同一匹配链：播放记录 → 文件缓存 → TMDB/哈希 → 拉取弹幕。
    LaunchedEffect(
        media.url,
        retryToken,
        settings.recognizeAnime,
        danmakuConfig.enabled,
        dandanplayApi,
        engine,
    ) {
        if (!settings.recognizeAnime || !danmakuConfig.enabled || danmakuEntries.isNotEmpty()) {
            return@LaunchedEffect
        }
        val api = dandanplayApi ?: return@LaunchedEffect
        val currentEngine = engine ?: return@LaunchedEffect
        val ready = currentEngine.state.first {
            it.status == PlaybackStatus.READY || it.status == PlaybackStatus.PAUSED ||
                it.status == PlaybackStatus.PLAYING || it.status == PlaybackStatus.ERROR
        }
        if (ready.status == PlaybackStatus.ERROR || danmakuEntries.isNotEmpty()) return@LaunchedEffect

        val sourceProvider = DandanplaySourceProvider(api)
        var playbackRecord = playbackRepository?.getByMediaKey(recordKey)
        if (playbackRepository != null && playbackRecord == null) {
            for (attempt in 0 until 10) {
                delay(100)
                playbackRecord = playbackRepository.getByMediaKey(recordKey)
                if (playbackRecord != null) break
            }
        }
        playbackRecord?.danmaku_episode_id?.let { episodeId ->
            val record = requireNotNull(playbackRecord)
            danmakuEntries = withContext(Dispatchers.IO) { sourceProvider.fetch(episodeId) }
            currentEpisodeTitle = record.danmaku_episode_title.orEmpty()
            if (settings.danmakuShowMatchToast) {
                matchToast = "弹幕匹配方式：播放记录（${record.danmaku_anime_title.orEmpty()}）"
            }
            logger?.appEvent("danmaku", "播放记录命中 番=${record.danmaku_anime_title.orEmpty()}")
            return@LaunchedEffect
        }

        val isWebDav = media.url.startsWith("http", ignoreCase = true)
        val fileName = media.title.ifBlank { media.url.substringAfterLast('/') }.let {
            runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it)
        }
        val localHash = if (isWebDav) null else computeDanmakuHash()
        val cacheKey = if (isWebDav) media.url else localHash?.second
        val cached = cacheKey?.let { manualMatchCacheRepository.load(it) }
        if (cached != null) {
            danmakuEntries = withContext(Dispatchers.IO) { sourceProvider.fetch(cached.episodeId) }
            currentEpisodeTitle = cached.episodeTitle
            if (settings.danmakuShowMatchToast) matchToast = "弹幕匹配方式：缓存命中（${cached.animeTitle}）"
            logger?.appEvent("danmaku", "缓存命中 番=${cached.animeTitle}")
            return@LaunchedEffect
        }

        val matchConfig = DanmakuMatchConfig(
            tmdbIdQuickMatch = settings.tmdbIdQuickMatch,
            tmdbIdMatchPattern = settings.tmdbIdMatchPattern,
            hashFallback = settings.danmakuHashFallback,
        )
        val result: DanmakuMatchResult? = withContext(Dispatchers.IO) {
            val matcher = DanmakuMatcher(api)
            if (isWebDav) {
                var matched: DanmakuMatchResult? = null
                if (matchConfig.tmdbIdQuickMatch) {
                    matcher.extractTmdbId(media.url, matchConfig.tmdbIdMatchPattern)?.let { tmdbId ->
                        matched = matcher.matchByTmdb(
                            tmdbId,
                            fileName,
                            EpisodeNumberExtractor.extractSeason(fileName),
                        )
                    }
                }
                if (matched == null && matchConfig.hashFallback) {
                    computeDanmakuHash()?.let { (size, hash) ->
                        matched = sourceProvider.match(fileName, hash, size)
                    }
                }
                matched
            } else if (localHash != null && matchConfig.hashFallback) {
                sourceProvider.match(fileName, localHash.second, localHash.first)
            } else {
                null
            }
        }

        if (result != null) {
            currentEpisodeTitle = result.episodeTitle
            val saveKey = if (isWebDav) media.url else localHash?.second
            saveKey?.let { key ->
                manualMatchCacheRepository.save(
                    key,
                    ManualMatchCacheEntry(
                        result.episodeId,
                        result.animeId,
                        result.animeTitle,
                        result.episodeTitle,
                        platformTimeMillis(),
                    ),
                )
            }
            playbackRepository?.updateDanmaku(
                recordKey,
                result.episodeId,
                result.animeId,
                result.animeTitle,
                result.episodeTitle,
                result.matchMethod.name,
            )
            danmakuEntries = withContext(Dispatchers.IO) { sourceProvider.fetch(result.episodeId) }
            logger?.appEvent(
                "danmaku",
                "匹配命中 方式=${result.matchMethod} 番剧=${result.animeTitle} 集=${result.episodeTitle}",
            )
            if (settings.danmakuShowMatchToast) {
                matchToast = "弹幕匹配方式：${result.matchMethod.name}（${result.animeTitle}）"
            }
        } else {
            logger?.appEvent("danmaku", "未匹配 文件名=$fileName")
            if (settings.danmakuShowMatchToast) matchToast = "弹幕未匹配，建议手动匹配"
            if (settings.danmakuAutoManualMatch) showManualMatchDialog = true
        }
    }

    // 就绪后建记录，每 10 秒轻量更新；退出时再写最终位置/完成态。
    LaunchedEffect(engine, recordKey, resumeReady, retryToken) {
        val currentEngine = engine ?: return@LaunchedEffect
        val repository = playbackRepository ?: return@LaunchedEffect
        if (!resumeReady) return@LaunchedEffect
        val existing = repository.getByMediaKey(recordKey)
        val initialPosition = existing?.position_ms ?: currentEngine.position.value
        repository.upsert(
            buildRecord(initialPosition, currentEngine.state.value.durationMs, 0L, existing),
        )
        while (true) {
            delay(10_000)
            val pos = currentEngine.position.value
            val dur = currentEngine.state.value.durationMs
            if (dur > 0 && pos > 0) {
                repository.updatePosition(
                    recordKey,
                    pos,
                    (pos.toDouble() / dur).coerceIn(0.0, 1.0),
                    nextPlaybackWriteTimestamp(),
                )
            }
        }
    }

    DisposableEffect(media.url) {
        onDispose {
            rightKeyLongPressJob?.cancel()
            rightKeyLongPressJob = null
            val currentEngine = engine
            val currentSubtitleLoader = siblingSubtitleLoader
            engine = null
            runCatching { currentEngine?.pause() }
            runCatching { currentEngine?.setMuted(true) }
            val finalPosition = currentEngine?.position?.value ?: 0L
            val finalDuration = currentEngine?.state?.value?.durationMs ?: 0L
            val finishedAt = nextPlaybackWriteTimestamp()
            val finalProgress = if (finalDuration > 0) {
                (finalPosition.toDouble() / finalDuration).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val completed = if (
                finalDuration > 0 &&
                (finalProgress >= 0.9 || finalPosition >= finalDuration - 15_000)
            ) 1L else 0L

            // 不能用组合 scope：组合销毁会取消它。进程级释放执行器会在数据库关闭前等待任务完成。
            // CR-066: finishPlayback(DB 写, 可阻塞 5s+ WAL checkpoint)与 destroy(native 句柄)分离提交,
            // 避免单线程 releaseExecutor 被 runBlocking finishPlayback 阻塞导致 destroy 队列背压、
            // 最坏 close() 超时 shutdownNow 强制中断 destroy -> native 句柄泄漏。二者独立可并发:
            // finishPlayback 只读写 DB, 不依赖 native engine; destroy 只释放 native, 不依赖 DB。
            if (playbackRepository != null && currentEngine != null &&
                (finalDuration > 0 || finalPosition > 0)
            ) {
                val repo = playbackRepository
                recordExecutor {
                    runCatching {
                        runBlocking {
                            repo.finishPlayback(
                                recordKey,
                                finalPosition,
                                finalDuration,
                                finalProgress,
                                completed,
                                finishedAt,
                            )
                        }
                    }
                }
            }
            releaseExecutor {
                try {
                    runCatching { currentEngine?.destroy() }
                } finally {
                    currentSubtitleLoader.close()
                }
            }
        }
    }

    fun finishRightKeyPress(performShortSeek: Boolean) {
        val wasPressed = rightKeyPressed
        rightKeyLongPressJob?.cancel()
        rightKeyLongPressJob = null
        if (rightKeySpeedActive) {
            engine?.setRate(rightKeyPreviousRate)
        } else if (performShortSeek && wasPressed) {
            engine?.seekTo(desktopForwardSeekTarget(engine?.position?.value ?: 0L, state.durationMs))
            controlsVisible = true
            controlsInteraction++
        }
        rightKeyPressed = false
        rightKeySpeedActive = false
    }

    val interactionSource = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                if (!focusState.isFocused && rightKeyPressed) finishRightKeyPress(performShortSeek = false)
            }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionRight && event.type == KeyEventType.KeyUp) {
                    finishRightKeyPress(performShortSeek = true)
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Spacebar -> {
                        if (state.paused) engine?.play() else engine?.pause()
                        controlsVisible = true
                        controlsInteraction++
                        true
                    }
                    event.key == Key.DirectionLeft -> {
                        engine?.seekTo((engine?.position?.value ?: 0L).minus(10_000).coerceAtLeast(0L))
                        controlsVisible = true
                        controlsInteraction++
                        true
                    }
                    event.key == Key.DirectionRight -> {
                        if (!rightKeyPressed) {
                            rightKeyPressed = true
                            rightKeyPreviousRate = state.rate
                            rightKeyLongPressJob = scope.launch {
                                delay(DESKTOP_KEY_LONG_PRESS_MS)
                                if (rightKeyPressed) {
                                    engine?.setRate(settings.longPressSpeed)
                                    rightKeySpeedActive = true
                                }
                            }
                        }
                        true
                    }
                    event.key == Key.DirectionUp -> {
                        engine?.setVolume((state.volume + 5).coerceAtMost(100))
                        true
                    }
                    event.key == Key.DirectionDown -> {
                        engine?.setVolume((state.volume - 5).coerceAtLeast(0))
                        true
                    }
                    event.key == Key.F || (event.key == Key.Enter && event.isAltPressed) -> {
                        onToggleFullscreen()
                        true
                    }
                    event.key == Key.Escape -> {
                        onEscape()
                        true
                    }
                    else -> false
                }
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                if (event.changes.any { it.isConsumed }) return@onPointerEvent
                val scrollDeltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                val nextVolume = desktopVolumeAfterScroll(state.volume, scrollDeltaY)
                if (scrollDeltaY != 0f) {
                    engine?.setVolume(nextVolume)
                    controlsVisible = true
                    controlsInteraction++
                    event.changes.forEach { it.consume() }
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onDoubleClick = {
                    focusRequester.requestFocus()
                    onToggleFullscreen()
                },
                onClick = {
                    focusRequester.requestFocus()
                    controlsVisible = !controlsVisible
                    if (!controlsVisible) showInfoPanel = false
                    controlsInteraction++
                },
            ),
    ) {
        MpvVideoSurface(
            engine = engine,
            modifier = Modifier.fillMaxSize(),
            renderTargetKey = isFullscreen,
            sourceWidth = mediaInfo?.width ?: 0,
            sourceHeight = mediaInfo?.height ?: 0,
            sourceRotation = mediaInfo?.rotation ?: 0,
        )
        engine?.let { currentEngine ->
            DanmakuLayer(
                playerEngine = currentEngine,
                entries = danmakuEntries,
                config = danmakuConfig,
                modifier = Modifier.fillMaxSize(),
            )
        }

        val errorText = initError ?: state.error
        if (errorText != null) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = friendlyError(errorText),
                    color = MaterialTheme.colorScheme.error,
                )
                Button(
                    onClick = {
                        initError = null
                        retryToken++
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text("重试")
                }
            }
        } else if (state.status == PlaybackStatus.LOADING) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }

        if (errorText == null && state.status != PlaybackStatus.LOADING && controlsVisible) {
            PlayerControls(
                state = state,
                positionFlow = engine?.position ?: defaultPos,
                mediaInfo = mediaInfo,
                playTitle = media.title,
                episodeTitle = currentEpisodeTitle,
                onBack = onClose,
                onPlayPause = {
                    if (state.paused) engine?.play() else engine?.pause()
                    controlsInteraction++
                },
                onSeek = { engine?.seekTo(it) },
                onSeekStarted = { controlsPinned = true },
                onSeekFinished = {
                    controlsPinned = false
                    controlsInteraction++
                },
                onToggleInfo = {
                    showInfoPanel = !showInfoPanel
                    controlsInteraction++
                },
                onToggleSettings = {
                    showSettingsSheet = true
                    controlsPinned = true
                },
                danmakuEnabled = danmakuConfig.enabled,
                onToggleDanmaku = {
                    updateDanmakuConfig(danmakuConfig.copy(enabled = !danmakuConfig.enabled))
                    controlsInteraction++
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (rightKeySpeedActive) {
            Text(
                text = "${formatDesktopSpeed(settings.longPressSpeed)}x",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }


        matchToast?.let { message ->
            Text(
                message,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 92.dp)
                    .background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        if (showInfoPanel) {
            mediaInfo?.let { info ->
                TechInfoPanel(
                    mediaInfo = info,
                    state = state,
                    systemVolumePct = if (state.muted) 0 else state.volume,
                    engine = engine ?: return@let,
                    onClose = { showInfoPanel = false },
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(340.dp)
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            event.changes.forEach { it.consume() }
                        },
                )
            }
        }
    }

    if (showSettingsSheet) {
        PlayerSettingsSheet(
            tracks = tracks,
            currentSpeed = state.rate,
            speedPresets = settings.speedPresets,
            onPickSubtitle = {
                engine?.let { currentEngine ->
                    chooseSubtitleFile(media, preferSiblingDirectory = false)?.let { selected ->
                        runCatching { currentEngine.addExternalSubtitle(selected.absolutePath, selected.name) }
                            .onSuccess { userPickedSubtitle = true }
                            .onFailure { initError = "外挂字幕加载失败：${it.message ?: it.javaClass.simpleName}" }
                    }
                }
            },
            onSelectSubtitle = {
                userPickedSubtitle = true
                engine?.setSubtitleTrack(it)
            },
            onSelectAudio = { engine?.setAudioTrack(it) },
            onSelectSpeed = { engine?.setRate(it) },
            onDismiss = {
                showSettingsSheet = false
                controlsPinned = false
                controlsInteraction++
                focusRequester.requestFocus()
            },
            scale = subtitleScale,
            borderSize = subtitleBorder,
            bold = subtitleBold,
            onScaleChange = { subtitleScale = it },
            onBorderChange = { subtitleBorder = it },
            onBoldChange = { subtitleBold = it },
            danmakuConfig = danmakuConfig,
            onDanmakuConfigChange = ::updateDanmakuConfig,
            danmakuShowMatchToast = settings.danmakuShowMatchToast,
            onDanmakuMatchToastChange = { enabled ->
                scope.launch { settingsRepository.update { it.copy(danmakuShowMatchToast = enabled) } }
            },
            danmakuApiReady = dandanplayApi != null,
            onManualMatch = {
                showSettingsSheet = false
                controlsPinned = false
                showManualMatchDialog = true
            },
            onPickSiblingSubtitle = {
                if (media.sourceKind != MediaSourceKind.WEBDAV) {
                    engine?.let { currentEngine ->
                        chooseSubtitleFile(media, preferSiblingDirectory = true)?.let { selected ->
                            runCatching { currentEngine.addExternalSubtitle(selected.absolutePath, selected.name) }
                                .onSuccess { userPickedSubtitle = true }
                                .onFailure { initError = "外挂字幕加载失败：${it.message ?: it.javaClass.simpleName}" }
                        }
                    }
                } else {
                    showSettingsSheet = false
                    controlsPinned = true
                    val targetUrl = media.url
                    scope.launch {
                        try {
                            val candidates = withContext(Dispatchers.IO) {
                                siblingSubtitleLoader.listAllSubtitles(media)
                            }
                            if (latestMediaUrl != targetUrl) return@launch
                            if (candidates.isEmpty()) {
                                matchToast = "未找到同目录字幕文件"
                                controlsPinned = false
                                focusRequester.requestFocus()
                            } else {
                                val currentEpisode = EpisodeNumberExtractor.extractEpisode(media.title)
                                siblingSubtitleCandidates = if (currentEpisode != null) {
                                    candidates.sortedByDescending {
                                        EpisodeNumberExtractor.extractEpisode(it.displayName) == currentEpisode
                                    }
                                } else {
                                    candidates
                                }
                                showSiblingSubtitleDialog = true
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            if (latestMediaUrl == targetUrl) {
                                matchToast = "未找到同目录字幕文件"
                                logger?.appEvent(
                                    "player",
                                    "列同目录字幕失败: ${error.message ?: error.javaClass.simpleName}",
                                )
                            }
                            controlsPinned = false
                            focusRequester.requestFocus()
                        } finally {
                            if (!showSiblingSubtitleDialog) {
                                controlsPinned = false
                                focusRequester.requestFocus()
                            }
                        }
                    }
                }
            },
        )
    }

    if (showSiblingSubtitleDialog) {
        SiblingSubtitleDialog(
            displayNames = siblingSubtitleCandidates.map { it.displayName },
            videoTitle = media.title,
            onDismiss = {
                showSiblingSubtitleDialog = false
                controlsPinned = false
                focusRequester.requestFocus()
            },
            onSelect = { index ->
                val candidate = siblingSubtitleCandidates.getOrNull(index)
                showSiblingSubtitleDialog = false
                val targetUrl = media.url
                val currentEngine = engine
                scope.launch {
                    try {
                        val localFile = candidate?.let {
                            withContext(Dispatchers.IO) { siblingSubtitleLoader.materialize(it) }
                        }
                        if (latestMediaUrl != targetUrl || engine !== currentEngine) return@launch
                        if (candidate == null || localFile == null || currentEngine == null) {
                            matchToast = "同目录字幕加载失败"
                            return@launch
                        }
                        currentEngine.addExternalSubtitle(
                            localFile.absolutePath,
                            title = candidate.displayName.substringBeforeLast('.'),
                        )
                        userPickedSubtitle = true
                        logger?.appEvent("player", "手动加载同目录字幕 ${candidate.displayName}")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        if (latestMediaUrl == targetUrl) {
                            matchToast = "同目录字幕加载失败"
                            logger?.appEvent(
                                "player",
                                "手动加载同目录字幕失败: ${error.message ?: error.javaClass.simpleName}",
                            )
                        }
                    } finally {
                        controlsPinned = false
                        focusRequester.requestFocus()
                    }
                }
            },
        )
    }

    if (showManualMatchDialog && dandanplayApi != null) {
        val api = dandanplayApi
        val initialKeyword = remember(media.url, media.title) {
            DanmakuMatcher.cleanSearchKeyword(
                media.title.ifBlank { media.url.substringAfterLast('/') }.let {
                    runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it)
                },
            )
        }
        ManualMatchDialog(
            api = api,
            initialKeyword = initialKeyword,
            onDismiss = {
                showManualMatchDialog = false
                focusRequester.requestFocus()
            },
            onConfirm = { selection ->
                showManualMatchDialog = false
                val targetUrl = media.url
                scope.launch {
                    try {
                        val fetchResult = withContext(Dispatchers.IO) {
                            runSuspendCatching { DandanplaySourceProvider(api).fetch(selection.episodeId) }
                        }
                        fetchResult.exceptionOrNull()?.let { error ->
                            logger?.appEvent(
                                "danmaku",
                                "手动匹配拉取失败 episodeId=${selection.episodeId}: " +
                                    (error.message ?: error.javaClass.simpleName),
                            )
                        }
                        val entries = fetchResult.getOrElse { emptyList() }
                        if (latestMediaUrl != targetUrl) return@launch

                        danmakuEntries = entries
                        currentEpisodeTitle = selection.episodeTitle
                        withContext(Dispatchers.IO) {
                            val isWebDav = targetUrl.startsWith("http", ignoreCase = true)
                            val cacheKey = if (isWebDav) targetUrl else computeDanmakuHash()?.second
                            cacheKey?.let { key ->
                                runSuspendCatching {
                                    manualMatchCacheRepository.save(
                                        key,
                                        ManualMatchCacheEntry(
                                            selection.episodeId,
                                            selection.animeId,
                                            selection.animeTitle,
                                            selection.episodeTitle,
                                            platformTimeMillis(),
                                        ),
                                    )
                                }.onFailure { error ->
                                    logger?.appEvent(
                                        "danmaku",
                                        "手动匹配缓存写入失败: ${error.message ?: error.javaClass.simpleName}",
                                    )
                                }
                            }
                            runSuspendCatching {
                                playbackRepository?.updateDanmaku(
                                    recordKey,
                                    selection.episodeId,
                                    selection.animeId,
                                    selection.animeTitle,
                                    selection.episodeTitle,
                                    DanmakuMatchMethod.MANUAL.name,
                                )
                            }.onFailure { error ->
                                logger?.appEvent(
                                    "danmaku",
                                    "手动匹配播放记录写入失败: ${error.message ?: error.javaClass.simpleName}",
                                )
                            }
                        }
                        if (settings.danmakuShowMatchToast) {
                            matchToast = "弹幕匹配方式：手动匹配（${selection.animeTitle}）"
                        }
                        logger?.appEvent(
                            "danmaku",
                            "手动匹配成功 episodeId=${selection.episodeId} 番=${selection.animeTitle}",
                        )
                    } finally {
                        focusRequester.requestFocus()
                    }
                }
            },
        )
    }
}

internal const val DESKTOP_VOLUME_SCROLL_STEP = 5
internal const val DESKTOP_KEY_LONG_PRESS_MS = 500L

internal fun formatDesktopSpeed(speed: Float): String = speed.toString().removeSuffix(".0")

internal fun desktopForwardSeekTarget(positionMs: Long, durationMs: Long, stepMs: Long = 10_000L): Long {
    val target = (positionMs.coerceAtLeast(0L) + stepMs.coerceAtLeast(0L)).coerceAtLeast(0L)
    return if (durationMs > 0L) target.coerceAtMost(durationMs) else target
}

/** 将不同鼠标上报的滚轮幅度归一为固定音量步进，避免单次滚动产生过大跳变。 */
internal fun desktopVolumeAfterScroll(
    currentVolume: Int,
    scrollDeltaY: Float,
    step: Int = DESKTOP_VOLUME_SCROLL_STEP,
): Int {
    val boundedVolume = currentVolume.coerceIn(0, 100)
    if (!scrollDeltaY.isFinite() || scrollDeltaY == 0f || step <= 0) return boundedVolume
    val direction = if (scrollDeltaY < 0f) 1 else -1
    return (boundedVolume + direction * step).coerceIn(0, 100)
}

private fun chooseSubtitleFile(media: PlayableMedia, preferSiblingDirectory: Boolean): File? {
    val initialDirectory = if (preferSiblingDirectory) {
        runCatching { File(media.url).takeIf { it.isFile }?.parentFile }.getOrNull()
    } else {
        null
    }
    val chooser = JFileChooser(initialDirectory).apply {
        dialogTitle = if (preferSiblingDirectory) "从同目录选择字幕" else "加载外挂字幕"
        fileSelectionMode = JFileChooser.FILES_ONLY
        isAcceptAllFileFilterUsed = false
        fileFilter = FileNameExtensionFilter("字幕文件 (*.srt, *.ass, *.ssa, *.vtt)", "srt", "ass", "ssa", "vtt")
    }
    return chooser.takeIf { it.showOpenDialog(null) == JFileChooser.APPROVE_OPTION }?.selectedFile
}

private fun friendlyError(raw: String): String {
    val lower = raw.lowercase()
    return when {
        "timeout" in lower || "超时" in raw -> "媒体加载超时，请检查网络或文件后重试"
        "not found" in lower || "no such file" in lower -> "找不到媒体文件，请确认文件仍然存在"
        "access" in lower || "permission" in lower || "403" in lower -> "没有权限访问该媒体"
        "401" in lower || "unauthorized" in lower -> "媒体服务器认证失败，请检查账号和密码"
        else -> raw
    }
}
