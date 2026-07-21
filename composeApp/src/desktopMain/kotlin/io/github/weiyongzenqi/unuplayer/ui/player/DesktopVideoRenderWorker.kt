package io.github.weiyongzenqi.unuplayer.ui.player

import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Window
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** 播放期间稳定一段时间后才切换 libmpv software render 尺寸；拖窗期间沿用上一尺寸。 */
internal data class DesktopVideoRenderSize(val width: Int, val height: Int)

/** CPU RGB render 的尺寸预算；最终 target 取 surface/source/display/budget 的共同上限。 */
internal data class DesktopVideoRenderBudget(
    val displayWidth: Int,
    val displayHeight: Int,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val budgetWidth: Int = SOFTWARE_RENDER_BUDGET_WIDTH,
    val budgetHeight: Int = SOFTWARE_RENDER_BUDGET_HEIGHT,
)

internal fun desktopVideoRenderBudget(
    window: Window?,
    sourceWidth: Int = 0,
    sourceHeight: Int = 0,
): DesktopVideoRenderBudget {
    val configuration = window?.graphicsConfiguration
        ?: runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        }.getOrNull()
    val display = configuration?.let(::displayPixelSize) ?: DesktopVideoRenderSize(
        SOFTWARE_RENDER_BUDGET_WIDTH,
        SOFTWARE_RENDER_BUDGET_HEIGHT,
    )
    return DesktopVideoRenderBudget(
        displayWidth = display.width,
        displayHeight = display.height,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
    )
}

private fun displayPixelSize(configuration: GraphicsConfiguration): DesktopVideoRenderSize {
    val bounds = configuration.bounds
    val transform = configuration.defaultTransform
    val width = (bounds.width * abs(transform.scaleX)).roundToInt().coerceAtLeast(2)
    val height = (bounds.height * abs(transform.scaleY)).roundToInt().coerceAtLeast(2)
    return DesktopVideoRenderSize(width, height)
}

internal fun desktopVideoRenderSize(
    viewportWidth: Int,
    viewportHeight: Int,
    maxWidth: Int = SOFTWARE_RENDER_BUDGET_WIDTH,
    maxHeight: Int = SOFTWARE_RENDER_BUDGET_HEIGHT,
    budget: DesktopVideoRenderBudget? = null,
): DesktopVideoRenderSize? {
    if (viewportWidth < MIN_VIDEO_RENDER_EDGE || viewportHeight < MIN_VIDEO_RENDER_EDGE) return null
    val effectiveMaxWidth = min(maxWidth, budget?.let { min(it.displayWidth, it.budgetWidth) } ?: maxWidth)
        .let { value -> budget?.sourceWidth?.takeIf { it > 0 }?.let { min(value, it) } ?: value }
    val effectiveMaxHeight = min(maxHeight, budget?.let { min(it.displayHeight, it.budgetHeight) } ?: maxHeight)
        .let { value -> budget?.sourceHeight?.takeIf { it > 0 }?.let { min(value, it) } ?: value }
    if (effectiveMaxWidth < 2 || effectiveMaxHeight < 2) return null
    val scale = min(
        1.0,
        min(effectiveMaxWidth.toDouble() / viewportWidth, effectiveMaxHeight.toDouble() / viewportHeight),
    )
    val width = ((viewportWidth * scale).roundToInt().coerceAtLeast(2) / 2) * 2
    val height = ((viewportHeight * scale).roundToInt().coerceAtLeast(2) / 2) * 2
    return DesktopVideoRenderSize(width, height)
}

/**
 * 在专用线程消费 libmpv update 请求。一次只允许一张未被 UI 确认的帧：
 * UI 卡顿、拖窗或最小化时请求只合并为一个 pending，不会淹没 EDT 或覆盖当前帧缓冲。
 */
