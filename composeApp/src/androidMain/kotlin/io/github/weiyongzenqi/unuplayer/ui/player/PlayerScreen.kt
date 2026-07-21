package io.github.weiyongzenqi.unuplayer.ui.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import io.github.weiyongzenqi.unuplayer.core.player.MpvPlayerEngine
import io.github.weiyongzenqi.unuplayer.core.player.AndroidPlayerLifecycleTasks
import io.github.weiyongzenqi.unuplayer.core.player.AndroidPlayerSessionCloseLease
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepositoryImpl
import io.github.weiyongzenqi.unuplayer.playback.nextPlaybackWriteTimestamp
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.render.DanmakuLayer
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchConfig
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatcher
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchResult
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayApi
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayProxyConfig
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplaySourceProvider
import io.github.weiyongzenqi.unuplayer.danmaku.source.ManualMatchCacheEntry
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.danmaku.source.calcDanmakuHash
import io.github.weiyongzenqi.unuplayer.danmaku.source.calcDanmakuHashFromContentUri
import io.github.weiyongzenqi.unuplayer.danmaku.source.remoteHashForUrl
import io.github.weiyongzenqi.unuplayer.domain.EpisodeNumberExtractor
import io.github.weiyongzenqi.unuplayer.core.player.PlayerConfig
import io.github.weiyongzenqi.unuplayer.core.player.PlaybackStatus
import io.github.weiyongzenqi.unuplayer.core.player.HdrMode
import io.github.weiyongzenqi.unuplayer.domain.DEFAULT_AUDIO_TRACK_PATTERN
import io.github.weiyongzenqi.unuplayer.domain.DEFAULT_SUBTITLE_TRACK_PATTERN
import io.github.weiyongzenqi.unuplayer.platform.AndroidPlatformInfo
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import kotlin.math.abs

/**
 * 播放器页。
 *
 * 管理 MpvPlayerEngine 生命周期(create/init/destroy) + SurfaceView + 控制层。
 *
 * engine 生命周期(见 DESIGN.md §7.6):
 * - create + init: 首次进入时一次性完成
 * - destroy: 离开页面时在 IO 协程调用(阻塞)
 *
 * @param recognizeAnime 番剧识别开关(P1-7): false=纯播放器模式, 不触发任何番剧相关逻辑。
 *        P2 实现番剧元数据/弹幕时, 在此分支控制是否发起 Bangumi/弹弹play 请求。
 * @param hdrMode HDR 模式设置。用于动态切换 Activity window colorMode:
 *        HDR_PASSTHROUGH(或 AUTO 且设备支持 HDR)时, 检测到 PQ/HLG 片源把 window 切 COLOR_MODE_HDR,
 *        让 SurfaceFlinger 按 HDR 合成; SDR 片源或非直通模式保持 DEFAULT。
 *        注意: libmpv-android native 层未调 ANativeWindow_setBuffersDataSpace,
 *        setColorMode 是第一步补偿(零成本), 不够再升级自建 JNI .so(见 DESIGN.md §11.3)。
 */
