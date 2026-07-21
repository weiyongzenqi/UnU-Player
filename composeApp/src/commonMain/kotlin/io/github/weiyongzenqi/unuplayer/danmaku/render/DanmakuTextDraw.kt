package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 平台原生弹幕文本绘制 + 测量(expect)。Android actual 走 `nativeCanvas.drawText` + `TextPaint`,
 * 桌面/iOS actual 待补(P2 预留)。
 *
 * 为何不用 Compose `drawText`: Android release 下 `drawText` 的 color/drawStyle 渲染不可靠
 * (填充透明 = "空心 + 白描边"), 两轮烘焙 color/drawStyle 进 TextStyle 的修复均无效。
 * 改走平台原生文本绘制(与 [BitmapDanmakuEngine] 预渲染同一条已验证路径, 字实心不空心),
 * 绕开 Compose drawText 问题。
 *
 * 测量宽度与绘制同源(同一 TextPaint), 防止"轨道按 A 宽度分配、实际画 B 宽度"导致重叠/留白。
 *
 * @param text 文本
 * @param topLeftX 文本左上 x(px)
 * @param laneTopY 所在轨道顶部 y(px; actual 内部按 ascent/descent 垂直居中到轨道)
 * @param laneHeight 轨道高(px; 垂直居中用)
 * @param fontPx 字号 px(已含 density/fontScale)
 * @param colorRgb 文本色 0xRRGGBB
 * @param strokePx 描边宽度 px(<=0 不描边)
 */
internal expect fun DrawScope.drawDanmakuText(
    text: String,
    topLeftX: Float,
    laneTopY: Float,
    laneHeight: Float,
    fontPx: Float,
    colorRgb: Int,
    strokePx: Float,
)

/**
 * 测量弹幕文本宽度(px), 与 [drawDanmakuText] 同源(同一 TextPaint/font), 轨道分配用。
 *
 * @param fontPx 字号 px(已含 density/fontScale)
 * @return 文本宽度 px(空文本返回 0)
 */
internal expect fun measureDanmakuTextWidth(text: String, fontPx: Float): Float

/**
 * 清理平台原生弹幕文本缓存(如桌面 Skia TextLine/Font 缓存)。
 *
 * CA-004: [ComposeDanmakuEngine] 的 [BaseDanmakuEngine.onEntriesReplaced] 调用此钩子,
 * 使播放器关闭(engine.clear() onDispose)或换集(load)时释放进程级 native 文本缓存,
 * 避免残留至进程退出。Android actual 走 no-op(Android 用 [BitmapDanmakuEngine] 时
 * 无文本缓存; [ComposeDanmakuEngine] 复用 [sharedPaint] 不持有需 close 的 native 对象)。
 */
internal expect fun clearDanmakuTextLineCache()
