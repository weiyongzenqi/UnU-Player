package io.github.weiyongzenqi.unuplayer.core.gl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.SkiaLayer
import java.awt.Component
import java.awt.EventQueue
import java.awt.Container
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.RootPaneContainer

/**
 * skiko 反射工具: 拿 Compose Desktop 窗口底层 SkiaLayer 的 [DirectContext](Skia)。
 * 复刻 compose-gl CompositionLocals.kt, 改 Java 反射免 kotlin-reflect 依赖。
 *
 * **必须在 Compose 渲染线程调用**。spike0(40518e4) 已验证 DirectContext 反射路径。
 */
private val localWindow: CompositionLocal<Window?> by lazy {
    val clazz = Class.forName("androidx.compose.ui.window.LocalWindowKt")
    val method = clazz.getMethod("getLocalWindow")
    @Suppress("UNCHECKED_CAST")
    method.invoke(null) as CompositionLocal<Window?>
}

/** 当前 Compose 窗口的 AWT Window(反射 LocalWindow, 跨 skiko 版本稳)。 */
@Composable
fun currentAwtWindow(): Window? = localWindow.current

/** 反射拿 skiko 的 [DirectContext](Skia GL context 句柄, adoptTextureFrom 用)。Compose 渲染线程调。 */
fun Window.skikoDirectContext(): DirectContext? = findSkiaLayer()?.directContext()

/** 读取 Skiko 实际创建的后端，供技术信息和诊断日志显示。 */
fun Window.skikoRenderBackendDescription(): String? = findSkiaLayer()?.let { layer ->
    buildString {
        append("Skiko ")
        append(layer.renderApi.name)
        layer.renderInfo.takeIf { it.isNotBlank() }?.let { info ->
            append(" (")
            append(info)
            append(')')
        }
    }
}

/**
 * 在 AWT bounds/layout 事件完成后的下一个事件循环刷新 Compose surface。
 *
 * 不能调用 Container.validate 或 SkiaLayer.needRender(true)：Compose Desktop
 * 在 onRender 内会暂时禁止 Swing interop 的 redraw 请求，嵌套请求会抛出
 * `Reentry into ignoringRedrawRequests is not allowed`。这里故意使用两次
 * invokeLater，把刷新推到当前 layout/paint 完成之后，并在短时间内合并请求。
 */
internal class WindowBoundsRefreshScheduler(
    private val window: Window,
) : ComponentAdapter(), AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val coalescer = DeferredRequestCoalescer(
        post = { task -> EventQueue.invokeLater(Runnable(task)) },
        action = window::revalidateAndRepaintAfterBoundsChange,
    )

    fun start() {
        if (closed.get()) return
        window.addComponentListener(this)
        request()
    }

    override fun componentResized(event: ComponentEvent?) = request()

    override fun componentMoved(event: ComponentEvent?) = request()

    fun request() = coalescer.request()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            window.removeComponentListener(this)
            coalescer.close()
        }
    }
}

internal class DeferredRequestCoalescer(
    private val post: (() -> Unit) -> Unit,
    private val action: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val queued = AtomicBoolean(false)

    fun request() {
        if (closed.get() || !queued.compareAndSet(false, true)) return
        post {
            post {
                try {
                    if (!closed.get()) action()
                } finally {
                    queued.set(false)
                }
            }
        }
    }

    override fun close() {
        closed.set(true)
    }
}

private fun Window.revalidateAndRepaintAfterBoundsChange() {
    check(EventQueue.isDispatchThread()) { "窗口 bounds 刷新必须运行在 AWT EDT" }
    // invalidate 只标记布局过期，不同步进入 Container.validate，避免重入 Compose draw。
    invalidate()
    syncComposeBounds()
    repaint()
}

