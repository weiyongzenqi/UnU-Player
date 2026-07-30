package io.github.weiyongzenqi.unuplayer.danmaku.render

import kotlin.test.Test
import kotlin.test.assertEquals

class DanmakuLayoutTest {
    @Test
    fun `滚动轨道查询不占用且提交后才阻塞`() {
        val allocator = ScrollLaneAllocator(1)

        assertEquals(0, allocator.findAvailableLane(0.0, 100f))
        assertEquals(0, allocator.findAvailableLane(0.0, 100f))
        allocator.occupy(0, 0.0, 200f)

        assertEquals(-1, allocator.findAvailableLane(1.0, 100f))
        assertEquals(0, allocator.findAvailableLane(2.0, 100f))
    }

    @Test
    fun `固定轨道查询不占用且提交后按时释放`() {
        val allocator = FixedLaneAllocator(1)

        assertEquals(0, allocator.findAvailableLane(0.0))
        assertEquals(0, allocator.findAvailableLane(0.0))
        allocator.occupy(0, 0.0, 5.0)

        assertEquals(-1, allocator.findAvailableLane(4.999))
        assertEquals(0, allocator.findAvailableLane(5.0))
    }
}
