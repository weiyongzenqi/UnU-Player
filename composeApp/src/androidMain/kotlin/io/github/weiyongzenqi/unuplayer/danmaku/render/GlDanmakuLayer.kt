package io.github.weiyongzenqi.unuplayer.danmaku.render

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import io.github.weiyongzenqi.unuplayer.danmaku.gl.DanmakuGlRenderer
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GLES 弹幕渲染层：帧驱动 + TextureView 手动 EGL 宿主。
 *
 * 帧驱动复用 [DanmakuCanvas] 的 vsync 循环 + 空闲挂起逻辑（B-13/B-14），
 * 但把 Canvas draw 替换为调用 [GlDanmakuSurfaceView.requestRender]。
 *
 * [GlDanmakuTextureView] 内嵌 TextureView（GLES 3.0 + 透明），
 * 渲染逻辑全部在 [DanmakuGlRenderer] 中。
 */
@Composable
internal fun GlDanmakuLayer(
    engine: GlesDanmakuEngine,
    entries: List<DanmakuEntry>,
    config: DanmakuConfig,
    positionFlow: StateFlow<Long>,
    frozen: Boolean,
    seekPositionMs: Long,
    seekGeneration: Long,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fontScalePx = density.density * density.fontScale
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // TextureView + 手动 EGL（替代 GLSurfaceView，参与 Compose 动画管线）
    val glView = remember { GlDanmakuTextureView(appContext, engine) }

    val applied = remember(engine) { GlesAppliedInputs() }
    val idleGate = remember(engine) { GlesIdleGate() }
    val wakeGeneration = remember { MutableStateFlow(0L) }
    val preparedState = remember { mutableStateOf(GlesPreparedInput.Empty) }
    val screenSize = remember { mutableLongStateOf(0L) } // width(high32) | height(low32)

    // B-14：后台排序
    LaunchedEffect(GlesReferentialKey(entries)) {
        val prepared = withContext(Dispatchers.Default) { prepareDanmakuEntries(entries) }
        preparedState.value = GlesPreparedInput(entries, prepared)
        wakeGeneration.update { it + 1 }
    }
    val glesInput = preparedState.value
    val preparedEntries = if (glesInput.source === entries) glesInput.entries else emptyList()

    // 恢复信号
    LaunchedEffect(entries, seekGeneration, config) {
        wakeGeneration.update { it + 1 }
    }

    // 帧驱动：vsync 循环 + 空闲挂起
    LaunchedEffect(engine, positionFlow, frozen) {
        idleGate.reset()
        if (frozen) {
            withFrameNanos { /* one tick for state flush */ }
            engine.setPaused(true)
            applyInputs(engine, preparedEntries, config, fontScalePx, frozen, seekPositionMs, seekGeneration, applied)
            engine.onFrame(positionFlow.value, screenSize.screenW(), screenSize.screenH(), 0f)
            engine.publishFrame()
            glView.requestRender()
            return@LaunchedEffect
        }
        while (true) {
            val frameNanos = withFrameNanos { it }
            val suspendReq = idleGate.request
            if (suspendReq != null) {
                val suspendedAt = wakeGeneration.value
                if (idleGate.request != null) {
                    withTimeoutOrNull(IDLE_WAKE_FALLBACK_MS) {
                        val wakePos = suspendReq.wakePositionMs
                        if (wakePos == null) {
                            wakeGeneration.first { it > suspendedAt }
                        } else {
                            merge(
                                wakeGeneration.filter { it > suspendedAt },
                                positionFlow.filter { it >= wakePos },
                            ).first()
                        }
                    }
                }
            }
            // 应用输入 + 推进位置 + 发布快照 + 触发 GL 渲染
            applyInputs(engine, preparedEntries, config, fontScalePx, frozen, seekPositionMs, seekGeneration, applied)
            engine.onFrame(positionFlow.value, screenSize.screenW(), screenSize.screenH(), 0f)
            engine.publishFrame()
            glView.requestRender()

            val wasSuspended = idleGate.request != null
            idleGate.onDrawnFrame(engine.frameSchedule())
            if (wasSuspended && idleGate.request == null) {
                wakeGeneration.update { it + 1 }
            }
        }
    }

    // 生命周期清理：停止渲染线程 + 释放 EGL
    DisposableEffect(glView) {
        onDispose {
            glView.stopRenderThread()
        }
    }

    AndroidView(
        modifier = modifier.onSizeChanged { size ->
            screenSize.longValue = (size.width.toLong() shl 32) or (size.height.toLong() and 0xFFFF_FFFF)
        },
        factory = { glView },
        update = { /* RENDERMODE_WHEN_DIRTY */ },
    )
}