private fun Window.syncComposeBounds() {
    val root = (this as? RootPaneContainer)?.contentPane ?: return
    val panel = findComponentByClassName(this, "androidx.compose.ui.awt.ComposeWindowPanel")
    val layer = findSkiaLayer()
    propagateComposeBounds(root, panel, layer) { x, y, width, height ->
        prepareSkiaBackingBounds(layer, width, height)
        layer?.reshape(x, y, width, height)
    }
}

private fun prepareSkiaBackingBounds(layer: SkiaLayer?, width: Int, height: Int) {
    if (layer == null || width <= 0 || height <= 0) return
    runCatching {
        // reshape() 在 Direct3D 下会先同步绘制；先按 Skiko 自身算法调整 backing Canvas，避免首帧沿用旧尺寸。
        val scale = layer.contentScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        layer.canvas.setBounds(
            0,
            0,
            skiaBackingSize(width, scale),
            skiaBackingSize(height, scale),
        )
    }
}

internal fun skiaBackingSize(size: Int, contentScale: Float): Int {
    val scaled = size.toFloat() * contentScale
    val fraction = scaled - kotlin.math.floor(scaled.toDouble()).toFloat()
    return if (fraction > 0.4f && fraction < 0.6f) size + 1 else size
}

internal fun propagateComposeBounds(
    root: Container,
    panel: JComponent?,
    layer: JComponent?,
    reshapeLayer: (Int, Int, Int, Int) -> Unit,
) {
    root.invalidate()
    root.doLayout()

    if (panel != null) {
        val host = panel.parent ?: root
        val width = host.width.takeIf { it > 0 } ?: root.width
        val height = host.height.takeIf { it > 0 } ?: root.height
        if (width > 0 && height > 0) {
            panel.setBounds(panel.x, panel.y, width, height)
            panel.doLayout()
        }
    }

    if (layer != null) {
        layer.parent?.doLayout()
        val host = layer.parent
        val width = host?.width?.takeIf { it > 0 } ?: layer.width
        val height = host?.height?.takeIf { it > 0 } ?: layer.height
        if (width > 0 && height > 0) {
            reshapeLayer(layer.x, layer.y, width, height)
            layer.doLayout()
        }
    }
}

private fun SkiaLayer.directContext(): DirectContext? =
    contextHandler()?.findFieldByType("org.jetbrains.skia.DirectContext") as? DirectContext

private fun SkiaLayer.contextHandler(): Any? =
    redrawer()?.findFieldByType("org.jetbrains.skiko.context.ContextHandler")

private fun SkiaLayer.redrawer(): Any? = try {
    val m = SkiaLayer::class.java.getDeclaredMethod("getRedrawer\$skiko")
    m.isAccessible = true
    m.invoke(this)
} catch (_: Throwable) { null }

/** Java 反射: 在对象(含父类)找 assignable 到 [typeName] 的字段值。 */
fun Any.findFieldByType(typeName: String): Any? = try {
    val target = Class.forName(typeName)
    var clazz: Class<*>? = this.javaClass
    while (clazz != null) {
        for (f in clazz.declaredFields) {
            if (target.isAssignableFrom(f.type)) { f.isAccessible = true; return f.get(this) }
        }
        clazz = clazz.superclass
    }
    null
} catch (_: Throwable) { null }


private fun Window.findSkiaLayer(): SkiaLayer? = findComponent(this, SkiaLayer::class.java)

private fun findComponentByClassName(container: Container, className: String): JComponent? {
    for (component in container.components) {
        if (component.javaClass.name == className && component is JComponent) return component
        if (component is Container) {
            findComponentByClassName(component, className)?.let { return it }
        }
    }
    return null
}

private fun <T : Component> findComponent(container: Container, klass: Class<T>): T? {
    val seq = container.components.asSequence()
    return seq.filter { klass.isInstance(it) }.ifEmpty {
        seq.filterIsInstance<Container>().mapNotNull { findComponent(it, klass) }
    }.map { klass.cast(it) }.firstOrNull()
}
