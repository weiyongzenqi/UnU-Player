package io.github.weiyongzenqi.unuplayer.ui.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerfMonitorMetricsTest {

    @Test
    fun `FPS 按真实经过时间归一化`() {
        assertEquals(50f, normalizedFrameRate(sampleCount = 61, elapsedNanos = 1_200_000_000L), 0.001f)
        assertEquals(0f, normalizedFrameRate(sampleCount = 1, elapsedNanos = 1_000_000_000L))
    }

    @Test
    fun `CPU 保留多核进程超过百分百的占用`() {
        assertEquals(250, processCpuPercent(cpuDeltaMillis = 2_500L, wallDeltaNanos = 1_000_000_000L))
        assertEquals(0, processCpuPercent(cpuDeltaMillis = 0L, wallDeltaNanos = 1_000_000_000L))
    }

    @Test
    fun `帧间隔按当前刷新周期分类而非固定十六毫秒`() {
        val period120Hz = 1_000f / 120f
        assertEquals(FrameIntervalBucket.ON_TIME, frameIntervalBucket(8.4f, period120Hz))
        assertEquals(FrameIntervalBucket.ONE_MISSED, frameIntervalBucket(16.7f, period120Hz))
        assertEquals(FrameIntervalBucket.MULTIPLE_MISSED, frameIntervalBucket(25.1f, period120Hz))
    }

    @Test
    fun `累加器只按发布周期产生 Compose 快照`() {
        val accumulator = PerfFrameAccumulator(
            refreshRateHz = 120f,
            publishIntervalNanos = 250_000_000L,
        )
        var published = 0
        var latest: PerfFrameMetrics? = null
        repeat(121) { index ->
            accumulator.record(index * 8_333_333L)?.let {
                published++
                latest = it
            }
        }

        assertTrue(published in 4..5)
        assertEquals(120f, latest?.fps1s ?: 0f, 0.2f)
        assertTrue((latest?.onTimePercent ?: 0) >= 99)
    }
}
