package io.github.weiyongzenqi.unuplayer.danmaku.render

import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType

/** Android 端弹幕内核工厂 actual。 */
actual fun createDanmakuEngine(type: DanmakuEngineType): DanmakuEngine = when (type) {
    DanmakuEngineType.COMPOSE -> ComposeDanmakuEngine()
    DanmakuEngineType.BITMAP -> BitmapDanmakuEngine()
    DanmakuEngineType.ATLAS -> AndroidAtlasDanmakuEngine()
}
