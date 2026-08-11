package io.github.weiyongzenqi.unuplayer.danmaku.render

import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType

/**
 * 桌面端弹幕内核工厂 actual, 对应 androidMain 的 DanmakuEngineFactory.android.kt。
 *
 * - [DanmakuEngineType.COMPOSE]: [ComposeDanmakuEngine](Canvas 原生文本绘制, 跨平台默认)
 * - [DanmakuEngineType.BITMAP]:  [DesktopAtlasDanmakuEngine] 有界 atlas + drawVertices 批量提交
 * - [DanmakuEngineType.ATLAS]:   [DesktopAtlasDanmakuEngine] 桌面原生 atlas(与 BITMAP 同实现,
 *                                桌面无独立逐条 Bitmap 内核, BITMAP/ATLAS 均映射到桌面 atlas)
 */
actual fun createDanmakuEngine(type: DanmakuEngineType): DanmakuEngine = when (type) {
    DanmakuEngineType.COMPOSE -> ComposeDanmakuEngine()
    DanmakuEngineType.BITMAP -> DesktopAtlasDanmakuEngine()
    DanmakuEngineType.ATLAS -> DesktopAtlasDanmakuEngine()
}
