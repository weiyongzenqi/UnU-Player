package io.github.weiyongzenqi.unuplayer.danmaku.render

import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType

/**
 * 弹幕渲染内核工厂(跨平台 expect; 各平台 actual 按 [DanmakuEngineType] 产出实现)。
 *
 * - [DanmakuEngineType.COMPOSE]: [ComposeDanmakuEngine](Canvas drawText, 跨平台默认)
 * - [DanmakuEngineType.BITMAP]:  位图缓存内核(Android 端用 android.graphics 预渲染贴图)
 */
expect fun createDanmakuEngine(type: DanmakuEngineType): DanmakuEngine
