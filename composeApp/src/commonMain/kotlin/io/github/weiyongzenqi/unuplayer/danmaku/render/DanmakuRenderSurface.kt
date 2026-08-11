package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import kotlinx.coroutines.flow.StateFlow

/** 弹幕渲染层平台实现；三个现存内核都使用 [DanmakuCanvas] 宿主。 */
@Composable
internal expect fun DanmakuRenderSurface(
    engine: DanmakuEngine,
    entries: List<DanmakuEntry>,
    config: DanmakuConfig,
    positionFlow: StateFlow<Long>,
    frozen: Boolean,
    seekPositionMs: Long,
    seekGeneration: Long,
    modifier: Modifier,
)
