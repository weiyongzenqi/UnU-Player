package io.github.weiyongzenqi.unuplayer.danmaku.gl

import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.get

/**
 * 字符级文本布局引擎：将弹幕文本展开为 glyph 序列。
 *
 * 处理 Unicode surrogate pairs（emoji / 扩展 CJK），
 * 用 Android [Paint.getTextWidths] 逐字符测量宽度，
 * 用 [Paint.getFontMetrics] 获取行高。
 *
 * 线程：UI 线程（在 GlesDanmakuEngine.activate 中调用）。
 */
internal object GlyphLayoutEngine {

    /** 共享 Paint，UI 线程独占复用。 */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
    }

    /** 缓存最近一次 textSize 避免重复设置。 */
    private var cachedTextSize = -1f

    /**
     * 对 [text] 做字符级布局。
     *
     * @param text 弹幕文本
     * @param fontPx 字号（像素，已含 density × fontScale）
     * @return 字符布局序列 + 总宽度 + 行高（ascent→descent 距离）
     */
    fun layout(text: String, fontPx: Float): GlyphLayoutResult {
        if (text.isEmpty() || fontPx <= 0f) return GlyphLayoutResult.EMPTY

        if (cachedTextSize != fontPx) {
            paint.textSize = fontPx
            cachedTextSize = fontPx
        }

        // 1. 按 codepoint 分割（处理 surrogate pairs）
        val codepoints = text.codePoints().toArray()
        val charStrs = codepoints.map { String(Character.toChars(it)) }

        // 2. 逐字符宽度
        val widths = FloatArray(charStrs.size)
        for (i in charStrs.indices) {
            widths[i] = paint.measureText(charStrs[i])
        }
        // 批量获取完整文本的逐字符 advance（更精确的 kerning 感知）
        val fullWidths = FloatArray(text.length)
        val fullCount = paint.getTextWidths(text, 0, text.length, fullWidths)

        // 3. 将 Java char 级 advance 合并为 codepoint 级
        val cpWidths = FloatArray(codepoints.size)
        if (fullCount == text.length) {
            var cpIdx = 0
            var charIdx = 0
            while (charIdx < text.length && cpIdx < codepoints.size) {
                val cp = codepoints[cpIdx]
                val charCount = Character.charCount(cp)
                var sum = 0f
                repeat(charCount) {
                    if (charIdx < text.length) sum += fullWidths[charIdx]
                    charIdx++
                }
                cpWidths[cpIdx] = sum
                cpIdx++
            }
        } else {
            // getTextWidths 失败时回退到逐字 measureText
            widths.copyInto(cpWidths)
        }

        // 4. 字体度量
        val fm = paint.fontMetrics
        val ascent = -fm.ascent  // 正值：baseline 到顶部距离
        val descent = fm.descent  // 正值：baseline 到底部距离
        val lineHeight = ascent + descent

        // 5. 构建 glyph 列表（阶段 2：codepoint 即 glyph 标识；阶段 3 由 SdfAtlas.ensureGlyph 分配 slotIndex）
        val totalWidth = cpWidths.sum()
        var cursorX = 0f
        val glyphs = codepoints.mapIndexed { i, cp ->
            val w = cpWidths[i]
            val g = GlyphLayout(codepoint = cp, offsetX = cursorX, width = w)
            cursorX += w
            g
        }

        return GlyphLayoutResult(
            glyphs = glyphs,
            totalWidth = totalWidth,
            lineHeight = lineHeight,
        )
    }

    /** 只测量整条文本宽度（不拆分字符）。 */
    fun measureWidth(text: String, fontPx: Float): Float {
        if (text.isEmpty() || fontPx <= 0f) return 0f
        if (cachedTextSize != fontPx) {
            paint.textSize = fontPx
            cachedTextSize = fontPx
        }
        return paint.measureText(text)
    }
}

/** 单个 glyph 的布局数据：字符 + 在弹幕内的偏移 + 宽度。 */
internal data class GlyphLayout(
    val codepoint: Int,
    val offsetX: Float,
    val width: Float,
)

/** 一条弹幕的完整 glyph 布局结果。 */
internal data class GlyphLayoutResult(
    val glyphs: List<GlyphLayout>,
    val totalWidth: Float,
    val lineHeight: Float,
) {
    companion object {
        val EMPTY = GlyphLayoutResult(emptyList(), 0f, 0f)
    }
}