@Composable
fun PlayerScreen(
    playUrl: String,
    playTitle: String = "",
    /** 原始 content://(引擎内每次 load 转 fdclose://, 此处用 ContentResolver 读前 16MB 算弹幕哈希)。非 content 为 null。 */
    contentUri: String? = null,
    /** 播放记录稳定 key(source 层 fill=导航位置 webdav/local; 外部拉起 null 时 fallback url/contentUri)。 */
    mediaKey: String? = null,
    recognizeAnime: Boolean = true,
    hdrMode: HdrMode = HdrMode.AUTO,
    longPressSpeed: Float = 2f,
    hwdec: String = "auto-copy",
    audioOutput: String = "audiotrack",
    cacheSize: Int = 32,
    cacheSecs: Int = 20,
    /** 允许 TLS 降级(系统 CA 不可用时回退 tls-verify=no)。init-only, 改了需重进播放器。 */
    allowTlsInsecure: Boolean = false,
    /** 播放用 HTTP 头(WebDAV basic auth 的 Authorization), init 前设 http-header-fields。 */
    playHeaders: Map<String, String> = emptyMap(),
    /** 日志器(可选, 设置开启时传入; engine init 时设 log-level + 注入 LogObserver)。 */
    appLogger: io.github.weiyongzenqi.unuplayer.platform.AppLogger? = null,
    logLevel: String = "info",
    // 字幕默认设置(来自 SettingsState, 播放时应用; 字幕面板可临时覆盖)
    subtitleFont: String = "",
    subtitleFontDir: String? = null,
    subtitleScale: Float = 1.0f,
    subtitleColor: String = "#FFFFFFFF",
    subtitleBorderSize: Float = 2.0f,
    subtitleBold: Boolean = false,
    subtitleStyleOverride: String = "force",
    defaultSubtitleTrackPattern: String = DEFAULT_SUBTITLE_TRACK_PATTERN,
    defaultAudioTrackPattern: String = DEFAULT_AUDIO_TRACK_PATTERN,
    // 倍速预设档(播放设置弹层倍速页用), 来自 SettingsState.speedPresets
    speedPresets: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f),
    // 预测性返回跟手动画开关(Android 14+), 关则用普通 BackHandler
    predictiveBack: Boolean = true,
    // 弹幕(凭证 + 匹配配置 + 渲染配置)
    danmakuConfig: DanmakuConfig = DanmakuConfig(),
    onDanmakuConfigChange: (DanmakuConfig) -> Unit = {},
    danmakuShowMatchToast: Boolean = false,
    onDanmakuMatchToastChange: (Boolean) -> Unit = {},
    dandanplayAppId: String = "",
    dandanplayAppSecret: String = "",
    dandanplayUseProxy: Boolean = false,
    danmakuMatchConfig: DanmakuMatchConfig = DanmakuMatchConfig(false, "", true),
    // 手动匹配 per-file 记忆缓存(可选; null=不缓存, 每次都弹手动)
    onLoadManualMatch: (suspend (String) -> ManualMatchCacheEntry?)? = null,
    onSaveManualMatch: (suspend (String, ManualMatchCacheEntry) -> Unit)? = null,
    // 同目录外挂字幕加载器(可选; null=不支持, 如外部拉起无 mediaKey/contentUri 场景)
    siblingSubtitleLoader: io.github.weiyongzenqi.unuplayer.core.media.SiblingSubtitleLoader? = null,
    // 无内封字幕时自动加载同目录同名字幕开关
    autoLoadSiblingSubtitle: Boolean = true,
    // 同目录字幕语言偏好: sc=简中优先 / tc=繁中优先 / none=不限(严格同名)
    subtitleLanguagePreference: String = "sc",
    // 基础匹配失败时自动拉起手动匹配弹窗开关(关后需用户手动点按钮)
    danmakuAutoManualMatch: Boolean = true,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tempFileSession = remember { PlaybackTempFileSession(context.cacheDir) }
    val recordWriteGate = remember { PlaybackRecordWriteGate() }
    val sessionCloseLease = remember { mutableStateOf<AndroidPlayerSessionCloseLease?>(null) }

    // 弹弹play API 实例(凭证/代理配置变了才重建): 自动匹配 + 手动匹配共用, 避免每次 LaunchedEffect new。
    // 代理模式: 经代理转发(端点 + API Key 内置于 DandanplayProxyConfig 且混淆存储, 签名在服务端)。
    // 直连模式: appId/secret 签名直连弹弹。两者皆未配置返回 null, 弹幕模块短路。
    val dandanplayApi = remember(dandanplayAppId, dandanplayAppSecret, dandanplayUseProxy) {
        if (dandanplayUseProxy) {
            DandanplayApi(baseUrl = DandanplayProxyConfig.proxyUrl(), proxyApiKey = DandanplayProxyConfig.apiKey())
        } else if (dandanplayAppId.isNotBlank()) {
            DandanplayApi(dandanplayAppId, dandanplayAppSecret)
        } else null
    }

    // 当前剧集标题(匹配/缓存命中时设置, 顶栏主标题右侧小字显示)
    var currentEpisodeTitle by remember { mutableStateOf("") }

    // 算文件前 16MB MD5 + fileSize(弹幕哈希)。抽出来复用: LaunchedEffect 查缓存 + 哈希回落,
    // 手动匹配 onConfirm 存缓存, 都用同一份 hash, 避免重复算。
    // 缓存 key 用 hash(文件指纹稳定); 本地 playUrl 保持 content://, 由引擎内部临时转 fdclose://。
    suspend fun computeHash(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        val authHeader = playHeaders["Authorization"] ?: ""
        when {
            playUrl.startsWith("http", ignoreCase = true) -> remoteHashForUrl(playUrl, authHeader)
            (playUrl.startsWith("content://", ignoreCase = true) || playUrl.startsWith("fd://")) &&
                !contentUri.isNullOrBlank() ->
                calcDanmakuHashFromContentUri(context.contentResolver, android.net.Uri.parse(contentUri))
            else -> {
                val path = playUrl.removePrefix("file://")
                val file = java.io.File(path)
                if (file.exists()) Pair(file.length(), calcDanmakuHash(path)) else null
            }
        }
    }

    // 平台信息(查 HDR 能力, 用于 hdrMode=AUTO 决策)
    val platformInfo = remember { AndroidPlatformInfo(context.applicationContext) }

    // engine 单例, 跨重组保持
    val engine = remember {
        MpvPlayerEngine(
            context = context.applicationContext,
            platformInfo = platformInfo,
            mainDispatcher = Dispatchers.Main,
            logger = appLogger,
        )
    }

    // 续播 seek 完成标记(声明于此: init 协程需在 play 前等续播 seek, 见下; 节流协程也用它协调)
    var resumeReady by remember { mutableStateOf(false) }

    // 初始化 + 加载 URL；字幕样式随 PlayerConfig 在 native init 后、load 前可靠应用，并随 HDR reinit 重放。
    // 其余设置使用 hwdec/ao/cacheSize/hdrMode + 播放头构造 PlayerConfig(非默认!)
    // 整段切到 IO: applyOptions 会同步生成系统 CA bundle(遍历上百张证书 + Base64 + 写文件),
    // 且 MPVLib.create/init 是阻塞 native 初始化, 在主线程跑会卡顿/ANR。
    // load/play 只是把命令入队, 切到 IO 不影响时序; attachSurface 仍由 SurfaceView 回调触发,
    // engine 内 pendingSurface 兜底 init 前就绪的 surface。
    LaunchedEffect(Unit) {
        // 三类最终任务先取得固定容量许可；等待可取消，且许可到位前绝不创建 native。
        sessionCloseLease.value = AndroidPlayerLifecycleTasks.acquireSessionCloseLease()
        withContext(Dispatchers.IO) {
            engine.init(PlayerConfig(
                hwdec = hwdec,
                audioOutput = audioOutput,
                cacheSize = cacheSize,
                cacheSecs = cacheSecs,
                hdrMode = hdrMode,
                allowTlsInsecure = allowTlsInsecure,
                httpHeaders = playHeaders,
                logLevel = logLevel,
                subtitleFont = subtitleFont,
                subtitleFontDir = subtitleFontDir,
                subtitleScale = subtitleScale,
                subtitleColor = subtitleColor,
                subtitleBorderSize = subtitleBorderSize,
                subtitleBold = subtitleBold,
                subtitleStyleOverride = subtitleStyleOverride,
            ))
            appLogger?.appEvent("player", "load title=$playTitle")
            engine.load(playUrl)
            // 等 READY(FILE_LOADED)后, 等续播 seek 完成再 play(): seek 须在 play 前发出, mpv 处于
            // READY(pause)态 seek 稳定生效; 若 play 后才 seek(PLAYING 态), seek 与初始播放冲突会失效
            // (已踩坑: 续播从头播 / seek 后卡加载没画面)。无记录时续播很快置 resumeReady, 不阻塞。
            engine.state.first {
                it.status == PlaybackStatus.READY || it.status == PlaybackStatus.PAUSED || it.status == PlaybackStatus.ERROR
            }
            if (engine.state.value.status != PlaybackStatus.ERROR) {
                if (!resumeReady) {
                    kotlinx.coroutines.withTimeoutOrNull(20000) { snapshotFlow { resumeReady }.first { it } }
                }
                engine.play()
            }
        }
    }

    // === 播放记录: 续播 + 进度节流写入(3b) ===
    val recordRepo = remember { PlaybackRecordRepositoryImpl.get(context) }
    val isWebDavMedia = playUrl.startsWith("http", ignoreCase = true)
    // recordKey: 优先用 source 层算的"导航位置"key(webdav:{connId}:{path} / local:{contentUri});
    // 外部 Intent 拉起无导航上下文 -> mediaKey=null, fallback 用 url/contentUri(仍可记, 仅与浏览进入的记录不互通)
    val recordKey = mediaKey ?: if (isWebDavMedia) playUrl else (contentUri ?: playUrl)
    // resumeReady 已于上方 init 前声明(init 协程协调 play 时机用); 节流协程也等它置 true 再建记录,
    // 避免抢在续播 seek 前用 position=0 覆盖已有记录。

    // 构造播放记录条目(整行 upsert 用)。
    // existing 非空时弹幕字段从其带回, 避免节流首次整行 upsert 覆盖 3c 存的弹幕匹配信息。
    fun buildRecord(pos: Long, dur: Long, completed: Long, existing: PlaybackRecord? = null): PlaybackRecord {
        val progress = if (dur > 0) (pos.toDouble() / dur).coerceIn(0.0, 1.0) else 0.0
        return PlaybackRecord(
            id = 0, media_key = recordKey,
            source_kind = if (isWebDavMedia) "WEBDAV" else "LOCAL",
            url = playUrl, content_uri = contentUri,
            title = playTitle.ifBlank { playUrl.substringAfterLast('/') },
            position_ms = pos, duration_ms = dur, watch_progress = progress, is_completed = completed,
            danmaku_episode_id = existing?.danmaku_episode_id,
            danmaku_anime_id = existing?.danmaku_anime_id,
            danmaku_anime_title = existing?.danmaku_anime_title,
            danmaku_episode_title = existing?.danmaku_episode_title ?: currentEpisodeTitle.ifBlank { null },
            danmaku_match_method = existing?.danmaku_match_method,
            last_played_at = nextPlaybackWriteTimestamp(existing?.last_played_at ?: Long.MIN_VALUE), sync_status = 0, sync_version = 0,
        )
    }

    // 续播: 查记录, 就绪后 seek 到上次位置(未完成且进度>5s 才续)。seek 完成(或确定无记录)置 resumeReady。
    // 抽成可复调用函数(P3②): 首次进入由 LaunchedEffect(playUrl) 驱动; 错误页 onRetry 时 key 未变
    // LaunchedEffect 不重跑, 手动复用本函数续播, 否则重试 load 把 position 归 0 从头播。
    // 首次进入行为与分块 review 逻辑不变(原样抽出)。
    suspend fun resumeSeekFromRecord() {
        resumeReady = false
        val record = AndroidPlayerLifecycleTasks.runSerialized(appLogger, "读取续播记录") {
            recordRepo.getByMediaKey(recordKey)
        }
        if (record != null && record.is_completed == 0L && record.position_ms > 5_000) {
            // polling 等就绪(参考 nipaplay): duration>0 且 video 已 reconfig(width>0)再 seek,
            // 避免冷启动 demux/video 未完成时 seek 撞上失效(已踩坑: seek 延迟数秒 + 视频崩 0x0)。
            // FILE_LOADED 时 demux/video reconfig 尚未完成, 那时 seek 会与初始 reconfig 冲突。
            // 纯音频(无 video 轨, width 恒 0): 3s 后降级为 duration>0 即 seek, 不死等 width。
            var attempts = 0
            while (engine.state.value.status != PlaybackStatus.ERROR && attempts < 150) {
                val mi = engine.mediaInfo.value
                val durOk = engine.state.value.durationMs > 0
                if (durOk && mi != null && mi.width > 0) break
                if (durOk && attempts >= 30) break  // 3s 降级: 纯音频/慢 reconfig
                delay(100)
                attempts++
            }
            if (engine.state.value.status != PlaybackStatus.ERROR) {
                engine.seekTo(record.position_ms)
                appLogger?.appEvent("player", "续播 seek=${record.position_ms}ms", LogLevel.INFO)
            }
        }
        resumeReady = true
    }

    LaunchedEffect(playUrl) { resumeSeekFromRecord() }

    // 进度节流写入: 就绪后 + 续播 seek 完成后, upsert 建记录(含 duration/title), 之后每 10s updatePosition(单行轻写);
    // 退出 onDispose 用 finishPlayback 存位置+完成态(不碰弹幕字段)。
    LaunchedEffect(playUrl) {
        engine.state.first {
            it.status == PlaybackStatus.READY || it.status == PlaybackStatus.PAUSED || it.status == PlaybackStatus.PLAYING || it.status == PlaybackStatus.ERROR
        }
        if (engine.state.value.status == PlaybackStatus.ERROR) return@LaunchedEffect
        // 等续播 seek 完成: 无记录时续促很快置 true; 有记录 seek 完置 true。
        // 不等的话节流 upsert(position=0) 抢在续播前覆盖已有记录 -> 从头播(本 bug 根因, 已修)。
        if (!resumeReady) snapshotFlow { resumeReady }.first { it }
        // 重查记录: 有则保留其 position(续播 seek 目标)与弹幕匹配, 不被 position=0 覆盖; 无则建新记录。
        val existing = AndroidPlayerLifecycleTasks.runSerialized(appLogger, "读取播放记录") {
            recordRepo.getByMediaKey(recordKey)
        }
        val initPos = existing?.position_ms ?: engine.position.value
        val initialRecord = buildRecord(initPos, engine.state.value.durationMs, 0L, existing)
        AndroidPlayerLifecycleTasks.runSerialized(appLogger, "初始化播放记录") {
            recordRepo.upsert(initialRecord)
        }
        while (true) {
            delay(10_000)
            val pos = engine.position.value
            val dur = engine.state.value.durationMs
            if (dur > 0 && pos > 0) {
                val recordedAt = nextPlaybackWriteTimestamp()
                val submitted = recordWriteGate.submitIfOpen {
                    val progress = (pos.toDouble() / dur).coerceIn(0.0, 1.0)
                    AndroidPlayerLifecycleTasks.submitRecord(appLogger, "播放进度") {
                        runCatching {
                            runBlocking {
                                recordRepo.updatePosition(recordKey, pos, progress, recordedAt)
                            }
                        }.onFailure { error ->
                            appLogger?.appEvent(
                                "player",
                                "保存播放进度失败: ${error.javaClass.simpleName}: ${error.message}",
                                LogLevel.WARN,
                            )
                        }
                    }
                }
                if (!submitted) return@LaunchedEffect
            }
        }
    }

    val tracks by engine.tracks.collectAsStateWithLifecycle()
    // 播放设置弹层(字幕/音轨/倍速 分页): 轨道切换 + 外挂加载 + 临时样式调整
    var showSettingsSheet by remember { mutableStateOf(false) }
    // 用户手动选轨标记: 自动选轨仅在未手动选过且无已选轨道时触发, 避免覆盖用户选择(含"关闭字幕")
    var userPickedSubtitle by remember { mutableStateOf(false) }
    var userPickedAudio by remember { mutableStateOf(false) }
    // 临时覆盖的样式(默认来自设置, 面板内调时改这个, 实时应用不写回设置)
    var subScale by remember { mutableFloatStateOf(subtitleScale) }
    var subBorder by remember { mutableFloatStateOf(subtitleBorderSize) }
    var subBold by remember { mutableStateOf(subtitleBold) }

    // 外挂字幕 SAF 选择器: 选 .srt/.ass/.ssa, 拷到 cache 再 sub-add 喂 mpv
    val pickSubLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runSuspendCatching {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "subtitle"
                    val ext = name.substringAfterLast('.', "srt").lowercase()
                    val lease = tempFileSession.newFile("sub_import", ext)
                    try {
                        checkNotNull(context.contentResolver.openInputStream(uri)) { "无法打开字幕文件" }.use { input ->
                            lease.file.outputStream().use { output -> input.copyTo(output) }
                        }
                        lease.file to name
                    } finally {
                        lease.close()
                    }
                }.onSuccess { (tmp, name) ->
                    engine.addExternalSubtitle(tmp.absolutePath, title = name.substringBeforeLast('.'))
                } // 失败静默: 无 logger 实例, 不走 logcat; UI 层字幕未加载即用户可见反馈
            }
        }
    }

    // 自动选偏好语言字幕轨: 内置字幕轨到达后, 按偏好语言正则匹配 title+lang 自动切。
    // 仅用户手动选过(含"关闭字幕")才不抢; mpv 自带的默认选轨可以覆盖——否则 mpv 默认选了
    // 英文字幕时, 用户偏好中文也不会切, 偏好设置形同虚设。
    LaunchedEffect(tracks.subtitle, defaultSubtitleTrackPattern) {
        val pattern = defaultSubtitleTrackPattern.trim()
        if (pattern.isEmpty() || userPickedSubtitle) return@LaunchedEffect
        val match = tracks.subtitle.firstOrNull { it.matchesPattern(pattern) } ?: return@LaunchedEffect
        // 已选中且就是偏好轨, 无需重复切
        if (!match.selected) {
            engine.setSubtitleTrack(match.id)
            appLogger?.appEvent("player", "自动选字幕轨 id=${match.id} title=${match.title}", LogLevel.INFO)
        }
    }
    // 自动选偏好语言音轨: 同字幕逻辑, 默认偏好日文(二次元动漫)。
    LaunchedEffect(tracks.audio, defaultAudioTrackPattern) {
        val pattern = defaultAudioTrackPattern.trim()
        if (pattern.isEmpty() || userPickedAudio) return@LaunchedEffect
        val match = tracks.audio.firstOrNull { it.matchesPattern(pattern) } ?: return@LaunchedEffect
        if (!match.selected) {
            engine.setAudioTrack(match.id)
            appLogger?.appEvent("player", "自动选音轨 id=${match.id} title=${match.title}", LogLevel.INFO)
        }
    }
    // 运行时热切换: 设置改了, 当前播放的 engine 同步切换(无需重新进播放器)
    LaunchedEffect(hwdec) { engine.setHardwareDecoding(hwdec) }
    LaunchedEffect(audioOutput) { engine.setAudioOutput(audioOutput) }
    LaunchedEffect(hdrMode) { engine.setHdrMode(hdrMode) }
    // cacheSize 需要重新 init 才生效, 运行时只能提示
    PlayerReleaseEffect(
        engine = engine,
        appLogger = appLogger,
        recordRepo = recordRepo,
        recordKey = recordKey,
        tempFileSession = tempFileSession,
        recordWriteGate = recordWriteGate,
        sessionCloseLease = sessionCloseLease,
    )

    val state by engine.state.collectAsStateWithLifecycle()
    val mediaInfo by engine.mediaInfo.collectAsStateWithLifecycle()

    // 弹幕数据加载: 视频 READY/PLAYING 后, DanmakuMatcher 自动匹配 + fetch 拉弹幕。
    // recognizeAnime 关 / 凭证空 / 弹幕关时不加载(entries 空 -> DanmakuLayer 不渲染)。
    var danmakuEntries by remember { mutableStateOf<List<DanmakuEntry>>(emptyList()) }
    // 匹配方式气泡提醒(开启时, 每次匹配到弹幕弹 2s 小气泡显示匹配方式)
    var matchToast by remember { mutableStateOf<String?>(null) }
    // 手动匹配弹幕对话框(弹幕页按钮 或 自动匹配失败时主动触发)
    var showManualMatchDialog by remember { mutableStateOf(false) }
    LaunchedEffect(matchToast) {
        if (matchToast != null) { kotlinx.coroutines.delay(2000); matchToast = null }
    }
    // 弹幕数据加载: key 仅 playUrl(不 key state.status) -> 状态抖动(READY/PLAYING/BUFFERING 闪烁)
    // 不会取消正在跑的 fetch。内部等播放器就绪后再匹配+拉取, 每个 playUrl 只加载一次。
    LaunchedEffect(playUrl) {
        if (!recognizeAnime || !danmakuConfig.enabled) return@LaunchedEffect
        if (danmakuEntries.isNotEmpty()) return@LaunchedEffect  // 已加载, 不重复
        val api = dandanplayApi ?: return@LaunchedEffect  // 凭证空 -> api null -> 不加载
        // 等播放器就绪(READY/PLAYING); 出错则放弃。engine.state.first 挂起到首个匹配值。
        val st = engine.state.first {
            it.status == PlaybackStatus.READY || it.status == PlaybackStatus.PAUSED || it.status == PlaybackStatus.PLAYING || it.status == PlaybackStatus.ERROR
        }
        if (st.status == PlaybackStatus.ERROR) return@LaunchedEffect
        if (danmakuEntries.isNotEmpty()) return@LaunchedEffect  // double-check(等就绪期间可能已被填)

        // 先查播放记录: 有 danmaku_episode_id 直接套用(省 hash 计算 + 网络匹配), 跳过三级匹配
        val pbRecord = withContext(Dispatchers.IO) { recordRepo.getByMediaKey(recordKey) }
        if (pbRecord?.danmaku_episode_id != null) {
            val entries = withContext(Dispatchers.IO) {
                runSuspendCatching { DandanplaySourceProvider(api).fetch(pbRecord.danmaku_episode_id) }
                    .getOrElse { emptyList() }
            }
            danmakuEntries = entries
            currentEpisodeTitle = pbRecord.danmaku_episode_title ?: ""
            if (danmakuShowMatchToast) matchToast = "弹幕匹配方式：播放记录（${pbRecord.danmaku_anime_title ?: ""}）"
            appLogger?.appEvent("danmaku", "播放记录命中 番=${pbRecord.danmaku_anime_title}", LogLevel.INFO)
            return@LaunchedEffect
        }

        // 分流: WebDAV(http) 与 本地(file/content) 走不同流程, 缓存 key 也不同。
        // WebDAV: playUrl 缓存(不算 hash) -> tmdb -> hash 匹配(此时才 Range GET 16MB) -> 失败弹手动
        // 本地:   算 hash(本地快) -> hash 缓存 -> hash 匹配 -> 失败弹手动
        // (本地使用文件 hash 做稳定 key; WebDAV URL 稳定, 用 playUrl 省 hash)
        val isWebDav = playUrl.startsWith("http", ignoreCase = true)
        val fileName = playTitle.ifBlank { playUrl.substringAfterLast('/') }.let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        }
        // 本地先算 hash(查缓存 + hash 匹配 + 存缓存共用); WebDAV 暂不算(查缓存用 playUrl)
        val localHash = if (isWebDav) null else computeHash()
        // 1. 查缓存(WebDAV: playUrl; 本地: hash)
        val cacheKey = if (isWebDav) playUrl else localHash?.second
        val cached = cacheKey?.let { k ->
            withContext(Dispatchers.IO) {
                runSuspendCatching { onLoadManualMatch?.invoke(k) }.getOrNull()
            }
        }
        if (cached != null) {
            val entries = withContext(Dispatchers.IO) {
                runSuspendCatching { DandanplaySourceProvider(api).fetch(cached.episodeId) }
                    .getOrElse { emptyList() }
            }
            danmakuEntries = entries
            currentEpisodeTitle = cached.episodeTitle
            if (danmakuShowMatchToast) matchToast = "弹幕匹配方式：缓存命中（${cached.animeTitle}）"
            appLogger?.appEvent("danmaku", "缓存命中 key=${cacheKey.take(8)} 番=${cached.animeTitle}", LogLevel.INFO)
            return@LaunchedEffect
        }
        // 2. 自动匹配(WebDAV: tmdb -> hash; 本地: hash)。去掉文件名搜索(取首结果易错, 失败弹手动更准)
        val result = withContext(Dispatchers.IO) {
            runSuspendCatching {
                val matcher = DanmakuMatcher(api)
                val sourceProvider = DandanplaySourceProvider(api)
                if (isWebDav) {
                    // tmdb 快速匹配(从 URL/文件名提 tmdbId; 不需 hash)
                    var r: DanmakuMatchResult? = null
                    if (danmakuMatchConfig.tmdbIdQuickMatch) {
                        val tmdbId = matcher.extractTmdbId(playUrl, danmakuMatchConfig.tmdbIdMatchPattern)
                        if (tmdbId != null) {
                            val season = EpisodeNumberExtractor.extractSeason(fileName)
                            r = matcher.matchByTmdb(tmdbId, fileName, season)
                        }
                    }
                    // hash 匹配(此时才算 hash, Range GET 16MB; 仅 tmdb 未命中才走)
                    if (r == null && danmakuMatchConfig.hashFallback) {
                        val hi = computeHash()
                        if (hi != null) r = sourceProvider.match(fileName, hi.second, hi.first)
                    }
                    r
                } else {
                    // 本地: hash 匹配(复用 localHash)
                    if (localHash != null && danmakuMatchConfig.hashFallback)
                        sourceProvider.match(fileName, localHash.second, localHash.first) else null
                }
            }.getOrNull()
        }
        if (result != null) {
            currentEpisodeTitle = result.episodeTitle
            // 存缓存(WebDAV: playUrl; 本地: hash 复用 localHash)
            val saveKey = if (isWebDav) playUrl else localHash?.second
            saveKey?.let { k ->
                onSaveManualMatch?.invoke(k, ManualMatchCacheEntry(result.episodeId, result.animeId, result.animeTitle, result.episodeTitle, platformTimeMillis()))
            }
            // 存播放记录(下次套用省 hash+网络; media_key=contentUri/url 与缓存 key 互补)。
            // B11: 走与初始 upsert 同一序列化记录队列(recordAdmission FIFO, runSerialized); 旧实现裸
            // withContext(IO) 可能与初始整行 INSERT OR REPLACE 并行, 陈旧快照把刚写入的弹幕匹配字段擦除。
            // 队列饱和时 runSerialized 抛 RejectedExecutionException, 由 runSuspendCatching 吞掉(同原静默语义, 不新起线程/scope)。
            runSuspendCatching {
                AndroidPlayerLifecycleTasks.runSerialized(appLogger, "写弹幕匹配记录") {
                    recordRepo.updateDanmaku(recordKey, result.episodeId, result.animeId, result.animeTitle, result.episodeTitle, result.matchMethod.name)
                }
            }
            danmakuEntries = withContext(Dispatchers.IO) {
                runSuspendCatching { DandanplaySourceProvider(api).fetch(result.episodeId) }
                    .getOrElse { emptyList() }
            }
            appLogger?.appEvent("danmaku", "匹配命中 方式=${result.matchMethod} 番剧=${result.animeTitle} 集=${result.episodeTitle}", LogLevel.INFO)
            if (danmakuShowMatchToast) {
                matchToast = "弹幕匹配方式：${matchMethodLabel(result.matchMethod)}（${result.animeTitle}）"
            }
        } else {
            // 自动匹配失败 -> 开关开才主动弹手动匹配(关后需用户手动点按钮)
            appLogger?.appEvent("danmaku", "未匹配 文件名=$fileName, ${if (danmakuAutoManualMatch) "弹手动匹配" else "自动拉起已关"}", LogLevel.WARN)
            if (danmakuShowMatchToast) matchToast = "弹幕未匹配, 建议手动匹配"
            if (danmakuAutoManualMatch) showManualMatchDialog = true
        }
    }

    // 同目录外挂字幕自动加载(无内封时): 等就绪 + track-list 稳定后, 若 tracks.subtitle 为空
    // (无内封且未手动加外挂), 找同目录同名字幕(严格同名 + 带中文语言段, 按语言偏好排序)并加载首个。
    // PlayerActivity 独立 Activity 拿不到 source, siblingSubtitleLoader 从 mediaKey/contentUri 重建列目录能力。
    LaunchedEffect(playUrl) {
        if (!autoLoadSiblingSubtitle || siblingSubtitleLoader == null) return@LaunchedEffect
        var attempts = 0
        while (engine.state.value.status != PlaybackStatus.ERROR && attempts < 60) {
            val mi = engine.mediaInfo.value
            val durOk = engine.state.value.durationMs > 0
            if (durOk && mi != null && mi.width > 0) break
            if (durOk && attempts >= 30) break
            kotlinx.coroutines.delay(100)
            attempts++
        }
        if (engine.state.value.status == PlaybackStatus.ERROR) return@LaunchedEffect
        kotlinx.coroutines.delay(150)  // 让 track-list 上报稳定
        if (engine.tracks.value.subtitle.isNotEmpty()) return@LaunchedEffect  // 有内封或已加载外挂 -> 不抢
        val cands = withContext(Dispatchers.IO) {
            runSuspendCatching {
                siblingSubtitleLoader.listCandidates(mediaKey, contentUri, playTitle, subtitleLanguagePreference)
            }.getOrDefault(emptyList())
        }
        val first = cands.firstOrNull() ?: return@LaunchedEffect
        val lease = tempFileSession.newFile(
            "sub_auto",
            first.displayName.substringAfterLast('.', "srt"),
        )
        val file = try {
            siblingSubtitleLoader.download(first, lease.file)
        } finally {
            lease.close()
        } ?: return@LaunchedEffect
        engine.addExternalSubtitle(file.absolutePath, title = first.displayName.substringBeforeLast('.'))
        appLogger?.appEvent("player", "自动加载同目录字幕 ${first.displayName}", LogLevel.INFO)
    }

    // 同目录字幕手动选择器(字幕面板"从同目录选择"触发)
    var siblingSubs by remember { mutableStateOf<List<io.github.weiyongzenqi.unuplayer.core.media.SiblingSubtitleLoader.Candidate>>(emptyList()) }
    var showSiblingSubDialog by remember { mutableStateOf(false) }

    var showControls by remember { mutableStateOf(true) }
    var showInfoPanel by remember { mutableStateOf(false) }

    // 控制层 5 秒无操作自动隐藏: 显示后计时, 到点自动收起(拖动/长按进行中除外)。
    // 拖动 seek/长按倍速时 seeking/longPressActive 为 true, 此时即使到点也不隐藏。
    // sliderDragging: 进度条 Slider 拖动中(独立于根手势 seeking), 也抑制自动隐藏,
    // 但不触发根手势的浮动 seek 提示(那个用 seekTargetMs/seekBaseMs, 与 Slider 无关)。
    var seeking by remember { mutableStateOf(false) }
    var longPressActive by remember { mutableStateOf(false) }
    var sliderDragging by remember { mutableStateOf(false) }
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(5000)
            // 计时到点: 若不在拖动/长按/拖进度条, 才隐藏(避免操作中途控制层消失)
            if (!seeking && !longPressActive && !sliderDragging) showControls = false
        }
    }

    // === 手势状态 ===
    val activity = context as? android.app.Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15

    // 读系统当前媒体音量(0..maxVolume → 归一化 0..1)
    fun systemVolumeRatio(): Float {
        val cur = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        return if (maxVolume > 0) cur.toFloat() / maxVolume else 0f
    }
    // 读系统当前亮度(归一化 0..1, 强制 coerce 防越界)。
    // 自动亮度模式回退 0.5(应用级 window brightness 无法读真实面板亮度, 用中间值作起点)。
    // 注: 安卓11 等旧版 SCREEN_BRIGHTNESS 在自动亮度下可能返回 255, 必须先判 mode。
    fun systemBrightnessRatio(): Float {
        val resolver = context.contentResolver
        val mode = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        }.getOrDefault(0)
        return if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            0.5f
        } else {
            runCatching {
                (Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                    .toFloat() / 255f).coerceIn(0f, 1f)
            }.getOrDefault(0.5f)
        }
    }

    // 长按倍速(longPressActive 已在上方声明, 与自动隐藏共用)
    var rateBeforeLongPress by remember { mutableFloatStateOf(1f) }

    // 横向 seek 拖动(seeking 已在上方声明, 与自动隐藏共用)
    var seekTargetMs by remember { mutableLongStateOf(0L) }
    var seekBaseMs by remember { mutableLongStateOf(0L) }   // 按下时位置, 供浮层算偏移

    // 纵向亮度(左半屏)/ 音量(右半屏)
    // P3③: systemBrightnessRatio/systemVolumeRatio 走 Settings.System.getInt(binder IPC),
    // 不在组合期(remember 初值)与手势主线程同步执行: 初值先用默认值顶上, LaunchedEffect 在 IO 异步读入,
    // 之后手势复用缓存值, 不再每按一次读一次。
    var showBrightness by remember { mutableStateOf(false) }
    var brightnessVal by remember { mutableFloatStateOf(0.5f) }  // 0.5 = 自动亮度回退中点, 与 systemBrightnessRatio 回退一致
    var showVolume by remember { mutableStateOf(false) }
    // 音量浮层量纲是 0..1 系统媒体音量比例, 初始用系统当前值(非 state.volume——那是 mpv volume 恒 100,
    // 否则首次显示音量浮层会显示 1000%)。首拖即被 baseVolume 覆盖, 此处仅为初始正确。
    var volumeVal by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            brightnessVal = systemBrightnessRatio()
            volumeVal = systemVolumeRatio()
        }
    }

    // HDR: 按片源 gamma + 用户 hdrMode 动态切换 Activity window colorMode。
    // libmpv-android native 未设 ANativeWindow dataspace, setColorMode 是第一步补偿。
    // API 33+ 才有 Window.setColorMode; 低版本静默不切(不崩)。
    val hdrEnabled = hdrMode == HdrMode.HDR_PASSTHROUGH ||
        (hdrMode == HdrMode.AUTO && platformInfo.supportsHdr)
    val gamma = mediaInfo?.hdrInfo?.gamma
    DisposableEffect(gamma, hdrEnabled) {
        // hdrEnabled=false 时也要显式复位为 DEFAULT: 旧实现仅在 hdrEnabled=true 分支内设 colorMode,
        // 导致同一播放器会话内从 HDR 档切到 OFF/SDR 时 window 仍停留 COLOR_MODE_HDR → "关不掉"。
        // 注意: hdrMode 本身 init-only, 运行时改了需重进播放器才真正切 mpv 管线; 但 window colorMode
        // 跟着 hdrEnabled 实时复位无害, 且避免残留 HDR。
        if (Build.VERSION.SDK_INT >= 33) {
            val activity = context as? android.app.Activity
            activity?.window?.let { w ->
                val wantHdr = hdrEnabled && (gamma == "pq" || gamma == "hlg")
                // 动态切换: HDR 片源切 HDR, SDR 片源切回 DEFAULT(否则切视频后停留 HDR 发灰)
                w.colorMode =
                    if (wantHdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
        onDispose {
            // 离开播放器复位为默认, 避免影响其他页面
            if (Build.VERSION.SDK_INT >= 33) {
                (context as? android.app.Activity)?.window?.colorMode =
                    ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
    }

    // 视频方向: 进播放器即锁横屏(二次元动漫绝大多数是横向, 确定性横屏避免竖屏渲染拉伸——
    // 这是经真机验证能正常播放的做法)。video-params 到来后, 竖向视频再切竖屏匹配。
    // 离开播放器复位为 UNSPECIFIED(交回系统/其他页面)。
    val videoW = mediaInfo?.width ?: 0
    val videoH = mediaInfo?.height ?: 0
    val isLandscapeVideo = videoW > 0 && videoH > 0 && videoW > videoH
    // 进播放器即锁横屏: 避免等 video-params 期间竖屏渲染拉伸过渡
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // video-params 回来: 竖向视频锁竖屏匹配, 横向保持横屏
    DisposableEffect(isLandscapeVideo) {
        if (videoW > 0 && videoH > 0) {
            activity?.requestedOrientation =
                if (isLandscapeVideo) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        onDispose {}
    }

    // 全屏沉浸式 + 亮度范围管理: 进播放器隐藏系统栏/常亮/Cutout铺满, 并记录原始亮度;
    // 离开时复原系统栏、Cutout 模式, **并把 screenBrightness 复位为原始值**(否则调节亮度会残留整个 App)。
    DisposableEffect(Unit) {
        val w = activity?.window
        val origBrightness: Float = if (w != null) {
            @Suppress("DEPRECATION")
            val b = w.attributes.screenBrightness
            // 设 cutout + 保留原亮度(此时还没调亮度, 保留进入时值)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // edge-to-edge: 窗口内容延伸到系统栏/cutout, 消除横屏左侧安全区黑边
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            val controller = androidx.core.view.WindowInsetsControllerCompat(w, w.decorView)
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            w.attributes = w.attributes.apply {
                // ALWAYS(API31+)强制铺满 cutout(含长边), 否则 SHORT_EDGES; 填横屏左侧挖孔
                layoutInDisplayCutoutMode =
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            b
        } else -1f  // -1 = 跟随系统亮度(非 0f 全黑)
        onDispose {
            val w = activity?.window
            if (w != null) {
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val controller = androidx.core.view.WindowInsetsControllerCompat(w, w.decorView)
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                // 复位 edge-to-edge, 一次性写回(连同下方 attributes)
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, true)
                // 复位亮度为 -1(跟随系统, 还原程序内调节)+ Cutout 模式, 一次性写回
                w.attributes = w.attributes.apply {
                    screenBrightness = -1f
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
    }

    // 过渡遮罩: 方向/尺寸未定时黑屏盖住, 避免旋转期间渲染拉伸闪现。
    // 横屏视频: 进即锁横屏, 无拉伸, video-params 到来即可撤。
    // 竖屏视频: 进时短暂横屏, video-params 到来切竖屏(旋转~300ms), 用延迟盖住旋转过渡。
    // 统一: video-params 到来后延迟 600ms 再撤遮罩(横屏本就正确, 延迟无妨; 竖屏盖旋转)。
    // 预测性返回: 跟手轻缩反馈。commit 时 onBack(=finish) 由系统跨 Activity 过渡
    // 淡入竖屏首页——首页 Activity 不被播放器方向旋转, 故无黑幕/横屏首页/旋转闪烁。
    // 关闭开关则即时返回, 无跟手反馈。
    var backProgress by remember { mutableFloatStateOf(0f) }
    if (predictiveBack) {
        PredictiveBackHandler(enabled = true) { progress ->
            try {
                progress.collect { backProgress = it.progress }
                onBack()
            } finally {
                backProgress = 0f
            }
        }
    } else {
        BackHandler(enabled = true, onBack = onBack)
    }

    var surfaceReady by remember { mutableStateOf(false) }
    LaunchedEffect(videoW, videoH) {
        if (videoW > 0 && videoH > 0) {
            delay(600)
            surfaceReady = true
        } else {
            surfaceReady = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer {
                val p = backProgress
                scaleX = 1f - 0.04f * p
                scaleY = 1f - 0.04f * p
            }
            // 单指手势统一分派: 单击/双击/长按倍速/横向seek/纵向亮度音量
            // 用 awaitEachGesture 手动分派, 避免 detectTapGestures 消费 down 事件后
            // detectDragGestures 拿不到事件(链式 pointerInput 互相阻塞)。
            .pointerInput(showSettingsSheet, showInfoPanel) {
                // 设置弹层(ModalBottomSheet)自带 scrim, 打开时禁用根手势。
                // 技术信息面板打开时: 保留 tap(点空白处关闭面板), 禁 drag/longPress(下面条件 guard)。
                // 控制层显示时不屏蔽拖动: 进度条/按钮 consume down 后根手势拿不到(见 requireUnconsumed),
                // 空白处拖动仍可 seek/调亮度音量, 不与进度条冲突。
                if (showSettingsSheet) return@pointerInput
                val touchSlop = 40f   // 拖动判定阈值(px), 近似 viewConfiguration.pointerSlop
                val longPressTimeout = 500L  // 长按超时(ms), 近似 viewConfiguration.longPressTimeoutMillis
                val doubleTapTimeout = 280L  // 双击判定窗口(ms), 同时作单击延迟确认时长
                var lastTapTimeMs = 0L
                // 待确认的单击: 松开后不立即切面板, 延迟 doubleTapTimeout 看是否双击。
                // 期间第二次 tap 到达则取消(判双击, 不切面板), 避免双击暂停时面板误弹。
                var pendingSingleTap: Job? = null
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)  // 子节点(按钮/进度条)消费的 down 不到根, 避免点按钮误触发 tap
                    // 顶部死区: 从屏幕顶下滑(开状态栏/通知栏)不触发音量/亮度, 不消费让系统处理
                    val topDeadZonePx = 28.dp.toPx()
                    if (down.position.y < topDeadZonePx) return@awaitEachGesture
                    down.consume()
                    val startX = down.position.x
                    val isLeftSide = startX < size.width / 2f
                    val pressTime = System.currentTimeMillis()
                    var dragged = false
                    var longPressFired = false
                    var prevRate = 1f
                    // 按下时不显示亮度/音量 UI —— 必须拖动越 touchSlop 并锁定为纵向轴后才显示(基础死区)。
                    // 否则单击会被误判为亮度/音量调整, 双击也会被覆盖。
                    // 亮度/音量起点复用缓存值(brightnessVal/volumeVal: 首次=IO 异步读的系统值, 之后=上次调整值),
                    // 不每次按下重读系统(P3③: Settings.System 是 binder IPC, 不放手势主线程;
                    // 也避免自动亮度下 systemBrightnessRatio() 返回 0.5 与实际面板亮度不符致跳变)。
                    val baseBrightness = if (isLeftSide) brightnessVal else 0f
                    val baseVolume = if (!isLeftSide) volumeVal else 0f
                    val downY = down.position.y
                    seekBaseMs = engine.position.value
                    var prevX = startX
                    var prevY = down.position.y
                    var dragAxis: Int = 0  // 0=未定 1=横向seek 2=纵向亮度音量
                    // 循环等后续事件, 直到松开或取消
                    while (true) {
                        // 长按改为超时驱动: 未拖动且未触发长按时, 用 withTimeoutOrNull 限时等下个事件,
                        // 超时(手指按住不动、无事件)即触发长按。原写法把判定放在 awaitPointerEvent() 之后,
                        // 手指不动时无触摸事件, awaitPointerEvent() 一直挂起, 长按永不触发(按很久才响应)。
                        // 控制层显示时禁用长按(否则手指在进度条上按住会误触发倍速)。
                        val event = if (!showControls && !showInfoPanel && !dragged && !longPressFired) {
                            val remaining = longPressTimeout - (System.currentTimeMillis() - pressTime)
                            if (remaining <= 0) null
                            else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            // 超时: 触发长按(此时未拖动未触发过, 条件已满足)
                            prevRate = state.rate
                            engine.setRate(longPressSpeed)
                            longPressActive = true
                            longPressFired = true
                            showBrightness = false
                            showVolume = false
                            continue
                        }
                        val change = event.changes.firstOrNull() ?: continue
                        if (!change.pressed) {
                            // 松开
                            if (longPressFired) {
                                engine.setRate(prevRate)
                                longPressActive = false
                            } else if (!dragged) {
                                if (showInfoPanel) {
                                    // 信息面板打开时, tap 直接关闭面板(不走延迟/双击)
                                    showInfoPanel = false
                                } else {
                                    // tap: 单击延迟确认, 防双击暂停时面板误弹
                                    val now = System.currentTimeMillis()
                                    if (now - lastTapTimeMs < doubleTapTimeout) {
                                        // 双击: 取消第一次单击的延迟切面板, 只暂停/播放
                                        pendingSingleTap?.cancel()
                                        pendingSingleTap = null
                                        if (state.paused) engine.play() else engine.pause()
                                        lastTapTimeMs = 0L
                                    } else {
                                        // 可能单击: 延迟 doubleTapTimeout, 期间无第二次 tap 则切面板
                                        lastTapTimeMs = now
                                        pendingSingleTap?.cancel()
                                        pendingSingleTap = scope.launch {
                                            delay(doubleTapTimeout)
                                            showControls = !showControls
                                            pendingSingleTap = null
                                        }
                                    }
                                }
                            }
                            // 收尾: 若在 seek, 落地
                            if (seeking) { engine.seekTo(seekTargetMs); seeking = false }
                            showBrightness = false
                            showVolume = false
                            break
                        }
                        val dx = change.position.x - startX
                        val dy = change.position.y - down.position.y
                        val moved = abs(dx) > touchSlop || abs(dy) > touchSlop
                        if (moved && !longPressFired) {
                            // 增量
                            val incX = change.position.x - prevX
                            val incY = change.position.y - prevY
                            prevX = change.position.x
                            prevY = change.position.y
                            if (!dragged) {
                                // 首次越 touchSlop: 标记已拖动(无论控制层是否显示, 防松手误判 tap)
                                dragged = true
                                // 信息面板打开时不锁定方向(禁 drag); 控制层显示时仍可拖动:
                                // 进度条/按钮消费 down 不冲突, 空白处 seek/亮度音量照常。
                                if (!showInfoPanel) {
                                    // 首次越 touchSlop, 锁定主方向(基础死区: 不越阈值不触发任何调整)
                                    dragAxis = if (abs(dx) >= abs(dy)) 1 else 2
                                    if (dragAxis == 1) {
                                        seeking = true
                                        seekTargetMs = seekBaseMs
                                    } else if (isLeftSide) {
                                        brightnessVal = baseBrightness
                                        showBrightness = true
                                    } else {
                                        volumeVal = baseVolume
                                        showVolume = true
                                    }
                                    // 首帧只锁定方向 + 重置基线, 不算 delta:
                                    // 此帧 incX 含 touchSlop 累计位移(可能 24px+), 直接算会首帧大跳 ~2s
                                    prevX = change.position.x
                                    prevY = change.position.y
                                }
                            } else if (!showInfoPanel) {
                                when (dragAxis) {
                                    1 -> {
                                        // 横向 seek: 按屏宽归一化, 全屏拖动=最多 90s(或整片时长),
                                        // 小拖动调整小; 防抖死区: 小于 8px 忽略
                                        if (abs(incX) >= 8f) {
                                            val seekRangeMs = state.durationMs.coerceAtMost(90_000L)
                                            val delta = (incX / size.width.toFloat() * seekRangeMs).toLong()
                                            seekTargetMs = (seekTargetMs + delta)
                                                .coerceIn(0, state.durationMs)
                                        }
                                    }
                                2 -> {
                                    // 纵向用绝对位移(相对按下点), 避免增量累加漂移/抖动
                                    val totalDy = change.position.y - downY
                                    if (isLeftSide) {
                                        // 亮度 0..1, 全屏高对应 1.0
                                        val b = (baseBrightness - totalDy / size.height).coerceIn(0f, 1f)
                                        brightnessVal = b
                                        activity?.window?.let { w ->
                                            val attrs = w.attributes
                                            attrs.screenBrightness = b
                                            w.attributes = attrs
                                        }
                                    } else {
                                        // 系统媒体音量 0..1, 全屏高对应 1.0
                                        val v = (baseVolume - totalDy / size.height).coerceIn(0f, 1f)
                                        // 死区: 变化小于 1% 不写, 减少抖动
                                        if (abs(v - volumeVal) >= 0.01f) {
                                            volumeVal = v
                                            audioManager?.setStreamVolume(
                                                AudioManager.STREAM_MUSIC,
                                                (v * maxVolume).toInt().coerceIn(0, maxVolume),
                                                0,
                                            )
                                        }
                                    }
                                }
                                }
                            }
                        }
                    }
                }
            }
            // 3. 双指缩放(video-zoom)
            .pointerInput(showSettingsSheet, showInfoPanel) {
                if (showSettingsSheet || showInfoPanel) return@pointerInput
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) {
                        val cur = engine.getPropertyDouble("video-zoom") ?: return@detectTransformGestures
                        engine.setPropertyString("video-zoom", "%.3f".format((cur + (zoom - 1f)).coerceIn(-1.0, 2.0)))
                    }
                }
            },
    ) {
        // 视频渲染层
        MpvVideoSurface(engine = engine, modifier = Modifier.fillMaxSize())

        // 弹幕层: 叠在视频之上, 手势穿透(不加 pointerInput -> 冒泡到根分派器); 加载遮罩/控制层在其上
        DanmakuLayer(
            playerEngine = engine,
            entries = danmakuEntries,
            config = danmakuConfig,
            modifier = Modifier.fillMaxSize(),
        )

        // 过渡遮罩: 方向/尺寸未定时黑屏盖住, 避免竖屏拉伸画面闪现; 中央转圈指示加载
        if (!surfaceReady) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.8f))
            }
        }

        // 错误覆盖层: 播放失败时把原因展示给用户(半透明卡片 + 图标 + 重试/返回),
        // 而非黑屏转圈让人无从判断。state.error 来自 mpv create 失败或 END_FILE 的 file-error。
        val errMsg = state.error
        if (state.status == PlaybackStatus.ERROR && errMsg != null) {
            PlaybackErrorOverlay(
                error = errMsg,
                onRetry = {
                    // engine 已 init, 重试只需重新 load + 续播 + play(不重建 engine, 避免重走 CA bundle 等)。
                    // P3②: LaunchedEffect(playUrl) key 未变续播不重跑, 手动复用 resumeSeekFromRecord;
                    // engine.load 会把旧 ERROR 复位为 IDLE, 故这里等的是本次加载结果(READY 才续播+播放)。
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            engine.load(playUrl)
                            engine.state.first {
                                it.status == PlaybackStatus.READY || it.status == PlaybackStatus.PAUSED || it.status == PlaybackStatus.ERROR
                            }
                            if (engine.state.value.status != PlaybackStatus.ERROR) {
                                resumeSeekFromRecord()
                                engine.play()
                            }
                        }
                    }
                },
                onBack = onBack,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 控制层
        if (showControls) {
            PlayerControls(
                state = state,
                positionFlow = engine.position,
                mediaInfo = mediaInfo,
                playTitle = playTitle,
                episodeTitle = currentEpisodeTitle,
                showInfoPanel = showInfoPanel,
                onBack = onBack,
                onPlayPause = {
                    if (state.paused) engine.play() else engine.pause()
                },
                onSeek = { ms -> engine.seekTo(ms) },
                onSeekStarted = { sliderDragging = true },
                onSeekFinished = { sliderDragging = false },
                onToggleInfo = { showInfoPanel = !showInfoPanel },
                onToggleSubtitle = { showSettingsSheet = !showSettingsSheet },
                danmakuEnabled = danmakuConfig.enabled,
                onToggleDanmaku = { onDanmakuConfigChange(danmakuConfig.copy(enabled = !danmakuConfig.enabled)) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 播放设置弹层(字幕/音轨/倍速 分页): 轨道切换 + 外挂加载 + 临时样式调整
        if (showSettingsSheet) {
            PlayerSettingsSheet(
                tracks = tracks,
                currentSpeed = state.rate,
                speedPresets = speedPresets,
                onPickSubtitle = {
                    pickSubLauncher.launch(arrayOf(
                        "application/x-subrip", "text/plain",
                        "application/octet-stream",
                    ))
                },
                onPickSiblingSubtitle = {
                    if (siblingSubtitleLoader != null) {
                        scope.launch {
                            val subs = withContext(Dispatchers.IO) {
                                runSuspendCatching { siblingSubtitleLoader.listAllSubtitles(mediaKey, contentUri) }
                                    .getOrDefault(emptyList())
                            }
                            if (subs.isEmpty()) {
                                matchToast = "未找到同目录字幕文件"
                            } else {
                                // 与当前视频同集(SxxExx/EP)的字幕置顶, 方便识别
                                val curEp = EpisodeNumberExtractor.extractEpisode(playTitle)
                                siblingSubs = if (curEp != null) {
                                    subs.sortedByDescending { EpisodeNumberExtractor.extractEpisode(it.displayName) == curEp }
                                } else subs
                                showSiblingSubDialog = true
                                showSettingsSheet = false
                            }
                        }
                    }
                },
                onSelectSubtitle = { id ->
                    userPickedSubtitle = true
                    engine.setSubtitleTrack(id)
                },
                onSelectAudio = { id ->
                    userPickedAudio = true
                    engine.setAudioTrack(id)
                },
                onSelectSpeed = { v -> engine.setRate(v) },
                onDismiss = { showSettingsSheet = false },
                scale = subScale,
                borderSize = subBorder,
                bold = subBold,
                onScaleChange = { v ->
                    subScale = v
                    engine.setPropertyString("sub-scale", v.toString())
                },
                onBorderChange = { v ->
                    subBorder = v
                    engine.setPropertyString("sub-border-size", v.toString())
                },
                onBoldChange = { v ->
                    subBold = v
                    engine.setPropertyString("sub-bold", if (v) "yes" else "no")
                },
                danmakuConfig = danmakuConfig,
                onDanmakuConfigChange = onDanmakuConfigChange,
                danmakuShowMatchToast = danmakuShowMatchToast,
                onDanmakuMatchToastChange = onDanmakuMatchToastChange,
                danmakuApiReady = dandanplayApi != null,
                onManualMatch = {
                    showSettingsSheet = false
                    showManualMatchDialog = true
                },
            )
        }

        // 同目录字幕选择对话框(字幕面板"从同目录选择"触发)
        if (showSiblingSubDialog) {
            val curEp = EpisodeNumberExtractor.extractEpisode(playTitle)
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSiblingSubDialog = false },
                title = { Text("选择同目录字幕") },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                    ) {
                        items(siblingSubs) { cand ->
                            val ep = EpisodeNumberExtractor.extractEpisode(cand.displayName)
                            val badge = EpisodeNumberExtractor.formatSxxExx(cand.displayName)
                                ?: ep?.let { "第${it}集" }
                            val isCur = ep != null && ep == curEp
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    val picked = cand
                                    showSiblingSubDialog = false
                                    scope.launch {
                                        val f = withContext(Dispatchers.IO) {
                                            val lease = tempFileSession.newFile(
                                                "sub_auto",
                                                picked.displayName.substringAfterLast('.', "srt"),
                                            )
                                            try {
                                                siblingSubtitleLoader?.download(picked, lease.file)
                                            } finally {
                                                lease.close()
                                            }
                                        }
                                        if (f != null) {
                                            engine.addExternalSubtitle(f.absolutePath, title = picked.displayName.substringBeforeLast('.'))
                                            userPickedSubtitle = true
                                            appLogger?.appEvent("player", "手动加载同目录字幕 ${picked.displayName}", LogLevel.INFO)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "${if (isCur) "▶ " else ""}${badge?.let { "[$it] " } ?: ""}${cand.displayName}",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showSiblingSubDialog = false }) { Text("取消") }
                },
            )
        }

        // 手动匹配弹幕对话框(弹幕设置 Sheet 的"手动匹配弹幕"按钮触发)
        if (showManualMatchDialog && dandanplayApi != null) {
            val api = dandanplayApi  // 守卫已确保非空, 赋局部 val 供 lambda 内用(避 smart cast 不跨非 inline lambda)
            val initialKeyword = remember(playUrl, playTitle) {
                DanmakuMatcher.cleanSearchKeyword(
                    playTitle.ifBlank { playUrl.substringAfterLast('/') }.let {
                        runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
                    }
                )
            }
            ManualMatchDialog(
                api = api,
                initialKeyword = initialKeyword,
                onDismiss = { showManualMatchDialog = false },
                onConfirm = { sel ->
                    showManualMatchDialog = false
                    val targetUrl = playUrl  // 捕获当前 url, 回调时对比守卫(视频切走不加载)
                    scope.launch {
                        val entries = withContext(Dispatchers.IO) {
                            runSuspendCatching { DandanplaySourceProvider(api).fetch(sel.episodeId) }
                                .getOrElse { emptyList() }
                        }
                        if (playUrl == targetUrl) {
                            danmakuEntries = entries
                            currentEpisodeTitle = sel.episodeTitle
                            // 存缓存(WebDAV: playUrl 稳定; 本地: hash, 不依赖引擎内部临时 fdclose://)
                            val isWebDav = playUrl.startsWith("http", ignoreCase = true)
                            val cacheKey = if (isWebDav) playUrl else computeHash()?.second
                            cacheKey?.let { k ->
                                onSaveManualMatch?.invoke(
                                    k,
                                    ManualMatchCacheEntry(sel.episodeId, sel.animeId, sel.animeTitle, sel.episodeTitle, platformTimeMillis()),
                                )
                            }
                            // B11: 与初始 upsert 同一序列化记录队列, 避免陈旧快照整行 upsert 擦除手动匹配。
                            runSuspendCatching {
                                AndroidPlayerLifecycleTasks.runSerialized(appLogger, "写手动匹配记录") {
                                    recordRepo.updateDanmaku(
                                        recordKey,
                                        sel.episodeId,
                                        sel.animeId,
                                        sel.animeTitle,
                                        sel.episodeTitle,
                                        io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchMethod.MANUAL.name,
                                    )
                                }
                            }
                            if (danmakuShowMatchToast) matchToast = "弹幕匹配方式：手动匹配（${sel.animeTitle}）"
                            appLogger?.appEvent("danmaku", "手动匹配成功 episodeId=${sel.episodeId} 番=${sel.animeTitle}", LogLevel.INFO)
                        }
                    }
                },
            )
        }

        // 技术信息面板(可滑出, 右侧)
        if (showInfoPanel && mediaInfo != null) {
            TechInfoPanel(
                mediaInfo = mediaInfo!!,
                state = state,
                systemVolumePct = (volumeVal * 100).toInt(),  // P3③: 复用缓存值, 不在组合期同步读 Settings.System
                engine = engine,
                onClose = { showInfoPanel = false },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .widthIn(max = 320.dp)
                    .fillMaxHeight(),
            )
        }

        // === 手势 transient 浮层 ===
        // 匹配方式气泡提醒(2s 自动消失)
        matchToast?.let { toast ->
            GestureHint(
                text = toast,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp).widthIn(max = 340.dp),
            )
        }
        if (longPressActive) {
            GestureHint(
                text = "%.1fx".format(longPressSpeed),
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (seeking) {
            // 显示相对偏移(+/- 秒) + 目标时间, 让用户感知拖了多少
            val deltaSec = (seekTargetMs - seekBaseMs) / 1000
            val sign = if (deltaSec >= 0) "+" else "-"
            GestureHint(
                text = "$sign${kotlin.math.abs(deltaSec)}s  →  ${formatTime(seekTargetMs)}",
                modifier = Modifier.align(Alignment.Center).offset(y = (-80).dp),
            )
        }
        if (showBrightness) {
            GestureHint(
                icon = Icons.Filled.Brightness6,
                text = "${(brightnessVal.coerceIn(0f, 1f) * 100).toInt()}%",
                modifier = Modifier.align(Alignment.Center).offset(y = (-80).dp),
            )
        }
        if (showVolume) {
            GestureHint(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                text = "${(volumeVal * 100).toInt()}%",
                modifier = Modifier.align(Alignment.Center).offset(y = (-80).dp),
            )
        }
    }
}

/** 控制层: 顶栏(返回/信息) + 底栏(播放/暂停/进度条/时间)。 */
@Composable
private fun PlayerControls(
    state: io.github.weiyongzenqi.unuplayer.core.player.PlayerState,
    positionFlow: StateFlow<Long>,
    mediaInfo: io.github.weiyongzenqi.unuplayer.core.player.MediaInfo?,
    playTitle: String = "",
    episodeTitle: String = "",
    showInfoPanel: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekStarted: () -> Unit,
    onSeekFinished: () -> Unit,
    onToggleInfo: () -> Unit,
    onToggleSubtitle: () -> Unit,
    danmakuEnabled: Boolean,
    onToggleDanmaku: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            // 标题区: 主标题(文件名) + 剧集标题(小字), 都 Marquee 滚动; weight 占剩余, 右侧按钮固定不被挤
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // 优先 playTitle: 本地 content:// 会在引擎内转 fdclose://, 用透传的文件名展示;
                    // WebDAV 时 playTitle 多为空, 回落 mediaInfo.title(文件名)
                    text = playTitle.ifBlank { mediaInfo?.title ?: "播放中" },
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .basicMarquee()
                        .padding(start = 8.dp),
                )
                if (episodeTitle.isNotBlank()) {
                    Text(
                        text = episodeTitle,
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(0.7f)
                            .basicMarquee()
                            .padding(start = 6.dp),
                    )
                }
            }
            IconButton(onClick = onToggleSubtitle) {
                Icon(Icons.Filled.Settings, contentDescription = "播放设置", tint = Color.White)
            }
            IconButton(onClick = onToggleInfo) {
                Icon(Icons.Filled.Info, contentDescription = "技术信息", tint = Color.White)
            }
        }

        // 底栏
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(8.dp),
        ) {
            // 进度条: 拖动中用本地值(不被 time-pos 回推拉回), 松手才 seek 一次。
            // 旧实现 onValueChange 每帧都 seekTo → WebDAV 流式源每次 seek 都是新 range 请求,
            // 拖一下进度条发几十次 HTTP, 严重卡顿。
            // position 在此叶节点内收集: time-pos 高频更新只重组本控制层, 不波及整个播放页。
            val duration = state.durationMs.coerceAtLeast(1)
            val positionMs by positionFlow.collectAsStateWithLifecycle()
            var sliderDragging by remember { mutableStateOf(false) }
            var sliderValue by remember { mutableFloatStateOf(0f) }
            // 松手后等 mpv 真实位置追上 seek 目标期间, 继续显示目标(不切回 positionMs)。
            // 否则松手瞬间 positionMs 仍是拖动前旧值 → 滑块 snap 回旧位置闪一下 + 时间文本瞬显旧进度。
            var pendingSeekMs by remember { mutableLongStateOf(-1L) }
            var seekFromMs by remember { mutableLongStateOf(0L) }   // 拖动前位置, 判断 seek 是否已落地
            val displayPos = when {
                sliderDragging -> (sliderValue * duration).toLong()
                pendingSeekMs >= 0 -> pendingSeekMs
                else -> positionMs
            }
            val displayedValue = (displayPos.toFloat() / duration).coerceIn(0f, 1f)
            Slider(
                value = displayedValue,
                onValueChange = { ratio ->
                    if (!sliderDragging) {
                        sliderDragging = true
                        onSeekStarted()
                    }
                    sliderValue = ratio
                },
                onValueChangeFinished = {
                    val target = (sliderValue * duration).toLong()
                    seekFromMs = positionMs
                    onSeek(target)
                    pendingSeekMs = target       // 保持显示目标, 等真实位置追上再切回
                    sliderDragging = false
                    onSeekFinished()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            // 等真实播放位置追上 seek 目标后切回实时位置:
            // 需"已离开拖动前位置(说明 seek 生效, 非松手瞬间的旧值)" 且 "接近目标",
            // 双条件避免向后 seek 时旧位置恰好落在目标容差内被误判清除。
            LaunchedEffect(positionMs, pendingSeekMs) {
                if (pendingSeekMs >= 0) {
                    val moved = abs(positionMs - seekFromMs) > 500
                    val nearTarget = abs(positionMs - pendingSeekMs) < 2000
                    if (moved && nearTarget) pendingSeekMs = -1L
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = "播放/暂停",
                        tint = Color.White,
                    )
                }
                // 弹幕快捷开关(左下角, 播放暂停右一位): 开=白色, 关=灰色
                IconButton(onClick = onToggleDanmaku) {
                    Icon(
                        Icons.Filled.Subtitles,
                        contentDescription = if (danmakuEnabled) "关闭弹幕" else "开启弹幕",
                        tint = if (danmakuEnabled) Color.White else Color.Gray,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    // 显示 displayPos: 拖动中=手指目标, 松手后等落地期间=seek 目标, 否则=实时位置。
                    // 这样松手不会瞬显旧进度(闪动), 真实位置追上后自然切回。
                    text = "${formatTime(displayPos)} / ${formatTime(state.durationMs)}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.buffering) {
                    Text(
                        text = "缓冲中…",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** 技术信息面板(可滑出, 分组卡片)。 */
@Composable
private fun TechInfoPanel(
    mediaInfo: io.github.weiyongzenqi.unuplayer.core.player.MediaInfo,
    state: io.github.weiyongzenqi.unuplayer.core.player.PlayerState,
    systemVolumePct: Int,
    engine: io.github.weiyongzenqi.unuplayer.core.player.PlayerEngine,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 码率轮询: 面板可见时每 1s 读瞬时码率。面板关 -> TechInfoPanel 离开组合 -> LaunchedEffect 自动取消, 不后台耗电。
    // 不用 observe: video-bitrate 高频变化会产生大量事件; 轮询只在用户看面板时做, 功耗更低。
    var videoBitrate by remember { mutableStateOf(mediaInfo.videoBitrate) }
    var audioBitrate by remember { mutableStateOf(mediaInfo.audioBitrate) }
    LaunchedEffect(Unit) {
        while (true) {
            val (v, a) = withContext(Dispatchers.IO) {
                (engine.getPropertyDouble("video-bitrate")?.toInt() ?: 0) to
                    (engine.getPropertyDouble("audio-bitrate")?.toInt() ?: 0)
            }
            if (v > 0) videoBitrate = v
            if (a > 0) audioBitrate = a
            kotlinx.coroutines.delay(1000)
        }
    }
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("技术信息", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Box(modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Info, contentDescription = "关闭", tint = Color.White)
            }
        }
        InfoGroup("视频") {
            InfoRow("编码", mediaInfo.videoCodec)
            InfoRow("分辨率", "${mediaInfo.width}x${mediaInfo.height}")
            InfoRow("帧率", "%.2f".format(mediaInfo.fps))
            InfoRow("码率", "${videoBitrate / 1000} kbps")
            if (mediaInfo.rotation != 0) InfoRow("旋转", "${mediaInfo.rotation}°")
            mediaInfo.hdrInfo?.let { InfoRow("HDR", it.gamma ?: "是") }
        }
        InfoGroup("音频") {
            InfoRow("编码", mediaInfo.audioCodec)
            InfoRow("采样率", "${mediaInfo.audioSampleRate} Hz")
            InfoRow("声道", "${mediaInfo.audioChannels}")
        }
        InfoGroup("解码") {
            InfoRow("解码器", mediaInfo.hwdecCurrent ?: "—")
            InfoRow("请求", mediaInfo.requestedHwdec ?: "—")
        }
        InfoGroup("渲染") {
            InfoRow("渲染器", mediaInfo.vo ?: "—")
            InfoRow("图形API", mediaInfo.gpuApi ?: "—")
        }
        InfoGroup("状态") {
            InfoRow("速度", "${state.rate}x")
            // 音量显示系统媒体音量(手势调的就是系统 STREAM_MUSIC), 非 mpv volume(恒 100)
            InfoRow("音量", "$systemVolumePct%")
        }
    }
}

@Composable
private fun InfoGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        Text(value ?: "—", color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

private enum class AndroidPlayerSettingsPane { SUBTITLE, AUDIO, SPEED, DANMAKU }

/**
 * 播放设置弹层: 字幕 / 音轨 / 倍速 / 弹幕 四分页。
 * - 字幕页: 关闭字幕 + 内置/外挂字幕轨 + 加载外挂 + 临时样式(缩放/描边/粗体)
 * - 音轨页: 内置音轨切换
 * - 倍速页: 预设档切换
 * - 弹幕页: 开关 + 不透明度/字号/速度/区域 + 内核切换(含原理说明) + 匹配方式气泡提醒
 * 字幕临时样式不写回设置, 仅本次播放生效; 弹幕页设置写回全局设置(经 onDanmakuConfigChange)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSettingsSheet(
    tracks: io.github.weiyongzenqi.unuplayer.core.player.TrackList,
    currentSpeed: Float,
    speedPresets: List<Float>,
    onPickSubtitle: () -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
    scale: Float,
    borderSize: Float,
    bold: Boolean,
    onScaleChange: (Float) -> Unit,
    onBorderChange: (Float) -> Unit,
    onBoldChange: (Boolean) -> Unit,
    danmakuConfig: DanmakuConfig,
    onDanmakuConfigChange: (DanmakuConfig) -> Unit,
    danmakuShowMatchToast: Boolean,
    onDanmakuMatchToastChange: (Boolean) -> Unit,
    danmakuApiReady: Boolean = false,
    onManualMatch: () -> Unit = {},
    onPickSiblingSubtitle: () -> Unit = {},
) {
    var pane by remember { mutableStateOf(AndroidPlayerSettingsPane.SUBTITLE) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(440.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("播放设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 左侧分页按钮
                Column(
                    modifier = Modifier
                        .width(112.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsPaneButton("字幕", pane == AndroidPlayerSettingsPane.SUBTITLE) { pane = AndroidPlayerSettingsPane.SUBTITLE }
                    SettingsPaneButton("音轨", pane == AndroidPlayerSettingsPane.AUDIO) { pane = AndroidPlayerSettingsPane.AUDIO }
                    SettingsPaneButton("倍速", pane == AndroidPlayerSettingsPane.SPEED) { pane = AndroidPlayerSettingsPane.SPEED }
                    SettingsPaneButton("弹幕", pane == AndroidPlayerSettingsPane.DANMAKU) { pane = AndroidPlayerSettingsPane.DANMAKU }
                }
                // 右侧内容
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        when (pane) {
                            AndroidPlayerSettingsPane.SUBTITLE -> {
                                val subTracks = tracks.subtitle
                                if (subTracks.isNotEmpty()) {
                                    item {
                                        SheetOptionRow(
                                            label = "关闭字幕",
                                            selected = subTracks.none { it.selected },
                                            onSelect = { onSelectSubtitle(0) },
                                        )
                                    }
                                    items(subTracks) { track ->
                                        SheetOptionRow(
                                            label = trackLabel(track),
                                            selected = track.selected,
                                            onSelect = { onSelectSubtitle(track.id) },
                                        )
                                    }
                                } else {
                                    item {
                                        Text(
                                            "无可用字幕轨",
                                            modifier = Modifier.padding(12.dp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                item {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                                    OutlinedButton(onClick = onPickSubtitle, modifier = Modifier.fillMaxWidth()) {
                                        Icon(
                                            Icons.Filled.Subtitles,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 4.dp).size(18.dp),
                                        )
                                        Text("加载外挂字幕(.srt/.ass)")
                                    }
                                }
                                item {
                                    OutlinedButton(onClick = onPickSiblingSubtitle, modifier = Modifier.fillMaxWidth()) {
                                        Icon(
                                            Icons.Filled.Subtitles,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 4.dp).size(18.dp),
                                        )
                                        Text("从同目录选择字幕")
                                    }
                                }
                                item {
                                    Text("字号缩放  ${"%.1f".format(scale)}x", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = scale,
                                        onValueChange = onScaleChange,
                                        valueRange = 0.5f..4.0f,
                                        steps = 34,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                item {
                                    Text("描边  ${"%.1f".format(borderSize)}", style = MaterialTheme.typography.bodySmall)
                                    Slider(
                                        value = borderSize,
                                        onValueChange = onBorderChange,
                                        valueRange = 0.0f..6.0f,
                                        steps = 59,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("粗体", style = MaterialTheme.typography.bodySmall)
                                        Switch(checked = bold, onCheckedChange = onBoldChange)
                                    }
                                }
                            }

                            AndroidPlayerSettingsPane.AUDIO -> {
                                val audioTracks = tracks.audio
                                if (audioTracks.isEmpty()) {
                                    item {
                                        Text(
                                            "无可用音轨",
                                            modifier = Modifier.padding(12.dp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                items(audioTracks) { track ->
                                    SheetOptionRow(
                                        label = trackLabel(track),
                                        selected = track.selected,
                                        onSelect = { onSelectAudio(track.id) },
                                    )
                                }
                            }

                            AndroidPlayerSettingsPane.SPEED -> {
                                items(speedPresets) { speed ->
                                    SheetOptionRow(
                                        label = formatSpeed(speed),
                                        selected = kotlin.math.abs(currentSpeed - speed) < 0.001f,
                                        onSelect = { onSelectSpeed(speed) },
                                    )
                                }
                            }
                            AndroidPlayerSettingsPane.DANMAKU -> {
                                item { SheetSwitchRow(
                                    label = "显示弹幕",
                                    checked = danmakuConfig.enabled,
                                    onCheckedChange = { v -> onDanmakuConfigChange(danmakuConfig.copy(enabled = v)) },
                                ) }
                                item {
                                    OutlinedButton(
                                        onClick = onManualMatch,
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = danmakuApiReady,
                                    ) {
                                        Icon(
                                            Icons.Filled.Search,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp).padding(end = 4.dp),
                                        )
                                        Text("手动匹配弹幕")
                                    }
                                }
                                item { SheetSliderRow(
                                    title = "不透明度", valueText = "%.0f%%".format(danmakuConfig.opacity * 100),
                                    value = danmakuConfig.opacity, range = 0.2f..1f,
                                    onValueChangeFinished = { v -> onDanmakuConfigChange(danmakuConfig.copy(opacity = v)) },
                                ) }
                                item { SheetSliderRow(
                                    title = "字号", valueText = if (danmakuConfig.fontSize <= 0) "默认" else "%.0f".format(danmakuConfig.fontSize),
                                    value = if (danmakuConfig.fontSize <= 0) 0f else danmakuConfig.fontSize, range = 0f..48f,
                                    onValueChangeFinished = { v -> onDanmakuConfigChange(danmakuConfig.copy(fontSize = v)) },
                                ) }
                                item { SheetSliderRow(
                                    title = "滚动速度", valueText = "%.1fx".format(danmakuConfig.speedMultiplier),
                                    value = danmakuConfig.speedMultiplier, range = 0.5f..2f,
                                    onValueChangeFinished = { v -> onDanmakuConfigChange(danmakuConfig.copy(speedMultiplier = v)) },
                                ) }
                                item { SheetSliderRow(
                                    title = "显示区域", valueText = "%.0f%%".format(danmakuConfig.displayArea * 100),
                                    value = danmakuConfig.displayArea, range = 0.3f..1f,
                                    onValueChangeFinished = { v -> onDanmakuConfigChange(danmakuConfig.copy(displayArea = v)) },
                                ) }
                                item { SheetSliderRow(
                                    title = "同屏上限", valueText = if (danmakuConfig.maxOnScreen <= 0) "自动（最多5000）" else danmakuConfig.maxOnScreen.toString(),
                                    value = danmakuConfig.maxOnScreen.toFloat(), range = 0f..300f,
                                    onValueChangeFinished = { v -> onDanmakuConfigChange(danmakuConfig.copy(maxOnScreen = v.toInt())) },
                                ) }
                                item {
                                    Text("渲染内核", style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                                }
                                item { SheetOptionRow(
                                    label = "Canvas drawText(默认, 描边+填充, 效果好)",
                                    selected = danmakuConfig.engineType == io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType.COMPOSE,
                                    onSelect = { onDanmakuConfigChange(danmakuConfig.copy(engineType = io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType.COMPOSE)) },
                                ) }
                                item { SheetOptionRow(
                                    label = "位图缓存(预渲染贴图, 高密度更省 GPU)",
                                    selected = danmakuConfig.engineType == io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType.BITMAP,
                                    onSelect = { onDanmakuConfigChange(danmakuConfig.copy(engineType = io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType.BITMAP)) },
                                ) }
                                item { SheetOptionRow(
                                    label = "Atlas 批渲染(预光栅化 atlas, draw call N->1-3, 高密度最省)",
                                    selected = danmakuConfig.engineType == io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType.ATLAS,
                                    onSelect = { onDanmakuConfigChange(danmakuConfig.copy(engineType = io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType.ATLAS)) },
                                ) }
                                item {
                                    Text(
                                        "内核说明:\n" +
                                        "• Canvas: 每帧 drawText 描边+填充(Skia GPU), 跨平台, 文字最清晰。高密度弹幕时 GPU 负载较高。\n" +
                                        "• 位图缓存: 每条唯一弹幕预渲染一次(描边+填充烘焙到位图), 之后每帧只贴图(1 次 GPU blit 替代 2 次 drawText)。高密度场景更省 GPU, 但首次出现新文本有微小光栅化开销, 内存略增。\n" +
                                        "• Atlas 批渲染: 文本烘焙到有界 atlas page(1024×1024), draw 时同 atlas 的 drawBitmap 由 RenderThread 批合并, draw call 从 N 降到 1-3, 内存比位图缓存更低(atlas page 复用 vs 逐条 Bitmap)。高密度首选。\n" +
                                        "三者运动/轨道/倍速/暂停行为一致(共享 BaseDanmakuEngine)。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                }
                                item { SheetSwitchRow(
                                    label = "匹配方式气泡提醒",
                                    subtitle = "每次匹配到弹幕弹 2s 提示(tmdb/哈希/文件名)",
                                    checked = danmakuShowMatchToast,
                                    onCheckedChange = onDanmakuMatchToastChange,
                                ) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SettingsPaneButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, modifier = Modifier.weight(1f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SheetOptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            label,
            modifier = Modifier.padding(start = 8.dp),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 弹层内开关行(显示弹幕 / 匹配气泡提醒 用)。 */
@Composable
private fun SheetSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 弹层内滑条行(弹幕不透明度/字号/速度/区域 用)。
 * 松手才回调 [onValueChangeFinished](对齐 SettingsScreen 的写盘时机, 避免拖动中频繁写设置)。
 */
@Composable
private fun SheetSliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: (Float) -> Unit,
) {
    var local by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { local = value }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            valueRange = range,
            onValueChangeFinished = { onValueChangeFinished(local) },
        )
    }
}

private fun trackLabel(track: io.github.weiyongzenqi.unuplayer.core.player.TrackInfo): String = buildString {
    track.title?.let { append(it) }
    track.lang?.let { if (isNotEmpty()) append(" · "); append(it) }
    if (track.external) { if (isNotEmpty()) append(" · "); append("外挂") }
    if (isEmpty()) append("轨道 ${track.id}")
}

private fun formatSpeed(speed: Float): String =
    if (speed == 1f) "1x" else "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x"

/** 弹幕匹配方式 -> 中文标签(气泡提醒用)。 */
private fun matchMethodLabel(method: io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchMethod): String = when (method) {
    io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchMethod.TMDB_QUICK -> "TMDB快速"
    io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchMethod.HASH -> "哈希"
    io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchMethod.FILENAME_SEARCH -> "文件名搜索"
    io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchMethod.MANUAL -> "手动匹配"
    io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchMethod.NONE -> "未匹配"
}

/**
 * 按用户正则匹配轨道的 title+lang(自动选轨用)。
 * 非法正则回退到子串包含(忽略大小写), 避免手误输错正则导致永不选轨。
 */
private fun io.github.weiyongzenqi.unuplayer.core.player.TrackInfo.matchesPattern(pattern: String): Boolean {
    val searchable = buildString {
        title?.let { append(it); append(' ') }
        lang?.let { append(it) }
    }
    val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
    return regex?.containsMatchIn(searchable) ?: searchable.contains(pattern, ignoreCase = true)
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 手势提示浮层: 半透明圆角胶囊, 居中或顶部, 显示图标+文本(倍速/seek 时间/亮度/音量)。 */
@Composable
private fun GestureHint(
    text: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier = modifier
            .padding(top = 24.dp)
            .background(Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.padding(end = 8.dp))
        }
        Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 播放失败覆盖层。半透明黑底 + 居中卡片: 错误图标 + 友好标题 + 原文详情 + 重试/返回。
 *
 * state.error 来自 mpv 的 file-error 英文技术串(如 "Loading failed"), [friendlyError]
 * 做轻量中文映射 + 保留原文小字供排查。
 */
@Composable
private fun PlaybackErrorOverlay(
    error: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, hint) = friendlyError(error)
    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(max = 360.dp).padding(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (hint != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(16.dp))
                // 原文: 供排查用, 小字次色, 截断长串
                Text(
                    error,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("返回")
                    }
                    OutlinedButton(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重试")
                    }
                }
            }
        }
    }
}