internal class DesktopVideoRenderWorker<T : Any>(
    private val renderFrame: (Int, Int) -> T?,
    private val frameVersion: (T) -> Long,
    private val reportError: (Throwable) -> Unit,
    private val onFrameAvailable: () -> Unit,
) : AutoCloseable {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val monitor = java.lang.Object()
    private var stopped = false
    private var renderRequested = false
    private var renderSize: DesktopVideoRenderSize? = null
    private var pendingRenderSize: DesktopVideoRenderSize? = null
    private var pendingSizeSinceNanos = 0L
    private var renderBudget: DesktopVideoRenderBudget? = null
    private var awaitingPresentationVersion: Long? = null
    private var activeFrameReaders = 0
    private var lastPublishedVersion = -1L

    @Volatile
    private var publishedFrame: T? = null

    private val workerThread = Thread(::renderLoop, "mpv-software-render").apply {
        isDaemon = true
        start()
    }

    /** 布局尺寸稳定后才切换 target；拖窗期间只更新候选，不反复分配 RGB 缓冲。 */
    fun setViewportSize(
        width: Int,
        height: Int,
        budget: DesktopVideoRenderBudget? = renderBudget,
    ): Boolean {
        val candidate = desktopVideoRenderSize(width, height, budget = budget) ?: return false
        synchronized(monitor) {
            if (stopped) return false
            renderBudget = budget
            if (renderSize == candidate) {
                if (pendingRenderSize == null) return false
                pendingRenderSize = null
                pendingSizeSinceNanos = 0L
                monitor.notifyAll()
                return true
            }
            if (pendingRenderSize == candidate) return false
            pendingRenderSize = candidate
            pendingSizeSinceNanos = System.nanoTime()
            renderRequested = true
            monitor.notifyAll()
            return true
        }
    }

    /** mpv update callback 可从任意 native 线程调用；多次请求合并为一次。 */
    fun requestRender() {
        synchronized(monitor) {
            if (stopped) return
            renderRequested = true
            monitor.notifyAll()
        }
    }

    /** stopped 后已发布帧的底层 Data 可能正被 engine destroy 关闭, 必须返回 null 而非停住的旧帧。 */
    internal fun latestFrame(): T? = synchronized(monitor) { if (stopped) null else publishedFrame }

    /** 在 UI draw 期间持有一份读取 lease，阻止 worker 在 resize 时关闭底层 Data。 */
    fun <R> withLatestFrame(block: (T) -> R): R? {
        val frame = synchronized(monitor) {
            if (stopped) null else publishedFrame?.also { activeFrameReaders++ }
        } ?: return null
        return try {
            block(frame)
        } finally {
            synchronized(monitor) {
                activeFrameReaders--
                monitor.notifyAll()
            }
        }
    }

    /** Canvas 完成 draw 后释放背压，worker 才能渲染下一张帧。 */
    fun markPresented(version: Long) {
        synchronized(monitor) {
            if (awaitingPresentationVersion != version) return
            awaitingPresentationVersion = null
            monitor.notifyAll()
        }
    }

    internal fun lockedRenderSize(): DesktopVideoRenderSize? = synchronized(monitor) { renderSize }

    override fun close() {
        synchronized(monitor) {
            if (stopped) return
            stopped = true
            renderRequested = false
            monitor.notifyAll()
        }
    }

    internal fun awaitStopped(timeoutMillis: Long): Boolean {
        workerThread.join(timeoutMillis)
        return !workerThread.isAlive
    }

    private fun renderLoop() {
        while (true) {
            val size = synchronized(monitor) {
                while (true) {
                    if (stopped) return
                    val candidate = pendingRenderSize
                    if (candidate != null) {
                        val elapsed = System.nanoTime() - pendingSizeSinceNanos
                        val remaining = VIEWPORT_STABLE_NANOS - elapsed
                        if (remaining > 0L) {
                            val millis = remaining / 1_000_000L
                            val nanos = (remaining % 1_000_000L).toInt()
                            monitor.wait(millis, nanos)
                            continue
                        }
                        if (awaitingPresentationVersion != null || activeFrameReaders != 0) {
                            monitor.wait()
                            continue
                        }
                        // 旧帧已经完成 draw 且没有读取者，先撤销发布，再让 engine 安全重建 Data。
                        publishedFrame = null
                        renderSize = candidate
                        pendingRenderSize = null
                    }
                    if (renderSize == null) {
                        monitor.wait()
                        continue
                    }
                    if (renderRequested && awaitingPresentationVersion == null) break
                    monitor.wait()
                }
                renderRequested = false
                checkNotNull(renderSize)
            }

            val frame = try {
                renderFrame(size.width, size.height)
            } catch (error: Throwable) {
                failWorker(error)
                return
            } ?: continue

            val published = synchronized(monitor) {
                val version = frameVersion(frame)
                if (stopped || version == lastPublishedVersion) {
                    false
                } else {
                    publishedFrame = frame
                    lastPublishedVersion = version
                    awaitingPresentationVersion = version
                    true
                }
            }
            if (published) {
                try {
                    onFrameAvailable()
                } catch (error: Throwable) {
                    failWorker(error)
                    return
                }
            }
        }
    }

    private fun failWorker(error: Throwable) {
        try {
            reportError(error)
        } finally {
            synchronized(monitor) {
                stopped = true
                monitor.notifyAll()
            }
        }
    }
}

private const val MIN_VIDEO_RENDER_EDGE = 64
private const val SOFTWARE_RENDER_BUDGET_WIDTH = 3840
private const val SOFTWARE_RENDER_BUDGET_HEIGHT = 2160
private const val VIEWPORT_STABLE_NANOS = 150_000_000L
