package io.github.weiyongzenqi.unuplayer.core.player

import android.content.Context
import android.view.Surface
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformInfo
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private class LoadOwnershipLostException : RuntimeException()
private class InitializationReleasedException : RuntimeException()

/**
 * Android 端 PlayerEngine 实现: 包装 libmpv-android 的 MPVLib。
 *
 * 线程模型(必读, 见 DESIGN.md §7.6):
 * - MPVLib.EventObserver 回调全在 libmpv-android 内部 pthread 事件线程, **不是主线程**。
 * - 本类用 MutableStateFlow.update {} 更新 state(线程安全), Compose 端主线程收集。
 * - destroy() 阻塞(pthread_join), 调用方必须在 IO 协程调用。
 *
 * 生命周期顺序(严格):
 *   create() → setOptionString*() → init() → attachSurface() → command(loadfile)
 */
class MpvPlayerEngine(
    private val context: Context,
    private val platformInfo: PlatformInfo,
    @Suppress("unused") private val mainDispatcher: CoroutineDispatcher,  // 预留: 需要时用于 marshal UI 副作用
    private val logger: io.github.weiyongzenqi.unuplayer.platform.AppLogger? = null,
) : PlayerEngine {

    override val kernelName: String = "MPV"

    @Volatile private var mpv: MPVLib? = null
    private val lifecycleState = MpvLifecycleState()
    private val surfaceBindings = MpvSurfaceBindingState<Surface>()
    private val loadTargetCoordinator = MpvLoadTargetCoordinator(AndroidMpvDetachedFdAccess(context))

    // === 渲染后端(Vulkan/OpenGL)。gpu-api 是 init-only, SDR/HDR 切换需 reinit 整个 mpv。 ===
    // SDR -> OpenGL+零拷贝(最省电); HDR -> Vulkan+拷回(HDR 直出, 零拷贝在 Vulkan 下不可用)。
    private enum class RenderBackend { OPENGL, VULKAN }
    @Volatile private var currentBackend: RenderBackend = RenderBackend.OPENGL
    @Volatile private var currentUrl: String? = null        // 保存稳定原始 URL(content:// 也不转换), HDR reinit 时重新解析并 load
    @Volatile private var currentConfig: PlayerConfig? = null   // init 时存, HDR reinit 复用
    @Volatile private var reinitDone: Boolean = false       // 防止 reinit 后 FILE_LOADED 再触发
    @Volatile private var reiniting: Boolean = false        // reinit 进行中, 拦截并发操作

    // === B1: HDR reinit 会 destroy+重建 mpv, 轨道选择与外挂字幕随之丢失, 需快照 + 重放 ===
    // picked* 记录最近一次 setAudioTrack/setSubtitleTrack 写入的 aid/sid 及轨道指纹(title/lang/external);
    // reinit 前打快照 pendingTrackRestore, 新 mpv 的 track-list 就绪后按 id(优先)或指纹匹配重放,
    // 匹配不到保留 mpv 默认并记日志。快照在一次成功重放或新 load(loadIfActive)时清除。
    private data class TrackMatch(val title: String?, val lang: String?, val external: Boolean)
    private data class ExternalSubtitleEntry(val path: String, val title: String?)
    private class TrackRestoreSnapshot(
        val sourceUrl: String?,                   // 快照所属文件: 重放命令经 FIFO 排队可能延迟, 执行前比对防作用于后续文件
        val audioId: Int?,
        val audioMatch: TrackMatch?,
        val subtitleId: Int?,
        val subtitleMatch: TrackMatch?,
        val externalSubtitles: List<ExternalSubtitleEntry>,
        var externalReadded: Boolean = false,     // sub-add 只重放一次(防 updateTrackList 反复触发重复加载)
        var audioRestored: Boolean = false,
        var subtitleRestored: Boolean = false,
        var attempts: Int = 0,                    // 重试计数: 外挂字幕 sub-add 异步入 track-list, 需多等几轮
    )
    @Volatile private var pickedAudioId: Int? = null
    @Volatile private var pickedAudioMatch: TrackMatch? = null
    @Volatile private var pickedSubtitleId: Int? = null
    @Volatile private var pickedSubtitleMatch: TrackMatch? = null
    private val externalSubsLock = Any()
    private val externalSubtitles = mutableListOf<ExternalSubtitleEntry>()
    @Volatile private var pendingTrackRestore: TrackRestoreSnapshot? = null

    // B4-Android: 已有活跃播放时 loadfile replace 会先给旧文件发 END_FILE(reason=stop, 无 file-error),
    // 不加标志会被当 EOF 置 ENDED/eof=true, 重试加载时状态闪烁。load 前置位, END_FILE 无错且为真 → 跳过, FILE_LOADED 清。
    @Volatile private var replacingFile: Boolean = false

    private val _state = MutableStateFlow(PlayerState())
    override val state = _state.asStateFlow()

    /** 当前播放位置(ms)。time-pos 高频更新单独走此流, 不进 _state, 避免整个 UI 每帧重组。 */
    private val _position = MutableStateFlow(0L)
    override val position = _position.asStateFlow()

    private val _mediaInfo = MutableStateFlow<MediaInfo?>(null)
    override val mediaInfo = _mediaInfo.asStateFlow()

    private val _tracks = MutableStateFlow(TrackList())
    override val tracks = _tracks.asStateFlow()

    private val observers = mutableListOf<PlayerEventObserver>()
    private val observersLock = Any()

    // === 生命周期(用 synchronized 保护, 修 P0-2/3/4 竞态) ===

    private val lifecycleLock = Any()
    /** 串行可能与 stop/destroy 冲突的 native 事务；任何路径都按 native → lifecycle 顺序取锁。 */
    private val nativeCommandLock = ReentrantLock(true)

    override fun init(config: PlayerConfig) {
        synchronized(lifecycleLock) {
            lifecycleState.beginInitialization()
        }
        // 失败后的同一 engine 重试不能保留旧 ERROR；否则调用方等待 READY/ERROR 时会立刻
        // 命中上一次错误，即使本次 native init 已成功也不会继续正常加载/播放。
        _state.update { it.copy(status = PlaybackStatus.IDLE, buffering = false, error = null) }

        var created: MPVLib? = null
        var nativeInitialized = false
        try {
            nativeCommandLock.withLock {
                ensureInitializationActive()
                val m = MPVLib.create(context)
                    ?: throw IllegalStateException("MPVLib.create returned null")
                created = m
                synchronized(lifecycleLock) {
                    if (released) throw InitializationReleasedException()
                    mpv = m
                    currentConfig = config   // 存配置, HDR 探测后 reinit 复用
                }

                var pendingDestroyTarget: MPVLib? = null
                runMpvInitializationSteps(
                    applyOptions = {
                        applyOptions(m, config, platformInfo.supportsHdr)
                        // 日志 option 同样必须在 native init 前设置。
                        if (logger != null) {
                            m.setOptionString("log-level", config.logLevel)
                            logger.appEvent("engine", "init log-level=${config.logLevel}")
                        }
                    },
                    initializeNative = {
                        m.init()
                        nativeInitialized = true
                    },
                    registerObservers = {
                        m.addObserver(MpvEventBridge())
                        registerObservers(m)
                        if (logger != null) m.addLogObserver(MpvLogBridge())
                    },
                    applyInitialSubtitleStyle = {
                        applySubtitleStyle(
                            m = m,
                            font = config.subtitleFont,
                            fontDir = config.subtitleFontDir,
                            scale = config.subtitleScale,
                            color = config.subtitleColor,
                            borderSize = config.subtitleBorderSize,
                            bold = config.subtitleBold,
                            styleOverride = config.subtitleStyleOverride,
                        )
                    },
                    attachSurfaceAndPublish = {
                        var published = false
                        while (!published) {
                            val surface = synchronized(lifecycleLock) {
                                if (released || mpv !== m) throw InitializationReleasedException()
                                surfaceBindings.pendingForInitialization().also { pending ->
                                    if (pending == null) {
                                        pendingDestroyTarget =
                                            if (lifecycleState.publishReady()) destroyCapture() else null
                                        published = true
                                    }
                                }
                            } ?: continue
                            m.attachSurface(surface)
                            val stable = synchronized(lifecycleLock) {
                                if (released || mpv !== m) throw InitializationReleasedException()
                                if (surfaceBindings.current === surface &&
                                    surfaceBindings.pendingForInitialization() === surface
                                ) {
                                    surfaceBindings.markAttached(surface)
                                    pendingDestroyTarget =
                                        if (lifecycleState.publishReady()) destroyCapture() else null
                                    published = true
                                    true
                                } else {
                                    false
                                }
                            }
                            if (!stable) {
                                runCatching { m.detachSurface() }.onFailure { error ->
                                    logLifecycleWarning(
                                        "初始化期间解绑过期 Surface 失败: " +
                                            "${error.javaClass.simpleName}: ${error.message}",
                                    )
                                }
                            }
                        }
                    },
                    checkActive = { ensureInitializationActive(m) },
                )
                pendingDestroyTarget?.let { target ->
                    destroyNativeTarget(target, detachSurface = true, stopBeforeDestroy = false)
                }
            }
        } catch (_: InitializationReleasedException) {
            cleanupAbortedInitialization(created, nativeInitialized)
        } catch (error: Throwable) {
            cleanupFailedInitialization(created, nativeInitialized, error)
        }
    }

    private fun ensureInitializationActive(expected: MPVLib? = null) {
        synchronized(lifecycleLock) {
            if (released || (expected != null && mpv !== expected)) {
                throw InitializationReleasedException()
            }
        }
    }

    /** 主动退出导致的初始化中止不发布 ERROR，但必须回收已创建的 native candidate。 */
    private fun cleanupAbortedInitialization(created: MPVLib?, nativeInitialized: Boolean) {
        val target = synchronized(lifecycleLock) {
            if (created != null && mpv === created) mpv = null
            if (lifecycleState.callbacksEnabled) lifecycleState.abortInitialization()
            surfaceBindings.clearPendingForDestroy()
            currentConfig = null
            created
        }
        nativeCommandLock.withLock {
            target?.let {
                destroyNativeTarget(it, detachSurface = nativeInitialized, stopBeforeDestroy = false)
            }
        }
        _state.update { it.copy(status = PlaybackStatus.IDLE, buffering = false, error = null) }
    }

    /**
     * init 任一阶段失败后的唯一回收入口。先在锁内撤销公开所有权并阻止旧回调写状态，
     * 再在 init 调用线程（正常为 Dispatchers.IO）阻塞销毁，最后才允许下一次 init。
     */
    private fun cleanupFailedInitialization(
        created: MPVLib?,
        nativeInitialized: Boolean,
        error: Throwable,
    ) {
        val target = synchronized(lifecycleLock) {
            val owned = created?.takeIf { mpv === it }
            if (owned != null) mpv = null
            lifecycleState.beginFailedCleanup()
            surfaceBindings.retainCurrentForRetry()
            currentConfig = null
            owned
        }

        _position.value = 0L
        _mediaInfo.value = null
        _tracks.value = TrackList()
        _state.update {
            it.copy(
                status = PlaybackStatus.ERROR,
                buffering = false,
                error = "mpv init failed",
            )
        }
        logLifecycleError("mpv init 失败: ${error.javaClass.simpleName}: ${error.message}")

        try {
            nativeCommandLock.withLock {
                target?.let {
                    destroyNativeTarget(it, detachSurface = nativeInitialized, stopBeforeDestroy = false)
                }
            }
        } catch (destroyError: Throwable) {
            logLifecycleError(
                "mpv init 失败后 destroy 失败: ${destroyError.javaClass.simpleName}: ${destroyError.message}",
            )
        } finally {
            synchronized(lifecycleLock) {
                if (mpv == null) {
                    lifecycleState.finishFailedCleanup()
                }
            }
        }
    }

    /** 清理路径不能再被日志实现异常打断，否则会把 native 所有权和生命周期标志留在半完成状态。 */
    private fun logLifecycleError(message: String) {
        runCatching { logger?.appEvent("engine", message, LogLevel.ERROR) }
    }

    private fun logLifecycleWarning(message: String) {
        runCatching { logger?.appEvent("engine", message, LogLevel.WARN) }
    }

    override fun destroy() {
        nativeCommandLock.withLock {
            val target = synchronized(lifecycleLock) {
                when (lifecycleState.requestDestroy()) {
                    MpvDestroyDecision.NONE,
                    MpvDestroyDecision.DEFERRED -> null
                    MpvDestroyDecision.CAPTURE_READY -> destroyCapture()
                }
            }
            target?.let { destroyNativeTarget(it, detachSurface = true, stopBeforeDestroy = false) }
        }
    }

    /**
     * 在 synchronized(lifecycleLock) 内调用：只撤销公开所有权和回调，不执行任何 native 调用。
     */
    private fun destroyCapture(): MPVLib? {
        val m = mpv ?: return null
        mpv = null
        lifecycleState.markCaptured()
        surfaceBindings.clearPendingForDestroy()
        _state.update { it.copy(status = PlaybackStatus.IDLE) }
        return m
    }

    /** 调用方必须持有 nativeCommandLock；stop/detach 失败不阻止最终 destroy。 */
    private fun destroyNativeTarget(
        target: MPVLib,
        detachSurface: Boolean,
        stopBeforeDestroy: Boolean,
    ) {
        if (stopBeforeDestroy) {
            runCatching {
                target.setPropertyBoolean("pause", true)
                target.command(arrayOf("stop"))
            }.onFailure { error ->
                logLifecycleWarning("退出时停止播放失败: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
        if (detachSurface) {
            runCatching { target.detachSurface() }.onFailure { error ->
                logLifecycleError("detachSurface 失败: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
        target.destroy()
    }

    override fun close() = destroy()

    /** 运行时短命令与退出销毁串行，避免在 JNA/native 边界使用已撤销的 mpv 引用。 */
    private fun withActiveMpv(description: String, action: (MPVLib) -> Unit) {
        if (nativeCommandLock.tryLock()) {
            try {
                runActiveMpvAction(action)
            } finally {
                nativeCommandLock.unlock()
            }
            return
        }
        val accepted = AndroidPlayerLifecycleTasks.submit(logger, description) {
            nativeCommandLock.withLock { runActiveMpvAction(action) }
        }
        if (!accepted) logLifecycleError("$description 未能进入有界 native 队列")
    }

    private fun runActiveMpvAction(action: (MPVLib) -> Unit) {
        val target = synchronized(lifecycleLock) {
            mpv?.takeIf { !released && lifecycleState.isReady }
        } ?: return
        action(target)
    }

    /**
     * 同步属性读取不能越过 release/destroy，也不能因 HDR 重建等长事务阻塞 UI 调用者。
     * 锁忙时返回 null，下一次技术信息轮询会自然重试。
     */
    private fun <T> tryReadActiveMpv(action: (MPVLib) -> T?): T? {
        if (!nativeCommandLock.tryLock()) return null
        return try {
            val target = synchronized(lifecycleLock) {
                mpv?.takeIf { !released && lifecycleState.isReady }
            } ?: return null
            action(target)
        } finally {
            nativeCommandLock.unlock()
        }
    }

    /** UI 退出只发布 released；stop/detach/destroy 全部由后台 native FIFO 串行执行。 */
    private @Volatile var released = false

    fun captureReleaseTask(): (() -> Unit)? {
        synchronized(lifecycleLock) {
            if (released) return null
            released = true
        }
        return {
            nativeCommandLock.withLock {
                val target = synchronized(lifecycleLock) {
                    when (lifecycleState.requestDestroy()) {
                        MpvDestroyDecision.NONE,
                        MpvDestroyDecision.DEFERRED -> null
                        MpvDestroyDecision.CAPTURE_READY -> destroyCapture()
                    }
                }
                target?.let { destroyNativeTarget(it, detachSurface = true, stopBeforeDestroy = true) }
            }
        }
    }

    /**
     * 切到 Vulkan 渲染后端(HDR 直出)。必须在独立线程调用(destroy 阻塞 pthread_join, 不能在事件线程)。
     *
     * 触发: 加载视频探测到 HDR 且用户要直出时, 从 OpenGL reinit 到 Vulkan。
     * SDR 视频不触发(保持 OpenGL + 零拷贝, 最省电)。
     *
     * 流程: 存 url/位置/surface -> destroy(阻塞) -> 缓存 Surface -> 切 VULKAN -> init ->
     *       发布前补绑 Surface -> load + seek 恢复位置 -> play。
     * 中途若 release 已触发(released=true), 不建新 mpv(避免泄漏)。
     */
    fun reinitToVulkan() {
        val cfg = synchronized(lifecycleLock) {
            if (released || reiniting || currentBackend == RenderBackend.VULKAN) return
            reiniting = true
            currentConfig
        } ?: run {
            synchronized(lifecycleLock) { reiniting = false }
            return
        }
        val url = currentUrl
        val posMs = _position.value
        // B1: destroy 前把当前手选 aid/sid 与已 sub-add 的外挂字幕打成待恢复快照。
        // 新 mpv 的 FILE_LOADED → updateTrackList 后由 tryRestoreTracksFromSnapshot 重放;
        // 无任何手选/外挂字幕时不设快照, 恢复路径首行短路, 对未选轨的常规 reinit 零开销。
        val audioId = pickedAudioId
        val audioMatch = pickedAudioMatch
        val subtitleId = pickedSubtitleId
        val subtitleMatch = pickedSubtitleMatch
        val externalSubs = synchronized(externalSubsLock) { externalSubtitles.toList() }
        if (audioId != null || subtitleId != null || externalSubs.isNotEmpty()) {
            pendingTrackRestore = TrackRestoreSnapshot(currentUrl, audioId, audioMatch, subtitleId, subtitleMatch, externalSubs)
            logger?.appEvent(
                "engine",
                "reinit 轨道快照 aid=$audioId sid=$subtitleId 外挂字幕=${externalSubs.size}",
                LogLevel.INFO,
            )
        }
        val surfaceSnapshot = synchronized(lifecycleLock) {
            surfaceBindings.current to surfaceBindings.generation
        }
        _state.update { it.copy(buffering = true) }   // reinit 期间显示加载中(复用 buffering, 不新增状态)
        try {
            destroy()   // 阻塞: detachSurface + pthread_join
            synchronized(lifecycleLock) {
                surfaceBindings.retainForReinitialization(surfaceSnapshot.first, surfaceSnapshot.second)
            }
            synchronized(lifecycleLock) {
                if (released) return   // 退出已触发, 不建新 mpv(避免泄漏)
                currentBackend = RenderBackend.VULKAN
                reinitDone = true
            }
            init(cfg)              // applyOptions 按 VULKAN: gpu-api=vulkan + hwdec=mediacodec-copy
            if (url != null) {
                // isReinitRestore=true: 保留 pendingTrackRestore 快照供 FILE_LOADED 后重放(新 load 才会清)。
                if (!loadIfActive(url, isReinitRestore = true)) {
                    destroy()
                    return
                }
                val abort = synchronized(lifecycleLock) { released }
                if (abort) {
                    destroy()
                    return
                }
                if (posMs > 500) seekTo(posMs)   // 恢复位置(太短不 seek, 省一次 range 请求)
                play()
            }
        } catch (e: Throwable) {
            logger?.appEvent("engine", "reinit Vulkan 失败: ${e.javaClass.simpleName}: ${e.message}")
            _state.update { it.copy(status = PlaybackStatus.ERROR, error = "HDR 模式切换失败") }
        } finally {
            synchronized(lifecycleLock) { reiniting = false }
        }
    }

    /**
     * HDR 探测: 当前视频是 HDR 且用户要直出时, dispatch reinit 到 Vulkan。
     * 事件线程调用判断, reinit 本身 dispatch 到独立线程(destroy 阻塞)。
     * reinitDone 防重入; 非 HDR / 不需直出 / 已 Vulkan 则跳过(零开销, 不影响 SDR 主路径)。
     */
    private fun maybeReinitToVulkanForHdr() {
        if (reiniting || reinitDone) return
        if (currentBackend != RenderBackend.OPENGL) return
        val cfg = currentConfig ?: return
        // 仅 hdrMode 需直出时才考虑 reinit(OFF/TONE_MAP_SDR 始终 OpenGL, 不 reinit)
        val wantPassthrough = cfg.hdrMode == HdrMode.HDR_PASSTHROUGH ||
            (cfg.hdrMode == HdrMode.AUTO && platformInfo.supportsHdr)
        if (!wantPassthrough) return
        // 复用 updateMediaInfoSnapshot 已算的 hdrInfo(避免重复 getPropertyString JNI)
        if (_mediaInfo.value?.hdrInfo?.isHDR != true) return   // SDR 不 reinit
        reinitDone = true   // 先占位, 防 dispatch 期间 RECONFIG 重复触发
        logger?.appEvent("engine", "HDR -> reinit Vulkan", LogLevel.INFO)
        if (!AndroidPlayerLifecycleTasks.submit(
                logger,
                "HDR Vulkan 重建",
                onDropped = { reinitDone = false },
                task = ::reinitToVulkan,
            )
        ) {
            reinitDone = false
        }
    }

    // === 选项(必须在 init() 前) ===

    private fun applyOptions(m: MPVLib, config: PlayerConfig, hdrCapable: Boolean) {
        // 渲染后端由 currentBackend 决定: init 默认 OpenGL(SDR 友好 + 零拷贝可用); 加载 HDR 视频后 reinit 切 Vulkan。
        // 不再凭 hdrMode+设备直接选 Vulkan: 否则 SDR 视频也被迫走 Vulkan, 零拷贝硬解失效 + 白耗 fp16 功耗。
        if (currentBackend == RenderBackend.VULKAN) {
            m.setOptionString("vo", config.vo)
            m.setOptionString("gpu-context", "androidvk")
            m.setOptionString("gpu-api", "vulkan")
            // HDR 浮点格式: 仅 HDR 路径需要 fp16 精度。SDR 片源不设(用 mpv 默认 rgb8),
            // 避免每帧走 fp16 浮点管线(浮点混合 + 双倍带宽)的无谓 GPU 功耗——二次元动漫几乎都是 SDR。
            m.setOptionString("fbo-format", "rgba16f")
        } else {
            m.setOptionString("vo", config.vo)
            m.setOptionString("gpu-context", "android")
            m.setOptionString("gpu-api", "opengl")
        }
        // 解码: Vulkan 下零拷贝(mediacodec)不可用, 强制拷回(mediacodec-copy); 尊重软解(no)。
        //   OpenGL 下用用户配置(默认零拷贝最省电)。
        val hwdec = if (currentBackend == RenderBackend.VULKAN && config.hwdec != "no") "mediacodec-copy" else config.hwdec
        m.setOptionString("hwdec", hwdec)
        // 音频
        m.setOptionString("ao", config.audioOutput)
        m.setOptionString("audio-channels", "7.1,5.1,stereo")
        m.setOptionString("audio-pitch-correction", "yes")
        m.setOptionString("af", "scaletempo2=min-speed=${config.minSpeed}:max-speed=${config.maxSpeed}")
        // HDR
        applyHdrOptions(m, config.hdrMode, hdrCapable)
        // 缓存: 只放内存, 不写盘; 严格按 cacheSize 上限, 超限自动回收最旧数据。
        // demuxer-seekable-cache=yes: 缓存可 seek(内存), 短暂左右滑回放不重新请求不卡。
        //   注意: seekable-cache 是"内存里可回放", 与写盘无关; 防写盘靠 cache-on-disk=no。
        // demuxer-seekable-cache-min: 保留"上一段"可回放的最小量(75%), 配合 max-bytes 自动淘汰,
        //   即最多保留当前点附近 + 上一段, 超限丢最旧 → 及时回收。
        m.setOptionString("cache", "yes")
        m.setOptionString("demuxer-max-bytes", "${config.cacheSize}MiB")
        m.setOptionString("demuxer-seekable-cache", "yes")
        m.setOptionString("demuxer-seekable-cache-min", "75%")
        m.setOptionString("cache-secs", "${config.cacheSecs}")
        m.setOptionString("cache-on-disk", "no")              // 显式: 缓存只放内存, 永不写盘
        // 不设 demuxer-cache-dir(设了才会写盘)
        // 软解性能
        m.setOptionString("vd-lavc-threads", "0")
        m.setOptionString("framedrop", "vo")
        // 播放结束不自动关闭, 便于续播/重播
        m.setOptionString("keep-open", "yes")
        // 初始暂停: loadfile 后 mpv 不自动播放, 等续播 seek(在 pause 态稳定生效)完成后再 play()。
        // 避免 mpv 默认 pause=no 自动播放(PLAYING)时, seek 撞上初始 demux 冲突导致 seek 失效/卡加载。
        m.setOptionString("pause", "yes")
        // HTTP 头(WebDAV basic auth 用 Authorization 头, 不再用 URL 内嵌 user:pass@)。
        // http-header-fields 是 STRING_LIST, setOptionString 接受逗号分隔; init 前设。
        if (config.httpHeaders.isNotEmpty()) {
            val joined = config.httpHeaders.entries.joinToString(",") { "${it.key}: ${it.value}" }
            m.setOptionString("http-header-fields", joined)
        }
        // 网络超时(B2-Android): 无响应 WebDAV 服务器(握手后不回包也不断开)会让 ffmpeg http demux
        // 无限挂起, 用户卡"缓冲中"没有错误页可重试。30s 上限, 超时后 END_FILE 带 file-error → ERROR 页。
        // 必须在 init 前设置(危险区 #2: setOptionString init 后静默失败)。
        m.setOptionString("network-timeout", "30")
        // TLS: libmpv 的 ffmpeg 用 OpenSSL, OpenSSL 在 Android 上找不到系统 CA 文件
        // (set_default_verify_paths 指向的 /etc/ssl/certs/ 等路径不存在), 故需导出系统 CA
        // 为 PEM bundle 设给 tls-ca-file, 否则 HTTPS 握手无法验证证书。
        // 系统 CA 不可用时的策略由 allowTlsInsecure 决定:
        //   false(默认): 保持 tls-verify=yes, 让握手失败暴露问题(宁可播不了也不偷偷不验证);
        //   true: 回退 tls-verify=no 能播但 HTTPS 不验证身份(中间人风险, 用户知情同意)。
        val caBundle = io.github.weiyongzenqi.unuplayer.platform.SystemCaBundle.ensureBundle(context)
        if (caBundle != null) {
            m.setOptionString("tls-ca-file", caBundle)
            m.setOptionString("tls-verify", "yes")
            logger?.appEvent("engine", "tls-ca-file=$caBundle verify=yes")
        } else if (config.allowTlsInsecure) {
            m.setOptionString("tls-verify", "no")
            logger?.appEvent("engine", "CA bundle 不可用, tls-verify=no(用户已开启降级开关)")
        } else {
            // 默认: 不降级。保留 tls-verify=yes(未设 ca-file 时 mpv/OpenSSL 用默认路径, 必失败),
            // 让用户看到播放失败而非悄悄不验证。开启 allowTlsInsecure 才能播此类源。
            m.setOptionString("tls-verify", "yes")
            logger?.appEvent("engine", "CA bundle 不可用, tls-verify=yes(未降级, 播放将失败)")
        }
        // UA: 部分 CDN 拒绝默认 UA, 用通用浏览器 UA 提高兼容性
        m.setOptionString("user-agent", "Mozilla/5.0 (Linux; Android) UnUPlayer")
        // 字幕: 自动加载同名字幕(fuzzy) + 编码自动检测
        m.setOptionString("sub-auto", config.subAuto)
        m.setOptionString("sub-codepage", config.subCodepage)
    }

    /**
     * HDR 档位 → mpv 三件套参数(target-colorspace-hint / tone-mapping / hdr-compute-peak)。
     *
     * 这三个选项 mpv 既支持 init 前 setOptionString, 也支持 init 后 setPropertyString 运行时改
     * (见 mpv manual: mpv_set_property 可设 option; target-colorspace-hint 运行时 set 可行)。
     * 故 init 与运行时热切换共用本函数, 避免两套参数不一致(旧 bug: init 设 yes, 运行时只改
     * tone-mapping 漏改 target-colorspace-hint → 切档后仍残留直通)。
     *
     * gpu-api/gpu-context/fbo-format 才是真 init-only, 但只决定 Vulkan/OpenGL + fp16 管线,
     * 不决定 SDR/HDR 输出色彩空间——故运行时切 SDR/HDR 不必换 gpu-api, 三件套够用。
     * 返回 Triple(hint, toneMapping, computePeak)。
     */
    private fun hdrParams(mode: HdrMode, hdrCapable: Boolean): Triple<String, String, String> = when (mode) {
        HdrMode.AUTO -> if (hdrCapable) {
            // AUTO + HDR 屏: 直通(target-colorspace-hint=yes 让 swapchain 通知 SurfaceFlinger BT.2020 PQ/HLG)。
            Triple("yes", "auto", "auto")
        } else {
            // AUTO + 非 HDR 屏: tone-map 到 SDR。
            Triple("no", "auto", "auto")
        }
        HdrMode.TONE_MAP_SDR -> Triple("no", "auto", "auto")   // 强制 tone-map 到 SDR(最可靠)
        HdrMode.OFF -> Triple("no", "clip", "no")              // 当 SDR 处理: clip 截断 HDR 超亮部
        HdrMode.HDR_PASSTHROUGH -> Triple("yes", "clip", "no") // 强制直通: 不二次映射
    }

    private fun applyHdrOptions(m: MPVLib, mode: HdrMode, hdrCapable: Boolean) {
        // OpenGL 后端不支持 HDR swapchain, 始终 tone-map SDR; Vulkan 按 hdrMode 直出/映射。
        val (hint, tone, peak) = if (currentBackend == RenderBackend.VULKAN) hdrParams(mode, hdrCapable)
            else hdrParams(HdrMode.TONE_MAP_SDR, hdrCapable)
        m.setOptionString("target-colorspace-hint", hint)
        m.setOptionString("tone-mapping", tone)
        m.setOptionString("hdr-compute-peak", peak)
    }

    // === 属性观察(init() 后) ===

    private fun registerObservers(m: MPVLib) {
        val F = MPVLib.MpvFormat
        // 播放状态
        m.observeProperty("time-pos", F.MPV_FORMAT_DOUBLE)
        m.observeProperty("duration", F.MPV_FORMAT_DOUBLE)
        m.observeProperty("pause", F.MPV_FORMAT_FLAG)
        m.observeProperty("paused-for-cache", F.MPV_FORMAT_FLAG)
        m.observeProperty("eof-reached", F.MPV_FORMAT_FLAG)
        m.observeProperty("volume", F.MPV_FORMAT_INT64)
        m.observeProperty("mute", F.MPV_FORMAT_FLAG)
        m.observeProperty("speed", F.MPV_FORMAT_DOUBLE)
        // 媒体信息
        m.observeProperty("track-list/count", F.MPV_FORMAT_INT64)
        m.observeProperty("hwdec-current", F.MPV_FORMAT_STRING)
        m.observeProperty("video-params/gamma", F.MPV_FORMAT_STRING)
        m.observeProperty("video-params/primaries", F.MPV_FORMAT_STRING)
        m.observeProperty("video-params/rotate", F.MPV_FORMAT_INT64)
    }

    // === Surface(由 UI 层 SurfaceView callback 调用) ===

    /** 绑定渲染 Surface。可在 init 前后调用: init 前缓存, init 后立即绑。 */
    fun attachSurface(surface: Surface) {
        val candidate = synchronized(lifecycleLock) {
            val current = mpv
            val target = surfaceBindings.onAvailable(
                surface = surface,
                nativeReady = !released && current != null && lifecycleState.isReady,
            )
            if (target != null && current != null) current to target else null
        } ?: return
        val accepted = AndroidPlayerLifecycleTasks.submit(logger, "绑定视频 Surface") {
            nativeCommandLock.withLock {
                val canAttach = synchronized(lifecycleLock) {
                    !released && mpv === candidate.first && lifecycleState.isReady &&
                        surfaceBindings.current === surface
                }
                if (canAttach) candidate.first.attachSurface(candidate.second)
            }
        }
        if (!accepted) logLifecycleError("绑定视频 Surface 未能进入有界 native 队列")
    }

    fun detachSurface() {
        val snapshot = synchronized(lifecycleLock) {
            surfaceBindings.onDestroyed()
            Triple(mpv, surfaceBindings.generation, lifecycleState.isReady)
        }
        if (!snapshot.third || snapshot.first == null) return
        val accepted = AndroidPlayerLifecycleTasks.submit(logger, "解绑视频 Surface") {
            nativeCommandLock.withLock {
                val canDetach = synchronized(lifecycleLock) {
                    mpv === snapshot.first && surfaceBindings.generation == snapshot.second
                }
                if (canDetach) snapshot.first?.detachSurface()
            }
        }
        if (!accepted) logLifecycleError("解绑视频 Surface 未能进入有界 native 队列")
    }

    // === 播放控制 ===

    override fun load(url: String) {
        loadIfActive(url, isReinitRestore = false)
    }

    /**
     * 打开 content fd 在锁外；loadfile 与 stop/destroy 由 nativeCommandLock 串行。
     *
     * @param isReinitRestore true=HDR reinit 恢复加载同一文件: 保留 pendingTrackRestore 快照供
     *        FILE_LOADED 后重放手选轨道(B1); false=新 load(含错误页重试), 连同快照一起清除。
     */
    private fun loadIfActive(url: String, isReinitRestore: Boolean): Boolean {
        // 凭据通过 init 时设的 http-header-fields 带入, mpv 直连 https(ffmpeg OpenSSL 后端)。
        val hadActiveFile = synchronized(lifecycleLock) {
            if (released) return false
            val had = currentUrl != null
            currentUrl = url        // 保留原始 URL, HDR reinit 后重新解析; content:// 会重新打开新 fd
            reinitDone = false      // 新文件允许再触发 HDR reinit
            had
        }
        // B4-Android: 覆盖已有文件时, 旧文件的 END_FILE(无 file-error)随后到达, 标志防其误报 ENDED。
        if (hadActiveFile) replacingFile = true
        // B1: 新文件与旧文件的轨道 id/外挂字幕互不相干, 清除上一文件的选择状态;
        // 待恢复快照仅在非 reinit 恢复的新 load 时清(reinit 路径靠它重放)。
        pickedAudioId = null
        pickedAudioMatch = null
        pickedSubtitleId = null
        pickedSubtitleMatch = null
        synchronized(externalSubsLock) { externalSubtitles.clear() }
        if (!isReinitRestore) pendingTrackRestore = null
        val m = synchronized(lifecycleLock) { mpv }
        if (m == null) {
            // P3①: reinit 中途 destroy 抛错等导致 mpv=null 时, 显式发布 ERROR 给用户可理解的反馈,
            // 而非静默 return false(旧行为: 错误页点重试无反应, 用户无从判断)。
            _state.update {
                it.copy(status = PlaybackStatus.ERROR, buffering = false, error = "播放内核已释放, 请退出重进")
            }
            logLifecycleError("loadIfActive: mpv 已释放, 发布 ERROR(url=$url)")
            return false
        }
        _position.value = 0L   // 重置进度, 避免新文件 time-pos 到达前显示旧位置
        // P3②: 错误页重试时旧 ERROR 不清会导致等待就绪的逻辑立刻命中旧错误; load 即"开始新加载",
        // 把 ERROR 复位为 IDLE 等本次结果(不动其他状态, 避免影响 reinit 的 buffering 指示)。
        _state.update {
            if (it.status == PlaybackStatus.ERROR) it.copy(status = PlaybackStatus.IDLE, error = null, eof = false) else it
        }
        return try {
            loadTargetCoordinator.load(url) { targetUrl ->
                nativeCommandLock.withLock {
                    synchronized(lifecycleLock) {
                        if (released || mpv !== m || !lifecycleState.isReady) {
                            throw LoadOwnershipLostException()
                        }
                    }
                    m.command(arrayOf("loadfile", targetUrl, "replace"))
                }
            }
            true
        } catch (_: LoadOwnershipLostException) {
            false
        }
    }

    override fun play() { withActiveMpv("继续播放") { it.setPropertyBoolean("pause", false) } }
    override fun pause() { withActiveMpv("暂停播放") { it.setPropertyBoolean("pause", true) } }

    override fun seekTo(positionMs: Long) {
        val secs = positionMs / 1000.0
        // absolute(关键帧 seek, 快): 拖动进度条松手后快速落地。
        // 不用 absolute+exact(精确 HR-seek): 后者要解码到精确帧, webdav 流式播放下
        // 每次 seek 触发 range 请求, exact 会明显卡顿不跟手。关键帧偏差可接受。
        withActiveMpv("跳转播放位置") { it.command(arrayOf("seek", secs.toString(), "absolute")) }
        logger?.appEvent("engine", "seek ${positionMs}ms", LogLevel.INFO)
    }

    override fun setVolume(volume: Int) {
        withActiveMpv("调整音量") { it.setPropertyInt("volume", volume.coerceIn(0, 100)) }
    }

    override fun setRate(rate: Float) {
        withActiveMpv("调整倍速") { it.setPropertyDouble("speed", rate.toDouble()) }
        logger?.appEvent("engine", "rate=$rate", LogLevel.INFO)
    }

    override fun setMuted(muted: Boolean) {
        withActiveMpv("切换静音") { it.setPropertyBoolean("mute", muted) }
    }

    // === 轨道 ===

    override fun setAudioTrack(id: Int) {
        // B1: 记录最近一次手选(自动选轨也走这里), HDR reinit 后据此恢复; 顺带存轨道指纹供 id 变化时匹配。
        pickedAudioId = id
        pickedAudioMatch = _tracks.value.audio.firstOrNull { it.id == id }
            ?.let { TrackMatch(it.title, it.lang, it.external) }
        withActiveMpv("切换音轨") { it.setPropertyInt("aid", id) }
    }
    override fun setSubtitleTrack(id: Int) {
        // B1: 同上; sid=0 表示"关闭字幕"(有效选择, 也需记录并在 reinit 后重放)。
        pickedSubtitleId = id
        pickedSubtitleMatch = _tracks.value.subtitle.firstOrNull { it.id == id }
            ?.let { TrackMatch(it.title, it.lang, it.external) }
        withActiveMpv("切换字幕轨") { it.setPropertyInt("sid", id) }
    }
    override fun setVideoTrack(id: Int) { withActiveMpv("切换视频轨") { it.setPropertyInt("vid", id) } }

    // === 字幕(外挂加载 + 样式) ===

    override fun addExternalSubtitle(path: String, title: String?) {
        // B1: 记录已加载的外挂字幕(path+title, flags 固定 cached), HDR reinit 后按此列表重新 sub-add。
        synchronized(externalSubsLock) { externalSubtitles.add(ExternalSubtitleEntry(path, title)) }
        // sub-add 语法: sub-add <url> [<flags> [<title> [<lang>]]], title/lang 是位置参数
        // (不是 key=value)。cached=yes 缓存到内存。
        val args = mutableListOf("sub-add", path, "cached")
        if (!title.isNullOrBlank()) {
            args.add(title)
        }
        withActiveMpv("加载外挂字幕") { it.command(args.toTypedArray()) }
    }

    override fun applySubtitleStyle(
        font: String,
        fontDir: String?,
        scale: Float,
        color: String,
        borderSize: Float,
        bold: Boolean,
        styleOverride: String,
    ) {
        withActiveMpv("应用字幕样式") { m ->
            applySubtitleStyle(m, font, fontDir, scale, color, borderSize, bold, styleOverride)
        }
    }

    private fun applySubtitleStyle(
        m: MPVLib,
        font: String,
        fontDir: String?,
        scale: Float,
        color: String,
        borderSize: Float,
        bold: Boolean,
        styleOverride: String,
    ) {
        // sub-ass-override: force/scale 才让 sub-* 样式作用于 ASS 字幕
        m.setPropertyString("sub-ass-override", styleOverride)
        if (font.isNotEmpty()) m.setPropertyString("sub-font", font)
        if (!fontDir.isNullOrBlank()) m.setPropertyString("sub-fonts-dir", fontDir)
        m.setPropertyString("sub-scale", scale.toString())
        m.setPropertyString("sub-color", color)
        m.setPropertyString("sub-border-size", borderSize.toString())
        m.setPropertyBoolean("sub-bold", bold)
    }

    // === 运行时热切换 ===

    override fun setHardwareDecoding(mode: String) {
        // Vulkan 下零拷贝(mediacodec)不可用, 热切换也强制拷回(尊重软解 no)
        val effective = if (currentBackend == RenderBackend.VULKAN && mode != "no") "mediacodec-copy" else mode
        withActiveMpv("切换硬件解码") { it.setPropertyString("hwdec", effective) }
        logger?.appEvent("engine", "hwdec=$effective", LogLevel.INFO)
    }
    override fun setAudioOutput(ao: String) { withActiveMpv("切换音频输出") { it.setPropertyString("ao", ao) } }

    override fun setHdrMode(mode: HdrMode) {
        // 运行时热切换: target-colorspace-hint/tone-mapping/hdr-compute-peak 三件套 mpv 支持
        // init 后 setPropertyString 改(见 hdrParams 注释)。与 init 共用 hdrParams 保证一致。
        // gpu-api/gpu-context/fbo-format 是 init-only, 运行时不换, 但不影响 SDR/HDR 输出切换。
        withActiveMpv("切换 HDR 模式") { m ->
            // OpenGL 后端始终 SDR(不支持 HDR swapchain); Vulkan 按 hdrMode。HDR 直出需 Vulkan(由 reinit 切)。
            val (hint, tone, peak) = if (currentBackend == RenderBackend.VULKAN) hdrParams(mode, platformInfo.supportsHdr)
                else hdrParams(HdrMode.TONE_MAP_SDR, platformInfo.supportsHdr)
            m.setPropertyString("target-colorspace-hint", hint)
            m.setPropertyString("tone-mapping", tone)
            m.setPropertyString("hdr-compute-peak", peak)
            logger?.appEvent("engine", "hdrMode=$mode backend=$currentBackend hint=$hint", LogLevel.INFO)
        }
    }

    // === mpv 原生透传 ===

    override fun getPropertyString(name: String): String? = tryReadActiveMpv { it.getPropertyString(name) }
    override fun getPropertyInt(name: String): Int? = tryReadActiveMpv { it.getPropertyInt(name) }
    override fun getPropertyDouble(name: String): Double? = tryReadActiveMpv { it.getPropertyDouble(name) }
    override fun getPropertyBoolean(name: String): Boolean? = tryReadActiveMpv { it.getPropertyBoolean(name) }
    override fun setPropertyString(name: String, value: String) { withActiveMpv("设置 mpv 属性 $name") { it.setPropertyString(name, value) } }
    override fun setOptionString(name: String, value: String) { withActiveMpv("设置 mpv 选项 $name") { it.setOptionString(name, value) } }
    override fun observeProperty(name: String, format: Int) { withActiveMpv("观察 mpv 属性 $name") { it.observeProperty(name, format) } }
    override fun command(args: Array<String>) { withActiveMpv("执行 mpv 命令 ${args.firstOrNull().orEmpty()}") { it.command(args) } }

    // === 事件观察 ===
    // hasObservers: 无 observer 时 dispatch 热路径(time-pos ~每帧)直接 volatile 读短路,
    // 跳过 synchronized + toList 分配。当前应用层无人 addObserver, 此标志恒 false。

    @Volatile private var hasObservers = false

    override fun addObserver(observer: PlayerEventObserver) {
        synchronized(observersLock) {
            observers.add(observer)
            hasObservers = true
        }
    }

    override fun removeObserver(observer: PlayerEventObserver) {
        synchronized(observersLock) {
            observers.remove(observer)
            hasObservers = observers.isNotEmpty()
        }
    }

    private fun dispatchEvent(event: PlayerEvent) {
        if (!hasObservers) return
        val snapshot = synchronized(observersLock) { observers.toList() }
        snapshot.forEach { it.onEvent(event) }
    }

    private fun dispatchProperty(name: String, value: Any?) {
        if (!hasObservers) return
        val snapshot = synchronized(observersLock) { observers.toList() }
        snapshot.forEach { it.onPropertyChanged(name, value) }
    }

    // === 媒体信息快照(FILE_LOADED / VIDEO_RECONFIG 后拉) ===

    private fun updateMediaInfoSnapshot() {
        val m = mpv ?: return
        val gamma = m.getPropertyString("video-params/gamma")
        val primaries = m.getPropertyString("video-params/primaries")
        val isHdr = gamma == "pq" || gamma == "hlg"
        val width = m.getPropertyInt("video-params/w") ?: 0
        val height = m.getPropertyInt("video-params/h") ?: 0
        val rotate = m.getPropertyInt("video-params/rotate") ?: 0
        _mediaInfo.update { existing ->
            (existing ?: MediaInfo()).copy(
                title = m.getPropertyString("media-title") ?: existing?.title,
                filePath = m.getPropertyString("path") ?: existing?.filePath,
                containerFormat = m.getPropertyString("file-format") ?: existing?.containerFormat,
                videoCodec = m.getPropertyString("video-codec") ?: existing?.videoCodec,
                audioCodec = m.getPropertyString("audio-codec") ?: existing?.audioCodec,
                width = width,
                height = height,
                fps = m.getPropertyDouble("container-fps") ?: existing?.fps ?: 0.0,
                videoBitrate = m.getPropertyInt("video-bitrate") ?: existing?.videoBitrate ?: 0,
                audioBitrate = m.getPropertyInt("audio-bitrate") ?: existing?.audioBitrate ?: 0,
                audioSampleRate = m.getPropertyInt("audio-params/samplerate") ?: existing?.audioSampleRate ?: 0,
                audioChannels = m.getPropertyInt("audio-params/channel-count") ?: existing?.audioChannels ?: 0,
                durationMs = ((m.getPropertyDouble("duration") ?: 0.0) * 1000).toLong(),
                hdrInfo = if (isHdr) HdrInfo(
                    isHDR = true,
                    gamma = gamma,
                    primaries = primaries,
                    maxCll = m.getPropertyDouble("video-params/max-cll") ?: 0.0,
                ) else existing?.hdrInfo,
                hwdecCurrent = m.getPropertyString("hwdec-current") ?: existing?.hwdecCurrent,
                requestedHwdec = currentConfig?.hwdec ?: existing?.requestedHwdec,
                vo = m.getPropertyString("current-vo") ?: currentConfig?.vo ?: existing?.vo,
                gpuApi = currentBackend.name.lowercase(),
                rotation = rotate,
            )
        }
    }

    private fun updateTrackList() {
        val m = mpv ?: return
        val count = m.getPropertyInt("track-list/count") ?: 0
        val video = mutableListOf<TrackInfo>()
        val audio = mutableListOf<TrackInfo>()
        val subtitle = mutableListOf<TrackInfo>()
        for (i in 0 until count) {
            val type = m.getPropertyString("track-list/$i/type") ?: continue
            val info = TrackInfo(
                id = m.getPropertyInt("track-list/$i/id") ?: continue,
                type = when (type) {
                    "video" -> TrackType.VIDEO
                    "audio" -> TrackType.AUDIO
                    "sub" -> TrackType.SUBTITLE
                    else -> continue
                },
                title = m.getPropertyString("track-list/$i/title"),
                lang = m.getPropertyString("track-list/$i/lang"),
                codec = m.getPropertyString("track-list/$i/codec"),
                external = m.getPropertyBoolean("track-list/$i/external") == true,
                selected = m.getPropertyBoolean("track-list/$i/selected") == true,
            )
            when (info.type) {
                TrackType.VIDEO -> video.add(info)
                TrackType.AUDIO -> audio.add(info)
                TrackType.SUBTITLE -> subtitle.add(info)
            }
        }
        _tracks.update { TrackList(video, audio, subtitle) }
        // B1: reinit 后 track-list 就绪(或外挂字幕 sub-add 入列引起 count 变化)时, 按快照重放手选轨道。
        // 非 reinit 场景 pendingTrackRestore==null, 首行短路, 常规路径零开销。
        tryRestoreTracksFromSnapshot()
    }

    /**
     * B1: HDR reinit 重建 mpv 后, 按 [pendingTrackRestore] 快照重放手选 aid/sid 与外挂字幕。
     *
     * 调用点: [updateTrackList] 末尾(FILE_LOADED 与 track-list/count 属性回调都会走到), 事件 pthread。
     * 时序: FILE_LOADED 时内封轨道已入 track-list, 先 sub-add 外挂字幕(一次), 其入列引起的
     * track-list/count 变化会再次触发本函数, 届时外挂字幕轨可被 sid 匹配。重试上限后仍匹配不到
     * 则保留 mpv 默认选择并记日志(不无限重试)。全部恢复完成(或放弃)后清快照。
     * 字幕样式不在此重放: 初始样式属 init 事务(CR-009), 已随 reinit 的 init 应用。
     */
    private fun tryRestoreTracksFromSnapshot() {
        val snapshot = pendingTrackRestore ?: return
        // 恢复命令经 withActiveMpv 可能入进程级 FIFO 排队; 排队期间若已加载新文件(错误页重试),
        // 快照里的 aid/sid 会作用到错误文件——文件身份不符直接丢弃快照。
        if (snapshot.sourceUrl != currentUrl) {
            pendingTrackRestore = null
            return
        }
        snapshot.attempts++
        val tracks = _tracks.value
        // 1) 外挂字幕重新 sub-add(仅一次; 参数构造沿用 addExternalSubtitle 的语法)。
        if (!snapshot.externalReadded) {
            snapshot.externalReadded = true
            for (entry in snapshot.externalSubtitles) {
                val args = mutableListOf("sub-add", entry.path, "cached")
                if (!entry.title.isNullOrBlank()) args.add(entry.title)
                withActiveMpv("reinit 恢复外挂字幕") { it.command(args.toTypedArray()) }
                logger?.appEvent("engine", "reinit 重放外挂字幕 path=${entry.path}", LogLevel.INFO)
            }
        }
        // 2) 音轨: 外挂音频不支持, FILE_LOADED 时内封音轨已就绪, 一次决策(找到→重放; 否则放弃+日志)。
        if (!snapshot.audioRestored) {
            val target = snapshot.audioId
            if (target == null) {
                snapshot.audioRestored = true
            } else {
                val found = tracks.audio.firstOrNull { it.id == target }
                    ?: snapshot.audioMatch?.let { match ->
                        tracks.audio.firstOrNull {
                            it.external == match.external && it.title == match.title && it.lang == match.lang
                        }
                    }
                if (found != null) {
                    withActiveMpv("reinit 恢复音轨") { m -> m.setPropertyInt("aid", found.id) }
                    logger?.appEvent("engine", "reinit 恢复音轨 id=${found.id}", LogLevel.INFO)
                    snapshot.audioRestored = true
                } else if (tracks.audio.isNotEmpty() || snapshot.attempts >= TRACK_RESTORE_MAX_ATTEMPTS) {
                    logger?.appEvent("engine", "reinit 未匹配到音轨 aid=$target, 保留 mpv 默认", LogLevel.WARN)
                    snapshot.audioRestored = true
                }
            }
        }
        // 3) 字幕轨: sid=0="关闭字幕"直接重放; 有外挂字幕时 sub-add 异步入列, 允许多等几轮 track-list 更新。
        if (!snapshot.subtitleRestored) {
            val target = snapshot.subtitleId
            when {
                target == null -> snapshot.subtitleRestored = true
                target == 0 -> {
                    withActiveMpv("reinit 恢复字幕关闭") { m -> m.setPropertyInt("sid", 0) }
                    logger?.appEvent("engine", "reinit 恢复字幕关闭(sid=0)", LogLevel.INFO)
                    snapshot.subtitleRestored = true
                }
                else -> {
                    val found = tracks.subtitle.firstOrNull { it.id == target }
                        ?: snapshot.subtitleMatch?.let { match ->
                            tracks.subtitle.firstOrNull {
                                it.external == match.external && it.title == match.title && it.lang == match.lang
                            }
                        }
                    if (found != null) {
                        withActiveMpv("reinit 恢复字幕轨") { m -> m.setPropertyInt("sid", found.id) }
                        logger?.appEvent("engine", "reinit 恢复字幕轨 id=${found.id}", LogLevel.INFO)
                        snapshot.subtitleRestored = true
                    } else if (snapshot.attempts >= TRACK_RESTORE_MAX_ATTEMPTS) {
                        logger?.appEvent("engine", "reinit 未匹配到字幕轨 sid=$target, 保留 mpv 默认", LogLevel.WARN)
                        snapshot.subtitleRestored = true
                    }
                }
            }
        }
        if (snapshot.audioRestored && snapshot.subtitleRestored) pendingTrackRestore = null
    }

    private fun updateHdrInfo() {
        val m = mpv ?: return
        val gamma = m.getPropertyString("video-params/gamma")
        val primaries = m.getPropertyString("video-params/primaries")
        val isHdr = gamma == "pq" || gamma == "hlg"
        _mediaInfo.update { existing ->
            existing?.copy(
                hdrInfo = if (isHdr) HdrInfo(
                    isHDR = true, gamma = gamma, primaries = primaries,
                    maxCll = m.getPropertyDouble("video-params/max-cll") ?: 0.0,
                ) else existing.hdrInfo
            )
        }
        // HDR 迟到路径复检: 部分片源 video-params/gamma 在 FILE_LOADED 时尚未就绪, 稍后才经本函数
        // (gamma/primaries 属性回调, :996)把 isHDR 由未知/false 填成 true。此处补一次 reinit 判断,
        // 否则 HDR 被 OpenGL 强制 tone-map 成 SDR, 用户"直通"设置形同虚设且无提示。
        // 仅 isHdr==true 时复检; reinit 经 AndroidPlayerLifecycleTasks.submit 派发独立线程(符合事件线程纪律),
        // 已 reinit / SDR 场景由 maybeReinitToVulkanForHdr 首行 guard 近零开销短路, 不额外读属性(复用 _mediaInfo)。
        if (isHdr) maybeReinitToVulkanForHdr()
    }

    /**
     * 把 libmpv-android 的 EventObserver 翻译成应用层事件 + StateFlow 更新。
     *
     * ⚠️ 所有回调都在 libmpv-android 的 pthread 事件线程, 不是主线程。
     * 用 StateFlow.update(线程安全)更新; 不直接碰 Compose。
     */
    private inner class MpvEventBridge : MPVLib.EventObserver {
        override fun event(eventId: Int) {
            // destroyCapture 后事件线程可能还投递一次回调(如 release 的 stop 触发的 END_FILE),
            // 此后 state 已置 IDLE, 不应再被改写——直接丢弃。
            if (!lifecycleState.callbacksEnabled) return
            val E = MPVLib.MpvEvent
            when (eventId) {
                E.MPV_EVENT_FILE_LOADED -> {
                    replacingFile = false   // B4: 新文件已正常加载, 清除 replace 防护标志
                    updateMediaInfoSnapshot()
                    updateTrackList()
                    _position.value = 0L
                    _state.update { it.copy(status = PlaybackStatus.READY, eof = false) }
                    logger?.appEvent("engine", "READY ${currentUrl?.substringAfterLast('/') ?: ""}", LogLevel.INFO)
                    dispatchEvent(PlayerEvent.FileLoaded)
                    maybeReinitToVulkanForHdr()   // HDR 视频切 Vulkan 直出(SDR 保持 OpenGL 零拷贝)
                }
                E.MPV_EVENT_END_FILE -> {
                    // 区分结束原因: 正常 EOF / 出错 / 被替换(stop/loadfile)。
                    // release() 里的 stop 也会进此事件, 此时 file-error 为空, 当 ENDED 处理。
                    val fileError = mpv?.getPropertyString("file-error")
                    if (!fileError.isNullOrBlank()) {
                        replacingFile = false
                        _state.update {
                            it.copy(status = PlaybackStatus.ERROR, error = fileError, eof = false)
                        }
                        logger?.appEvent("engine", "播放失败: $fileError", LogLevel.ERROR)
                        dispatchEvent(PlayerEvent.EndFile(EndReason.ERROR))
                    } else if (replacingFile) {
                        // B4-Android: loadfile replace 给旧文件发的 END_FILE(reason=stop, 无 file-error):
                        // 不是播放结束, 不改 status(否则重试加载时 ENDED/eof 闪烁)。纯 EOF 时标志为假, 照常 ENDED。
                        replacingFile = false
                        logger?.appEvent("engine", "END_FILE(replace) 忽略: 旧文件被替换, 新文件加载中", LogLevel.INFO)
                    } else {
                        _state.update { it.copy(status = PlaybackStatus.ENDED, eof = true) }
                        dispatchEvent(PlayerEvent.EndFile(EndReason.EOF))
                    }
                }
                E.MPV_EVENT_VIDEO_RECONFIG -> {
                    updateMediaInfoSnapshot()
                    // HDR 迟到路径复检: 部分片源 gamma 在 FILE_LOADED 时尚未就绪, VIDEO_RECONFIG 时
                    // updateMediaInfoSnapshot 内部(817/836 行)才算出 hdrInfo.isHDR=true。补一次 reinit 判断,
                    // 覆盖 FILE_LOADED 单次判定漏掉的迟到路径。与 FILE_LOADED :917 的调用冗余但无害——
                    // reinitDone/reiniting guard 防重复触发; SDR 场景首行 guard 近零开销短路, 不额外读属性。
                    maybeReinitToVulkanForHdr()
                    val mi = _mediaInfo.value
                    logger?.appEvent("engine", "reconfig ${mi?.width}x${mi?.height}", LogLevel.INFO)
                    dispatchEvent(PlayerEvent.VideoReconfig)
                }
                E.MPV_EVENT_SEEK -> {
                    _state.update { it.copy(buffering = true) }
                    dispatchEvent(PlayerEvent.Seek)
                }
                E.MPV_EVENT_PLAYBACK_RESTART -> {
                    _state.update { it.copy(buffering = false) }
                    dispatchEvent(PlayerEvent.PlaybackRestart)
                }
                E.MPV_EVENT_SHUTDOWN -> {
                    dispatchEvent(PlayerEvent.Shutdown)
                }
            }
        }

        override fun eventProperty(property: String) {
            dispatchProperty(property, null)
        }

        override fun eventProperty(property: String, value: Long) = handleProp(property, value)
        override fun eventProperty(property: String, value: Double) = handleProp(property, value)
        override fun eventProperty(property: String, value: Boolean) = handleProp(property, value)
        override fun eventProperty(property: String, value: String) = handleProp(property, value)

        private fun handleProp(name: String, value: Any?) {
            if (!lifecycleState.callbacksEnabled) return  // destroy 后不再写 state/position, 同 event()
            try {
                when (name) {
                    "time-pos" -> {
                        val secs = (value as? Double) ?: 0.0
                        _position.value = (secs * 1000).toLong()
                    }
                    "duration" -> {
                        val secs = (value as? Double) ?: 0.0
                        _state.update { it.copy(durationMs = (secs * 1000).toLong()) }
                        _mediaInfo.update { it?.copy(durationMs = (secs * 1000).toLong()) }
                    }
                    "pause" -> {
                        // cast 失败不 return(否则跳过下方 dispatchProperty), 用 let 跳过本次 update
                        (value as? Boolean)?.let { paused ->
                            _state.update {
                                it.copy(
                                    paused = paused,
                                    status = if (paused) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING,
                                )
                            }
                        }
                    }
                    "paused-for-cache" -> _state.update { it.copy(buffering = (value as? Boolean) ?: false) }
                    "eof-reached" -> _state.update { it.copy(eof = (value as? Boolean) ?: false) }
                    // volume: mpv 可能发 Long 或 Double, 用 Number 兼容(否则音量 UI 不更新)
                    "volume" -> _state.update { it.copy(volume = (value as? Number)?.toInt() ?: it.volume) }
                    "mute" -> _state.update { it.copy(muted = (value as? Boolean) ?: false) }
                    "speed" -> _state.update { it.copy(rate = ((value as? Double)?.toFloat()) ?: it.rate) }
                    "hwdec-current" -> (value as? String)?.let { v ->
                        _mediaInfo.update { it?.copy(hwdecCurrent = v) }
                    }
                    "video-params/gamma", "video-params/primaries" -> updateHdrInfo()
                    "video-params/rotate" -> _mediaInfo.update { it?.copy(rotation = ((value as? Long)?.toInt()) ?: 0) }
                    "track-list/count" -> updateTrackList()
                }
            } catch (_: ClassCastException) {
                // pthread 回调收到意外类型, 安全忽略不崩溃
            }
            dispatchProperty(name, value)
        }
    }

    /** mpv 日志 → AppLogger。回调在 mpv 内部线程。 */
    private inner class MpvLogBridge : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            // level 是 MPVLib.MpvLogLevel 常量(fatal=10...trace=70), 转字符串便于阅读
            val levelStr = when (level) {
                MPVLib.MpvLogLevel.MPV_LOG_LEVEL_FATAL -> "fatal"
                MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR -> "error"
                MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN -> "warn"
                MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO -> "info"
                MPVLib.MpvLogLevel.MPV_LOG_LEVEL_V -> "v"
                MPVLib.MpvLogLevel.MPV_LOG_LEVEL_DEBUG -> "debug"
                MPVLib.MpvLogLevel.MPV_LOG_LEVEL_TRACE -> "trace"
                else -> "?"
            }
            logger?.log(levelStr, prefix, text)
        }
    }

    private companion object {
        /** B1: reinit 轨道重放最大重试次数(外挂字幕 sub-add 异步入 track-list, 需多等几轮; 超限放弃保留默认)。 */
        const val TRACK_RESTORE_MAX_ATTEMPTS = 5
    }
}
