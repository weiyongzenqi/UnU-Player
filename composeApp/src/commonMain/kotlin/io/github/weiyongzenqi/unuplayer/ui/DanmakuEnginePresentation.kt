package io.github.weiyongzenqi.unuplayer.ui

import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType

internal data class DanmakuEnginePresentation(
    val type: DanmakuEngineType,
    val label: String,
    val experimental: Boolean,
)

/** 设置页和播放器面板共用同一份内核清单，避免入口数量与实验状态再次分叉。 */
internal val danmakuEnginePresentations = listOf(
    DanmakuEnginePresentation(DanmakuEngineType.ATLAS, "Atlas 批渲染（默认，推荐）", experimental = false),
    DanmakuEnginePresentation(DanmakuEngineType.COMPOSE, "Canvas 原生文本（兼容性优先）", experimental = false),
    DanmakuEnginePresentation(DanmakuEngineType.BITMAP, "位图缓存（实验性）", experimental = true),
)

internal val danmakuEngineDetailsText = """
    内核原理与适用场景：

    • Atlas（默认，推荐）
    原理：文字首次出现时光栅化进按需创建的图集页，活跃弹幕只保存图集区域。Android 10+ 按原始层叠顺序合并连续同页弹幕后批量提交；Android 8～9 使用逐条贴图兼容路径。图集满或碎片化时，单条回退原生文字绘制，不会因为缓存失败静默丢幕。
    特征：当前 Android 生产主路径，适合日常及中高密度弹幕。Android 图集最多 8 张 1024×1024 ARGB 页，32 MiB 是按需分配的 CPU 像素上限，不是启动常驻或应用总内存；实际帧率、内存和功耗仍取决于设备与弹幕密度。Windows 使用最多 4 页的 Skia Atlas 批渲染。

    • Canvas（兼容性优先）
    原理：每帧在平台原生画布上直接绘制每条活跃文字的黑色描边和颜色填充；Android 复用 TextPaint，Windows 复用有界 Skia TextLine 缓存，但不把整条文字预存成位图。
    特征：实现直接、额外位图占用少、兼容性最好，适合低到普通密度或排查其他内核问题。弹幕越密，每帧文字绘制与提交工作越多，CPU/GPU 开销通常线性增加，因此不是高密度性能首选。

    • 位图缓存（实验性）
    原理：Android 按“文字、颜色、字号、描边”把唯一弹幕预渲染成独立 Bitmap，后续帧逐条贴图，减少重复文字光栅化；缓存目标上限为 300 项/16 MiB，包含活跃引用时的位图像素硬上限为 32 MiB。Windows 没有独立的逐条位图实现，选择后使用桌面 Atlas。
    特征：重复文字较多时可用内存换取较少的重复光栅化，但独立位图数量、首次生成尖峰和逐条提交仍可能增加内存、卡顿与功耗；Android 高密度性能和缓存整理尚未完成生产验收，暂不建议日常使用。

    说明：位图缓存仍属于实验内核，尚未通过长时资源和功耗验收。任何内核都不能脱离同一设备、同一视频与同一弹幕密度直接推断功耗排名。
""".trimIndent()
