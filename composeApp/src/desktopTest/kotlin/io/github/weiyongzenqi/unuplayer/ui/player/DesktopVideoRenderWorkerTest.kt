package io.github.weiyongzenqi.unuplayer.ui.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopVideoRenderWorkerTest {
    @Test
    fun `软件渲染预算保持宽高比和偶数尺寸`() {
        assertEquals(DesktopVideoRenderSize(3840, 2160), desktopVideoRenderSize(3840, 2160))
        assertEquals(DesktopVideoRenderSize(1280, 720), desktopVideoRenderSize(1280, 720))
        assertEquals(DesktopVideoRenderSize(2000, 1124), desktopVideoRenderSize(2001, 1125))
        assertNull(desktopVideoRenderSize(32, 32))
    }

    @Test
    fun `渲染预算同时限制显示器和原始视频尺寸`() {
        val budget = DesktopVideoRenderBudget(
            displayWidth = 2560,
            displayHeight = 1440,
            sourceWidth = 1920,
            sourceHeight = 1080,
        )
        assertEquals(DesktopVideoRenderSize(1920, 1080), desktopVideoRenderSize(3840, 2160, budget = budget))
        assertEquals(
            DesktopVideoRenderSize(2560, 1440),
            desktopVideoRenderSize(
                viewportWidth = 5120,
                viewportHeight = 2880,
                budget = budget.copy(sourceWidth = 0, sourceHeight = 0),
            ),
        )
    }

    @Test
    fun `UI 确认前合并回调且 resize 等待稳定后切换尺寸`() {
        val renderCount = AtomicInteger(0)
        val version = AtomicLong(0L)
        val frameReady = CountDownLatch(2)
        val firstRenderStarted = CountDownLatch(1)
        val allowFirstRender = CountDownLatch(1)
        val error = arrayOfNulls<Throwable>(1)
        data class FakeFrame(val version: Long, val width: Int, val height: Int)
        val worker = DesktopVideoRenderWorker(
            renderFrame = { width, height ->
                val count = renderCount.incrementAndGet()
                if (count == 1) {
                    firstRenderStarted.countDown()
                    check(allowFirstRender.await(2, TimeUnit.SECONDS))
                }
                FakeFrame(version.incrementAndGet(), width, height)
            },
            frameVersion = FakeFrame::version,
            reportError = { error[0] = it },
            onFrameAvailable = frameReady::countDown,
        )
        try {
            assertTrue(worker.setViewportSize(800, 600))
            assertTrue(worker.setViewportSize(2560, 1440))
            assertTrue(firstRenderStarted.await(2, TimeUnit.SECONDS))
            repeat(100) { worker.requestRender() }
            allowFirstRender.countDown()
            assertTrue(waitUntil { worker.latestFrame()?.version == 1L })
            assertEquals(DesktopVideoRenderSize(2560, 1440), worker.lockedRenderSize())
            assertTrue(worker.setViewportSize(1280, 720))
            assertEquals(1, renderCount.get())

            worker.markPresented(1L)
            assertTrue(frameReady.await(2, TimeUnit.SECONDS))
            assertEquals(2, renderCount.get())
            assertEquals(2L, worker.latestFrame()?.version)
            assertEquals(1280, worker.latestFrame()?.width)
            assertEquals(720, worker.latestFrame()?.height)
            assertEquals(DesktopVideoRenderSize(1280, 720), worker.lockedRenderSize())
            assertNull(error[0])
        } finally {
            allowFirstRender.countDown()
            worker.close()
            assertTrue(worker.awaitStopped(2_000L))
        }
    }

    @Test
    fun `resize 在 UI 持有旧帧 lease 时不得重建缓冲`() {
        val renderCount = AtomicInteger(0)
        val version = AtomicLong(0L)
        val firstFrameReady = CountDownLatch(1)
        val resizedFrameReady = CountDownLatch(1)
        val leaseStarted = CountDownLatch(1)
        val releaseLease = CountDownLatch(1)
        data class FakeFrame(val version: Long, val width: Int, val height: Int)
        val worker = DesktopVideoRenderWorker(
            renderFrame = { width, height ->
                FakeFrame(version.incrementAndGet(), width, height).also {
                    renderCount.incrementAndGet()
                }
            },
            frameVersion = FakeFrame::version,
            reportError = { throw AssertionError("worker 不应失败", it) },
            onFrameAvailable = {
                if (renderCount.get() == 1) firstFrameReady.countDown() else resizedFrameReady.countDown()
            },
        )
        val leaseThread = Thread {
            worker.withLatestFrame {
                leaseStarted.countDown()
                check(releaseLease.await(2, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(worker.setViewportSize(800, 600))
            assertTrue(firstFrameReady.await(2, TimeUnit.SECONDS))
            leaseThread.start()
            assertTrue(leaseStarted.await(2, TimeUnit.SECONDS))

            assertTrue(worker.setViewportSize(1280, 720))
            worker.markPresented(1L)
            assertFalse(resizedFrameReady.await(300, TimeUnit.MILLISECONDS))
            assertEquals(1, renderCount.get())

            releaseLease.countDown()
            leaseThread.join(2_000L)
            assertFalse(leaseThread.isAlive)
            assertTrue(resizedFrameReady.await(2, TimeUnit.SECONDS))
            assertEquals(2, renderCount.get())
            assertEquals(1280, worker.latestFrame()?.width)
            assertEquals(720, worker.latestFrame()?.height)
        } finally {
            releaseLease.countDown()
            worker.close()
            leaseThread.join(2_000L)
            assertTrue(worker.awaitStopped(2_000L))
        }
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        repeat(200) {
            if (condition()) return true
            Thread.sleep(5L)
        }
        return false
    }
}
