package io.github.weiyongzenqi.unuplayer.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.math.min
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import io.github.weiyongzenqi.unuplayer.core.gl.WindowBoundsRefreshScheduler
import io.github.weiyongzenqi.unuplayer.core.gl.currentAwtWindow
import io.github.weiyongzenqi.unuplayer.core.gl.skikoRenderBackendDescription
import io.github.weiyongzenqi.unuplayer.core.player.DesktopMpvPlayerEngine

/**
 * 桌面视频 Surface：native RGB 渲染在 [DesktopVideoRenderWorker]，Compose draw 只上传并绘制
 * 已完成的最新帧。worker 等 UI 确认后才处理下一帧，拖窗/最小化时不会持续占用 EDT。
 */
@Composable
fun MpvVideoSurface(
    engine: DesktopMpvPlayerEngine?,
    modifier: Modifier = Modifier,
    renderTargetKey: Boolean = false,
    sourceWidth: Int = 0,
    sourceHeight: Int = 0,
    sourceRotation: Int = 0,
) {
    val window = currentAwtWindow()
    var frameTick by remember { mutableIntStateOf(0) }
    val active = remember(engine) { AtomicBoolean(true) }
    val repaintQueued = remember(engine) { AtomicBoolean(false) }
    val softwareImage = remember(engine) { SoftwareRasterImageHolder() }
    val boundsRefreshScheduler = remember(window) { window?.let(::WindowBoundsRefreshScheduler) }
    val renderBudget = remember(window, renderTargetKey, sourceWidth, sourceHeight) {
        desktopVideoRenderBudget(window, sourceWidth, sourceHeight)
    }
    // renderBudget 故意不作 remember key: 全屏切换(renderTargetKey 变)/源尺寸到达都会改变 budget,
    // 但 worker 支持 in-place 热切换——setViewportSize 在每次 draw 与 onSizeChanged 都传入最新 budget。
    // 若以 budget 为 key, 每次变化都销毁重建 worker, 需重走 150ms 尺寸稳定窗口 + 首帧, 期间黑闪。
    // remember 块内只捕获 engine 回调, 不捕获 budget 值, 故单 key(engine) 不会产生陈旧状态。
    val renderWorker = remember(engine) {
        engine?.let { currentEngine ->
            DesktopVideoRenderWorker(
                renderFrame = currentEngine::renderSoftwareFrame,
                frameVersion = { it.version },
                reportError = currentEngine::reportRenderFailure,
                onFrameAvailable = {
                    if (active.get() && repaintQueued.compareAndSet(false, true)) {
                        SwingUtilities.invokeLater {
                            repaintQueued.set(false)
                            if (active.get()) frameTick++
                        }
                    }
                },
            )
        }
    }

    LaunchedEffect(window, engine) {
        val currentEngine = engine ?: return@LaunchedEffect
        repeat(BACKEND_PROBE_ATTEMPTS) {
            val description = window?.skikoRenderBackendDescription()
            currentEngine.setUiRenderBackend(description)
            if (description != null) return@LaunchedEffect
            delay(BACKEND_PROBE_DELAY_MS)
        }
    }

    // WindowPlacement.Fullscreen 的原生 framebuffer 更新可能晚于 Compose 状态提交。
    // 只调度异步、合并后的 revalidate/repaint，禁止同步 validate/needRender 重入 Compose draw。
    LaunchedEffect(window, renderTargetKey) {
        WINDOW_REFRESH_DELAYS_MS.forEach { delayMs ->
            if (delayMs > 0L) delay(delayMs)
            boundsRefreshScheduler?.request()
        }
    }

    DisposableEffect(boundsRefreshScheduler) {
        boundsRefreshScheduler?.start()
        onDispose { boundsRefreshScheduler?.close() }
    }

    DisposableEffect(engine, renderWorker) {
        active.set(true)
        engine?.setRequestRepaint(renderWorker?.let { worker -> worker::requestRender })
        onDispose {
            active.set(false)
            engine?.setRequestRepaint(null)
            renderWorker?.close()
            // 组合树中子组件 onDispose 先于父组件(DesktopPlayerScreen 的 engine 释放),
            // 此处有界等待 worker 线程退出后再放行释放, 关闭"首帧建 ctx vs destroy"竞态主窗口;
            // 引擎侧锁内复查为主防护, 此为纵深防御。有界(1s)不拖死 dispose 线程, 超时由锁内复查兜底。
            renderWorker?.awaitStopped(RENDER_WORKER_STOP_TIMEOUT_MS)
            softwareImage.close()
        }
    }

    Canvas(
        modifier.onSizeChanged { size ->
            renderWorker?.setViewportSize(size.width, size.height, renderBudget)
        },
    ) {
        @Suppress("UNUSED_EXPRESSION") frameTick
        val worker = renderWorker ?: return@Canvas
        worker.setViewportSize(size.width.toInt(), size.height.toInt(), renderBudget)
        worker.withLatestFrame { videoFrame ->
            try {
                val image = softwareImage.imageFor(videoFrame)
                val layout = rotatedVideoFrameLayout(
                    size.width,
                    size.height,
                    image.width,
                    image.height,
                    sourceRotation,
                )
                val source = Rect.makeLTRB(0f, 0f, image.width.toFloat(), image.height.toFloat())
                drawIntoCanvas { canvas ->
                    val skiaCanvas = canvas.skiaCanvas
                    if (layout.rotationDegrees == 0f) {
                        // 无旋转: 目标矩形即画布绝对坐标, 保持原有直绘路径不做多余矩阵操作。
                        skiaCanvas.drawImageRect(
                            image,
                            source,
                            layout.rotatedBounds,
                            SamplingMode.Companion.MITCHELL,
                            null,
                            true,
                        )
                    } else {
                        // mpv sw render 输出未旋转帧, 旋转由合成侧做:
                        // 平移到旋转后占位矩形中心, 顺时针施加元数据角度后绘制局部坐标目标矩形。
                        skiaCanvas.save()
                        try {
                            skiaCanvas.translate(
                                (layout.rotatedBounds.left + layout.rotatedBounds.right) / 2f,
                                (layout.rotatedBounds.top + layout.rotatedBounds.bottom) / 2f,
                            )
                            skiaCanvas.rotate(layout.rotationDegrees)
                            skiaCanvas.drawImageRect(
                                image,
                                source,
                                layout.destination,
                                SamplingMode.Companion.MITCHELL,
                                null,
                                true,
                            )
                        } finally {
                            // drawImageRect 抛错也必须恢复 canvas 状态, 否则后续 draw 矩阵错乱。
                            skiaCanvas.restore()
                        }
                    }
                }
            } catch (error: Throwable) {
                engine?.reportRenderFailure(error)
            } finally {
                // CR-069: 不在此处 close Image。每帧 close 会置 image=null/pixels=null/version=-1,
                // 下次 draw 的 imageFor 因 image==null 必走 Image.makeRaster 重建, 击穿 version/pixels
                // 缓存(暂停时每帧重建 ~60/s)。imageFor 检测到 pixels/version 变化时自动 close 旧 Image
                // 换新(:251 已有此逻辑)。worker 切换 resize generation 的安全性由 withLatestFrame 的
                // activeFrameReaders lease 独立保护, 不依赖 Image.close -- lease 期间 worker 不切换,
                // lease 释放后 worker 可切换但旧 Data 由 Skia 引用计数持有, Image 不 close 时旧 Data
                // 内存延迟到下帧 imageFor 换新时释放(可接受, 不 use-after-free)。
                // markPresented 释放 worker 背压, 必须无条件执行(draw 成功/异常均要 ack)。
                worker.markPresented(videoFrame.version)
            }
        }
    }
}

