package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * B-13 vsync 空转治理: 引擎连续 [IDLE_SUSPEND_FRAMES] 帧无活跃弹幕后停止写 tick。
 * 稀疏空档等待 positionFlow 到达下一条弹幕时间，表尾等待外部状态信号，均不触发 Canvas draw。
 * 恢复有三重覆盖:
 * 1. wakeGeneration 递增: entries 变化(加载完成/手动匹配换源)、seekGeneration 变化、
 *    config 变化(含 opacity)各由 LaunchedEffect 递增;
 * 2. draw 侧撤销: 挂起期间信号触发的 draw 若使引擎不再空闲, 立即递增 wakeGeneration 唤醒
 *    (兜住 wakeGeneration 递增早于挂起快照的竞态窗口);
 * 3. [IDLE_WAKE_FALLBACK_MS] 兜底心跳，所有信号全漏也会周期重评估。
 * effect 与 draw 之间只发布不可变、volatile 的 [DanmakuSuspendRequest]，不共享引擎可变状态。
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
    val idleGate = remember(engine) { DanmakuIdleGate() }
    val wakeGeneration = remember { MutableStateFlow(0L) }
    val preparedState = remember { mutableStateOf(PreparedDanmakuInput.Empty) }

    // B-14：有序校验/必要排序在后台完成。来源变化期间先让 draw 应用空列表，避免旧集弹幕残留；
    // engine.load 本身仍在 draw 内串行提交，但已只剩 O(1) 引用替换与状态清理。
    LaunchedEffect(ReferentialKey(entries)) {
        val prepared = withContext(Dispatchers.Default) { prepareDanmakuEntries(entries) }
        preparedState.value = PreparedDanmakuInput(entries, prepared)
        wakeGeneration.update { it + 1 }
    }
    val preparedInput = preparedState.value
    val preparedEntries = if (preparedInput.source === entries) preparedInput.entries else emptyList()

    // B-13 恢复信号①②③④: ①条目列表变化(弹幕加载完成/手动匹配换源 -> entries 新实例)、
    // ②seekGeneration 变化、③config 变化(setConfig 各字段)、④opacity 变化(opacity 是 config 字段, 与③同路)。
    // 任一变化即递增 wakeGeneration 唤醒挂起的循环。初次组合时也会跑一次, 无害(循环尚未挂起)。
    LaunchedEffect(entries, seekGeneration, config) {
        wakeGeneration.update { it + 1 }
    }

    LaunchedEffect(engine, positionFlow, frozen) {
        // effect (重)入先清零空闲门: 换引擎/播放恢复(frozen 是 key, 变化即重启 = 信号⑤ PLAYING 恢复)后,
        // 必须至少 IDLE_SUSPEND_FRAMES 帧常态 tick, 不继承上一段的挂起请求。
        idleGate.reset()
        if (frozen) {
            withFrameNanos { tick.longValue = it }
            return@LaunchedEffect
        }
        // 每 vsync 驱动 redraw: deltaSec 连续(vsync 纳秒差), 增量式 onFrame 用它推进 x。
        // 不用 needsAnimation 轮询: 轮询 tick 自增与 vsync 纳秒混用 -> deltaSec 不连续 +
        // wallDelta=0 时 dirty=false -> needsAnimation=false 死循环(首次加载概率卡住)。
        // B-13: 引擎连续空闲(无活跃弹幕且游标到表尾)达 IDLE_SUSPEND_FRAMES 帧后停写 tick 挂起等待,
        // 恢复只靠明确信号(wakeGeneration/draw 撤销/兜底心跳), 绝不轮询等别人置位的标志。
        while (true) {
            withFrameNanos { tick.longValue = it }
            val suspendRequest = idleGate.request
            if (suspendRequest != null) {
                // 空闲挂起: 循环停在这里(可取消的协程挂起, 离页即退出), 不再注册 withFrameNanos -> 无每 vsync 唤醒。
                val suspendedAt = wakeGeneration.value
                // 双检: 挂起前一刻信号可能已触发一帧 draw 并撤销了挂起请求(此处读到的是上一帧发布的旧值)。
                if (idleGate.request != null) {
                    withTimeoutOrNull(IDLE_WAKE_FALLBACK_MS) {
                        val wakePositionMs = suspendRequest.wakePositionMs
                        if (wakePositionMs == null) {
                            wakeGeneration.first { it > suspendedAt }
                        } else {
                            merge(
                                wakeGeneration.filter { it > suspendedAt },
                                positionFlow.filter { it >= wakePositionMs },
                            ).first()
                        }
                    }
                }
                // 醒来(信号或兜底)后回到每 vsync 写 tick 常态: 下一帧 draw 重评估空闲状态——
                // 若信号使引擎有活干，draw 撤销挂起请求并递增 wakeGeneration，循环保持常态；
                // 若仍空闲, 按空闲门累计满后重新挂起。兜底心跳 = 每 2.5s 一帧 draw, 比 120Hz 便宜两个数量级,
                // 且醒来帧自然读 positionFlow.value, onFrame 的 SEEK_THRESHOLD 检测兜住漏信号下的位置跳变。
            }
        }
    }

    val alpha = config.opacity.coerceIn(0f, 1f)
    val canvasModifier = if (alpha < 1f) modifier.graphicsLayer { this.alpha = alpha } else modifier
    Canvas(canvasModifier) {
        val frameNanos = tick.longValue
        if (frameNanos <= 0L) return@Canvas

        if (applied.entries !== preparedEntries) {
            engine.load(preparedEntries)
            applied.entries = preparedEntries
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
        val wasSuspended = idleGate.request != null
        idleGate.onDrawnFrame(engine.frameSchedule())
        if (wasSuspended && idleGate.request == null) {
            // 空闲被撤销: 信号已在本次 draw 应用(load/seek/setConfig)且引擎又有活干, 立即唤醒挂起的循环。
            // 此路兜住 wakeGeneration 递增略早于挂起快照的竞态(信号 bump 被快照吞掉时, draw 撤销会再 bump)。
            wakeGeneration.update { it + 1 }
        }
    }
}