/** 应用 load/config/paused/seek 等输入到引擎。 */
private fun applyInputs(
    engine: GlesDanmakuEngine,
    preparedEntries: List<DanmakuEntry>,
    config: DanmakuConfig,
    fontScalePx: Float,
    frozen: Boolean,
    seekPositionMs: Long,
    seekGeneration: Long,
    applied: GlesAppliedInputs,
) {
    if (applied.entries !== preparedEntries) {
        engine.load(preparedEntries)
        applied.entries = preparedEntries
    }
    if (applied.config != config) {
        engine.setConfig(config)
        applied.config = config
    }
    if (applied.fontScalePx != fontScalePx) {
        engine.setFontScalePx(fontScalePx)
        applied.fontScalePx = fontScalePx
    }
    if (applied.frozen != frozen) {
        engine.setPaused(frozen)
        applied.frozen = frozen
    }
    if (applied.seekGeneration != seekGeneration) {
        engine.onSeek(seekPositionMs)
        applied.seekGeneration = seekGeneration
    }
}

/**
 * 弹幕 TextureView：手动 EGL + 专用渲染线程。
 *
 * TextureView 替代 GLSurfaceView：GLSurfaceView 的独立 Surface 不参与 Compose RenderThread
 * 动画管线，预测性返回时 SurfaceFlinger 合成与动画变换不同步导致掉帧。
 * TextureView 内容作为普通 View 纹理层，Compose 可平滑动画。
 */
internal class GlDanmakuTextureView(
    context: Context,
    private val engine: GlesDanmakuEngine,
) : TextureView(context), TextureView.SurfaceTextureListener {

    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var renderer: DanmakuGlRenderer? = null
    private var surfaceW: Int = 0
    private var surfaceH: Int = 0
    @Volatile private var renderRequested: Boolean = false
    @Volatile private var stopped: Boolean = false

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun requestRender() {
        renderRequested = true
        renderHandler?.post(renderRunnable)
    }

    fun stopRenderThread() {
        stopped = true
        renderHandler?.removeCallbacks(renderRunnable)
        renderThread?.quitSafely()
        renderThread = null; renderHandler = null
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surfaceW = w; surfaceH = h; stopped = false
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val nc = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, nc, 0)
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], st, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        renderer = DanmakuGlRenderer(context, engine)
        renderer!!.onSurfaceCreated(w, h)
        renderThread = HandlerThread("DanmakuGL").apply { start() }
        renderHandler = Handler(renderThread!!.looper)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        surfaceW = w; surfaceH = h
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stopped = true; renderHandler?.removeCallbacks(renderRunnable)
        renderThread?.quitSafely(); renderThread = null; renderHandler = null
        renderer?.onSurfaceDestroyed()
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
        eglSurface = null; eglContext = null; eglDisplay = null; renderer = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private val renderRunnable = Runnable {
        if (stopped || eglDisplay == null || eglSurface == null) return@Runnable
        if (!renderRequested) return@Runnable; renderRequested = false
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        renderer?.onDrawFrame(surfaceW, surfaceH)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }
}

/** no-op DrawScope 移除 —— GLES 路径不经过 Canvas draw，直接调 onFrame/publishFrame。 */

// ---- 从 DanmakuCanvas 复用的辅助类型 ----

internal class GlesIdleGate {
    @Volatile
    var request: GlesSuspendRequest? = null
        private set
    private var idleStreak: Int = 0

    fun onDrawnFrame(schedule: DanmakuFrameSchedule) {
        if (schedule is DanmakuFrameSchedule.Suspend) {
            idleStreak++
            request = if (idleStreak >= IDLE_SUSPEND_FRAMES) {
                GlesSuspendRequest(schedule.wakePositionMs)
            } else null
        } else {
            idleStreak = 0
            request = null
        }
    }

    fun reset() { idleStreak = 0; request = null }
}

internal data class GlesSuspendRequest(val wakePositionMs: Long?)

private const val IDLE_SUSPEND_FRAMES = 15
private const val IDLE_WAKE_FALLBACK_MS = 2_500L

internal class GlesReferentialKey(private val value: Any) {
    override fun equals(other: Any?): Boolean = other is GlesReferentialKey && value === other.value
    override fun hashCode(): Int = 0
}

internal class GlesPreparedInput(val source: List<DanmakuEntry>, val entries: List<DanmakuEntry>) {
    companion object { val Empty = GlesPreparedInput(emptyList(), emptyList()) }
}

internal class GlesAppliedInputs {
    var entries: List<DanmakuEntry>? = null
    var config: DanmakuConfig? = null
    var fontScalePx = Float.NaN
    var frozen: Boolean? = null
    var seekGeneration = Long.MIN_VALUE
}

/** 从打包的 Long 提取宽度（高位 32 bit）。 */
private fun androidx.compose.runtime.MutableLongState.screenW(): Float =
    ((longValue shr 32).toInt().coerceAtLeast(1)).toFloat()

/** 从打包的 Long 提取高度（低位 32 bit）。 */
private fun androidx.compose.runtime.MutableLongState.screenH(): Float =
    ((longValue and 0xFFFF_FFFF).toInt().coerceAtLeast(1)).toFloat()