/** 旋转感知后的视频帧放置结果, 见 [rotatedVideoFrameLayout]。 */
internal data class RotatedVideoFrameLayout(
    /** 旋转后占位的轴对齐外接矩形(画布绝对坐标), 即画面最终屏幕位置。 */
    val rotatedBounds: Rect,
    /** drawImageRect 目标矩形, 以 [rotatedBounds] 中心为原点的局部坐标; 旋转后恰好填满外接矩形。 */
    val destination: Rect,
    /** 合成侧需顺时针施加的旋转角度(0/90/180/270); 其他旋转元数据按 0 处理。 */
    val rotationDegrees: Float,
)

/**
 * 计算带旋转元数据视频帧的目标矩形与旋转角度。
 *
 * mpv sw render 输出未旋转帧, 旋转由合成侧做: 90/270 时用交换后的源宽高算 fit 缩放得到
 * 旋转后占位的外接矩形, 绘制矩形再用帧自身(未交换)尺寸以该外接矩形中心为原点给出,
 * 供 canvas translate+rotate 后直绘。
 */
internal fun rotatedVideoFrameLayout(
    canvasWidth: Float,
    canvasHeight: Float,
    frameWidth: Int,
    frameHeight: Int,
    rotation: Int,
): RotatedVideoFrameLayout {
    val degrees = when ((((rotation % 360) + 360) % 360)) {
        90 -> 90f
        180 -> 180f
        270 -> 270f
        else -> 0f
    }
    val swapped = degrees == 90f || degrees == 270f
    val displayWidth = if (swapped) frameHeight else frameWidth
    val displayHeight = if (swapped) frameWidth else frameHeight
    val scale = min(canvasWidth / displayWidth, canvasHeight / displayHeight)
    val boundsWidth = displayWidth * scale
    val boundsHeight = displayHeight * scale
    val boundsLeft = (canvasWidth - boundsWidth) / 2f
    val boundsTop = (canvasHeight - boundsHeight) / 2f
    val rotatedBounds = Rect.makeLTRB(
        boundsLeft,
        boundsTop,
        boundsLeft + boundsWidth,
        boundsTop + boundsHeight,
    )
    val imageWidth = frameWidth * scale
    val imageHeight = frameHeight * scale
    val destination = Rect.makeLTRB(
        -imageWidth / 2f,
        -imageHeight / 2f,
        imageWidth / 2f,
        imageHeight / 2f,
    )
    return RotatedVideoFrameLayout(rotatedBounds, destination, degrees)
}

