package io.github.weiyongzenqi.unuplayer.core.gl

import kotlin.test.Test
import kotlin.test.assertEquals

class DeferredRequestCoalescerTest {
    @Test
    fun `重复请求合并到第二个事件循环`() {
        val queue = ArrayDeque<() -> Unit>()
        var refreshes = 0
        val scheduler = DeferredRequestCoalescer(queue::addLast) { refreshes++ }

        repeat(10) { scheduler.request() }
        assertEquals(1, queue.size)
        queue.removeFirst().invoke()
        assertEquals(0, refreshes)
        assertEquals(1, queue.size)
        queue.removeFirst().invoke()
        assertEquals(1, refreshes)

        scheduler.request()
        assertEquals(1, queue.size)
    }

    @Test
    fun `关闭后已排队任务不再刷新`() {
        val queue = ArrayDeque<() -> Unit>()
        var refreshes = 0
        val scheduler = DeferredRequestCoalescer(queue::addLast) { refreshes++ }

        scheduler.request()
        queue.removeFirst().invoke()
        scheduler.close()
        queue.removeFirst().invoke()

        assertEquals(0, refreshes)
        scheduler.request()
        assertEquals(0, queue.size)
    }
}
