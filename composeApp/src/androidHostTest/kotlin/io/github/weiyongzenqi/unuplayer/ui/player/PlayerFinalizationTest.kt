package io.github.weiyongzenqi.unuplayer.ui.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerFinalizationTest {

    @Test
    fun `本地最终写失败仍会发送远端停止`() = runBlocking {
        val calls = mutableListOf<String>()

        val failures = runPlaybackFinalizers(
            finishLocal = {
                calls += "local"
                error("local failed")
            },
            finishRemote = { calls += "remote" },
        )

        assertEquals(listOf("local", "remote"), calls)
        assertNotNull(failures.local)
        assertNull(failures.remote)
    }

    @Test
    fun `远端停止失败不回滚本地最终写`() = runBlocking {
        val calls = mutableListOf<String>()

        val failures = runPlaybackFinalizers(
            finishLocal = { calls += "local" },
            finishRemote = {
                calls += "remote"
                error("remote failed")
            },
        )

        assertEquals(listOf("local", "remote"), calls)
        assertNull(failures.local)
        assertNotNull(failures.remote)
        Unit
    }

    @Test
    fun `最终写取消必须继续向上传播`() {
        assertFailsWith<CancellationException> {
            runBlocking {
                runPlaybackFinalizers(
                    finishLocal = { throw CancellationException("cancel") },
                    finishRemote = { error("不应执行") },
                )
            }
        }
    }

    @Test
    fun `媒体服务器空标题不会回退到带会话参数的 URL`() {
        val title = resolvePlaybackRecordTitle(
            playTitle = "",
            playUrl = "https://media.example.test/Videos/item-1/stream.mp4?PlaySessionId=private-session",
            mediaServerItemId = "item-1",
        )

        assertEquals("item-1", title)
    }

    @Test
    fun `只有观察到正位置才执行本地最终写 B-01`() {
        // duration 可能先于续播 seek 的 time-pos 到达，零位置始终不能覆盖已有续播点。
        assertFalse(shouldPersistFinalPlayback(finalPos = 0L))
        assertTrue(shouldPersistFinalPlayback(finalPos = 45_000L))
    }

    @Test
    fun `远端停止超时视为失败但不阻塞且不抛取消异常 A-08`() = runBlocking {
        val calls = mutableListOf<String>()

        // finishRemote 模拟卡死上报: 远超超时。withTimeoutOrNull 超时必须转普通失败(remote 非空),
        // 而非抛 CancellationException 取消整个最终写(那会连带吞掉本地写结果)。
        val failures = runPlaybackFinalizers(
            finishLocal = { calls += "local" },
            finishRemote = {
                calls += "remote"
                delay(60_000)
            },
            remoteTimeoutMs = 100,
        )

        assertEquals(listOf("local", "remote"), calls)
        assertNull(failures.local)
        assertNotNull(failures.remote)
        assertFalse(failures.remote is CancellationException)
    }

    @Test
    fun `远端停止限时内完成不算超时 A-08`() = runBlocking {
        val failures = runPlaybackFinalizers(
            finishLocal = null,
            finishRemote = { delay(50) },
            remoteTimeoutMs = 10_000,
        )

        assertNull(failures.remote)
    }

    @Test
    fun `跳过本地最终写时远端停止仍上报 B-01`() = runBlocking {
        val calls = mutableListOf<String>()

        // finishLocal=null 即守卫命中(未观察到有效播放): 本地不写, 远端 Stopped 照常
        val failures = runPlaybackFinalizers(
            finishLocal = null,
            finishRemote = { calls += "remote" },
        )

        assertEquals(listOf("remote"), calls)
        assertNull(failures.local)
        assertNull(failures.remote)
    }
}
