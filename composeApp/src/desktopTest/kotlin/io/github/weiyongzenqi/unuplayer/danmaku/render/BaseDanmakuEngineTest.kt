package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuSource

class BaseDanmakuEngineTest {

    @Test
    fun `暂停或seek后首帧墙钟归零`() {
        val clock = DanmakuFrameClock()
        assertEquals(0f, clock.deltaSeconds(1_000_000_000L))
        assertEquals(0.016f, clock.deltaSeconds(1_016_000_000L), 0.000_001f)

        clock.reset()
        assertEquals(0f, clock.deltaSeconds(10_000_000_000L))
        assertEquals(0.016f, clock.deltaSeconds(10_016_000_000L), 0.000_001f)
    }

    @Test
    fun `稀疏timePos更新不会被误判为seek`() {
        val engine = CountingEngine()
        engine.load(listOf(entry(0.0, "first")))

        engine.onFrame(0L, 1_000f, 500f, 0.016f)
        // 0.8s 跳变 < SEEK_THRESHOLD(1.0): 增量式下判为稀疏上报, 不清空重激活
        engine.onFrame(800L, 1_000f, 500f, 0.016f)

        assertEquals(1, engine.activations)
        assertEquals(1, engine.activeCount)
    }

    @Test
    fun `大跳变会被判seek并清空重激活`() {
        val engine = CountingEngine()
        engine.load(listOf(entry(0.0, "first")))

        engine.onFrame(0L, 1_000f, 500f, 0.016f)
        // 1.5s 跳变 > SEEK_THRESHOLD(1.0): 增量式下判 seek, 清空 + 按新时间重激活
        engine.onFrame(1_500L, 1_000f, 500f, 0.016f)

        assertEquals(2, engine.activations)
        assertEquals(1, engine.activeCount)
    }

    @Test
    fun `显式seek仍会清空并重新激活`() {
        val engine = CountingEngine()
        engine.load(listOf(entry(0.0, "first")))
        engine.onFrame(0L, 1_000f, 500f, 0.016f)

        engine.onSeek(1_500L)
        engine.onFrame(1_500L, 1_000f, 500f, 0.016f)

        assertEquals(2, engine.activations)
        assertEquals(1, engine.activeCount)
    }

    @Test
    fun `配置和 viewport 变化会按新规则重建活跃项`() {
        val engine = CountingEngine()
        engine.load(listOf(entry(0.0, "first")))
        engine.onFrame(100L, 1_000f, 500f, 0.016f)
        assertEquals(1, engine.activeCount)

        engine.onFrame(100L, 1_200f, 500f, 0.016f)
        assertEquals(1, engine.activeCount)

        engine.setConfig(DanmakuConfig(hideScroll = true))
        engine.onFrame(100L, 1_200f, 500f, 0.016f)
        assertEquals(0, engine.activeCount)
    }

    @Test
    fun `慢速弹幕 seek 会回看完整显示时长`() {
        val engine = CountingEngine()
        engine.setConfig(DanmakuConfig(speedMultiplier = 0.5f))
        engine.load(listOf(entry(2.0, "slow")))

        engine.onSeek(15_000L)
        engine.onFrame(15_000L, 1_000f, 500f, 0.016f)

        assertEquals(1, engine.activeCount)
    }

    @Test
    fun `高速滚动配置seek仍会回看固定弹幕五秒`() {
        val engine = CountingEngine()
        engine.setConfig(DanmakuConfig(speedMultiplier = 4f))
        engine.load(listOf(entry(6.0, "fixed", DanmakuMode.TOP)))

        engine.onSeek(10_000L)
        engine.onFrame(10_000L, 1_000f, 500f, 0.016f)

        assertEquals(1, engine.activeCount)
    }

    @Test
    fun `自动上限可推进五千活跃项且不会重复激活`() {
        val engine = CountingEngine()
        engine.setConfig(DanmakuConfig(maxOnScreen = 0))
        engine.load((0 until 5_000).map { index -> entry(0.0, "dense-$index") })

        assertTrue(engine.onFrame(1L, 1_000f, 500f, 0.016f))
        assertEquals(5_000, engine.activeCount)
        engine.onFrame(2L, 1_000f, 500f, 0.016f)
        assertEquals(5_000, engine.activeCount)
    }

    @Test
    fun `自动上限丢弃超过五千条且显式过大值也会收敛`() {
        val engine = CountingEngine()
        engine.setConfig(DanmakuConfig(maxOnScreen = 0))
        engine.load((0 until 6_000).map { index -> entry(0.0, "dense-$index") })

        engine.onFrame(1L, 1_000f, 500f, 0.016f)

        assertEquals(BaseDanmakuEngine.MAX_ON_SCREEN_HARD_LIMIT, engine.activeCount)
        assertEquals(BaseDanmakuEngine.MAX_ON_SCREEN_HARD_LIMIT, engine.activations)
    }

    @Test
    fun `子内核单帧预算只延后激活且游标不会丢条目`() {
        val engine = CountingEngine(activationBudget = 2)
        engine.load((0 until 5).map { index -> entry(0.0, "dense-$index") })

        engine.onFrame(1L, 1_000f, 500f, 0.016f)
        assertEquals(2, engine.activeCount)
        assertEquals(DanmakuFrameSchedule.Continuous, engine.frameSchedule())

        engine.onFrame(2L, 1_000f, 500f, 0.016f)
        assertEquals(4, engine.activeCount)
        engine.onFrame(3L, 1_000f, 500f, 0.016f)
        assertEquals(5, engine.activeCount)
        assertEquals(5, engine.activations)
    }

