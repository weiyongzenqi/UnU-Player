package io.github.weiyongzenqi.unuplayer.ui.player

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.weiyongzenqi.unuplayer.core.player.MpvPlayerEngine

/**
 * 视频渲染 Surface: AndroidView 嵌 SurfaceView。
 *
 * 关键(见 DESIGN.md §7.5):
 * - 必须用 SurfaceView(非 TextureView): HDR 直通 + 独立图层性能
 * - surfaceCreated 时 attachSurface, surfaceDestroyed 时 detachSurface
 * - 引擎 init 与 surface 时序: engine 内 pendingSurface 缓存, 先就绪则 init 后补绑
 */
@Composable
fun MpvVideoSurface(
    engine: MpvPlayerEngine?,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).also { sv ->
                sv.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        engine?.attachSurface(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int,
                    ) {
                        // 无需处理, mpv 自适应
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        engine?.detachSurface()
                    }
                })
            }
        },
        // engine 变化时无需重建 SurfaceView(只会从 null->实例一次),
        // surfaceCreated 在 factory 后由系统触发, engine 已就绪即可绑。
        update = { /* no-op */ },
    )
}
