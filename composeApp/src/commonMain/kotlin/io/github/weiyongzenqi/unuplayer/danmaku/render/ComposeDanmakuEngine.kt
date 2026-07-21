package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode

/**
 * Compose Canvas 弹幕渲染引擎([BaseDanmakuEngine] 子类, commonMain 跨平台)。
 *
 * 渲染走平台原生文本绘制([drawDanmakuText]): Android 用 `nativeCanvas.drawText` + `TextPaint`
 * (描边黑 + 填充色, 与 [BitmapDanmakuEngine] 同路径, 字实心不空心); 桌面/iOS actual 待补。
 *
 * 为何不用 Compose `drawText`: Android release 下 `drawText` 的 color/drawStyle 渲染不可靠
 * (填充透明 = "空心 + 白描边"), 两轮烘焙 color/drawStyle 进 TextStyle 的修复均无效 ->
 * 改走平台原生绕开。详见 [DanmakuTextDraw]。
 *
 * 宽度用 [measureDanmakuTextWidth](与绘制同源 TextPaint) 分配轨道, 防重叠/留白。
 * 不再持有 TextLayoutResult/位图(payload=null), 内存占用低于位图内核; 代价是 draw 每帧
 * CPU 光栅化两次(描边+填充)。
 *
 * 倍速/字号/暂停/seek 行为见 [BaseDanmakuEngine]。
 */
class ComposeDanmakuEngine : BaseDanmakuEngine() {

    override fun engineName(): String = "compose"

    /**
     * CA-004: 换集(load)或 dispose(clear)时清理平台原生文本缓存(桌面 Skia TextLine/Font)。
     * 避免播放器关闭后 256 个 TextLine + Font 残留至进程退出。Android actual no-op。
     */
    override fun onEntriesReplaced() {
        clearDanmakuTextLineCache()
    }

    override fun activate(e: DanmakuEntry, posSec: Double, screenW: Float, baseSpeed: Float): Boolean {
        val fontPx = effectiveFontSp() * fontScalePx
        val width = measureDanmakuTextWidth(e.text, fontPx)
        when (e.mode) {
            DanmakuMode.SCROLL -> {
                val lane = scrollAllocator.allocate(e.timeSec, width, baseSpeed)
                if (lane < 0) return false
                val x = (screenW - (posSec - e.timeSec) * baseSpeed).toFloat()
                active.add(ActiveDanmaku(e, lane, width, x))
            }
            DanmakuMode.TOP -> {
                val lane = topAllocator.allocate(e.timeSec, FIXED_DURATION)
                if (lane < 0) return false
                active.add(ActiveDanmaku(e, lane, width, (screenW - width) / 2f))
            }
            DanmakuMode.BOTTOM -> {
                val lane = bottomAllocator.allocate(e.timeSec, FIXED_DURATION)
                if (lane < 0) return false
                active.add(ActiveDanmaku(e, lane, width, (screenW - width) / 2f))
            }
            else -> return false
        }
        return true
    }

    override fun draw(scope: DrawScope) {
        if (active.isEmpty()) return
        val strokePx = config.strokeWidth
        val fontPx = effectiveFontSp() * fontScalePx
        val h = scope.size.height
        active.forEach { d ->
            val y = laneY(d.entry.mode, d.lane, h)
            scope.drawDanmakuText(d.entry.text, d.x, y, laneHeight, fontPx, d.entry.color, strokePx)
        }
    }
}