/**
 * 把 mpv file-error 英文串映射为友好中文标题 + 可选提示。返回 (title, hint?)。
 * 不命中则用通用提示。原文仍由覆盖层底部小字展示, 此处只做首屏可读性。
 */
private fun friendlyError(error: String): Pair<String, String?> {
    val e = error.lowercase()
    return when {
        "unreachable" in e || "network" in e || "timed out" in e || "timeout" in e ->
            "网络连接失败" to "请检查网络或 WebDAV 服务器是否可达"
        "permission" in e || "401" in e || "403" in e || "unauthorized" in e || "forbidden" in e ->
            "无访问权限" to "账号密码错误, 或服务器拒绝访问该文件"
        "404" in e || "not found" in e ->
            "文件不存在" to "URL 已失效或路径错误"
        "tls" in e || "certificate" in e || "ssl" in e || "handshake" in e ->
            "HTTPS 证书验证失败" to "可在设置→安全打开「允许 TLS 降级」后重试(有中间人风险)"
        "loading failed" in e || "failed to open" in e ->
            "无法加载视频" to "格式不支持、链接失效或服务器拒绝"
        "mpv create failed" in e ->
            "播放内核启动失败" to "尝试重启 App; 若反复出现请开启日志反馈"
        else -> "播放失败" to null
    }
}
