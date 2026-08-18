package io.github.weiyongzenqi.unuplayer.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPlayerReleaseCoordinatorTest {

    @Test
    fun `父级先释放时同一后台任务按顺序关闭全部 native 资源`() {
        val tasks = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val coordinator = DesktopPlayerReleaseCoordinator { task -> tasks += task }

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
        val coordinator = DesktopPlayerReleaseCoordinator { task -> tasks += task }
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
        val coordinator = DesktopPlayerReleaseCoordinator { task -> tasks += task }
        val oldToken = coordinator.attach { events += "old-worker" }
        coordinator.attach { events += "new-worker" }

        coordinator.detach(oldToken)
        coordinator.release { events += "engine-destroy" }

        while (tasks.isNotEmpty()) tasks.removeAt(0).invoke()
        assertEquals(listOf("old-worker", "new-worker", "engine-destroy"), events)
    }
}
