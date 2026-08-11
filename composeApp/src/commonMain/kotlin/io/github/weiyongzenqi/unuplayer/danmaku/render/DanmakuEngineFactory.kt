package io.github.weiyongzenqi.unuplayer.danmaku.render

import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType

/**
 * 弹幕渲染内核工厂(跨平台 expect; 各平台 actual 按 [DanmakuEngineType] 产出实现)。
 *
 * - [DanmakuEngineType.ATLAS]: 生产默认的有界图集批渲染内核。
 * - [DanmakuEngineType.COMPOSE]: 原生 Canvas 文本绘制兼容路径。
 * - [DanmakuEngineType.BITMAP]: 实验性；Android 使用独立预渲染位图，桌面映射到 Atlas。
 */
expect fun createDanmakuEngine(type: DanmakuEngineType): DanmakuEngine
