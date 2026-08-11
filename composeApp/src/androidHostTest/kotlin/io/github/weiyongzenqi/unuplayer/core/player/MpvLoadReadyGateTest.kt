package io.github.weiyongzenqi.unuplayer.core.player

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvLoadReadyGateTest {

    @Test
    fun `等待者在 load 开始前启动也能收到本轮 READY`() = runBlocking {
        val gate = MpvLoadReadyGate()
        val result = async { gate.awaitCurrentTerminal() }

        gate.onLoadStarted()
        assertTrue(gate.tryPublishReady())

        assertEquals(PlaybackStatus.READY, result.await())
    }

    @Test
    fun `强制错误后拒绝迟到 READY 且下一轮 load 自动恢复`() = runBlocking {
        val gate = MpvLoadReadyGate()
        gate.onLoadStarted()
        gate.publishError()

        assertFalse(gate.tryPublishReady())
        assertEquals(PlaybackStatus.ERROR, gate.awaitCurrentTerminal())

        gate.onLoadStarted()
        assertTrue(gate.tryPublishReady())
        assertEquals(PlaybackStatus.READY, gate.awaitCurrentTerminal())
    }
}
