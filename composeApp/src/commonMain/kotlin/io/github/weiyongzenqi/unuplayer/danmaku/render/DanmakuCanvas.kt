package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.StateFlow
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry

/**
 * 桌面和 Android 共用的弹幕 Canvas。
 *
 * vsync effect 只递增帧号，不再触碰引擎；全部可变状态在 Canvas draw 内按
 * load -> config -> seek -> advance -> draw 的顺序提交。这样桌面 Skiko 即使把
 * effect 与 draw 调度到不同线程，也不会并发遍历或修改 active 列表。
 *
 * 运动模型: 增量式墙钟--positionMs 直接用 positionFlow.value(不再外推),
 * onFrame 内用 deltaSec(墙钟)× rate 推进 x, seek 由 onFrame 内 rawDelta 检测。
 */
@Composable
fun DanmakuCanvas(
    engine: DanmakuEngine,
    entries: List<DanmakuEntry>,
    config: DanmakuConfig,
    positionFlow: StateFlow<Long>,
    frozen: Boolean,
    seekPositionMs: Long,
    seekGeneration: Long,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fontScalePx = density.density * density.fontScale
    val tick = remember { mutableLongStateOf(0L) }
    val applied = remember(engine) { AppliedDanmakuInputs() }

    LaunchedEffect(engine, positionFlow, frozen) {
        if (frozen) {
            withFrameNanos { tick.longValue = it }
            return@LaunchedEffect
        }
        // 每 vsync 驱动 redraw: deltaSec 连续(vsync 纳秒差), 增量式 onFrame 用它推进 x。
        // 不用 needsAnimation 轮询: 轮询 tick 自增与 vsync 纳秒混用 -> deltaSec 不连续 +
        // wallDelta=0 时 dirty=false -> needsAnimation=false 死循环(首次加载概率卡住)。
        // 无弹幕段 onFrame/draw 空遍历极轻量, vsync 唤醒开销可接受(Atlas 省 drawText 光栅化远大于此)。
        while (true) {
            withFrameNanos { tick.longValue = it }
        }
    }

    val alpha = config.opacity.coerceIn(0f, 1f)
    val canvasModifier = if (alpha < 1f) modifier.graphicsLayer { this.alpha = alpha } else modifier
    Canvas(canvasModifier) {
        val frameNanos = tick.longValue
        if (frameNanos <= 0L) return@Canvas

        if (applied.entries !== entries) {
            engine.load(entries)
            applied.entries = entries
        }
        if (applied.config != config) {
            engine.setConfig(config)
            applied.config = config
        }
        if (applied.fontScalePx != fontScalePx) {
            engine.setFontScalePx(fontScalePx)
            applied.fontScalePx = fontScalePx
        }
        if (applied.frozen != frozen) {
            engine.setPaused(frozen)
            applied.frozen = frozen
        }
        var sampledPositionMs = positionFlow.value
        // mpv 未就绪(position=0 且冻结中)且首次初始化时不渲染弹幕:
        // 进入续播视频时 mpv seek 完成前 positionFlow 仍是 0, 若此时锚定 0 会显示 0s 错位弹幕,
        // 等 mpv 上报真实位置 + onSeek 才跳过去 = "卡一会儿到特定位置开始滚动"。
        // 直接跳过渲染, 等真实位置到达(position!=0)或 frozen 解除(开始播放)再首次锚定。
        // 新视频从 0 播: buffering 期间跳过(本就不该显示), play 后 frozen=false 正常渲染 0s 弹幕。
        if (sampledPositionMs == 0L && frozen && applied.seekGeneration == Long.MIN_VALUE) {
            return@Canvas
        }
        if (applied.seekGeneration != seekGeneration) {
            // 首次初始化用 mpv 当前位置(positionFlow.value)而非默认 0:
            // 续播视频进入时 seek 已完成则 positionFlow 已是真实位置, 直接锚定正确位置,
            // 避免弹幕先卡在 0s 错位静止、等真实 onSeek 才跳(Bug A)。新视频 positionFlow=0 行为不变。
            val isFirstInit = applied.seekGeneration == Long.MIN_VALUE
            val seekTarget = if (isFirstInit) sampledPositionMs else seekPositionMs
            engine.onSeek(seekTarget)
            applied.seekGeneration = seekGeneration
            sampledPositionMs = seekTarget
        }

        val deltaSec = if (applied.lastFrameNanos > 0L) {
            ((frameNanos - applied.lastFrameNanos).coerceAtLeast(0L) / 1_000_000_000.0).toFloat()
        } else {
            0f
        }
        applied.lastFrameNanos = frameNanos
        engine.renderFrame(
            positionMs = sampledPositionMs,
            screenW = size.width,
            screenH = size.height,
            deltaSec = deltaSec,
            scope = this,
        )
    }
}

private class AppliedDanmakuInputs {
    var entries: List<DanmakuEntry>? = null
    var config: DanmakuConfig? = null
    var fontScalePx = Float.NaN
    var frozen: Boolean? = null
    var seekGeneration = Long.MIN_VALUE
    var lastFrameNanos = 0L
}