/**
 * B-13 空闲挂起门。draw 发布不可变请求，effect 跨线程读取 volatile 引用；idleStreak 只由 draw 访问。
 */
private class DanmakuIdleGate {
    @Volatile
    var request: DanmakuSuspendRequest? = null
        private set

    private var idleStreak: Int = 0

    fun onDrawnFrame(schedule: DanmakuFrameSchedule) {
        if (schedule is DanmakuFrameSchedule.Suspend) {
            idleStreak++
            request = if (idleStreak >= IDLE_SUSPEND_FRAMES) {
                DanmakuSuspendRequest(schedule.wakePositionMs)
            } else {
                null
            }
        } else {
            idleStreak = 0
            request = null
        }
    }

    /** effect (重)入时清零: 保证唤醒/播放恢复后至少 [IDLE_SUSPEND_FRAMES] 帧常态 tick, 不残留上段挂起请求。 */
    fun reset() {
        idleStreak = 0
        request = null
    }
}

/**
 * B-13: 连续空闲帧数阈值, 达到才请求挂起停写 tick。
 * 15 帧 = 60Hz 下约 0.25s 静默窗口(120Hz 下约 0.125s): 够小不影响恢复信号感知, 又避免瞬时空隙误挂起。
 */
private const val IDLE_SUSPEND_FRAMES = 15

/**
 * B-13: 空闲挂起期间的兜底心跳(ms)。所有唤醒信号全漏的极端场景下, 至多每 2.5s 自动醒一帧重评估
 * (醒来帧读 positionFlow.value, onFrame 内置 SEEK_THRESHOLD seek 检测兜住位置跳变)——
 * 比 120Hz 逐 vsync 重绘便宜两个数量级, 且保证弹幕永不被永久睡死。
 */
private const val IDLE_WAKE_FALLBACK_MS = 2_500L

private data class DanmakuSuspendRequest(val wakePositionMs: Long?)

/** Compose effect 的 key 使用引用语义，与 PreparedDanmakuInput.source 的身份校验保持一致。 */
private class ReferentialKey(private val value: Any) {
    override fun equals(other: Any?): Boolean = other is ReferentialKey && value === other.value
    override fun hashCode(): Int = 0
}

private class PreparedDanmakuInput(
    val source: List<DanmakuEntry>,
    val entries: List<DanmakuEntry>,
) {
    companion object {
        val Empty = PreparedDanmakuInput(emptyList(), emptyList())
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
