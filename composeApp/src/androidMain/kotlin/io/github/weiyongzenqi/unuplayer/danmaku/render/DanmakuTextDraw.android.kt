package io.github.weiyongzenqi.unuplayer.danmaku.render

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * Android actual: 走 `nativeCanvas.drawText` + [TextPaint](描边黑 + 填充弹幕色)。
 *
 * 与 [BitmapDanmakuEngine] 预渲染位图用的是**同一条已验证路径**(字实心不空心), 区别是
 * 不预渲染位图、每帧直接画到屏幕 Canvas: 省内存(不存 bitmap), 代价是 draw 阶段每帧 CPU
 * 光栅化两次(描边+填充), 比位图内核的 drawImage 贴图重 -- 适合内存敏感/中低密度场景。
 *
 * 性能优化(减少每帧每条弹幕的边际开销, 不改渲染效果):
 * - 复用单个 [sharedPaint](渲染在主线程, 单线程安全); strokeJoin/strokeCap/typeface 构造时
 *   设一次, draw 不再重复设(帧内全场一致)。
 * - textSize 帧内去重([sharedTextSize] 记忆, 一致则跳过, 省 native setTextSize 重算字体度量)。
 * - 垂直度量用 [Paint.ascent] / [Paint.descent](返回 float, 无对象分配), 替代 [Paint.getFontMetrics]
 *   每次返回新 FontMetrics 对象(高密度场景每帧每条分配, GC 压力大)。
 * 每条弹幕只按需设 color/style/strokeWidth。垂直居中到轨道(与位图内核 offY 算法一致)。
 *
 * 注: 每帧 2 次 drawText(描边+填充)的字形光栅化是 Canvas 内核的本质代价, Kotlin 层减不了;
 * 进一步减负需降同屏弹幕数或切位图内核([BitmapDanmakuEngine] 预渲染贴图, drawImage 1 次 GPU blit)。
 */
internal actual fun DrawScope.drawDanmakuText(
    text: String,
    topLeftX: Float,
    laneTopY: Float,
    laneHeight: Float,
    fontPx: Float,
    colorRgb: Int,
    strokePx: Float,
) {
    if (text.isEmpty() || fontPx <= 0f) return
    drawIntoCanvas { canvas ->
        val p = sharedPaint
        // textSize 帧内全场一致, 去重跳过(省 native setTextSize 重算字体度量)
        if (sharedTextSize != fontPx) { p.textSize = fontPx; sharedTextSize = fontPx }
        // ascent()/descent() 返回 float 无分配; fontMetrics 每次返回新对象(每帧每条分配, GC 压力大)
        val ascent = p.ascent()
        val descent = p.descent()
        val textH = -ascent + descent
        // 垂直居中到轨道: 轨道顶 + (轨道高 - 文本高)/2 - ascent(ascent 负, 即 +|ascent| 到基线)
        val baseline = laneTopY + (laneHeight - textH) / 2f - ascent
        // 描边(黑)在前 -> 填充(弹幕色)覆盖内部, 留黑边
        if (strokePx > 0f) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = strokePx
            p.color = Color.BLACK
            canvas.nativeCanvas.drawText(text, topLeftX, baseline, p)
        }
        p.style = Paint.Style.FILL
        // colorRgb 是 0xRRGGBB(无 alpha); Paint.color 要 0xAARRGGBB, 补 alpha=255 防填充透明
        p.color = (0xFF shl 24) or (colorRgb and 0xFFFFFF)
        canvas.nativeCanvas.drawText(text, topLeftX, baseline, p)
    }
}

/**
 * Android actual: [TextPaint.measureText] 拿文本宽度, 与 [drawDanmakuText] 同一 [sharedPaint]
 * (textSize 一致), 保证"轨道分配宽度 = 实际绘制宽度"。
 */
internal actual fun measureDanmakuTextWidth(text: String, fontPx: Float): Float {
    if (text.isEmpty() || fontPx <= 0f) return 0f
    if (sharedTextSize != fontPx) { sharedPaint.textSize = fontPx; sharedTextSize = fontPx }
    return sharedPaint.measureText(text)
}

/**
 * CA-004: Android actual no-op。Android [ComposeDanmakuEngine] 复用 [sharedPaint]
 * (Java 对象, GC 管理, 无需显式 close); [BitmapDanmakuEngine] 走独立位图缓存路径,
 * 不共享此处的 TextPaint。故无进程级 native 文本缓存需清理。
 */
internal actual fun clearDanmakuTextLineCache() = Unit

/**
 * 复用 TextPaint(主线程渲染, 单线程安全; 避免每帧每条弹幕 new)。
 * strokeJoin/strokeCap/typeface 永不改变, 构造时设一次, draw 不再重复设。
 */
private val sharedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    strokeJoin = Paint.Join.ROUND
    strokeCap = Paint.Cap.ROUND
    typeface = Typeface.DEFAULT
}

/** 记忆上次 textSize, 帧内全场一致则跳过 setTextSize(省 native 字体度量重算)。
 *  draw/measure 共用此缓存, 二者均限主线程(渲染单线程); 后台预计算勿碰, 否则数据竞争。 */
private var sharedTextSize = Float.NaN
