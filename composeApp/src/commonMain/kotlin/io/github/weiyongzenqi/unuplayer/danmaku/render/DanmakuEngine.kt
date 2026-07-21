package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry

/**
 * 弹幕渲染引擎接口(commonMain, 跨平台)。实现持有活跃弹幕 + 轨道 + 时间轴。
 *
 * 单向: [load] -> [onFrame](每帧推进) -> [draw](每帧绘制); seek 走 [onSeek]。
 *
 * 多内核: [createDanmakuEngine] 按 [DanmakuEngineType] 产出实现(Compose Canvas / 位图缓存)。
 */
interface DanmakuEngine {
    /** 加载弹幕数据(按 timeSec 升序; 内部会再排序保险)。 */
    fun load(entries: List<DanmakuEntry>)

    /** 清空(换集/退出)。 */
    fun clear()

    fun setConfig(config: DanmakuConfig)

    /**
     * 倍速联动(默认 no-op; 需墙钟运动的内核用)。
     *
     * 内核用**墙钟增量 × rate** 驱动滚动([onFrame] 的 deltaSec 为墙钟): 平滑 60fps、
     * 增量不跳变(倍速切换不瞬移)、暂停冻结(deltaSec 置 0)。rate 来自此方法。
     */
    fun setRate(rate: Float) {}

    /** 暂停/播放状态(默认 no-op; 内核据此冻结/恢复墙钟运动, 暂停时弹幕不动也不重绘)。 */
    fun setPaused(paused: Boolean) {}

    /** 注入 px/sp(默认 no-op; 需密度感知的内核用, sp 字号 -> px 轨道高度统一防堆叠)。 */
    fun setFontScalePx(px: Float) {}

    /** seek: 清空活跃弹幕(避免跳跃残影), 按新时间重激活。 */
    fun onSeek(positionMs: Long)

    /**
     * 每帧推进: 激活当前时间该出现的弹幕 + 回收过期 + 用墙钟增量推进 x。
     *
     * @param positionMs 当前播放位置(ms, 视频时间; 激活时机 + seek 检测用)
     * @param screenW / screenH 画布尺寸(px)
     * @param deltaSec 距上一帧秒数(**墙钟**; 滚动运动用它 × rate, 平滑不抖)
     * @return 是否需要重绘(暂停/无变化时返回 false, 调用方跳过 tick -> 不触发 draw)
     */
    fun onFrame(positionMs: Long, screenW: Float, screenH: Float, deltaSec: Float): Boolean

    /** 绘制活跃弹幕到 [scope]。 */
    fun draw(scope: DrawScope)

    /**
     * 在同一个 Canvas draw 阶段推进并绘制，避免桌面端 effect 与 Skiko draw
     * 同时访问可变 active 列表。实现不得把可变帧状态发布到其他线程。
     */
    fun renderFrame(
        positionMs: Long,
        screenW: Float,
        screenH: Float,
        deltaSec: Float,
        scope: DrawScope,
    ): Boolean {
        val dirty = onFrame(positionMs, screenW, screenH, deltaSec)
        draw(scope)
        return dirty
    }
}
