package io.github.weiyongzenqi.unuplayer.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopPlayerReleaseCoordinatorTest {

    @Test
    fun `父级先释放时同一后台任务按顺序关闭全部 native 资源`() {
        val tasks = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val coordinator = DesktopPlayerReleaseCoordinator(submit = { task -> tasks += task })

        coordinator.attach {
            events += "worker-stop"
            events += "image-close"
        }
        coordinator.release { events += "engine-destroy" }

        assertTrue(events.isEmpty(), "dispose 调用线程不得执行 join 或 native close")
        assertEquals(1, tasks.size)
        tasks.removeAt(0).invoke()
        assertEquals(listOf("worker-stop", "image-close", "engine-destroy"), events)
    }

    @Test
    fun `子级先释放时 FIFO 仍保证 worker 先于 engine`() {
        val tasks = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val coordinator = DesktopPlayerReleaseCoordinator(submit = { task -> tasks += task })
        val token = coordinator.attach { events += "worker-stop" }

        coordinator.detach(token)
        coordinator.release { events += "engine-destroy" }

        assertEquals(2, tasks.size)
        while (tasks.isNotEmpty()) tasks.removeAt(0).invoke()
        assertEquals(listOf("worker-stop", "engine-destroy"), events)
    }

    @Test
    fun `worker generation 替换只清理一次且终态接管最新资源`() {
        val tasks = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val coordinator = DesktopPlayerReleaseCoordinator(submit = { task -> tasks += task })
        val oldToken = coordinator.attach { events += "old-worker" }
        coordinator.attach { events += "new-worker" }

        coordinator.detach(oldToken)
        coordinator.release { events += "engine-destroy" }

        while (tasks.isNotEmpty()) tasks.removeAt(0).invoke()
        assertEquals(listOf("old-worker", "new-worker", "engine-destroy"), events)
    }

    @Test
    fun `父级终态使用独立提交入口归还会话许可`() {
        val regularTasks = mutableListOf<() -> Unit>()
        val terminalTasks = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val coordinator = DesktopPlayerReleaseCoordinator(
            submit = { task -> regularTasks += task },
            submitTerminal = { task -> terminalTasks += task },
        )

        coordinator.attach { events += "worker" }
        coordinator.release { events += "engine" }

        assertTrue(regularTasks.isEmpty())
        assertEquals(1, terminalTasks.size)
        terminalTasks.single().invoke()
        assertEquals(listOf("worker", "engine"), events)
    }

    @Test
    fun `父级先释放时直接消费当前 worker 预留并归还会话许可`() {
        val pool = DesktopPlayerReleaseLeasePool(capacity = 1)
        val lease = assertNotNull(pool.tryAcquire())
        assertTrue(lease.claim())
        val events = mutableListOf<String>()
        val coordinator = DesktopPlayerReleaseCoordinator(
            submit = lease::submit,
            submitTerminal = lease::submitTerminal,
            reserveChild = lease::tryReserveChildRelease,
        )
        val reservation = assertNotNull(coordinator.tryReserveChildRelease())
        coordinator.attach(reservation) { events += "worker" }

        coordinator.release { events += "engine" }

        assertTrue(pool.closeAndAwait(2_000L))
        assertEquals(listOf("worker", "engine"), events)
    }
}
