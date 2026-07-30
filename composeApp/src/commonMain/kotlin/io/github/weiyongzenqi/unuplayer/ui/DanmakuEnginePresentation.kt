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
    DanmakuEnginePresentation(DanmakuEngineType.GLES, "SDF + OpenGL ES（实验性，仅 Android）", experimental = true),
    DanmakuEnginePresentation(
        DanmakuEngineType.GLES_HB,
        "SDF + 离屏合成（GLES_HB，实验性，仅 Android）",
        experimental = true,
    ),
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

    • SDF + OpenGL ES（实验性，仅 Android）
    原理：把字符生成到 4096×4096 的单通道 SDF 字形图集，活跃弹幕转换为实例数据，由独立 GLES 线程通过实例化绘制合成。SDF 可让同一字形在不同位置和缩放下复用，理论上适合大量重复字符。
    特征：当前仍有帧时钟、EGL/线程生命周期、缓存与资源释放问题，可能出现静止、空白或异常资源占用；Windows 选择该项会使用 Atlas。尚无同媒体功耗证据证明它比 Atlas 更省电，不能作为生产或日常内核。

    • SDF + 离屏合成（GLES_HB，实验性，仅 Android）
    原理：名称中的 GLES_HB 是历史保存标识；当前实现并非真正的 HardwareBuffer 零拷贝，而是 SDF/GLES 渲染到离屏 FBO，再逐帧 glReadPixels 回读整层到 Bitmap，最后由 Compose/HWUI 贴到画面。
    特征：全屏 GPU→CPU 回读、缓冲分配和异步队列可能带来较高带宽、内存和功耗，并且初始化、背压、画面同步与资源释放仍未达到生产要求；Windows 选择该项会使用 Atlas。仅用于开发验证，不建议日常使用。

    说明：三个实验内核均保留完整功能入口，但不代表已通过稳定性、长时资源和功耗验收。任何内核都不能脱离同一设备、同一视频与同一弹幕密度直接推断功耗排名。
""".trimIndent()
