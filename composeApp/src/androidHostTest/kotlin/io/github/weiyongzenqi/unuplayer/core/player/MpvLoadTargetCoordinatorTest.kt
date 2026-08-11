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
    fun `每次加载 SMB locator 都打开新 fdclose 目标`() {
        val access = FakeFdAccess(43, 44)
        val coordinator = MpvLoadTargetCoordinator(access)
        val targets = mutableListOf<String>()

        coordinator.load("smbfd://connection/path") { targets += it }
        coordinator.load("SMBFD://connection/path") { targets += it }

        assertEquals(listOf("fdclose://43", "fdclose://44"), targets)
        assertEquals(
            listOf("smbfd://connection/path", "SMBFD://connection/path"),
            access.openedUrls,
        )
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

    @Test
    fun `同步打开失败只展示显式安全消息`() {
        val safeError = MpvLoadTargetException("SMB 连接失败，请检查连接设置", IllegalStateException("secret"))

        assertEquals("SMB 连接失败，请检查连接设置", playbackLoadFailureMessage(safeError))
        assertEquals("加载失败", playbackLoadFailureMessage(IllegalStateException("sensitive detail")))
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