internal class SoftwareRasterImageHolder : AutoCloseable {
    private var pixels: org.jetbrains.skia.Data? = null
    private var version = -1L
    private var width = 0
    private var height = 0
    private var stride = 0
    private var image: Image? = null

    fun imageFor(frame: DesktopMpvPlayerEngine.SoftwareVideoFrame): Image {
        if (
            image == null || version != frame.version || pixels !== frame.pixels ||
            width != frame.width || height != frame.height || stride != frame.stride
        ) {
            // m1(终审): makeRaster 先于 close 旧 image; 失败(native OOM)时抛异常, 旧 image/pixels 未动,
            // 下次 imageFor 同帧命中缓存返回旧有效 image, 不 UAF(原顺序先 close 旧再 makeRaster, 失败时
            // image 残留已 close 引用 + 字段已更新 -> 下次缓存命中返回已 close image -> UAF)。
            val newImage = Image.makeRaster(
                ImageInfo(frame.width, frame.height, ColorType.RGB_888X, ColorAlphaType.OPAQUE),
                frame.pixels,
                frame.stride,
            )
            image?.close()
            image = newImage
            pixels = frame.pixels
            version = frame.version
            width = frame.width
            height = frame.height
            stride = frame.stride
        }
        return checkNotNull(image)
    }

    override fun close() {
        image?.close()
        image = null
        pixels = null
        version = -1L
    }
}

private const val BACKEND_PROBE_ATTEMPTS = 50
private const val BACKEND_PROBE_DELAY_MS = 100L
private val WINDOW_REFRESH_DELAYS_MS = longArrayOf(0L, 16L, 50L, 100L)

/** onDispose 等待 render worker 退出的有界超时; 超时不阻塞 dispose 线程, 引擎侧锁内复查兜底。 */
private const val RENDER_WORKER_STOP_TIMEOUT_MS = 1000L
