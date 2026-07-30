package io.github.weiyongzenqi.unuplayer.danmaku.gl

import io.github.weiyongzenqi.unuplayer.danmaku.render.ActiveDanmaku
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 将活跃弹幕列表转换为实例化 VBO 数据（Direct FloatBuffer）。
 *
 * 每个字符实例 12 floats = 48 bytes：
 * ```
 * position(2f) + scale(2f) + texRect(4f) + textColor(4f)
 * ```
 *
 * 线程：GL 线程（onDrawFrame 内调用）。FloatBuffer 复用，每帧 clear + bulk put。
 */
internal class DanmakuVboBuilder {

    /** 每个实例的 float 数量。 */
    private val floatsPerInstance = 12

    /** 最大实例数对应的缓冲区容量。 */
    private val maxInstances = 50_000 // 5000条×10字/条

    /** 复用 FloatBuffer（Direct, native byte order）。 */
    private var buffer: FloatBuffer = allocate(maxInstances * floatsPerInstance)
    private var capacity = maxInstances

    /**
     * 构建实例数据。
     *
     * @param activeSnapshot GL 线程读到的活跃弹幕快照
     * @param screenW 屏幕宽度 px
     * @param screenH 屏幕高度 px
     * @param laneYFn 根据 mode + lane 计算 y 坐标的函数
     * @param laneHeight 轨道高度 px
     * @param sdfAtlas SDF 图集（用于获取 texRect）
     * @param engine GlesDanmakuEngine（读取 currentFontPx 等）
     * @return (FloatBuffer, instanceCount) — buffer 是复用的，下次调用内容会变
     */
    fun build(
        activeSnapshot: List<ActiveDanmaku>,
        screenW: Float,
        screenH: Float,
        laneYFn: (mode: io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode, lane: Int, screenH: Float) -> Float,
        laneHeight: Float,
        sdfAtlas: SdfAtlas,
    ): Pair<FloatBuffer, Int> {
        // 预估总实例数用于扩容
        var estimate = 0
        for (d in activeSnapshot) {
            val layout = d.payload as? GlyphLayoutResult ?: continue
            estimate += layout.glyphs.size
        }
        if (estimate == 0) return Pair(buffer.apply { clear(); limit(0) }, 0)

        // 确保缓冲区够大
        val needed = estimate * floatsPerInstance
        if (needed > capacity * floatsPerInstance) {
            capacity = estimate * 2
            buffer = allocate(capacity * floatsPerInstance)
        }
        buffer.clear()

        var instanceCount = 0
        for (d in activeSnapshot) {
            val layout = d.payload as? GlyphLayoutResult ?: continue
            if (layout.glyphs.isEmpty()) continue

            val baseY = laneYFn(d.entry.mode, d.lane, screenH) + (laneHeight - layout.lineHeight) / 2f
            // 颜色直接内联写入 VBO，避免每弹幕分配 FloatArray
            val r = ((d.entry.color shr 16) and 0xFF) / 255f
            val g = ((d.entry.color shr 8) and 0xFF) / 255f
            val b = (d.entry.color and 0xFF) / 255f

            for (glyph in layout.glyphs) {
                val slot = sdfAtlas.ensureGlyph(glyph.codepoint)
                if (slot < 0) continue // atlas 满，跳过硬字幕

                sdfAtlas.markActive(slot)
                val tex = sdfAtlas.getTexRect(slot)
                val gx = d.x + glyph.offsetX
                val gy = baseY
                val gw = glyph.width
                val gh = layout.lineHeight

                buffer.put(gx); buffer.put(gy)
                buffer.put(gw); buffer.put(gh)
                buffer.put(tex.u0); buffer.put(tex.v0)
                buffer.put(tex.u1); buffer.put(tex.v1)
                buffer.put(r); buffer.put(g); buffer.put(b); buffer.put(1f)
                instanceCount++
            }
        }

        buffer.flip()
        return Pair(buffer, instanceCount)
    }

    companion object {
        private fun allocate(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

        /** 将弹幕颜色（0xRRGGBB）转为 RGBA float 数组。 */
        private fun danmakuColorToRGBA(rgb: Int): FloatArray = floatArrayOf(
            ((rgb shr 16) and 0xFF) / 255f,
            ((rgb shr 8) and 0xFF) / 255f,
            (rgb and 0xFF) / 255f,
            1.0f,
        )
    }
}
