package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuSource

class BaseDanmakuEngineTest {

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

    private class CountingEngine : BaseDanmakuEngine() {
        var activations = 0
        val activeCount: Int get() = active.size

        override fun activate(e: DanmakuEntry, posSec: Double, screenW: Float, baseSpeed: Float): Boolean {
            activations++
            active.add(ActiveDanmaku(e, 0, 100f, screenW))
            return true
        }

        override fun engineName(): String = "test"
        override fun draw(scope: DrawScope) = Unit
    }

    private fun entry(timeSec: Double, text: String) = DanmakuEntry(
        timeSec = timeSec,
        mode = DanmakuMode.SCROLL,
        color = 0xFFFFFF,
        text = text,
        source = DanmakuSource.LOCAL,
    )
}
