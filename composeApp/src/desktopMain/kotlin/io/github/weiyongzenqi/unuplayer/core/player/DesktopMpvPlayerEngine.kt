package io.github.weiyongzenqi.unuplayer.core.player

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.skia.Data
import io.github.weiyongzenqi.unuplayer.core.gl.DesktopRenderBackend
import io.github.weiyongzenqi.unuplayer.core.player.MpvEndFileReason.MPV_END_FILE_REASON_EOF
import io.github.weiyongzenqi.unuplayer.core.player.MpvEndFileReason.MPV_END_FILE_REASON_ERROR
import io.github.weiyongzenqi.unuplayer.core.player.MpvFormat.MPV_FORMAT_DOUBLE
import io.github.weiyongzenqi.unuplayer.core.player.MpvFormat.MPV_FORMAT_FLAG
import io.github.weiyongzenqi.unuplayer.core.player.MpvFormat.MPV_FORMAT_INT64
import io.github.weiyongzenqi.unuplayer.core.player.MpvFormat.MPV_FORMAT_STRING
import io.github.weiyongzenqi.unuplayer.core.player.MpvEventId.MPV_EVENT_END_FILE
import io.github.weiyongzenqi.unuplayer.core.player.MpvEventId.MPV_EVENT_FILE_LOADED
import io.github.weiyongzenqi.unuplayer.core.player.MpvEventId.MPV_EVENT_LOG_MESSAGE
import io.github.weiyongzenqi.unuplayer.core.player.MpvEventId.MPV_EVENT_NONE
import io.github.weiyongzenqi.unuplayer.core.player.MpvEventId.MPV_EVENT_PLAYBACK_RESTART
import io.github.weiyongzenqi.unuplayer.core.player.MpvEventId.MPV_EVENT_PROPERTY_CHANGE
import io.github.weiyongzenqi.unuplayer.core.player.MpvEventId.MPV_EVENT_SEEK
import io.github.weiyongzenqi.unuplayer.core.player.MpvEventId.MPV_EVENT_SHUTDOWN
import io.github.weiyongzenqi.unuplayer.core.player.MpvEventId.MPV_EVENT_VIDEO_RECONFIG
import io.github.weiyongzenqi.unuplayer.core.player.MpvRenderParamType.MPV_RENDER_PARAM_SW_FORMAT
import io.github.weiyongzenqi.unuplayer.core.player.MpvRenderParamType.MPV_RENDER_PARAM_SW_POINTER
import io.github.weiyongzenqi.unuplayer.core.player.MpvRenderParamType.MPV_RENDER_PARAM_SW_SIZE
import io.github.weiyongzenqi.unuplayer.core.player.MpvRenderParamType.MPV_RENDER_PARAM_SW_STRIDE
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val DESKTOP_DEFAULT_SUBTITLE_FONT = "sans-serif"
private const val SOFTWARE_FRAME_BUFFER_COUNT = 3
private const val SOFTWARE_POINTER_PARAM_INDEX = 3
private const val SOFTWARE_RENDER_LOG_INTERVAL = 120
/** destroy 循环 join 事件线程的硬超时: 超时后照常 terminate(libmpv 线程安全), 记 WARN。 */
private const val EVENT_THREAD_JOIN_TIMEOUT_MS = 5000L
/** 每轮 join 的间隔: 配合 mpv_wakeup 反复唤醒, 直到事件线程退出或硬超时。 */
private const val EVENT_THREAD_JOIN_INTERVAL_MS = 100L

internal fun desktopSubtitleFontValue(font: String): String =
    font.trim().ifBlank { DESKTOP_DEFAULT_SUBTITLE_FONT }

internal fun desktopSubtitleFontDirectoryValue(fontDir: String?): String =
    fontDir?.trim().orEmpty()

/**
 * libmpv 的 sw render API 可以使用 copy-back 硬解，但不能直接消费 GPU 原生帧。
 * 将旧设置中的直出模式迁移为对应 copy 模式，避免静默回落到软件解码。
 */
internal fun effectiveDesktopHwdec(requested: String): String = when (val mode = requested.trim().lowercase()) {
    "no" -> "no"
    "auto", "auto-safe", "auto-copy" -> "auto-copy"
    "nvdec", "nvdec-copy" -> "nvdec-copy"
    "d3d11va", "d3d11va-copy" -> "d3d11va-copy"
    "dxva2", "dxva2-copy" -> "dxva2-copy"
    else -> mode.takeIf { it.endsWith("-copy") } ?: "auto-copy"
}

/**
 * 构造 mpv_render_param 数组(共享连续内存)。JNA 直接传 Array<Structure> 不保证连续布局，
 * 故用 [com.sun.jna.Structure.toArray] 共享内存后逐个填充并 write。
 */
private fun renderParamArray(vararg pairs: Pair<Int, Pointer?>): Array<MpvRenderParam> {
    @Suppress("UNCHECKED_CAST")
    val array = MpvRenderParam().toArray(pairs.size) as Array<MpvRenderParam>
    pairs.forEachIndexed { index, (type, data) ->
        array[index].type = type
        array[index].data = data
        array[index].write()
    }
    return array
}

/**
 * 桌面(JVM/Linux/Windows) PlayerEngine 实现, 用 JNA 绑定系统 libmpv(对应 androidMain 的 MpvPlayerEngine)。
 *
 * 与 android 版的差异:
 * - 底层从 MPVLib(libmpv-android AAR) 换 [LibMpvLoader]/[LibMpv](JNA 绑定系统 libmpv.so.2/libmpv-2.dll)
 * - 事件: 自跑 mpv_wait_event 专用线程([eventLoop]), 替代 MPVLib.EventObserver 回调
 * - 渲染: mpv `--wid` 嵌入原生窗口(构造传 [wid]); android 用 attachSurface(SurfaceView)
 * - 无 Vulkan 重init: 桌面 vo=gpu-next + gpu-api=auto, HDR 走 target-colorspace-hint 真直通(gpu-next)
 * - TLS: 桌面 mpv 用系统 OpenSSL, 默认能找系统 CA(/etc/ssl/certs), 不需导出 CA bundle(android 才需要)
 *
 * 线程模型(对齐 android, 见 DESIGN.md §7.6):
 * - [eventLoop] 在专用 mpv-event 线程, mpv_wait_event 阻塞轮询
 * - 事件/属性回调在 mpv-event 线程触发, StateFlow.update 线程安全; observer 自行 marshal 主线程
 * - mpv_command/set_property 线程安全, 任意线程可调
 * - destroy() 阻塞(mpv_terminate_destroy pthread_join), 在 IO 协程调用
 *
 * @param wid 原生窗口句柄(X11 Window / Windows HWND), init 前 setOptionString("wid") 嵌入; 0=无窗口
 * @param logger 可选 AppLogger, mpv 日志 + 引擎事件写入
 */
