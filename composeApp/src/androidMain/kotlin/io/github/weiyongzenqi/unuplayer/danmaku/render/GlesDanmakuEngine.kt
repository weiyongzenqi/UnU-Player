package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.weiyongzenqi.unuplayer.danmaku.gl.GlyphLayoutEngine
import io.github.weiyongzenqi.unuplayer.danmaku.gl.GlyphLayoutResult
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import java.util.concurrent.atomic.AtomicReference

/**
 * SDF + OpenGL ES 3.0 实例化弹幕引擎。
 *
 * 继承 [BaseDanmakuEngine] 复用全部运动模型/轨道分配/光标/seek 逻辑；
 * [draw] 为 no-op（GL 渲染走 TextureView 或离屏 FBO 的独立线程）。
 * [activate] 使用 [GlyphLayoutEngine] 做字符级布局，payload 为 [GlyphLayoutResult]。
 *
 * 线程模型：UI 线程写 active 列表并 [publishFrame] 发布不可变快照到 [readActiveRef]；
 * GL 线程读快照构建 VBO 并调用 glDrawElementsInstanced。
 */
internal class GlesDanmakuEngine : BaseDanmakuEngine() {

    /** GL 线程读取的活跃弹幕快照（不可变，只替换引用）。 */
    val readActiveRef: AtomicReference<List<ActiveDanmaku>> = AtomicReference(emptyList())

    /** 自上一次 publishFrame 后是否有新 glyph 需要 SDF 生成（GL 线程重置）。 */
    @Volatile
    var hasNewGlyphs: Boolean = false
        private set

    /** 当前字号像素（UI 线程更新，GL 线程只读）。 */
    @Volatile
    var currentFontPx: Float = 0f
        private set

    /** GL 线程只读：当前 lane 高度（px）。 */
    @Volatile
    var currentLaneHeight: Float = 0f
        private set

    /** GL 线程只读：当前描边宽度（px，来自 DanmakuConfig）。 */
    @Volatile
    var currentStrokeWidth: Float = 2f
        private set

    override fun engineName(): String = "android-gles"

    override fun setConfig(config: DanmakuConfig) {
        super.setConfig(config)
        currentStrokeWidth = config.strokeWidth.coerceAtLeast(0f)
    }

    override fun onFrame(
        positionMs: Long,
        screenW: Float,
        screenH: Float,
        deltaSec: Float,
    ): Boolean {
        val dirty = super.onFrame(positionMs, screenW, screenH, deltaSec)
        currentLaneHeight = laneHeight
        return dirty
    }

    override fun setFontScalePx(px: Float) {
        super.setFontScalePx(px)
        currentFontPx = effectiveFontSp() * fontScalePx
    }

    override fun activate(
        e: DanmakuEntry,
        posSec: Double,
        screenW: Float,
        baseSpeed: Float,
    ): Boolean {
        if (e.text.isEmpty()) return false

        val fontPx = effectiveFontSp() * fontScalePx
        currentFontPx = fontPx
        val layout = GlyphLayoutEngine.layout(e.text, fontPx)
        if (layout.glyphs.isEmpty()) return false

        val textWidth = layout.totalWidth
        val laneHeight = fontPx * LINE_HEIGHT_FACTOR
        val allocLane: Int
        val x: Float
        when (e.mode) {
            io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode.SCROLL -> {
                allocLane = scrollAllocator.allocate(e.timeSec, textWidth, baseSpeed)
                if (allocLane < 0) return false
                x = screenW - (posSec - e.timeSec).toFloat() * baseSpeed
            }
            io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode.TOP -> {
                allocLane = topAllocator.allocate(e.timeSec, FIXED_DURATION)
                if (allocLane < 0) return false
                x = (screenW - textWidth) / 2f
            }
            io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode.BOTTOM -> {
                allocLane = bottomAllocator.allocate(e.timeSec, FIXED_DURATION)
                if (allocLane < 0) return false
                x = (screenW - textWidth) / 2f
            }
            else -> return false
        }

        hasNewGlyphs = true
        active.add(ActiveDanmaku(e, allocLane, textWidth, x, payload = layout))
        return true
    }

    override fun draw(scope: DrawScope) {
        // no-op：GL 渲染走独立线程
    }

    override fun renderFrame(
        positionMs: Long,
        screenW: Float,
        screenH: Float,
        deltaSec: Float,
        scope: DrawScope,
    ): Boolean {
        val dirty = onFrame(positionMs, screenW, screenH, deltaSec)
        publishFrame()
        return dirty
    }

    /** 将当前 [active] 列表的快照发布到 [readActiveRef]，供 GL 线程读取。 */
    fun publishFrame() {
        if (active.isEmpty()) {
            readActiveRef.set(emptyList())
            return
        }
        readActiveRef.set(ArrayList(active))
    }

    override fun onEntriesReplaced() {
        hasNewGlyphs = true
    }

    override fun onActiveRemoved(item: ActiveDanmaku) {
        // glyph 引用计数由 SdfAtlas.beginFrame/endFrame 管理
    }
}
