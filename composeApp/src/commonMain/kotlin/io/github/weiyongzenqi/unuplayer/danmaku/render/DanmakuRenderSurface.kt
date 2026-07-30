package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * 弹幕渲染层平台实现（expect/actual）。
 *
 * 非 GLES 引擎（COMPOSE/BITMAP/ATLAS）走 [DanmakuCanvas]（Compose Canvas 渲染）；
 * Android GLES 引擎走 [GlDanmakuLayer]（TextureView）或 [HbDanmakuLayer]（离屏 FBO 回读）；
 * 桌面端 GLES 不支持（工厂回退 ATLAS，永远不触发此路径）。
 */
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
