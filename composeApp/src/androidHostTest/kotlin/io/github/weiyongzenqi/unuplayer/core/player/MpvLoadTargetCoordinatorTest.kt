package io.github.weiyongzenqi.unuplayer.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MpvLoadTargetCoordinatorTest {

    @Test
    fun `普通 URL 不打开 fd 且原样传给 mpv`() {
        val access = FakeFdAccess()
        val coordinator = MpvLoadTargetCoordinator(access)
        var loadedUrl = ""

        coordinator.load("https://example.invalid/video.mkv") { loadedUrl = it }

        assertEquals("https://example.invalid/video.mkv", loadedUrl)
        assertTrue(access.openedUrls.isEmpty())
        assertTrue(access.closedFds.isEmpty())
    }

    @Test
    fun `每次加载 content URI 都打开新 fdclose 目标`() {
        val access = FakeFdAccess(41, 42)
        val coordinator = MpvLoadTargetCoordinator(access)
        val targets = mutableListOf<String>()

        coordinator.load("content://media/video/1") { targets += it }
        coordinator.load("content://media/video/1") { targets += it }

        assertEquals(listOf("fdclose://41", "fdclose://42"), targets)
        assertEquals(listOf("content://media/video/1", "content://media/video/1"), access.openedUrls)
        assertTrue(access.closedFds.isEmpty())
    }

    @Test
    fun `mpv 命令失败时应用收回 detached fd`() {
        val access = FakeFdAccess(51)
        val coordinator = MpvLoadTargetCoordinator(access)

        assertFailsWith<IllegalStateException> {
            coordinator.load("CONTENT://media/video/1") { error("command failed") }
        }

        assertEquals(listOf(51), access.closedFds)
    }

    private class FakeFdAccess(
        vararg fds: Int,
    ) : MpvDetachedFdAccess {
        private val remainingFds = ArrayDeque(fds.toList())
        val openedUrls = mutableListOf<String>()
        val closedFds = mutableListOf<Int>()

        override fun openReadOnly(contentUrl: String): Int {
            openedUrls += contentUrl
            return remainingFds.removeFirst()
        }

        override fun close(fd: Int) {
            closedFds += fd
        }
    }
}
