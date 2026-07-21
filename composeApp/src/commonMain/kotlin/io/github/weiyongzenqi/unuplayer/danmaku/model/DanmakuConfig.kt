package io.github.weiyongzenqi.unuplayer.danmaku.model

/**
 * 弹幕渲染内核类型(多内核可选)。
 *
 * - [COMPOSE]: Compose Canvas drawText(描边+填充), 默认, 跨平台, 效果好。
 * - [BITMAP]:  预渲染位图缓存(每条唯一弹幕渲染一次, drawImage 贴图), 高密度场景更省 GPU。
 * - [ATLAS]:   预光栅化 atlas 批渲染(文本烘焙到有界 atlas page, drawBitmap/drawVertices 批提交),
 *              高密度场景 draw call N->1-3, 内存 48MiB->12-16MiB; Android 用 nativeCanvas.drawBitmap,
 *              桌面用 Skia drawVertices。
 */
enum class DanmakuEngineType { COMPOSE, BITMAP, ATLAS }

/**
 * 弹幕渲染配置。由 SettingsState 映射, 渲染层消费。
 *
 * @param enabled 弹幕总开关
 * @param opacity 不透明度 0..1(graphicsLayer alpha, 不触发 draw 重绘)
 * @param fontSize 字号 sp; 0 = 平台默认(手机 ~16sp)
 * @param displayArea 显示区域 0..1(屏幕高度利用率, 顶部往下算)
 * @param speedMultiplier 滚动速度倍率(1=基准; 倍速经视频时间自然联动, 不再乘 rate)
 * @param strokeWidth 描边宽度 px
 * @param hideScroll / hideTop / hideBottom 按类型屏蔽
 * @param timeOffsetSec 弹幕时间偏移(秒); 正=延后, 负=提前
 * @param engineType 渲染内核(见 [DanmakuEngineType])
 * @param maxOnScreen 同屏弹幕上限(0=自动使用 5000 条硬上限); 超出丢弃, 防高密度卡顿/遮挡
 */
data class DanmakuConfig(
    val enabled: Boolean = true,
    val opacity: Float = 1.0f,
    val fontSize: Float = 0f,
    val displayArea: Float = 1.0f,
    val speedMultiplier: Float = 1.0f,
    val strokeWidth: Float = 2.0f,
    val hideScroll: Boolean = false,
    val hideTop: Boolean = false,
    val hideBottom: Boolean = false,
    val timeOffsetSec: Double = 0.0,
    val engineType: DanmakuEngineType = DanmakuEngineType.ATLAS,
    val maxOnScreen: Int = 150,
)