class DesktopMpvPlayerEngine(
    private val logger: AppLogger? = null,
) : PlayerEngine {

    override val kernelName: String = "MPV"

    @Volatile private var handle: Pointer? = null
    @Volatile private var initialized = false
    @Volatile private var destroyed = false
    @Volatile private var stopped = false
    private var eventThread: Thread? = null
    @Volatile private var currentConfig: PlayerConfig? = null
    private var currentUrl: String? = null

    /** libmpv 固定使用 sw render API；Skiko 的 Direct3D/SOFTWARE 选择与它相互独立。 */
    @Volatile
    var usesSoftwareRendering: Boolean = true
        private set

    @Volatile private var uiRenderBackend: String = DesktopRenderBackend.requestedApi()?.let { "Skiko $it" }
        ?: "Skiko default"

    private val _state = MutableStateFlow(PlayerState())
    override val state = _state.asStateFlow()
    private val _position = MutableStateFlow(0L)
    override val position = _position.asStateFlow()
    private val _mediaInfo = MutableStateFlow<MediaInfo?>(null)
    override val mediaInfo = _mediaInfo.asStateFlow()
    private val _tracks = MutableStateFlow(TrackList(emptyList(), emptyList(), emptyList()))
    override val tracks = _tracks.asStateFlow()

    private val observersLock = Any()
    private val observers = mutableListOf<PlayerEventObserver>()
    @Volatile private var hasObservers = false

    // === render API 状态（生产固定 software render, WGL/OpenGL 共享纹理路径已作废） ===
    @Volatile private var renderCtx: Pointer? = null
    @Volatile private var loaded = false  // loadfile 是否已发(防重复; render context 建后才能 load)
    @Volatile private var playbackFileLoaded = false
    @Volatile private var renderFailureReported = false
    private val renderLock = Any()
    private val loadLock = Any()
    /**
     * 串行所有 native 透传(command/property/observe/option/loadfile)与 destroy 的 handle 发布。
     * 锁序约束(违反则死锁):
     * - loadLock -> nativeCommandLock 单向(loadNow 持 loadLock 内取 nativeCommandLock)。
     * - seekLock -> nativeCommandLock 单向(seekTo 持 seekLock 计算, sendSeekCommand -> command 在锁外取 nativeCommandLock)。
     * - nativeCommandLock **不与 renderLock 嵌套**: render worker 持 renderLock 调 render API, 不调 command/property;
     *   destroy 先 destroyRenderContext(释放 renderLock) 再取 nativeCommandLock, 不同时持有。
     * - observersLock 独立, 不与 nativeCommandLock 嵌套(dispatch 在锁外通知观察者)。
     * - destroy 持 nativeCommandLock 时不持 loadLock/seekLock/renderLock。
     */
    private val nativeCommandLock = ReentrantLock()
    /** software render API 的可复用像素缓冲；由 Skia Data 持有 native memory，避免逐帧 ByteArray 拷贝。 */
    private val softwarePixelBuffers = arrayOfNulls<Data>(SOFTWARE_FRAME_BUFFER_COUNT)
    private var softwarePublishedBuffer = 0
    private var softwareFrameVersion = 0L
    private var softwareRenderSamples = 0
    private var softwareRenderTotalNanos = 0L
    private var softwareRenderMaxNanos = 0L
    private var softwareWidth: Int = 0
    private var softwareHeight: Int = 0
    private var softwareStride: Int = 0
    private var softwareHasFrame: Boolean = false
    /** software render 参数只在尺寸变化时重建；逐帧复用，避免 JNA native Memory 持续分配。 */
    private var softwareSizeParam: Memory? = null
    private var softwareFormatParam: Memory? = null
    private var softwareStrideParam: Memory? = null
    private var softwareRenderParams: Array<MpvRenderParam>? = null
    /** update 回调由 mpv 线程触发，只唤醒 software render worker。强引用防 GC。 */
    @Volatile private var requestRepaint: (() -> Unit)? = null
    private val updateCb = object : MpvRenderUpdateCallback {
        override fun invoke(cbCtx: Pointer?) {
            if (!renderFailureReported) requestRepaint?.invoke()
        }
    }

    // === seek 节流(滑条洪泛防护) ===
    /** seek 节流间隔(ms): 区间内的滑条变更合并为最新目标, 尾沿送达保证末值必达。 */
    private val seekThrottleMs = 120L
    /** seek 尾沿送达 scope; destroy 时取消。Dispatchers.Default 复用共享池, 不新起裸线程。 */
    private val seekScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val seekLock = Any()
    /** 区间内合并的最新 seek 目标(中间值被覆盖); 由 seekFlushJob 到期送达。 */
    private var pendingSeekMs: Long? = null
    /** 上次实际发出 seek 命令的时间戳(ms); 用于判断是否出区间。 */
    private var lastSeekSentAt = 0L
    private var seekFlushJob: Job? = null

    private fun lib() = LibMpvLoader.INSTANCE

    // === 生命周期 ===

    @Synchronized
    override fun init(config: PlayerConfig) {
        if (initialized || destroyed) return
        val m = lib().mpv_create()
        if (m == null) {
            // mpv_create 返回 null 的已知原因: locale 非 C(LibMpvLoader 已 setlocale 修); 或 libmpv 加载异常
            val apiVer = runCatching { lib().mpv_client_api_version() }.getOrNull()
            error("mpv_create 返回 null (libmpv client_api=$apiVer, 加载成功=${apiVer != null})")
        }
        handle = m
        currentConfig = config
        try {
            applyOptions(m, config)
            val result = lib().mpv_initialize(m)
            check(result >= 0) { "mpv_initialize 失败: ${lib().mpv_error_string(result)}" }
            initialized = true
            registerObservers(m)
            if (config.logLevel.isNotBlank()) {
                runCatching { lib().mpv_request_log_messages(m, config.logLevel) }
            }
            startEventLoop()
            logger?.appEvent(
                "engine",
                "desktop mpv init render=sw ui=$uiRenderBackend " +
                    "hwdec=${effectiveDesktopHwdec(config.hwdec)} requested=${config.hwdec} ao='${config.audioOutput}'",
                LogLevel.INFO,
            )
        } catch (error: Throwable) {
            destroyed = true
            // 在 nativeCommandLock 内发布 handle=null: 并发的 command/property 持锁后读到 null 直接返回,
            // 不会在 mpv_terminate_destroy 释放 m 后仍调 mpv_*(m) 触发 UAF。
            nativeCommandLock.withLock { handle = null }
            initialized = false
            runCatching { lib().mpv_terminate_destroy(m) }
            throw error
        }
    }

    @Synchronized
    override fun destroy() {
        if (destroyed) return
        destroyed = true
        stopped = true
        seekScope.cancel()  // 停止 seek 节流协程, 避免 destroy 后仍有滞留 seek 落到已释放句柄
        // 先释放 render context(持 renderLock), 再取 nativeCommandLock; 避免与 renderLock 嵌套。
        runCatching { destroyRenderContext() }
            .onFailure { logger?.appEvent("engine", "释放 render context 失败: ${it.message}", LogLevel.WARN) }
        // 在 nativeCommandLock 内发布 handle=null: command/property 等持 nativeCommandLock 后读到 null 直接返回,
        // 不会在 mpv_terminate_destroy 释放 m 后仍调 mpv_*(m) 触发 UAF(CR-063)。
        val m = nativeCommandLock.withLock {
            val h = handle
            handle = null
            h
        }
        if (m == null) {
            initialized = false
            _state.update { it.copy(status = PlaybackStatus.IDLE) }
            return
        }
        // 循环 join 事件线程: mpv_wakeup 唤醒 mpv_wait_event 阻塞让其尽快出循环; 事件线程退出前
        // 不会在 handleEvent 内继续做 getProperty* 等 JNA 调用(CR-065 收窄窗口)。
        // 硬超时 5s: 超时后照常走 terminate_destroy(libmpv 线程安全, native 资源终由它回收), 记 WARN。
        val deadline = System.currentTimeMillis() + EVENT_THREAD_JOIN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val t = eventThread ?: break
            if (!t.isAlive) break
            runCatching { lib().mpv_wakeup(m) }
            t.join(EVENT_THREAD_JOIN_INTERVAL_MS)
        }
        if (eventThread?.isAlive == true) {
            logger?.appEvent(
                "engine",
                "事件线程 ${EVENT_THREAD_JOIN_TIMEOUT_MS}ms 内未退出, 继续 terminate_destroy",
                LogLevel.WARN,
            )
        }
        eventThread = null
        runCatching { lib().mpv_terminate_destroy(m) }
            .onFailure { logger?.appEvent("engine", "mpv native 释放失败: ${it.message}", LogLevel.ERROR) }
        initialized = false
        _state.update { it.copy(status = PlaybackStatus.IDLE) }
    }

    override fun close() = destroy()

    // === render API（CPU software，Compose 渲染线程调；WGL/OpenGL 共享纹理路径已作废） ===

    data class SoftwareVideoFrame(
        val pixels: Data,
        val width: Int,
        val height: Int,
        val stride: Int,
        val version: Long,
    )

    fun setUiRenderBackend(description: String?) {
        description?.takeIf { it.isNotBlank() }?.let { uiRenderBackend = it }
    }

    /** 由 UI 层绑定 software render worker；回调中不得同步渲染或阻塞。 */
    fun setRequestRepaint(fn: (() -> Unit)?) { requestRepaint = fn }

    /** 首次创建当前进程所需的 render context；必须先于 loadfile，之后调用幂等。 */
    fun ensureRenderContext(): Boolean {
        if (destroyed) return false
        return runCatching {
            // 生产路径固定 software render(WGL/OpenGL 共享纹理已作废), usesSoftwareRendering 恒 true。
            ensureSoftwareRenderContext()
            renderCtx != null
        }.getOrElse { error ->
            val message = "视频渲染器初始化失败: ${error.message ?: error.javaClass.simpleName}"
            logger?.appEvent("engine", message, LogLevel.ERROR)
            failPlayback(message)
            false
        }
    }

    fun reportRenderFailure(error: Throwable) {
        synchronized(renderLock) {
            if (renderFailureReported) return
            renderFailureReported = true
        }
        val message = "视频渲染失败: ${error.message ?: error.javaClass.simpleName}"
        logger?.appEvent("engine", message, LogLevel.ERROR)
        failPlayback(message)
    }

    /**
     * Skiko SOFTWARE/RDP 路径：创建 libmpv 的 `sw` render context，不接触 WGL。
     * 创建和后续 render/free 都由 [renderLock] 串行化。
     */
    private fun ensureSoftwareRenderContext() {
        // 快路径: renderCtx 只在锁内设置, 一旦非空即有效; 即使随后被 destroy 释放, 锁内复查兜底。
        if (renderCtx != null) return
        var shouldLoad = false
        synchronized(renderLock) {
            if (renderCtx != null) return
            // destroyed/handle 检查必须在锁内: destroy 侧先置 destroyed=true 再抢锁 free ctx,
            // 锁内检查与之构成 happens-before, 关闭"首帧建 ctx vs 后台 destroy"的 native UAF 竞态。
            if (destroyed) return
            val m = handle ?: return
            val apiTypeMem = Memory(3).also { it.setString(0, "sw") }
            val createParams = renderParamArray(
                MpvRenderParamType.MPV_RENDER_PARAM_API_TYPE to apiTypeMem,
                MpvRenderParamType.MPV_RENDER_PARAM_INVALID to null,
            )
            val result = PointerByReference()
            val status = lib().mpv_render_context_create(result, m, createParams)
            check(status >= 0) {
                "mpv software render context 创建失败: ${lib().mpv_error_string(status)}"
            }
            val context = checkNotNull(result.value) { "mpv software render context 返回 null" }
            renderCtx = context
            lib().mpv_render_context_set_update_callback(context, updateCb, null)
            logger?.appEvent("engine", "render context 创建 OK (CPU software)", LogLevel.INFO)
            shouldLoad = true
        }
        // loadNow 在 renderLock 外调: 内部取 loadLock -> nativeCommandLock,
        // 避免与 renderLock 嵌套(nativeCommandLock 不与 renderLock 同时持有)。
        if (shouldLoad) loadNow()
    }

    /** software render worker 调用：把最新视频帧直接写入可复用的 Skia native Data。 */
    fun renderSoftwareFrame(w: Int, h: Int): SoftwareVideoFrame? {
        if (destroyed || !usesSoftwareRendering || renderFailureReported) return null
        ensureSoftwareRenderContext()
        synchronized(renderLock) {
            // 锁内复查 destroyed: destroy 侧先置 destroyed=true 再抢锁 free ctx, 构成 happens-before 兜底。
            if (destroyed) return null
            val context = renderCtx ?: return null
            val width = w.coerceAtLeast(1)
            val height = h.coerceAtLeast(1)
            val minimumStride = Math.multiplyExact(width.toLong(), 4L)
            val stride = Math.multiplyExact(Math.floorDiv(minimumStride + 63L, 64L), 64L).toInt()
            val byteSize = Math.multiplyExact(stride, height)
            val resized = softwarePixelBuffers[0] == null || softwareWidth != width || softwareHeight != height
            if (resized) {
                clearSoftwareRenderParams()
                softwarePixelBuffers.forEachIndexed { index, pixels ->
                    pixels?.close()
                    softwarePixelBuffers[index] = null
                }
                try {
                    softwarePixelBuffers.indices.forEach { index ->
                        softwarePixelBuffers[index] = Data.makeUninitialized(byteSize).also { buffer ->
                            Pointer(buffer.writableData()).clear(byteSize.toLong())
                        }
                    }
                } catch (error: Throwable) {
                    softwarePixelBuffers.forEachIndexed { index, pixels ->
                        pixels?.close()
                        softwarePixelBuffers[index] = null
                    }
                    throw error
                }
                softwareWidth = width
                softwareHeight = height
                softwareStride = stride
                softwareHasFrame = false
                softwarePublishedBuffer = 0
                softwareRenderSamples = 0
                softwareRenderTotalNanos = 0L
                softwareRenderMaxNanos = 0L

                val pixels = checkNotNull(softwarePixelBuffers[0])
                softwareSizeParam = Memory(8).also {
                    it.setInt(0, width)
                    it.setInt(4, height)
                }
                softwareFormatParam = Memory(5).also { it.setString(0, "rgb0") }
                softwareStrideParam = Memory(Native.SIZE_T_SIZE.toLong()).also {
                    if (Native.SIZE_T_SIZE == 8) it.setLong(0, stride.toLong()) else it.setInt(0, stride)
                }
                softwareRenderParams = renderParamArray(
                    MPV_RENDER_PARAM_SW_SIZE to softwareSizeParam,
                    MPV_RENDER_PARAM_SW_FORMAT to softwareFormatParam,
                    MPV_RENDER_PARAM_SW_STRIDE to softwareStrideParam,
                    MPV_RENDER_PARAM_SW_POINTER to Pointer(pixels.writableData()),
                    MpvRenderParamType.MPV_RENDER_PARAM_INVALID to null,
                )
            }

            val flags = lib().mpv_render_context_update(context)
            if (resized || (flags and MPV_RENDER_UPDATE_FRAME) != 0L) {
                val firstFrame = !softwareHasFrame
                val targetIndex = if (firstFrame) 0 else (softwarePublishedBuffer + 1) % SOFTWARE_FRAME_BUFFER_COUNT
                val targetPixels = checkNotNull(softwarePixelBuffers[targetIndex])
                // rgb0 是 mpv render.h 保证的公共格式；Skia 端用 RGB_888X，明确忽略未初始化的 X 字节。
                val renderParams = softwareRenderParams ?: return null
                renderParams[SOFTWARE_POINTER_PARAM_INDEX].apply {
                    data = Pointer(targetPixels.writableData())
                    write()
                }
                val renderStarted = System.nanoTime()
                val status = lib().mpv_render_context_render(context, renderParams)
                val renderNanos = System.nanoTime() - renderStarted
                if (status < 0) {
                    val message = "mpv software render 失败: ${lib().mpv_error_string(status)}"
                    logger?.appEvent("engine", message, LogLevel.ERROR)
                    failPlayback(message)
                    return null
                }
                softwareHasFrame = true
                softwarePublishedBuffer = targetIndex
                softwareFrameVersion++
                softwareRenderSamples++
                softwareRenderTotalNanos += renderNanos
                softwareRenderMaxNanos = maxOf(softwareRenderMaxNanos, renderNanos)
                if (softwareRenderSamples % SOFTWARE_RENDER_LOG_INTERVAL == 0) {
                    val averageMs = softwareRenderTotalNanos / softwareRenderSamples / 1_000_000.0
                    val maxMs = softwareRenderMaxNanos / 1_000_000.0
                    logger?.appEvent(
                        "engine",
                        "software render timing frames=$softwareRenderSamples " +
                            "avg=${"%.2f".format(averageMs)}ms max=${"%.2f".format(maxMs)}ms " +
                            "target=${softwareWidth}x$softwareHeight",
                        LogLevel.INFO,
                    )
                }
                if (firstFrame) {
                    logger?.appEvent(
                        "engine",
                        "首个 CPU software 视频帧 ${width}x${height} stride=$stride format=rgb0",
                        LogLevel.INFO,
                    )
                }
            }

            if (!softwareHasFrame) return null
            val pixels = softwarePixelBuffers[softwarePublishedBuffer] ?: return null
            return SoftwareVideoFrame(
                pixels,
                softwareWidth,
                softwareHeight,
                softwareStride,
                softwareFrameVersion,
            )
        }
    }

    /** 释放 render context + software 像素缓冲。须在 [destroy] 前调。 */
    fun destroyRenderContext() {
        synchronized(renderLock) {
            val context = renderCtx
            context?.let {
                runCatching { lib().mpv_render_context_set_update_callback(it, null, null) }
            }
            requestRepaint = null
            // 生产路径固定 software render(WGL/OpenGL 已作废, usesSoftwareRendering 恒 true);
            // 释放 sw render context、可复用参数与 Skia 像素缓冲。
            context?.let { runCatching { lib().mpv_render_context_free(it) } }
            renderCtx = null
            clearSoftwareRenderParams()
            softwarePixelBuffers.forEachIndexed { index, pixels ->
                pixels?.close()
                softwarePixelBuffers[index] = null
            }
            softwareWidth = 0
            softwareHeight = 0
            softwareStride = 0
            softwareHasFrame = false
            softwarePublishedBuffer = 0
            softwareFrameVersion = 0L
            softwareRenderSamples = 0
            softwareRenderTotalNanos = 0L
            softwareRenderMaxNanos = 0L
        }
    }

    /** 丢弃引用后先关闭参数持有的 JNA native Memory；调用方已持有 [renderLock]。 */
    private fun clearSoftwareRenderParams() {
        softwareRenderParams = null
        softwareSizeParam?.close()
        softwareFormatParam?.close()
        softwareStrideParam?.close()
        softwareSizeParam = null
        softwareFormatParam = null
        softwareStrideParam = null
    }

    // === 选项(init 前) ===

    private fun applyOptions(m: Pointer, config: PlayerConfig) {
        val o = { name: String, value: String ->
            val result = lib().mpv_set_option_string(m, name, value)
            if (result < 0) {
                logger?.appEvent(
                    "engine",
                    "mpv option '$name' 设置失败: ${lib().mpv_error_string(result)}",
                    LogLevel.WARN,
                )
            }
        }
        // 视频输出: libmpv(render API 模式, mpv 渲染到 GL FBO 由 Compose 采样显示)。
        // 不能用 gpu-next(render API 须 vo=libmpv); HDR/画质弱于 gpu-next 是 render API 固有代价。
        o("vo", "libmpv")
        // sw render API 不接收 GPU 原生帧，但可以使用 copy-back 硬解。
        o("hwdec", effectiveDesktopHwdec(config.hwdec))
        if (usesSoftwareRendering) o("sw-fast", "yes")
        // 音频: 空=autoprobe(桌面 pipewire>pulse>alsa / wasapi); 非空才显式设
        if (config.audioOutput.isNotBlank()) o("ao", config.audioOutput)
        o("audio-channels", "7.1,5.1,stereo")
        o("audio-pitch-correction", "yes")
        o("af", "scaletempo2=min-speed=${config.minSpeed}:max-speed=${config.maxSpeed}")
        // fbo-format 用 mpv 默认 auto(不显式设)。render API 模式 HDR 本就受限, 强制 rgba16f 在软件
        // GL(llvmpipe, 无显卡环境)下可能让 mpv 内部中间 FBO 不完整 -> 视频 plane 渲染失败(黑),
        // 而 OSD/字幕走另一路径仍正常(调研 tools/research/render-blackscreen.md 根因2)。HDR 直通留给未来 gpu-next backend。
        // software render 固定输出 8-bit BGR，HDR 必须 tone-map 到 SDR，不能宣称直通。
        applyHdrOptions(m, if (usesSoftwareRendering) HdrMode.TONE_MAP_SDR else config.hdrMode)
        // 缓存: 内存-only, 不写盘
        o("cache", "yes")
        o("demuxer-max-bytes", "${config.cacheSize}MiB")
        o("demuxer-seekable-cache", "yes")
        o("demuxer-seekable-cache-min", "75%")
        o("cache-secs", "${config.cacheSecs}")
        o("cache-on-disk", "no")
        o("vd-lavc-threads", "0")
        // 不设 framedrop: render API 下 framedrop=vo 语义不同于普通 VO(flip_page 等 render 用户消费帧,
        // 0.2s 超时即丢), 软解 + llvmpipe 渲染慢时加剧丢帧。改默认 none, 由 render 用户(Compose draw)控节奏。
        o("keep-open", "yes")       // 播放结束不自动关闭, 便于续播/重播
        // 初始暂停：FILE_LOADED 后先恢复播放记录位置，再由 UI play，避免从头播放与续播 seek 竞争。
        // 暂停状态仍会产出首帧并触发 render callback，不影响 render context 初始化。
        o("pause", "yes")
        // HTTP 头(WebDAV basic auth 用 Authorization 头)
        if (config.httpHeaders.isNotEmpty()) {
            o("http-header-fields", config.httpHeaders.entries.joinToString(",") { "${it.key}: ${it.value}" })
        }
        // TLS: 桌面 mpv 用系统 OpenSSL, 默认能找系统 CA(/etc/ssl/certs/ca-certificates.crt);
        // 不需导出 CA bundle(android OpenSSL 找不到系统 CA 才需要)。降级开关由 allowTlsInsecure 决定。
        if (config.allowTlsInsecure) {
            o("tls-verify", "no")
            logger?.appEvent("engine", "tls-verify=no(用户开启降级, HTTPS 不验证身份)", LogLevel.WARN)
        } else {
            o("tls-verify", "yes")
        }
        o("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; UnUPlayer)")
        // 网络超时(init-only, 单位秒): 无响应的 WebDAV 服务器此前会让首帧无限阻塞, 进而使 destroy
        // 等待 renderLock/native 的链条同样无界; 30s 上限收敛最坏阻塞(mpv 默认 180s 过长)。
        o("network-timeout", "30")
        // 字幕
        o("sub-auto", config.subAuto)
        o("sub-codepage", config.subCodepage)
        o("sub-ass-override", "force")
    }

    /** HDR 三件套(target-colorspace-hint/tone-mapping/hdr-compute-peak), init 与运行时共用。 */
    private fun applyHdrOptions(m: Pointer, mode: HdrMode) {
        val (hint, tone, peak) = hdrParams(mode)
        val o = { name: String, value: String -> lib().mpv_set_option_string(m, name, value) }
        o("target-colorspace-hint", hint)
        o("tone-mapping", tone)
        o("hdr-compute-peak", peak)
    }

    private fun hdrParams(mode: HdrMode) = when (mode) {
        HdrMode.AUTO -> Triple("yes", "auto", "auto")          // 桌面真直通(gpu-next 判断屏 HDR)
        HdrMode.TONE_MAP_SDR -> Triple("no", "auto", "auto")   // 强制 tone-map SDR
        HdrMode.OFF -> Triple("no", "clip", "no")              // 当 SDR: clip 截断超亮
        HdrMode.HDR_PASSTHROUGH -> Triple("yes", "clip", "no") // 强制直通不二次映射
    }

    // === 属性观察(init 后) ===

    private fun registerObservers(m: Pointer) {
        val ob = { name: String, fmt: Int -> lib().mpv_observe_property(m, 0L, name, fmt) }
        ob("time-pos", MPV_FORMAT_DOUBLE)
        ob("duration", MPV_FORMAT_DOUBLE)
        ob("pause", MPV_FORMAT_FLAG)
        ob("paused-for-cache", MPV_FORMAT_FLAG)
        ob("eof-reached", MPV_FORMAT_FLAG)
        ob("volume", MPV_FORMAT_INT64)
        ob("mute", MPV_FORMAT_FLAG)
        ob("speed", MPV_FORMAT_DOUBLE)
        ob("track-list/count", MPV_FORMAT_INT64)
        ob("hwdec-current", MPV_FORMAT_STRING)
        ob("video-params/gamma", MPV_FORMAT_STRING)
        ob("video-params/primaries", MPV_FORMAT_STRING)
        ob("video-params/rotate", MPV_FORMAT_INT64)
    }

    // === 播放控制 ===

    override fun load(url: String) {
        synchronized(loadLock) {
            currentUrl = url
            loaded = false
            playbackFileLoaded = false
            renderFailureReported = false
        }
        _position.value = 0L
        _state.update { it.copy(status = PlaybackStatus.LOADING, error = null, eof = false) }
        // render API: loadfile 须在 render context 之后(render.h: renderer 先于 playback)。
        // render context 在 ensureRenderContext 首次调时建(Compose 渲染线程); 已建则立即 load, 否则等建后 loadNow。
        if (renderCtx != null) loadNow()
    }

    /** 实际发 loadfile。由 load(render context 已建)或 ensureRenderContext(首次建 render context 后)触发。 */
    private fun loadNow() {
        synchronized(loadLock) {
            if (loaded || destroyed) return
            val url = currentUrl ?: return
            // 锁序 loadLock -> nativeCommandLock: 持 loadLock 内取 nativeCommandLock 调 mpv_command,
            // 与 destroy 的 nativeCommandLock 串行, 关闭 TOCTOU UAF(CR-063)。
            nativeCommandLock.withLock {
                if (destroyed) return@withLock
                val m = handle ?: return@withLock
                val result = lib().mpv_command(m, arrayOf("loadfile", url, "replace"))
                if (result < 0) {
                    failPlayback("加载媒体失败: ${lib().mpv_error_string(result)}")
                    return@withLock
                }
                loaded = true
            }
        }
    }

    override fun play() {
        setProp("pause", "no")
    }
    override fun pause() = setProp("pause", "yes")
    override fun seekTo(positionMs: Long) {
        // 滑条洪泛防护: 区间内节流 + 合并最新目标, 尾沿送达保证末值必达(调用方无需改动)。
        val now = System.currentTimeMillis()
        var immediateTarget: Long? = null
        var scheduleDelayMs = -1L
        synchronized(seekLock) {
            pendingSeekMs = positionMs  // 合并最新目标, 覆盖中间值
            val elapsed = now - lastSeekSentAt
            if (elapsed >= seekThrottleMs) {
                // 出区间, 立即发(前缘): 续播/快进快退等一次性 seek 零延迟响应。
                immediateTarget = positionMs
                pendingSeekMs = null
                lastSeekSentAt = now
            } else {
                // 区间内, 延后剩余时间发合并后的最新值(尾沿), 末值必达。
                scheduleDelayMs = seekThrottleMs - elapsed
            }
        }
        val target = immediateTarget
        if (target != null) {
            sendSeekCommand(target)
        } else if (scheduleDelayMs >= 0) {
            scheduleSeekFlush(scheduleDelayMs)
        }
    }

    /** 安排 delayMs 后送达合并的最新 seek 目标; 到期前有新 seek 则取消重排(绝对送达时刻锚定 lastSeekSentAt+间隔)。 */
    private fun scheduleSeekFlush(delayMs: Long) {
        synchronized(seekLock) {
            seekFlushJob?.cancel()
            seekFlushJob = seekScope.launch {
                delay(delayMs)
                val target = synchronized(seekLock) {
                    val pending = pendingSeekMs
                    pendingSeekMs = null
                    if (pending != null) lastSeekSentAt = System.currentTimeMillis()
                    pending
                }
                if (target != null) sendSeekCommand(target)
            }
        }
    }

    private fun sendSeekCommand(positionMs: Long) {
        // absolute(关键帧 seek, 快): 拖动进度条松手后快速落地。
        // 不用 absolute+exact(精确 HR-seek): webdav 流式播放下每次 seek 触发 range 请求, exact 明显卡顿不跟手, 关键帧偏差可接受。
        command(arrayOf("seek", (positionMs / 1000.0).toString(), "absolute"))
    }
    override fun setVolume(volume: Int) = setProp("volume", volume.toString())
    override fun setRate(rate: Float) = setProp("speed", rate.toString())
    override fun setMuted(muted: Boolean) = setProp("mute", if (muted) "yes" else "no")

    override fun setAudioTrack(id: Int) = setProp("aid", id.toString())
    override fun setSubtitleTrack(id: Int) = setProp("sid", id.toString())
    override fun setVideoTrack(id: Int) = setProp("vid", id.toString())

    override fun addExternalSubtitle(path: String, title: String?) {
        command(if (title != null) arrayOf("sub-add", path, "select", title) else arrayOf("sub-add", path, "select"))
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
        // 空设置也必须显式写回，否则正在播放的 mpv 会继续沿用上一次选择的自定义字体。
        setProp("sub-font", desktopSubtitleFontValue(font))
        setProp("sub-fonts-dir", desktopSubtitleFontDirectoryValue(fontDir))
        setProp("sub-scale", scale.toString())
        setProp("sub-color", color)
        setProp("sub-border-size", borderSize.toString())
        setProp("sub-bold", if (bold) "yes" else "no")
        setProp("sub-ass-override", styleOverride)
    }

    override fun setHardwareDecoding(mode: String) {
        currentConfig = currentConfig?.copy(hwdec = mode)
        val effectiveMode = effectiveDesktopHwdec(mode)
        setProp("hwdec", effectiveMode)
        logger?.appEvent(
            "engine",
            "hwdec 更新 requested=$mode effective=$effectiveMode render=sw",
            LogLevel.INFO,
        )
        _mediaInfo.update { it?.copy(requestedHwdec = mode, effectiveHwdec = effectiveMode) }
    }
    override fun setAudioOutput(ao: String) = setProp("ao", ao)
    override fun setHdrMode(mode: HdrMode) {
        val (hint, tone, peak) = hdrParams(if (usesSoftwareRendering) HdrMode.TONE_MAP_SDR else mode)
        setProp("target-colorspace-hint", hint)
        setProp("tone-mapping", tone)
        setProp("hdr-compute-peak", peak)
    }

    // === mpv 原生属性透传 ===
    // 所有 native 透传统一走 nativeCommandLock: destroy 在锁内先发布 handle=null 再 terminate_destroy,
    // 这些方法持锁后读到 null 直接返回, 关闭 TOCTOU UAF(CR-063)。锁序: 调用方不得持 renderLock。

    private fun setProp(name: String, value: String) {
        nativeCommandLock.withLock {
            val m = handle ?: return@withLock
            val result = lib().mpv_set_property_string(m, name, value)
            if (result < 0) {
                logger?.appEvent(
                    "engine",
                    "mpv property '$name' 设置失败: ${lib().mpv_error_string(result)}",
                    LogLevel.WARN,
                )
            }
        }
    }

    override fun getPropertyString(name: String): String? {
        // mpv_get_property_string 返回 char* 需 mpv_free, 故映射 Pointer 手动读+free(勿映射 String, 泄漏)
        // libmpv 的 char* 恒 UTF-8; 显式按 UTF-8 解码, 避免默认平台编码非 UTF-8 时中文乱码。
        return nativeCommandLock.withLock {
            val m = handle ?: return@withLock null
            val p = lib().mpv_get_property_string(m, name) ?: return@withLock null
            try { p.getString(0, "UTF-8") } finally { lib().mpv_free(p) }
        }
    }

    override fun getPropertyInt(name: String): Int? {
        return nativeCommandLock.withLock {
            val m = handle ?: return@withLock null
            val mem = Memory(8) // int64
            if (lib().mpv_get_property(m, name, MPV_FORMAT_INT64, mem) < 0) return@withLock null
            mem.getLong(0).toInt()
        }
    }

    override fun getPropertyDouble(name: String): Double? {
        return nativeCommandLock.withLock {
            val m = handle ?: return@withLock null
            val mem = Memory(8)
            if (lib().mpv_get_property(m, name, MPV_FORMAT_DOUBLE, mem) < 0) return@withLock null
            mem.getDouble(0)
        }
    }

    override fun getPropertyBoolean(name: String): Boolean? {
        return nativeCommandLock.withLock {
            val m = handle ?: return@withLock null
            val mem = Memory(4) // int(flag)
            if (lib().mpv_get_property(m, name, MPV_FORMAT_FLAG, mem) < 0) return@withLock null
            mem.getInt(0) != 0
        }
    }

    override fun setPropertyString(name: String, value: String) = setProp(name, value)
    override fun setOptionString(name: String, value: String) {
        nativeCommandLock.withLock {
            val m = handle ?: return@withLock
            lib().mpv_set_option_string(m, name, value)
        }
    }
    override fun observeProperty(name: String, format: Int) {
        nativeCommandLock.withLock {
            val m = handle ?: return@withLock
            lib().mpv_observe_property(m, 0L, name, format)
        }
    }
    override fun command(args: Array<String>) {
        nativeCommandLock.withLock {
            val m = handle ?: return@withLock
            lib().mpv_command(m, args)
        }
    }

    // === 事件观察 ===

    override fun addObserver(observer: PlayerEventObserver) {
        synchronized(observersLock) { observers.add(observer); hasObservers = true }
    }
    override fun removeObserver(observer: PlayerEventObserver) {
        synchronized(observersLock) { observers.remove(observer); hasObservers = observers.isNotEmpty() }
    }
    private fun dispatchEvent(event: PlayerEvent) {
        if (!hasObservers) return
        synchronized(observersLock) { observers.toList() }.forEach { it.onEvent(event) }
    }
    private fun dispatchProperty(name: String, value: Any?) {
        if (!hasObservers) return
        synchronized(observersLock) { observers.toList() }.forEach { it.onPropertyChanged(name, value) }
    }

    // === 事件线程(mpv_wait_event 轮询) ===

    private fun startEventLoop() {
        eventThread = Thread({ eventLoop() }, "mpv-event").apply { isDaemon = true; start() }
    }

    private fun eventLoop() {
        val m = handle ?: return
        while (!stopped && !destroyed) {
            val ev = lib().mpv_wait_event(m, 1.0) ?: break
            if (ev.event_id == MPV_EVENT_NONE) continue
            runCatching { handleEvent(ev) }
        }
    }

    private fun handleEvent(ev: MpvEvent) {
        // CR-065: 事件线程在 mpv_wait_event 间隙可能读到 destroyed=true, 复查后跳过 JNA 调用,
        // 收窄事件线程与 destroy 的 terminate_destroy 竞争窗口。
        if (destroyed) return
        when (ev.event_id) {
            MPV_EVENT_PROPERTY_CHANGE -> {
                val p = ev.data?.let { MpvEventProperty(it) } ?: return
                val name = p.name ?: return
                handleProp(name, readPropertyValue(p.data, p.format))
            }
            MPV_EVENT_LOG_MESSAGE -> {
                val log = ev.data?.let { MpvEventLogMessage(it) } ?: return
                logger?.log(log.level ?: "info", log.prefix ?: "", log.text ?: "")
            }
            MPV_EVENT_FILE_LOADED -> {
                updateMediaInfoSnapshot()
                updateTrackList()
                _position.value = 0L
                playbackFileLoaded = true
                _state.update { it.copy(status = PlaybackStatus.READY, eof = false) }
                logger?.appEvent("engine", "READY ${currentUrl?.substringAfterLast('/') ?: ""}", LogLevel.INFO)
                dispatchEvent(PlayerEvent.FileLoaded)
            }
            MPV_EVENT_END_FILE -> {
                val ef = ev.data?.let { MpvEventEndFile(it) }
                val reason = ef?.reason ?: MPV_END_FILE_REASON_EOF
                if (reason == MPV_END_FILE_REASON_ERROR || (ef?.error ?: 0) != 0) {
                    val err = ef?.error?.takeIf { it != 0 }?.let { lib().mpv_error_string(it) } ?: "播放失败"
                    _state.update { it.copy(status = PlaybackStatus.ERROR, error = err, eof = false) }
                    logger?.appEvent("engine", "播放失败: $err", LogLevel.ERROR)
                    dispatchEvent(PlayerEvent.EndFile(EndReason.ERROR))
                } else if (reason == MPV_END_FILE_REASON_EOF) {
                    // 仅真 EOF 置 ENDED/eof。
                    _state.update { it.copy(status = PlaybackStatus.ENDED, eof = true) }
                    dispatchEvent(PlayerEvent.EndFile(EndReason.EOF))
                } else {
                    // STOP/QUIT/REDIRECT: 中间事件(如 loadfile replace 产生的 reason=STOP),
                    // 改 status 会造成重试加载状态闪烁; 保持 status 不变, 仅记日志。
                    logger?.appEvent("engine", "END_FILE reason=$reason (非 EOF/ERROR), status 不变", LogLevel.DEBUG)
                }
            }
            MPV_EVENT_VIDEO_RECONFIG -> {
                updateMediaInfoSnapshot()
                dispatchEvent(PlayerEvent.VideoReconfig)
            }
            MPV_EVENT_SEEK -> {
                _state.update { it.copy(buffering = true) }
                dispatchEvent(PlayerEvent.Seek)
            }
            MPV_EVENT_PLAYBACK_RESTART -> {
                _state.update { it.copy(buffering = false) }
                dispatchEvent(PlayerEvent.PlaybackRestart)
            }
            MPV_EVENT_SHUTDOWN -> {
                stopped = true
                dispatchEvent(PlayerEvent.Shutdown)
            }
        }
    }

    /** 按 format 读 mpv_event_property.data 指向的值。 */
    private fun readPropertyValue(data: Pointer?, format: Int): Any? {
        if (data == null) return null
        return when (format) {
            // data 是 char**; libmpv 字符串恒 UTF-8, 显式按 UTF-8 解码防平台默认编码乱码。
            MPV_FORMAT_STRING -> runCatching { data.getPointer(0).getString(0, "UTF-8") }.getOrNull()
            MPV_FORMAT_DOUBLE -> data.getDouble(0)
            MPV_FORMAT_FLAG -> data.getInt(0) != 0
            MPV_FORMAT_INT64 -> data.getLong(0)
            else -> null
        }
    }

    private fun handleProp(name: String, value: Any?) {
        if (destroyed) return
        try {
            when (name) {
                "time-pos" -> {
                    val secs = (value as? Double) ?: 0.0
                    _position.value = (secs * 1000).toLong()
                }
                "duration" -> {
                    val secs = (value as? Double) ?: 0.0
                    val ms = (secs * 1000).toLong()
                    _state.update { it.copy(durationMs = ms) }
                    _mediaInfo.update { it?.copy(durationMs = ms) }
                }
                "pause" -> (value as? Boolean)?.let { paused ->
                    _state.update {
                        it.copy(
                            paused = paused,
                            status = if (!playbackFileLoaded) it.status
                            else if (paused) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING,
                        )
                    }
                }
                "paused-for-cache" -> _state.update { it.copy(buffering = (value as? Boolean) ?: false) }
                "eof-reached" -> _state.update { it.copy(eof = (value as? Boolean) ?: false) }
                "volume" -> _state.update { it.copy(volume = (value as? Number)?.toInt() ?: it.volume) }
                "mute" -> _state.update { it.copy(muted = (value as? Boolean) ?: false) }
                "speed" -> _state.update { it.copy(rate = (value as? Double)?.toFloat() ?: it.rate) }
                "hwdec-current" -> (value as? String)?.let { v ->
                    _mediaInfo.update { it?.copy(hwdecCurrent = v) }
                    logger?.appEvent(
                        "engine",
                        "hwdec-current=$v requested=${currentConfig?.hwdec ?: "unknown"} " +
                            "effective=${effectiveDesktopHwdec(currentConfig?.hwdec ?: "auto-copy")} render=sw ui=$uiRenderBackend",
                        LogLevel.INFO,
                    )
                }
                "video-params/gamma", "video-params/primaries" -> updateHdrInfo()
                "video-params/rotate" -> _mediaInfo.update { it?.copy(rotation = (value as? Long)?.toInt() ?: 0) }
                "track-list/count" -> updateTrackList()
            }
        } catch (_: ClassCastException) {
            // 事件线程收到意外类型, 安全忽略
        }
        dispatchProperty(name, value)
    }

    // === 媒体信息/轨道快照(FILE_LOADED / VIDEO_RECONFIG 后拉, 对齐 android) ===

    private fun updateMediaInfoSnapshot() {
        // CR-065: 入口复查 destroyed, 避免 destroy 发布 handle=null 并 terminate 后事件线程仍做 JNA 调用。
        if (destroyed || handle == null) return
        val gamma = getPropertyString("video-params/gamma")
        val primaries = getPropertyString("video-params/primaries")
        val isHdr = gamma == "pq" || gamma == "hlg"
        _mediaInfo.update { existing ->
            (existing ?: MediaInfo()).copy(
                title = getPropertyString("media-title") ?: existing?.title,
                filePath = getPropertyString("path") ?: existing?.filePath,
                containerFormat = getPropertyString("file-format") ?: existing?.containerFormat,
                videoCodec = getPropertyString("video-codec") ?: existing?.videoCodec,
                audioCodec = getPropertyString("audio-codec") ?: existing?.audioCodec,
                width = getPropertyInt("video-params/w") ?: 0,
                height = getPropertyInt("video-params/h") ?: 0,
                rotation = getPropertyInt("video-params/rotate") ?: 0,
                fps = getPropertyDouble("container-fps") ?: existing?.fps ?: 0.0,
                videoBitrate = getPropertyInt("video-bitrate") ?: existing?.videoBitrate ?: 0,
                audioBitrate = getPropertyInt("audio-bitrate") ?: existing?.audioBitrate ?: 0,
                audioSampleRate = getPropertyInt("audio-params/samplerate") ?: existing?.audioSampleRate ?: 0,
                audioChannels = getPropertyInt("audio-params/channel-count") ?: existing?.audioChannels ?: 0,
                durationMs = ((getPropertyDouble("duration") ?: 0.0) * 1000).toLong(),
                hdrInfo = if (isHdr) {
                    HdrInfo(true, gamma, primaries, getPropertyDouble("video-params/max-cll") ?: 0.0)
                } else existing?.hdrInfo,
                hwdecCurrent = getPropertyString("hwdec-current") ?: existing?.hwdecCurrent,
                requestedHwdec = currentConfig?.hwdec ?: existing?.requestedHwdec,
                effectiveHwdec = currentConfig?.hwdec?.let(::effectiveDesktopHwdec) ?: existing?.effectiveHwdec,
                vo = "libmpv sw output",
                gpuApi = uiRenderBackend,
            )
        }
    }

    private fun failPlayback(message: String) {
        _state.update { it.copy(status = PlaybackStatus.ERROR, error = message, buffering = false) }
    }

    private fun updateTrackList() {
        if (destroyed || handle == null) return
        val count = getPropertyInt("track-list/count") ?: 0
        val video = mutableListOf<TrackInfo>()
        val audio = mutableListOf<TrackInfo>()
        val subtitle = mutableListOf<TrackInfo>()
        for (i in 0 until count) {
            val type = getPropertyString("track-list/$i/type") ?: continue
            val id = getPropertyInt("track-list/$i/id") ?: continue
            val info = TrackInfo(
                id = id,
                type = when (type) {
                    "video" -> TrackType.VIDEO
                    "audio" -> TrackType.AUDIO
                    "sub" -> TrackType.SUBTITLE
                    else -> continue
                },
                title = getPropertyString("track-list/$i/title"),
                lang = getPropertyString("track-list/$i/lang"),
                codec = getPropertyString("track-list/$i/codec"),
                external = getPropertyBoolean("track-list/$i/external") == true,
                selected = getPropertyBoolean("track-list/$i/selected") == true,
            )
            when (info.type) {
                TrackType.VIDEO -> video.add(info)
                TrackType.AUDIO -> audio.add(info)
                TrackType.SUBTITLE -> subtitle.add(info)
            }
        }
        _tracks.update { TrackList(video, audio, subtitle) }
    }

    private fun updateHdrInfo() {
        if (destroyed || handle == null) return
        val gamma = getPropertyString("video-params/gamma")
        val primaries = getPropertyString("video-params/primaries")
        val isHdr = gamma == "pq" || gamma == "hlg"
        _mediaInfo.update { existing ->
            existing?.copy(
                hdrInfo = if (isHdr) {
                    HdrInfo(true, gamma, primaries, getPropertyDouble("video-params/max-cll") ?: 0.0)
                } else existing.hdrInfo
            )
        }
    }
}