    @Test
    fun `候选扫描预算包含激活失败并跨帧继续推进`() {
        val engine = CountingEngine(candidateBudget = 2, activationSucceeds = false)
        engine.load((0 until 5).map { index -> entry(0.0, "dense-$index") })

        engine.onFrame(1L, 1_000f, 500f, 0.016f)
        assertEquals(2, engine.activations)
        assertEquals(2, engine.lastActivationCandidateCount)
        assertEquals(DanmakuFrameSchedule.Continuous, engine.frameSchedule())

        engine.onFrame(2L, 1_000f, 500f, 0.016f)
        assertEquals(4, engine.activations)
        assertEquals(2, engine.lastActivationCandidateCount)
        engine.onFrame(3L, 1_000f, 500f, 0.016f)
        assertEquals(5, engine.activations)
        assertEquals(1, engine.lastActivationCandidateCount)
        assertEquals(DanmakuFrameSchedule.Suspend(null), engine.frameSchedule())
    }

    @Test
    fun `稀疏空档给出下一条弹幕播放位置`() {
        val engine = CountingEngine()
        engine.load(listOf(entry(10.0, "future")))

        engine.onFrame(1_000L, 1_000f, 500f, 0.016f)

        assertEquals(DanmakuFrameSchedule.Suspend(10_000L), engine.frameSchedule())
    }

    @Test
    fun `时间偏移正推迟负提前且帧调度按视频时间反向换算`() {
        // 正=推迟: timeSec=10s 的弹幕, offset +2s 时视频 11s 未激活(弹幕钟 9s), 视频 12s 才激活(弹幕钟 10s)
        val delayed = CountingEngine()
        delayed.setConfig(DanmakuConfig(timeOffsetSec = 2.0))
        delayed.load(listOf(entry(10.0, "offset")))
        // 反向换算: 弹幕时刻 10s + 偏移 2s = 视频 12s 唤醒
        assertEquals(DanmakuFrameSchedule.Suspend(12_000L), delayed.frameSchedule())

        delayed.onFrame(11_000L, 1_000f, 500f, 0.016f)
        assertEquals(0, delayed.activeCount)
        delayed.onFrame(12_000L, 1_000f, 500f, 0.016f)
        assertEquals(1, delayed.activeCount)

        // 负=提前: offset -2s 时视频 8s 即激活(弹幕钟 10s)
        val earlier = CountingEngine()
        earlier.setConfig(DanmakuConfig(timeOffsetSec = -2.0))
        earlier.load(listOf(entry(10.0, "offset")))
        earlier.onFrame(8_000L, 1_000f, 500f, 0.016f)
        assertEquals(1, earlier.activeCount)
    }

    @Test
    fun `播放中改时间偏移setConfig按新偏移换算cursor`() {
        val engine = CountingEngine()
        engine.load(
            listOf(
                entry(3.0, "a"), entry(5.0, "b"), entry(10.0, "c"),
                entry(13.0, "d"), entry(15.0, "e"),
            )
        )
        // 旧偏移 0: 播到视频 20s(弹幕钟 20s), 回看窗口(12s)内的 13/15s 两条激活
        engine.onFrame(20_000L, 1_000f, 500f, 0.016f)
        assertEquals(2, engine.activeCount)
        engine.activations = 0

        // 播放中改偏移 +10s: 换算后弹幕钟 = 20+0-10 = 10, cursor 应定位到回看窗口起点(10-8=2);
        // 若未换算会复用旧弹幕钟 20(窗口起点 12), 下一帧漏补 3/5/10s 三条临场未过期弹幕
        engine.setConfig(DanmakuConfig(timeOffsetSec = 10.0))
        engine.onFrame(20_000L, 1_000f, 500f, 0.016f)

        assertEquals(3, engine.activations)
        assertEquals(3, engine.activeCount)
    }

    @Test
    fun `后台准备仅在乱序时复制排序`() {
        val sorted = listOf(entry(1.0, "a"), entry(2.0, "b"))
        assertSame(sorted, prepareDanmakuEntries(sorted))

        val prepared = prepareDanmakuEntries(listOf(entry(2.0, "b"), entry(1.0, "a")))
        assertEquals(listOf(1.0, 2.0), prepared.map { it.timeSec })
    }

    private class CountingEngine(
        private val activationBudget: Int = Int.MAX_VALUE,
        private val candidateBudget: Int = Int.MAX_VALUE,
        private val activationSucceeds: Boolean = true,
    ) : BaseDanmakuEngine() {
        var activations = 0
        private var activationsThisFrame = 0
        val activeCount: Int get() = active.size

        override fun onFrameStarted() {
            activationsThisFrame = 0
        }

        override fun shouldDeferActivation(entry: DanmakuEntry): Boolean =
            activationsThisFrame >= activationBudget

        override fun activationCandidateBudgetPerFrame(): Int = candidateBudget

        override fun activate(e: DanmakuEntry, posSec: Double, screenW: Float, baseSpeed: Float): Boolean {
            activations++
            activationsThisFrame++
            if (!activationSucceeds) return false
            active.add(ActiveDanmaku(e, 0, 100f, screenW))
            return true
        }

        override fun engineName(): String = "test"
        override fun draw(scope: DrawScope) = Unit
    }

    private fun entry(timeSec: Double, text: String, mode: DanmakuMode = DanmakuMode.SCROLL) = DanmakuEntry(
        timeSec = timeSec,
        mode = mode,
        color = 0xFFFFFF,
        text = text,
        source = DanmakuSource.LOCAL,
    )
}
