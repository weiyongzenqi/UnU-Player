package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.weiyongzenqi.unuplayer.core.player.PlayerEngine
import io.github.weiyongzenqi.unuplayer.core.player.PlayerEvent
import io.github.weiyongzenqi.unuplayer.core.player.PlayerEventObserver
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry

/**
 * 弹幕层只收集不可变输入。load/config/seek/推进/绘制都由 [DanmakuCanvas]
 * 在同一个 draw 阶段应用，避免 effect 与 Skiko draw 并发访问引擎可变状态。
 *
 * Seek 处理(增量式, 098a5c1 风格): Seek 事件只记录目标位置 + 换代, draw 阶段应用 onSeek;
 * 不再等 PlaybackRestart(增量式 onFrame 内 rawDelta seek 检测兜底)。
 * rate 通过 LaunchedEffect(state.rate) 注入引擎。
 */
@Composable
fun DanmakuLayer(
    playerEngine: PlayerEngine,
    entries: List<DanmakuEntry>,
    config: DanmakuConfig,
    modifier: Modifier = Modifier,
) {
    if (!config.enabled || entries.isEmpty()) return

    val engine = remember(config.engineType) { createDanmakuEngine(config.engineType) }
    val seekPosition = remember(engine) { mutableLongStateOf(0L) }
    val seekGeneration = remember(engine) { mutableLongStateOf(0L) }
    val state by playerEngine.state.collectAsStateWithLifecycle()

    DisposableEffect(engine) {
        onDispose { engine.clear() }
    }
    DisposableEffect(playerEngine, engine) {
        val observer = object : PlayerEventObserver {
            override fun onEvent(event: PlayerEvent) {
                if (event is PlayerEvent.Seek) {
                    // Seek 事件: 记录目标位置, draw 阶段应用 onSeek(线程安全)
                    seekPosition.longValue = playerEngine.position.value
                    seekGeneration.longValue++
                }
            }
            override fun onPropertyChanged(name: String, value: Any?) = Unit
        }
        playerEngine.addObserver(observer)
        onDispose { playerEngine.removeObserver(observer) }
    }

    // 倍速联动: rate 变化注入引擎, onFrame 用 advanceSpeed = baseSpeed × rate 推进滚动
    LaunchedEffect(state.rate) {
        engine.setRate(state.rate)
    }

    DanmakuCanvas(
        engine = engine,
        entries = entries,
        config = config,
        positionFlow = playerEngine.position,
        frozen = state.paused || state.buffering,
        seekPositionMs = seekPosition.longValue,
        seekGeneration = seekGeneration.longValue,
        modifier = modifier,
    )
}
