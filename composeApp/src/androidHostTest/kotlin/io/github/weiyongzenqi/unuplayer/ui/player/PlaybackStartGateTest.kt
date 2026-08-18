package io.github.weiyongzenqi.unuplayer.ui.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackStartGateTest {

    @Test
    fun `STOP 会撤销等待中的 start 且新前台代次需重新捕获`() {
        val gate = PlaybackStartGate(initialForeground = true)
        val old = gate.capture(loadGeneration = 3)

        gate.setForeground(false)
        assertFalse(gate.permits(old, currentLoadGeneration = 3))

        gate.setForeground(true)
        assertFalse(gate.permits(old, currentLoadGeneration = 3))
        assertTrue(gate.permits(gate.capture(3), currentLoadGeneration = 3))
    }

    @Test
    fun `用户暂停播放往返也会让旧 native 决策失效`() {
        val gate = PlaybackStartGate(initialForeground = true)
        val old = gate.capture(loadGeneration = 1)

        gate.setPlayRequested(false)
        gate.setPlayRequested(true)

        assertFalse(gate.permits(old, currentLoadGeneration = 1))
        assertTrue(gate.permits(gate.capture(1), currentLoadGeneration = 1))
    }

    @Test
    fun `旧 load generation 永远不能启动或补偿为 ready`() {
        val gate = PlaybackStartGate(initialForeground = true)
        val old = gate.capture(loadGeneration = 7)

        assertFalse(gate.permits(old, currentLoadGeneration = 8))
        assertFalse(gate.matchesLoad(old, currentLoadGeneration = 8))
    }

    @Test
    fun `用户暂停后往返后台不能取得当前播放许可`() {
        val gate = PlaybackStartGate(initialForeground = true)

        gate.setPlayRequested(false)
        gate.setForeground(false)
        gate.setForeground(true)

        assertFalse(gate.permitsCurrentPlayback())
        gate.setPlayRequested(true)
        assertTrue(gate.permitsCurrentPlayback())
    }
}
