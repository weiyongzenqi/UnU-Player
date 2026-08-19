package io.github.weiyongzenqi.unuplayer.ui.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPlayerReleaseLeasePoolTest {

    @Test
    fun `许可在创建前限制未完成释放的会话总数`() {
        val pool = DesktopPlayerReleaseLeasePool(capacity = 2)
        val first = assertNotNull(pool.tryAcquire())
        val second = assertNotNull(pool.tryAcquire())

        assertNull(pool.tryAcquire())
        assertEquals(2, pool.activeLeaseCount())

        assertTrue(first.releaseIfUnclaimed())
        val replacement = assertNotNull(pool.tryAcquire())
        assertEquals(2, pool.activeLeaseCount())

        assertTrue(second.releaseIfUnclaimed())
        assertTrue(replacement.releaseIfUnclaimed())
        assertTrue(pool.closeAndAwait(1_000L))
    }

    @Test
    fun `永久等待的会话不阻塞另一会话释放且不会突破总许可`() {
        val pool = DesktopPlayerReleaseLeasePool(capacity = 2)
        val first = assertNotNull(pool.tryAcquire())
        val second = assertNotNull(pool.tryAcquire())
        assertTrue(first.claim())
        assertTrue(second.claim())
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)

        first.submitTerminal {
            firstStarted.countDown()
            releaseFirst.await(5, TimeUnit.SECONDS)
        }
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
        second.submitTerminal { secondFinished.countDown() }

        assertTrue(secondFinished.await(1, TimeUnit.SECONDS))
        assertTrue(waitUntil { pool.activeLeaseCount() == 1 })
        val replacement = assertNotNull(pool.tryAcquire())
        assertEquals(2, pool.activeLeaseCount())
        assertNull(pool.tryAcquire(), "永久等待的第一个会话仍应占用自己的许可")
        assertTrue(replacement.releaseIfUnclaimed())
        releaseFirst.countDown()
        assertTrue(pool.closeAndAwait(2_000L))
    }

    @Test
    fun `同一会话的子资源和终态释放保持 FIFO`() {
        val pool = DesktopPlayerReleaseLeasePool(capacity = 1)
        val lease = assertNotNull(pool.tryAcquire())
        assertTrue(lease.claim())
        assertTrue(lease.claim(), "重复组合认领必须幂等")
        val events = CopyOnWriteArrayList<String>()

        val oldWorker = assertNotNull(lease.tryReserveChildRelease())
        val latestWorker = assertNotNull(lease.tryReserveChildRelease())
        assertNull(lease.tryReserveChildRelease())
        oldWorker.submit { events += "old-worker" }
        lease.submitTerminal {
            latestWorker.runInline { events += "latest-worker" }
            events += "engine"
        }

        assertTrue(pool.closeAndAwait(2_000L))
        assertEquals(listOf("old-worker", "latest-worker", "engine"), events)
        assertFalse(lease.releaseIfUnclaimed())
    }

    @Test
    fun `关闭后拒绝新许可并等待未认领许可归还`() {
        val pool = DesktopPlayerReleaseLeasePool(capacity = 1)
        val lease = assertNotNull(pool.tryAcquire())

        assertFalse(pool.closeAndAwait(1L))
        assertNull(pool.tryAcquire())
        assertTrue(lease.releaseIfUnclaimed())
        assertTrue(pool.closeAndAwait(1_000L))
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        repeat(200) {
            if (condition()) return true
            Thread.sleep(5L)
        }
        return false
    }
}
