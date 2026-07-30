package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * Android 端弹幕渲染层 actual。
 *
 * GLES 引擎按设置路由到 [GlDanmakuLayer] 或 [HbDanmakuLayer]；其他引擎走 [DanmakuCanvas]。
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
    if (engine is GlesDanmakuEngine) {
        if (config.engineType == DanmakuEngineType.GLES_HB) {
            HbDanmakuLayer(engine, entries, config, positionFlow, frozen, seekPositionMs, seekGeneration, modifier)
        } else {
            GlDanmakuLayer(engine, entries, config, positionFlow, frozen, seekPositionMs, seekGeneration, modifier)
        }
    } else {
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
}
