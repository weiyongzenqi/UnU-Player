package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.TextLine

/**
 * 桌面 actual, 对应 androidMain 的 DanmakuTextDraw.android.kt。
 *
 * Android 走 `nativeCanvas.drawText` + `TextPaint`(描边黑 + 填充弹幕色);
 * 桌面走 **Skia**(`org.jetbrains.skia`)而非 AWT: Compose Desktop 的 [DrawScope] 底层是
 * Skia 画布, [androidx.compose.ui.graphics.nativeCanvas] / [skiaCanvas] 返回 [Canvas]
 * (Skia), 不能 cast 成 `java.awt.Graphics2D`(阶段1 旧实现误用, cast 永远失败会崩)。
 *
 * 渲染: [TextLine](shaped 文本) + [Paint] -- 先 STROKE 黑描边, 再 FILL 弹幕色, 效果与
 * Android 版一致。
 *
 * 性能优化(与 Android 版对齐):
 * - 复用单个 [sharedFont](渲染在 EDT, 单线程安全); font size 帧内去重([sharedFontSize])。
 * - 测量与绘制同源 Font, TextLine.width/ascent/descent 无额外对象分配。
 *
 * Typeface: `Font(null, size)` 用 Skia 默认 typeface(系统无衬线), 后续字幕字体设置阶段
 * 可改为读取用户字体目录。
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
        val skia: Canvas = canvas.skiaCanvas
        withCachedTextLine(text, fontPx) { line ->
            // Skia: ascent 为负(基线上方), descent 为正(基线下方); 文本高 = descent - ascent
            val ascent = line.ascent
            val descent = line.descent
            val textH = descent - ascent
            // 垂直居中到轨道: 文本顶(baseline + ascent)对齐到 轨道顶 + (轨道高 - 文本高)/2
            // => baseline = laneTopY + (laneHeight - textH)/2 - ascent (ascent 负, -ascent 下移)
            val baseline = laneTopY + (laneHeight - textH) / 2f - ascent
            // 描边(黑)在前 -> 填充(弹幕色)覆盖内部, 留黑边(与 Android 版 STROKE -> FILL 一致)
            if (strokePx > 0f) {
                strokePaint.strokeWidth = strokePx
                skia.drawTextLine(line, topLeftX, baseline, strokePaint)
            }
            // colorRgb 是 0xRRGGBB(无 alpha); Skia Paint.color 为 ARGB Int, 补不透明 alpha
            fillPaint.color = (0xFF shl 24) or (colorRgb and 0xFFFFFF)
            skia.drawTextLine(line, topLeftX, baseline, fillPaint)
        }
    }
}

/**
 * 桌面 actual: 用 [TextLine.width] 测量文本宽度, 与 [drawDanmakuText] 同源 Font。
 *
 * @param fontPx 字号 px(已含 density/fontScale)
 * @return 文本宽度 px(空文本返回 0)
 */
internal actual fun measureDanmakuTextWidth(text: String, fontPx: Float): Float {
    if (text.isEmpty() || fontPx <= 0f) return 0f
    return withCachedTextLine(text, fontPx) { it.width }
}

/**
 * 同一条弹幕通常连续显示数百帧；TextLine 是必须 close 的 native 对象，逐帧 make/close 虽不再泄漏，
 * 仍会推动 Skia allocator/字形 shaping 缓存扩容。缓存 256 条可覆盖默认同屏上限 150，淘汰时关闭。
 * measure 与 draw 可能由不同 Skiko 调度阶段进入，因此锁覆盖“取出到使用结束”，避免使用中被淘汰。
 */
private inline fun <T> withCachedTextLine(text: String, fontPx: Float, block: (TextLine) -> T): T =
    synchronized(textLineLock) {
        if (sharedFontSize != fontPx) {
            textLineCache.values.forEach(TextLine::close)
            textLineCache.clear()
            sharedFont.close()
            sharedFont = Font(null, fontPx)
            sharedFontSize = fontPx
        }
        val line = textLineCache[text] ?: TextLine.make(text, sharedFont).also { textLineCache[text] = it }
        block(line)
    }

/**
 * CA-004: 清理桌面 Skia TextLine/Font 进程级缓存。播放器关闭(engine.clear() onDispose)
 * 或换集(load)时由 [ComposeDanmakuEngine.onEntriesReplaced] 调用, 释放 256 个 TextLine + Font
 * native 对象, 避免残留至下次字号变化或进程退出。线程安全: 复用 [textLineLock]。
 */
internal actual fun clearDanmakuTextLineCache() {
    synchronized(textLineLock) {
        textLineCache.values.forEach(TextLine::close)
        textLineCache.clear()
        sharedFont.close()
        sharedFont = Font(null, 16f)
        sharedFontSize = Float.NaN
    }
}

private val textLineLock = Any()

private val textLineCache = object : LinkedHashMap<String, TextLine>(TEXT_LINE_CACHE_MAX, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TextLine>): Boolean {
        val remove = size > TEXT_LINE_CACHE_MAX
        if (remove) eldest.value.close()
        return remove
    }
}

/** 复用 Font；只允许在 [textLineLock] 内访问。null typeface = Skia 默认字体。 */
private var sharedFont: Font = Font(null, 16f)
private var sharedFontSize = Float.NaN

private const val TEXT_LINE_CACHE_MAX = 256

/** 描边画笔(黑, STROKE, 圆角端点)。color 固定黑, strokeWidth 每帧设。 */
private val strokePaint = Paint().apply {
    mode = PaintMode.STROKE
    color = 0xFF000000.toInt()
    isAntiAlias = true
    strokeCap = PaintStrokeCap.ROUND
    strokeJoin = PaintStrokeJoin.ROUND
}

/** 填充画笔(FILL, 弹幕色每条设)。 */
private val fillPaint = Paint().apply {
    mode = PaintMode.FILL
    isAntiAlias = true
}
