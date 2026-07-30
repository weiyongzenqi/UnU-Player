package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * 桌面端弹幕渲染层 actual。
 *
 * GLES 引擎桌面不支持（工厂已回退到 ATLAS），此路径永远不走；
 * 所有引擎统一走 [DanmakuCanvas]（Compose Canvas 渲染）。
 */
@Composable
internal actual fun DanmakuRenderSurface(
    engine: DanmakuEngine,
    entries: List<DanmakuEntry>,
    config: DanmakuConfig,
    positionFlow: StateFlow<Long>,
    frozen: Boolean,
    seekPositionMs: Long,
    seekGeneration: Long,
    modifier: Modifier,
) {
    DanmakuCanvas(
        engine = engine,
        entries = entries,
        config = config,
        positionFlow = positionFlow,
        frozen = frozen,
        seekPositionMs = seekPositionMs,
        seekGeneration = seekGeneration,
        modifier = modifier,
    )
}
