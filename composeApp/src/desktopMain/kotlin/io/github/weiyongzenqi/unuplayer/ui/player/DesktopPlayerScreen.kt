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
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.weiyongzenqi.unuplayer.core.media.resolveDanmakuEpisodeHint
import io.github.weiyongzenqi.unuplayer.core.media.resolveDanmakuSeasonHint
import io.github.weiyongzenqi.unuplayer.library.resolveManualDanmakuSearchKeyword
import io.github.weiyongzenqi.unuplayer.core.player.DesktopMpvPlayerEngine
import io.github.weiyongzenqi.unuplayer.core.player.PlaybackStatus
import io.github.weiyongzenqi.unuplayer.core.player.PlayerConfig
import io.github.weiyongzenqi.unuplayer.core.player.HttpRedirectPolicy
import io.github.weiyongzenqi.unuplayer.core.player.MediaInfo
import io.github.weiyongzenqi.unuplayer.core.player.PlayerState
import io.github.weiyongzenqi.unuplayer.core.player.TrackList
import io.github.weiyongzenqi.unuplayer.core.player.isTerminalPlaybackState
import io.github.weiyongzenqi.unuplayer.core.player.shouldPersistPeriodicPlayback
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.render.DanmakuLayer
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchConfig
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchMethod
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchResult
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatcher
import io.github.weiyongzenqi.unuplayer.danmaku.source.parseDanmakuMatchOrder
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayApi
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayProxyConfig
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplaySourceProvider
import io.github.weiyongzenqi.unuplayer.danmaku.source.ManualMatchCacheEntry
import io.github.weiyongzenqi.unuplayer.danmaku.source.ManualMatchCacheRepository
import io.github.weiyongzenqi.unuplayer.danmaku.source.isDanmakuShortcutCompatible
import io.github.weiyongzenqi.unuplayer.danmaku.source.calcDanmakuHash
import io.github.weiyongzenqi.unuplayer.danmaku.source.remoteHashForUrl
import io.github.weiyongzenqi.unuplayer.danmaku.source.remoteHashForMediaServer
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.domain.EpisodeNumberExtractor
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.toDanmakuConfig
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideIdentity
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideJson
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideSettings
import io.github.weiyongzenqi.unuplayer.library.diffUpdate
import io.github.weiyongzenqi.unuplayer.library.withOverride
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.playback.nextPlaybackWriteTimestamp
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.parseWebDavRecordConnectionId
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerPlaybackReportCoordinator
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerPlaybackState
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerPreparedPlayback
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerExternalSubtitle
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerPlaybackPlan
import io.github.weiyongzenqi.unuplayer.mediaserver.historyMediaKey
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Windows 播放页。保留既有桌面控制条设计，并接通稳定 mediaKey、续播、播放进度写回与
 * Jellyfin Started/Progress/Stopped 生命周期。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun DesktopPlayerScreen(
    media: PlayableMedia,
    mediaServerPlayback: MediaServerPreparedPlayback? = null,
    config: PlayerConfig,
    settingsRepository: SettingsRepository,
    webDavRepository: WebDavConnectionRepository,
    manualMatchCacheRepository: ManualMatchCacheRepository,
    playbackRepository: PlaybackRecordRepository?,
    scrapedRepository: ScrapedLibraryRepository? = null,
    logger: AppLogger?,
    releaseLease: DesktopPlayerReleaseLease,
    // CR-066: 播放记录最终写(DB, 可阻塞)独立提交, 不阻塞会话级 release worker 的 native destroy。
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
    onReplayMediaServer: (() -> Unit)? = null,
    onReplayWebDav: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    // B-P1-2: 计划一致性校验改为收集错误态而非组合期 require 裸崩——
    // 原四个 require 在 EDT 组合期抛 IllegalArgumentException, 任一失配直接进程崩溃。
    // 失配降级为 initError 覆盖层(不创建引擎播放), 单窗口失败不拖死整个进程。
    val releaseLeaseClaimed = remember(media.url, releaseLease) { releaseLease.claim() }
    val planMismatch = desktopMediaServerPlanMismatch(media, mediaServerPlayback?.plan, config)
        ?: if (releaseLeaseClaimed) null else "播放器释放许可已失效，请重新打开媒体"
    var engine by remember(media.url) { mutableStateOf<DesktopMpvPlayerEngine?>(null) }
    var initError by remember(media.url) { mutableStateOf<String?>(planMismatch) }
    val defaultState = remember { MutableStateFlow(PlayerState()) }
    val defaultPos = remember { MutableStateFlow(0L) }
    val defaultMediaInfo = remember { MutableStateFlow<MediaInfo?>(null) }
    val defaultTracks = remember { MutableStateFlow(TrackList(emptyList(), emptyList(), emptyList())) }
    val state by (engine?.state ?: defaultState).collectAsState()
    val mediaInfo by (engine?.mediaInfo ?: defaultMediaInfo).collectAsState()
    val tracks by (engine?.tracks ?: defaultTracks).collectAsState()
    val settings by settingsRepository.state.collectAsState()
    val latestMediaUrl by rememberUpdatedState(media.url)
    var playbackMedia by remember(media.url) { mutableStateOf(media) }
    val scope = rememberCoroutineScope()
    val releaseCoordinator = remember(media.url, releaseLease) {
        DesktopPlayerReleaseCoordinator(
            submit = releaseLease::submit,
            submitTerminal = releaseLease::submitTerminal,
            reserveChild = releaseLease::tryReserveChildRelease,
        )
    }
    val focusRequester = remember { FocusRequester() }
    val recordKey = media.mediaKey ?: media.contentUri ?: media.url
    var lastValidPositionMs by remember(media.url) { mutableLongStateOf(0L) }
    var lastValidDurationMs by remember(media.url) { mutableLongStateOf(0L) }

    fun effectiveMediaServerPositionMs(): Long {
        val raw = engine?.position?.value ?: 0L
        val current = engine?.state?.value
        return desktopFinalPlaybackPosition(
            currentPositionMs = raw,
            playbackEnded = current?.eof == true || current?.status == PlaybackStatus.ENDED,
            lastValidPositionMs = lastValidPositionMs,
            lastValidDurationMs = lastValidDurationMs,
        )
    }

    fun currentMediaServerPlaybackState(
        positionMs: Long = effectiveMediaServerPositionMs(),
    ): MediaServerPlaybackState {
        val plan = requireNotNull(mediaServerPlayback).plan
        val current = engine?.state?.value
        return MediaServerPlaybackState(
            itemId = plan.itemId,
            mediaSourceId = plan.mediaSourceId,
            playSessionId = plan.playSessionId,
            playMethod = plan.playMethod,
            positionMs = positionMs.coerceAtLeast(0L),
            audioStreamIndex = null,
            subtitleStreamIndex = null,
            isPaused = current == null || current.paused || current.status != PlaybackStatus.PLAYING,
            isMuted = current?.muted == true,
        )
    }

    fun logMediaServerReportFailure(error: Throwable) {
        logger?.appEvent(
            "media-server",
            "桌面播放状态上报失败: ${error.javaClass.simpleName}",
            LogLevel.WARN,
        )
    }
    // 节目专属弹幕覆盖身份键: 有 tmdbId(刮削番剧)走本部覆盖; null(非刮削/外部)维持写全局。
    val overrideKey = media.tmdbId?.let { ShowOverrideIdentity.tmdb(it) }
    // 本部弹幕覆盖内存态(按媒体 url 记, 换集复位); 初值空=全跟随全局。
    var currentOverride by remember(media.url) { mutableStateOf(ShowOverrideSettings()) }
    val siblingSubtitleLoader = remember(media.url, webDavRepository) {
        DesktopSiblingSubtitleLoader(webDavRepository)
    }
    val mediaServerReportCoordinator = remember(mediaServerPlayback) {
        mediaServerPlayback?.let { MediaServerPlaybackReportCoordinator(it.reporter) }
    }
    var mediaServerSeekReportGeneration by remember(media.url) { mutableIntStateOf(0) }
    var resolvedStartPositionMs by remember(media.url) { mutableLongStateOf(0L) }
    LaunchedEffect(engine) {
        val currentEngine = engine ?: return@LaunchedEffect
        currentEngine.position.collect { positionMs ->
            if (positionMs > 0L) lastValidPositionMs = positionMs
        }
    }
    LaunchedEffect(engine) {
        val currentEngine = engine ?: return@LaunchedEffect
        currentEngine.state.collect { current ->
            if (current.durationMs > 0L) lastValidDurationMs = current.durationMs
        }
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
    // 全局弹幕配置(随设置重组重算); 实际用的是叠加本部覆盖后的有效配置(仍名 danmakuConfig, 下游无感)。
    val globalCfg = settings.toDanmakuConfig()
    val danmakuConfig = globalCfg.withOverride(currentOverride)
    // 启动加载一次本部覆盖(有身份键且仓库可用才读); 读到即填 currentOverride, 触发重组刷新有效配置。
    // 无身份/仓库不可用时不早退: currentOverride 维持空态, 字幕样式本地态按全局值无条件刷新。
    LaunchedEffect(media.url) {
        val key = overrideKey
        val repo = scrapedRepository
        if (key != null && repo != null) {
            repo.getShowOverrideJson(key)?.let { raw ->
                ShowOverrideJson.decode(raw)?.let { decoded ->
                    // 仅空态才赋值: DB 异常慢时, 防止晚到的旧加载结果盖掉用户已调整的新值(窗口极小, 防御性)
                    if (currentOverride.isEmpty()) currentOverride = decoded
                }
            }
        }
        // 本部覆盖加载后, 字幕样式本地态按有效值刷新(覆盖 ?: 全局); applySubtitleStyle 随本地态变化重应用。
        subtitleScale = currentOverride.subtitleScale ?: settings.subtitleScale
        subtitleBorder = currentOverride.subtitleBorderSize ?: settings.subtitleBorderSize
        subtitleBold = currentOverride.subtitleBold ?: settings.subtitleBold
    }
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
    var screenshotInProgress by remember(media.url) { mutableStateOf(false) }
    var showManualMatchDialog by remember(media.url) { mutableStateOf(false) }

    fun updateDanmakuConfig(updated: DanmakuConfig) {
        val key = overrideKey
        val repo = scrapedRepository
        if (key != null && repo != null) {
            // enabled 总开关跟随全局(设计: 开关全局/样式本部): 变动即写全局, 不进覆盖。
            if (updated.enabled != globalCfg.enabled) {
                scope.launch { settingsRepository.update { it.copy(danmakuEnabled = updated.enabled) } }
            }
            // 样式字段差分写入本部覆盖(自动创建), 不动全局。old=变动前有效配置; 无样式变动不写。
            val old = globalCfg.withOverride(currentOverride)
            val next = currentOverride.diffUpdate(old, updated)
            if (next != currentOverride) {
                currentOverride = next
                scope.launch {
                    repo.upsertShowOverride(key, ShowOverrideJson.encode(next), platformTimeMillis())
                }
            }
        } else {
            // 无节目身份或仓库不可用: 原样写全局设置。
            scope.launch {
                settingsRepository.update {
                    it.copy(
                        danmakuEnabled = updated.enabled,
                        danmakuOpacity = updated.opacity,
                        danmakuFontSize = updated.fontSize,
                        danmakuDisplayArea = updated.displayArea,
                        danmakuSpeedMultiplier = updated.speedMultiplier,
                        danmakuStrokeWidth = updated.strokeWidth,
                        danmakuTimeOffsetSec = updated.timeOffsetSec,
                        danmakuEngine = updated.engineType.name,
                        danmakuMaxOnScreen = updated.maxOnScreen,
                    )
                }
            }
        }
    }

    suspend fun computeDanmakuHash(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        when {
            mediaServerPlayback != null -> remoteHashForMediaServer(playbackMedia.url, playbackMedia.headers)
            playbackMedia.url.startsWith("http", ignoreCase = true) -> remoteHashForUrl(
                playbackMedia.url,
                playbackMedia.headers["Authorization"].orEmpty(),
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
            // Jellyfin 直放 URL 含 PlaySessionId，只保存稳定 mediaKey；历史点击会在播放器内重建计划。
            url = desktopPlaybackRecordUrl(media.url, isMediaServer = mediaServerPlayback != null),
            content_uri = if (mediaServerPlayback == null) media.contentUri else null,
            title = media.title.ifBlank { media.url.substringAfterLast('/') },
            position_ms = pos,
            duration_ms = dur,
            watch_progress = progress,
            is_completed = completed,
            // 三元组(刮削番剧跨库续播锚点): 从 PlayableMedia 取值(刮削路径)或 null(外部路径)
            tmdb_id = media.tmdbId,
            season_number = media.seasonNumber,
            episode_number = media.episodeNumber,
            danmaku_episode_id = existing?.danmaku_episode_id,
            danmaku_anime_id = existing?.danmaku_anime_id,
            danmaku_anime_title = existing?.danmaku_anime_title,
            danmaku_episode_title = existing?.danmaku_episode_title,
            danmaku_match_method = existing?.danmaku_match_method,
            danmaku_sync_version = existing?.danmaku_sync_version ?: 0,
            danmaku_updated_at = existing?.danmaku_updated_at ?: 0,
            last_played_at = nextPlaybackWriteTimestamp(existing?.last_played_at ?: Long.MIN_VALUE),
            sync_status = existing?.sync_status ?: 0,
            // B-1: sync_version 由 upsertEntry 在 SQL 侧事务内原子 +1, 此处传值不再使用
            // (快照读-算-写在事务外有 Lamport 回退窗口, 同 Android PlayerScreen)。
            sync_version = 0,
        )
    }

    // native 初始化和 DLL 加载可能阻塞，始终放 IO；render context 仍由视频 Canvas 首帧创建。
    LaunchedEffect(media.url, retryToken, planMismatch) {
        if (planMismatch != null) {
            initError = planMismatch
            return@LaunchedEffect
        }
        initError = null
        resumeReady = false
        val existing = engine
        if (existing != null) {
            existing.load(media.url)
            return@LaunchedEffect
        }

        var created: DesktopMpvPlayerEngine? = null
        try {
            val (preparedMedia, preparedRedirectPolicy) = withContext(Dispatchers.IO) {
                val connectionId = if (media.sourceKind == MediaSourceKind.WEBDAV) {
                    media.mediaKey?.let(::parseWebDavRecordConnectionId)
                        ?: error("WebDAV 播放记录缺少连接定位")
                } else {
                    null
                }
                if (connectionId == null) {
                    media to config.httpRedirectPolicy
                } else {
                    val request = webDavRepository.preparePlayback(connectionId, media.url)
                    media.copy(url = request.url, headers = request.headers) to request.redirectPolicy
                }
            }
            playbackMedia = preparedMedia
            val preparedConfig = if (media.sourceKind == MediaSourceKind.WEBDAV) {
                config.copy(
                    httpHeaders = preparedMedia.headers,
                    httpRedirectPolicy = preparedRedirectPolicy,
                )
            } else {
                config
            }
            val readyEngine = withContext(Dispatchers.IO) {
                DesktopMpvPlayerEngine(logger).also {
                    created = it
                    it.init(preparedConfig)
                    it.load(preparedMedia.url)
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
    // P1b-B1: 两级续播(本文件优先 → 三元组语义进度比例换算)
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

        // 续播决策(跨库双向跟随): 本文件 vs 三元组语义进度, 取 last_played_at 较新者为真相(同 Android PlayerScreen)。
        // B-09: 读失败视为无记录继续播, 不向 LaunchedEffect 抛。
        val record = playbackRepository?.let { repo -> safeReadPlaybackRecord(repo, recordKey, logger) }
        val ownResume = desktopResumePosition(
            recordPositionMs = record?.position_ms,
            recordCompleted = record?.is_completed == 1L,
            initialPositionMs = 0L,
        )
        val resumePosition = desktopResumePosition(
            recordPositionMs = record?.position_ms,
            recordCompleted = record?.is_completed == 1L,
            initialPositionMs = mediaServerPlayback?.plan?.initialPositionMs ?: 0L,
        )
        resolvedStartPositionMs = resumePosition ?: 0L
        val crossLib = if (media.tmdbId != null && media.seasonNumber != null && media.episodeNumber != null && media.episodeNumber > 0L) {
            runSuspendCatching {
                playbackRepository?.getEpisodeProgressByTriple(media.tmdbId, media.seasonNumber, media.episodeNumber)
            }.getOrElse { e ->
                logger?.appEvent("player", "桌面跨库续播读取失败, 视为无: ${e.javaClass.simpleName}: ${e.message}", LogLevel.WARN)
                null
            }
        } else null
        val crossLibIsNewer = crossLib != null && (record == null || crossLib.last_played_at > record.last_played_at)

        // 等待就绪(polling)
        suspend fun waitForReady() {
            var attempts = 0
            while (attempts < 50) {
                val durationReady = currentEngine.state.value.durationMs > 0
                val videoReady = currentEngine.mediaInfo.value?.width?.let { it > 0 } == true
                if (durationReady && (videoReady || attempts >= 30)) break
                delay(100)
                attempts++
            }
        }

        val useCrossCompleted = crossLib?.is_completed == 1L && crossLibIsNewer
        val useCrossProgress = crossLib != null && crossLib.is_completed == 0L && crossLib.watch_progress > 0.0 && crossLibIsNewer
        val useOwnPosition = ownResume != null && (!crossLibIsNewer || (!useCrossCompleted && !useCrossProgress))
        if (useOwnPosition) {
            // 本文件有可用位置 → 绝对位置 seek
            waitForReady()
            currentEngine.seekTo(ownResume)
            logger?.appEvent("player", "桌面续播 seek=${ownResume}ms")
        } else if (useCrossCompleted) {
            // 跨库已看完 → 不 seek, 从头播(重播语义)
            logger?.appEvent("player", "桌面跨库已看完标记, 从头播", LogLevel.INFO)
        } else if (useCrossProgress) {
            // 跨库语义进度 → 比例换算 seek
            waitForReady()
            val dur = currentEngine.state.value.durationMs
            if (dur > 0) {
                val pos = (dur * crossLib.watch_progress).toLong().coerceIn(0L, (dur - 5_000L).coerceAtLeast(0L))
                if (pos > 0) {
                    currentEngine.seekTo(pos)
                    logger?.appEvent("player", "桌面跨库比例续播 seek=${pos}ms progress=${crossLib.watch_progress}", LogLevel.INFO)
                }
            }
        } else if (resumePosition != null) {
            waitForReady()
            currentEngine.seekTo(resumePosition)
            logger?.appEvent("player", "桌面初始位置续播 seek=${resumePosition}ms", LogLevel.INFO)
        }
        resumeReady = true
        currentEngine.play()
    }

    LaunchedEffect(mediaServerReportCoordinator, engine, retryToken) {
        val coordinator = mediaServerReportCoordinator ?: return@LaunchedEffect
        val currentEngine = engine ?: return@LaunchedEffect
        currentEngine.state.first { it.status == PlaybackStatus.PLAYING }
        coordinator.runPeriodic(
            currentState = ::currentMediaServerPlaybackState,
            onFailure = ::logMediaServerReportFailure,
            startedState = { currentMediaServerPlaybackState(resolvedStartPositionMs) },
            shouldStop = {
                val current = currentEngine.state.value
                current.eof || current.status == PlaybackStatus.ERROR || current.status == PlaybackStatus.ENDED
            },
        )
    }

    LaunchedEffect(mediaServerPlayback, engine, retryToken) {
        val plan = mediaServerPlayback?.plan ?: return@LaunchedEffect
        val currentEngine = engine ?: return@LaunchedEffect
        if (plan.externalSubtitles.isEmpty()) return@LaunchedEffect
        currentEngine.state.first {
            it.status == PlaybackStatus.READY || it.status == PlaybackStatus.PAUSED ||
                it.status == PlaybackStatus.PLAYING || it.status == PlaybackStatus.ERROR
        }
        if (currentEngine.state.value.status == PlaybackStatus.ERROR) return@LaunchedEffect
        desktopMediaServerSubtitleLoads(plan).forEach { load ->
            val subtitle = load.subtitle
            currentEngine.addExternalSubtitle(
                subtitle.url,
                subtitle.title ?: subtitle.language,
                select = load.select,
            )
        }
    }

    LaunchedEffect(mediaServerReportCoordinator, state.paused, state.status) {
        val coordinator = mediaServerReportCoordinator ?: return@LaunchedEffect
        if (state.status == PlaybackStatus.PLAYING || state.status == PlaybackStatus.PAUSED) {
            coordinator.reportNow(currentMediaServerPlaybackState())
                .exceptionOrNull()
                ?.let(::logMediaServerReportFailure)
        }
    }

    LaunchedEffect(mediaServerReportCoordinator, state.eof) {
        val coordinator = mediaServerReportCoordinator ?: return@LaunchedEffect
        if (!state.eof) return@LaunchedEffect
        val result = withTimeoutOrNull(MEDIA_SERVER_STOP_TIMEOUT_MS) {
            coordinator.reportStopped(currentMediaServerPlaybackState())
        }
        if (result == null) {
            logger?.appEvent("media-server", "桌面 EOF Stopped 上报超时", LogLevel.WARN)
        } else {
            result.exceptionOrNull()?.let(::logMediaServerReportFailure)
        }
    }

    LaunchedEffect(mediaServerReportCoordinator, mediaServerSeekReportGeneration) {
        val coordinator = mediaServerReportCoordinator ?: return@LaunchedEffect
        if (mediaServerSeekReportGeneration == 0) return@LaunchedEffect
        delay(250)
        coordinator.reportNow(currentMediaServerPlaybackState())
            .exceptionOrNull()
            ?.let(::logMediaServerReportFailure)
    }

    val selectedTrackSignature = tracks.audio.firstOrNull { it.selected }?.id to
        tracks.subtitle.firstOrNull { it.selected }?.id
    LaunchedEffect(mediaServerReportCoordinator, selectedTrackSignature) {
        val coordinator = mediaServerReportCoordinator ?: return@LaunchedEffect
        if (state.status != PlaybackStatus.PLAYING && state.status != PlaybackStatus.PAUSED) {
            return@LaunchedEffect
        }
        coordinator.reportNow(currentMediaServerPlaybackState())
            .exceptionOrNull()
            ?.let(::logMediaServerReportFailure)
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

    // 本部有效选轨偏好: 覆盖非 null 用覆盖, 否则全局。入选轨 effect key, 覆盖晚到(晚于轨道)也能重跑选轨。
    val effectiveAudioTrackPattern = currentOverride.defaultAudioTrackPattern ?: settings.defaultAudioTrackPattern
    val effectiveSubtitleTrackPattern =
        currentOverride.defaultSubtitleTrackPattern ?: settings.defaultSubtitleTrackPattern
    val mediaServerHasDefaultExternalSubtitle = mediaServerPlayback?.plan?.let { plan ->
        desktopMediaServerSubtitleLoads(plan).any { it.select }
    } == true
    // 记录上次自动选轨时的 pattern: pattern 不变的轨道更新仍被一次性标记挡住(不扰手动选轨/补载外挂字幕);
    // 本部 pattern 变(覆盖晚到)才放行重跑, 关闭"覆盖晚于轨道到达→本部选轨偏好此次不生效"竞态窗。
    var lastAppliedAudioTrackPattern by remember(media.url, retryToken) { mutableStateOf<String?>(null) }
    var lastAppliedSubtitleTrackPattern by remember(media.url, retryToken) { mutableStateOf<String?>(null) }
    LaunchedEffect(
        engine, tracks, resumeReady, retryToken, effectiveAudioTrackPattern, effectiveSubtitleTrackPattern,
        mediaServerHasDefaultExternalSubtitle,
    ) {
        val currentEngine = engine ?: return@LaunchedEffect
        if (!resumeReady) return@LaunchedEffect
        val patternChanged = effectiveAudioTrackPattern != lastAppliedAudioTrackPattern ||
            effectiveSubtitleTrackPattern != lastAppliedSubtitleTrackPattern
        if (automaticTracksApplied && !patternChanged) return@LaunchedEffect
        // 选轨偏好按本部有效值(见上方有效值变量; 覆盖加载通常快于轨道到达, 先生效)。
        effectiveAudioTrackPattern.takeIf { it.isNotBlank() }?.let { pattern ->
            tracks.audio.firstOrNull { it.matchesTrackPattern(pattern) }?.let { currentEngine.setAudioTrack(it.id) }
        }
        // 用户手动选过字幕轨或 Jellyfin 已给出可用默认外挂时，不再用本地 pattern 覆盖选择。
        if (!userPickedSubtitle && !mediaServerHasDefaultExternalSubtitle) {
            effectiveSubtitleTrackPattern.takeIf { it.isNotBlank() }?.let { pattern ->
                tracks.subtitle.firstOrNull { it.matchesTrackPattern(pattern) }
                    ?.let { currentEngine.setSubtitleTrack(it.id) }
            }
        }
        // 轨道确实到达后才标记; 空轨道不消耗一次性机会, 留给轨道到达时重跑。
        if (tracks.audio.isNotEmpty() || tracks.subtitle.isNotEmpty()) {
            lastAppliedAudioTrackPattern = effectiveAudioTrackPattern
            lastAppliedSubtitleTrackPattern = effectiveSubtitleTrackPattern
            automaticTracksApplied = true
        }
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
        val isRemote = media.url.startsWith("http", ignoreCase = true)
        val fileName = media.title.ifBlank {
            media.url.substringBefore('?').substringAfterLast('/')
        }.let {
            runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it)
        }
        val matcher = DanmakuMatcher(api)
        val animeContext = media.animeContext
        val trustedSubjectId = animeContext?.bangumiSubjectId?.takeIf { it > 0L }
        val directAnimeId = animeContext?.dandanplayAnimeId?.takeIf { it > 0L }
        val bangumiAnimeId = trustedSubjectId?.let { subjectId ->
            withContext(Dispatchers.IO) {
                matcher.resolveAnimeIdByBangumiSubject(
                    subjectId,
                    listOfNotNull(animeContext.seriesTitle, DanmakuMatcher.cleanSearchKeyword(fileName)),
                    animeContext.localSeasonNumber?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt(),
                )
            }
        }
        val expectedAnimeId = bangumiAnimeId ?: directAnimeId
        val identityConstrained = trustedSubjectId != null || directAnimeId != null
        val directEpisodeOrdinal = animeContext?.localEpisodeNumber
            ?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
            ?.toInt()
        val expectedShortcutEpisodeOrdinal = animeContext?.let { context ->
            directEpisodeOrdinal?.takeIf { context.bangumiEpisodeOffset != 0L }
        }
        if (bangumiAnimeId != null && directAnimeId != null && bangumiAnimeId != directAnimeId) {
            logger?.appEvent(
                "danmaku",
                "季度身份冲突：Bangumi 关联=$bangumiAnimeId，刮削记录=$directAnimeId，按 Bangumi 关联优先",
                LogLevel.WARN,
            )
        }
        // B-09: 读失败(异常)降级为 null, 不向 LaunchedEffect 抛; 重试仅针对"记录尚未写入"的 null。
        var playbackRecord = playbackRepository?.let { repo -> safeReadPlaybackRecord(repo, recordKey, logger) }
        if (playbackRepository != null && playbackRecord == null) {
            for (attempt in 0 until 10) {
                delay(100)
                playbackRecord = safeReadPlaybackRecord(playbackRepository, recordKey, logger)
                if (playbackRecord != null) break
            }
        }
        playbackRecord?.danmaku_episode_id?.takeIf {
            isDanmakuShortcutCompatible(
                savedAnimeId = playbackRecord.danmaku_anime_id,
                savedMatchMethod = playbackRecord.danmaku_match_method,
                expectedAnimeId = expectedAnimeId,
                identityConstrained = identityConstrained,
                savedEpisodeOrdinal = null,
                expectedEpisodeOrdinal = expectedShortcutEpisodeOrdinal,
            )
        }?.let { episodeId ->
            val record = requireNotNull(playbackRecord)
            danmakuEntries = withContext(Dispatchers.IO) { sourceProvider.fetch(episodeId) }
            currentEpisodeTitle = record.danmaku_episode_title.orEmpty()
            if (settings.danmakuShowMatchToast) {
                matchToast = "弹幕匹配方式：播放记录（${record.danmaku_anime_title.orEmpty()}）"
            }
            logger?.appEvent("danmaku", "播放记录命中 番=${record.danmaku_anime_title.orEmpty()}")
            return@LaunchedEffect
        }
        if (playbackRecord?.danmaku_episode_id != null && identityConstrained) {
            logger?.appEvent("danmaku", "忽略与当前季度身份不一致的旧播放记录", LogLevel.WARN)
        }

        val localHash = if (isRemote) null else computeDanmakuHash()
        val cacheKey = when {
            mediaServerPlayback != null -> recordKey
            isRemote -> media.url
            else -> localHash?.second
        }
        val cached = cacheKey?.let { manualMatchCacheRepository.load(it) }
        if (cached != null && isDanmakuShortcutCompatible(
                savedAnimeId = cached.animeId,
                savedMatchMethod = cached.matchMethod,
                expectedAnimeId = expectedAnimeId,
                identityConstrained = identityConstrained,
                savedEpisodeOrdinal = cached.episodeOrdinal,
                expectedEpisodeOrdinal = expectedShortcutEpisodeOrdinal,
            )
        ) {
            danmakuEntries = withContext(Dispatchers.IO) { sourceProvider.fetch(cached.episodeId) }
            currentEpisodeTitle = cached.episodeTitle
            if (settings.danmakuShowMatchToast) matchToast = "弹幕匹配方式：缓存命中（${cached.animeTitle}）"
            logger?.appEvent("danmaku", "缓存命中 番=${cached.animeTitle}")
            return@LaunchedEffect
        }
        if (cached != null && identityConstrained) {
            logger?.appEvent("danmaku", "忽略与当前季度身份不一致的旧文件缓存", LogLevel.WARN)
        }

        val matchConfig = DanmakuMatchConfig(
            tmdbIdMatchPattern = settings.tmdbIdMatchPattern,
            matchOrder = parseDanmakuMatchOrder(
                currentOverride.danmakuMatchPriority ?: settings.danmakuMatchPriority,
            ),
        )
        val result: DanmakuMatchResult? = withContext(Dispatchers.IO) {
            val hint = mediaServerPlayback?.plan?.danmakuHint
            val pathTmdbId = if (DanmakuMatchMethod.TMDB_PATH in matchConfig.matchOrder) {
                matcher.extractTmdbId(media.url, matchConfig.tmdbIdMatchPattern)
            } else {
                null
            }
            val structuredTmdbId = media.tmdbId ?: hint?.tmdbId
            if (structuredTmdbId != null && pathTmdbId != null && structuredTmdbId != pathTmdbId) {
                logger?.appEvent(
                    "danmaku",
                    "TMDB 元数据冲突：结构化=$structuredTmdbId，播放路径=$pathTmdbId，按结构化数据优先",
                )
            }
            val structuredSeason = resolveDanmakuSeasonHint(
                animeContext,
                media.seasonNumber,
                hint?.seasonNumber,
            )
            val structuredEpisode = resolveDanmakuEpisodeHint(
                animeContext,
                media.episodeNumber,
                hint?.episodeNumber,
            )
                // 已确认条目身份优先精确匹配; 失败(条目仲裁错选/集号超界)时回落完整优先级链,
                // 让 TMDB 定位与全系列集号覆盖兜底接手, 不在此短路成"未匹配"。
                (if (expectedAnimeId != null) {
                    matcher.matchByAnimeId(
                        animeId = expectedAnimeId,
                        fileName = fileName,
                        episodeHint = structuredEpisode.takeIf { directEpisodeOrdinal == null },
                        episodeOrdinalHint = directEpisodeOrdinal,
                        bangumiEpisodeOffset = animeContext?.bangumiEpisodeOffset ?: 0L,
                        matchMethod = if (bangumiAnimeId != null) {
                            DanmakuMatchMethod.BANGUMI_DATABASE
                        } else {
                            DanmakuMatchMethod.DANDANPLAY_DATABASE
                        },
                    )
                } else {
                    null
                }) ?: matcher.matchByPriority(
                    fileName = fileName,
                    urlOrPath = media.url,
                    config = matchConfig,
                    hashProvider = { localHash ?: computeDanmakuHash() },
                    databaseTmdbId = structuredTmdbId,
                    seasonHint = structuredSeason,
                    episodeHint = structuredEpisode,
                    episodeOrdinalHint = directEpisodeOrdinal,
                    bangumiEpisodeOffset = animeContext?.bangumiEpisodeOffset ?: 0L,
                )
        }

        if (result != null) {
            currentEpisodeTitle = result.episodeTitle
            val saveKey = when {
                mediaServerPlayback != null -> recordKey
                isRemote -> media.url
                else -> localHash?.second
            }
            saveKey?.let { key ->
                manualMatchCacheRepository.save(
                    key,
                    ManualMatchCacheEntry(
                        result.episodeId,
                        result.animeId,
                        result.animeTitle,
                        result.episodeTitle,
                        platformTimeMillis(),
                        result.matchMethod.name,
                        directEpisodeOrdinal,
                    ),
                )
            }
            playbackRepository?.let { repo ->
                safeRecordWrite(logger, "弹幕匹配写入") {
                    repo.updateDanmaku(
                        recordKey,
                        result.episodeId,
                        result.animeId,
                        result.animeTitle,
                        result.episodeTitle,
                        result.matchMethod.name,
                    )
                }
            }
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
    // B-09: 读/写失败均不向 LaunchedEffect 抛(读失败视为无记录, 写失败跳过继续播)。
    LaunchedEffect(engine, recordKey, resumeReady, retryToken) {
        val currentEngine = engine ?: return@LaunchedEffect
        val repository = playbackRepository ?: return@LaunchedEffect
        if (!resumeReady) return@LaunchedEffect
        val existing = safeReadPlaybackRecord(repository, recordKey, logger)
        val initialPosition = existing?.position_ms ?: currentEngine.position.value
        safeRecordWrite(logger, "初始化写入") {
            repository.upsertEntry(
                buildRecord(initialPosition, currentEngine.state.value.durationMs, 0L, existing),
            )
        }
        var lastPersistedPositionMs = initialPosition
        while (true) {
            delay(10_000)
            val currentState = currentEngine.state.value
            if (currentState.isTerminalPlaybackState()) return@LaunchedEffect
            val pos = currentEngine.position.value
            val dur = currentState.durationMs
            if (currentState.shouldPersistPeriodicPlayback(pos, lastPersistedPositionMs)) {
                safeRecordWrite(logger, "进度更新") {
                    repository.updatePosition(
                        recordKey,
                        pos,
                        (pos.toDouble() / dur).coerceIn(0.0, 1.0),
                        nextPlaybackWriteTimestamp(),
                    )
                }
                lastPersistedPositionMs = pos
            }
        }
    }

    DisposableEffect(media.url, releaseCoordinator) {
        onDispose {
            rightKeyLongPressJob?.cancel()
            rightKeyLongPressJob = null
            val currentEngine = engine
            val currentSubtitleLoader = siblingSubtitleLoader
            runCatching { currentEngine?.pause() }
            runCatching { currentEngine?.setMuted(true) }
            val currentPosition = currentEngine?.position?.value ?: 0L
            val finalEngineState = currentEngine?.state?.value
            val finalPosition = desktopFinalPlaybackPosition(
                currentPositionMs = currentPosition,
                playbackEnded = finalEngineState?.eof == true ||
                    finalEngineState?.status == PlaybackStatus.ENDED,
                lastValidPositionMs = lastValidPositionMs,
                lastValidDurationMs = lastValidDurationMs,
            )
            val finalDuration = finalEngineState?.durationMs?.takeIf { it > 0L }
                ?: lastValidDurationMs
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
            val finalFailed = finalEngineState?.status == PlaybackStatus.ERROR
            val finalMediaServerState = mediaServerPlayback?.let {
                currentMediaServerPlaybackState(finalPosition)
            }
            engine = null

            // 不能用组合 scope：组合销毁会取消它。进程级释放执行器会在数据库关闭前等待任务完成。
            // CR-066: finishPlayback(DB 写, 可阻塞 5s+ WAL checkpoint)与 destroy(native 句柄)分离提交,
            // 避免会话级 release worker 被 runBlocking finishPlayback 阻塞导致 destroy 队列背压、
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
            if (mediaServerReportCoordinator != null && finalMediaServerState != null) {
                val coordinator = mediaServerReportCoordinator
                recordExecutor {
                    runCatching {
                        runBlocking {
                            checkNotNull(withTimeoutOrNull(MEDIA_SERVER_STOP_TIMEOUT_MS) {
                                coordinator.reportStopped(finalMediaServerState, failed = finalFailed).getOrThrow()
                            }) { "Jellyfin Stopped 上报超时" }
                        }
                    }.onFailure { error -> logMediaServerReportFailure(error) }
                }
            }
            releaseCoordinator.release {
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
            mediaServerSeekReportGeneration++
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
                        mediaServerSeekReportGeneration++
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
            releaseCoordinator = releaseCoordinator,
            modifier = Modifier.fillMaxSize(),
            renderTargetKey = isFullscreen,
            sourceWidth = mediaInfo?.width ?: 0,
            sourceHeight = mediaInfo?.height ?: 0,
            sourceRotation = mediaInfo?.rotation ?: 0,
            retryToken = retryToken,
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
                        if (media.sourceKind == MediaSourceKind.WEBDAV && onReplayWebDav != null) {
                            onReplayWebDav()
                        } else {
                            initError = null
                            retryToken++
                        }
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
                    if (state.eof || state.status == PlaybackStatus.ENDED) {
                        if (mediaServerPlayback != null && onReplayMediaServer != null) {
                            onReplayMediaServer()
                        } else {
                            engine?.seekTo(0L)
                            engine?.play()
                        }
                    } else if (state.paused) {
                        engine?.play()
                    } else {
                        engine?.pause()
                    }
                    controlsInteraction++
                },
                onSeek = {
                    engine?.seekTo(it)
                    mediaServerSeekReportGeneration++
                },
                onSeekStarted = { controlsPinned = true },
                onSeekFinished = {
                    controlsPinned = false
                    controlsInteraction++
                },
                onToggleInfo = {
                    showInfoPanel = !showInfoPanel
                    controlsInteraction++
                },
                onCaptureScreenshot = {
                    val currentEngine = engine
                    if (currentEngine != null && !screenshotInProgress) {
                        screenshotInProgress = true
                        scope.launch {
                            val result = runSuspendCatching { captureDesktopVideoScreenshot(currentEngine) }
                            matchToast = result.fold(
                                onSuccess = { "截图已保存：$it" },
                                onFailure = { "截图失败：${it.message ?: "未知错误"}" },
                            )
                            screenshotInProgress = false
                        }
                    }
                    controlsInteraction++
                },
                screenshotEnabled = engine != null && (
                    state.status == PlaybackStatus.READY ||
                        state.status == PlaybackStatus.PLAYING ||
                        state.status == PlaybackStatus.PAUSED ||
                        state.status == PlaybackStatus.ENDED
                    ),
                screenshotInProgress = screenshotInProgress,
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
        val initialKeyword = remember(media.url, media.title, media.animeContext?.seriesTitle) {
            val fallback = media.title.ifBlank { media.url.substringAfterLast('/') }.let {
                runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it)
            }
            resolveManualDanmakuSearchKeyword(media.animeContext?.seriesTitle, fallback)
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
                            val isRemote = targetUrl.startsWith("http", ignoreCase = true)
                            val cacheKey = when {
                                mediaServerPlayback != null -> recordKey
                                isRemote -> targetUrl
                                else -> computeDanmakuHash()?.second
                            }
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
                                            DanmakuMatchMethod.MANUAL.name,
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
private const val MEDIA_SERVER_STOP_TIMEOUT_MS = 10_000L

/** 媒体服务器计划是带凭据播放的安全边界；不一致时必须阻止引擎创建与 URL 加载。 */
internal fun desktopMediaServerPlanMismatch(
    media: PlayableMedia,
    plan: MediaServerPlaybackPlan?,
    config: PlayerConfig,
): String? = plan?.let {
    when {
        config.httpRedirectPolicy != HttpRedirectPolicy.DENY -> "媒体服务器播放必须拒绝 HTTP 重定向"
        it.url != media.url || it.headers != media.headers -> "媒体服务器播放参数与计划不一致"
        it.vendor.sourceKind != media.sourceKind -> "媒体服务器来源类型与计划不一致"
        media.mediaKey != it.historyMediaKey -> "媒体服务器播放必须使用稳定媒体键"
        else -> null
    }
}

internal data class DesktopMediaServerSubtitleLoad(
    val subtitle: MediaServerExternalSubtitle,
    val select: Boolean,
)

/** 非默认外挂先缓存，服务端默认外挂最后加载并选中；没有可用默认项时不抢用户或内封选轨。 */
internal fun desktopMediaServerSubtitleLoads(
    plan: MediaServerPlaybackPlan,
): List<DesktopMediaServerSubtitleLoad> {
    val defaultIndex = plan.defaultSubtitleStreamIndex
    val defaultSubtitle = plan.externalSubtitles.firstOrNull { it.streamIndex == defaultIndex }
    if (defaultSubtitle == null) {
        return plan.externalSubtitles.map { DesktopMediaServerSubtitleLoad(it, select = false) }
    }
    return plan.externalSubtitles
        .filterNot { it.streamIndex == defaultSubtitle.streamIndex }
        .map { DesktopMediaServerSubtitleLoad(it, select = false) } +
        DesktopMediaServerSubtitleLoad(defaultSubtitle, select = true)
}

internal fun desktopPlaybackRecordUrl(url: String, isMediaServer: Boolean): String =
    if (isMediaServer) "" else url

internal fun desktopResumePosition(
    recordPositionMs: Long?,
    recordCompleted: Boolean,
    initialPositionMs: Long,
): Long? = recordPositionMs
    ?.takeIf { !recordCompleted && it > 5_000L }
    ?: initialPositionMs.takeIf { it > 5_000L }

internal fun desktopFinalPlaybackPosition(
    currentPositionMs: Long,
    playbackEnded: Boolean,
    lastValidPositionMs: Long,
    lastValidDurationMs: Long,
): Long = when {
    currentPositionMs > 0L -> currentPositionMs
    playbackEnded -> lastValidDurationMs.takeIf { it > 0L } ?: lastValidPositionMs.coerceAtLeast(0L)
    else -> currentPositionMs.coerceAtLeast(0L)
}

/**
 * B-09(桌面版): 播放器内的记录读写统一经以下防护。SQLite 异常(磁盘满/DB 损坏/驱动错误)
 * 不向 LaunchedEffect 传播——异常直达 Recomposer 会让 Compose Desktop 无处理器而进程退出。
 * 读失败视为无记录继续播; 写失败跳过, 播放不因记录子系统故障中断。
 * runSuspendCatching 正确重抛 CancellationException, 不误吞协程取消。
 */
internal suspend fun safeReadPlaybackRecord(
    repository: PlaybackRecordRepository,
    recordKey: String,
    logger: AppLogger?,
): PlaybackRecord? = runSuspendCatching { repository.getByMediaKey(recordKey) }.getOrElse { error ->
    logger?.appEvent(
        "player",
        "桌面播放记录读取失败, 视为无: ${error.javaClass.simpleName}: ${error.message}",
        LogLevel.WARN,
    )
    null
}

internal suspend fun safeRecordWrite(logger: AppLogger?, operation: String, write: suspend () -> Unit) {
    runSuspendCatching { write() }.onFailure { error ->
        logger?.appEvent(
            "player",
            "桌面播放记录${operation}失败, 跳过: ${error.javaClass.simpleName}: ${error.message}",
            LogLevel.WARN,
        )
    }
}

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
