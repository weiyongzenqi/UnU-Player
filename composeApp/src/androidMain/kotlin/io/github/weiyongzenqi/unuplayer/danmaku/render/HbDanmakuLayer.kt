package io.github.weiyongzenqi.unuplayer.danmaku.render

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import android.graphics.RectF
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import io.github.weiyongzenqi.unuplayer.danmaku.gl.HardwareBufferRenderer
import io.github.weiyongzenqi.unuplayer.danmaku.gl.OffscreenGLBridge
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
 * GLES_HB 历史路径的离屏弹幕渲染层（Compose）。
 *
 * 当前实现没有使用 Android HardwareBuffer：GL 线程渲染到普通 FBO，逐帧 `glReadPixels`
 * 回读到 Bitmap，再由 Compose Canvas 贴到画布。名称为兼容既有设置保存值而保留。
 *
 * 该实验路径仍有初始化、背压、同步和释放问题，且全屏回读可能产生显著带宽与功耗；
 * 不应视为生产路径或 HardwareBuffer 零拷贝实现。
 */
@Composable
internal fun HbDanmakuLayer(
    engine: GlesDanmakuEngine,
    entries: List<DanmakuEntry>,
    config: DanmakuConfig,
    positionFlow: StateFlow<Long>,
    frozen: Boolean,
    seekPositionMs: Long,
    seekGeneration: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val fontScalePx = density.density * density.fontScale

    // ---- 离屏 GL 回读 Bitmap + Compose Canvas 路径 ----
    val screenSize = remember { mutableLongStateOf(0L) }
    fun displayW(): Float = ((screenSize.longValue shr 32).toInt().coerceAtLeast(1)).toFloat()
    fun displayH(): Float = ((screenSize.longValue and 0xFFFF_FFFF).toInt().coerceAtLeast(1)).toFloat()

    val applied = remember(engine) { GlesAppliedInputs() }
    val idleGate = remember(engine) { GlesIdleGate() }
    val wakeGeneration = remember { MutableStateFlow(0L) }
    val preparedState = remember { mutableStateOf(GlesPreparedInput.Empty) }

    // B-14: 后台排序
    LaunchedEffect(GlesReferentialKey(entries)) {
        val prepared = withContext(Dispatchers.Default) { prepareDanmakuEntries(entries) }
        preparedState.value = GlesPreparedInput(entries, prepared)
        wakeGeneration.update { it + 1 }
    }
    val glesInput = preparedState.value
    val preparedEntries = if (glesInput.source === entries) glesInput.entries else emptyList()

    LaunchedEffect(entries, seekGeneration, config) { wakeGeneration.update { it + 1 } }

    // ---- GL 线程 + 普通 FBO 初始化 ----
    val glState = remember { mutableStateOf<HbGlState?>(null) }

    LaunchedEffect(screenSize.longValue) {
        val w = displayW().toInt(); val h = displayH().toInt()
        if (w <= 0 || h <= 0) return@LaunchedEffect

        // 清理旧状态
        glState.value?.let { old ->
            old.thread.quitSafely()
            old.handler.post { old.bridge?.destroy(); old.renderer?.destroy() }
        }

        val thread = HandlerThread("HbRenderer", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        val handler = Handler(thread.looper)
        var bridge: OffscreenGLBridge? = null
        var renderer: HardwareBufferRenderer? = null

        handler.post {
            try {
                // 创建专用 EGL context + pbuffer surface（离屏渲染）
                val disp = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
                val version = IntArray(2)
                android.opengl.EGL14.eglInitialize(disp, version, 0, version, 1)
                val configAttribs = intArrayOf(
                    android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    android.opengl.EGL14.EGL_RED_SIZE, 8, android.opengl.EGL14.EGL_GREEN_SIZE, 8,
                    android.opengl.EGL14.EGL_BLUE_SIZE, 8, android.opengl.EGL14.EGL_ALPHA_SIZE, 8,
                    android.opengl.EGL14.EGL_DEPTH_SIZE, 16, android.opengl.EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val nc = IntArray(1)
                android.opengl.EGL14.eglChooseConfig(disp, configAttribs, 0, configs, 0, 1, nc, 0)
                val ctxAttribs = intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, android.opengl.EGL14.EGL_NONE)
                val ctx = android.opengl.EGL14.eglCreateContext(disp, configs[0], android.opengl.EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
                val surf = android.opengl.EGL14.eglCreatePbufferSurface(disp, configs[0], intArrayOf(android.opengl.EGL14.EGL_WIDTH, 1, android.opengl.EGL14.EGL_HEIGHT, 1, android.opengl.EGL14.EGL_NONE), 0)
                android.opengl.EGL14.eglMakeCurrent(disp, surf, surf, ctx)

                bridge = OffscreenGLBridge(w, h)
                renderer = HardwareBufferRenderer(context, engine, bridge!!)
                renderer!!.init()

                // 保持 context current 供后续渲染帧使用（不销毁 pbuffer）
            } catch (e: Exception) {
                Log.e("HbDanmaku", "Offscreen GL init failed: ${e.message}")
                glState.value?.thread?.quitSafely()
                glState.value = null
            }
        }

        glState.value = HbGlState(thread, handler, bridge, renderer, displayW(), displayH())
    }

    // ---- 帧驱动（vsync 循环 + 空闲挂起） ----
    LaunchedEffect(engine, positionFlow, frozen, glState.value) {
        val hbState = glState.value ?: return@LaunchedEffect
        idleGate.reset()
        if (frozen) {
            withFrameNanos { } // 一帧状态刷新
            engine.setPaused(true)
            applyHbInputs(engine, preparedEntries, config, fontScalePx, frozen, seekPositionMs, seekGeneration, applied)
            engine.onFrame(positionFlow.value, hbState.width, hbState.height, 0f)
            engine.publishFrame()
            hbState.handler.post { hbState.renderer?.renderFrame() }
            return@LaunchedEffect
        }
        var frameCount = 0L
        var totalNanos = 0L
        while (true) {
            withFrameNanos { }
            val suspendReq = idleGate.request
            if (suspendReq != null) {
                val suspendedAt = wakeGeneration.value
                if (idleGate.request != null) {
                    withTimeoutOrNull(IDLE_WAKE_FALLBACK_MS) {
                        val wp = suspendReq.wakePositionMs
                        if (wp == null) wakeGeneration.first { it > suspendedAt }
                        else merge(wakeGeneration.filter { it > suspendedAt }, positionFlow.filter { it >= wp }).first()
                    }
                }
            }
            applyHbInputs(engine, preparedEntries, config, fontScalePx, frozen, seekPositionMs, seekGeneration, applied)
            engine.onFrame(positionFlow.value, hbState.width, hbState.height, 0f)
            engine.publishFrame()
            val t0 = System.nanoTime()
            hbState.handler.post { hbState.renderer?.renderFrame() }
            val t1 = System.nanoTime()
            frameCount++; totalNanos += (t1 - t0)
            if (frameCount % PERF_LOG_INTERVAL == 0L) {
                val avgUs = totalNanos / PERF_LOG_INTERVAL / 1000
                // Log only if > 100us (meaningful overhead)
                if (avgUs > 100) Log.d("HbPerf", "HB render avg ${avgUs}us/frame over $PERF_LOG_INTERVAL frames")
                totalNanos = 0L
            }

            val wasSuspended = idleGate.request != null
            idleGate.onDrawnFrame(engine.frameSchedule())
            if (wasSuspended && idleGate.request == null) wakeGeneration.update { it + 1 }
        }
    }

    // 生命周期清理
    DisposableEffect(Unit) {
        onDispose {
            glState.value?.let { s ->
                s.handler.post { s.renderer?.destroy(); s.bridge?.destroy() }
                s.thread.quitSafely()
            }
        }
    }

    // ---- Compose Canvas：用 nativeCanvas.drawBitmap（HWUI display-list 管线，动画丝滑） ----
    val bridge = glState.value?.bridge
    if (bridge != null) {
        Canvas(modifier.onSizeChanged { size ->
            screenSize.longValue = (size.width.toLong() shl 32) or (size.height.toLong() and 0xFFFF_FFFF)
        }) {
            // 关键：用 drawIntoCanvas → nativeCanvas.drawBitmap，绕开 Compose drawImage 的 Skia 管线。
            // HWUI 将 Bitmap 缓存为 GPU 纹理 → display list replay → 动画时不重绘，只做矩阵变换。
            // 这与 ATLAS 引擎 AndroidAtlasDanmakuEngine.draw() 使用完全相同的渲染路径。
            drawIntoCanvas { canvas ->
                val bmp = bridge.bitmap
                val w = displayW().toInt()
                val h = displayH().toInt()
                canvas.nativeCanvas.drawBitmap(bmp, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null)
            }
        }
    }
}

/** GL 线程 + 离屏 FBO 渲染器状态。 */
private class HbGlState(
    val thread: HandlerThread,
    val handler: Handler,
    @Volatile var bridge: OffscreenGLBridge?,
    @Volatile var renderer: HardwareBufferRenderer?,
    val width: Float,
    val height: Float,
)

/** 应用 load/config/paused/seek 到引擎（与 GlDanmakuLayer 的 applyInputs 相同逻辑）。 */
private fun applyHbInputs(
    engine: GlesDanmakuEngine,
    preparedEntries: List<DanmakuEntry>,
    config: DanmakuConfig,
    fontScalePx: Float,
    frozen: Boolean,
    seekPositionMs: Long,
    seekGeneration: Long,
    applied: GlesAppliedInputs,
) {
    if (applied.entries !== preparedEntries) { engine.load(preparedEntries); applied.entries = preparedEntries }
    if (applied.config != config) { engine.setConfig(config); applied.config = config }
    if (applied.fontScalePx != fontScalePx) { engine.setFontScalePx(fontScalePx); applied.fontScalePx = fontScalePx }
    if (applied.frozen != frozen) { engine.setPaused(frozen); applied.frozen = frozen }
    if (applied.seekGeneration != seekGeneration) { engine.onSeek(seekPositionMs); applied.seekGeneration = seekGeneration }
}

private const val IDLE_WAKE_FALLBACK_MS = 2_500L
private const val PERF_LOG_INTERVAL = 60L
