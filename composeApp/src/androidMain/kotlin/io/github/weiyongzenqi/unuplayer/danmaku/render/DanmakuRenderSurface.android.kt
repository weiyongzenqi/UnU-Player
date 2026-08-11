package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import kotlinx.coroutines.flow.StateFlow

/** Android 端三个现存内核统一通过 Compose Canvas 宿主绘制。 */
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
) = DanmakuCanvas(
    engine = engine,
    entries = entries,
    config = config,
    positionFlow = positionFlow,
    frozen = frozen,
    seekPositionMs = seekPositionMs,
    seekGeneration = seekGeneration,
    modifier = modifier,
)
