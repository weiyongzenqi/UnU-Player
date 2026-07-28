package io.github.weiyongzenqi.unuplayer.core.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioFocusStateTest {
    @Test
    fun `瞬时 LOSS 保留请求且 GAIN 自动恢复`() {
        val state = AudioFocusState()
        assertTrue(state.beginPlaybackRequest())
        state.completePlaybackRequest(true)

        assertTrue(state.transientLoss())

        assertTrue(state.waitingForGain)
        assertTrue(state.gain())
        assertFalse(state.waitingForGain)
        assertFalse(state.beginPlaybackRequest())
    }

    @Test
    fun `永久 LOSS 清理请求且手动恢复会重新申请`() {
        val state = AudioFocusState()
        assertTrue(state.beginPlaybackRequest())
        state.completePlaybackRequest(true)
        assertTrue(state.transientLoss())

        assertTrue(state.permanentLoss())

        assertFalse(state.waitingForGain)
        assertFalse(state.gain())
        assertTrue(state.beginPlaybackRequest())
    }

    @Test
    fun `主动放弃后的迟到 LOSS 不改变状态`() {
        val state = AudioFocusState()
        assertTrue(state.beginPlaybackRequest())
        state.completePlaybackRequest(true)
        assertTrue(state.abandon())

        assertFalse(state.transientLoss())
        assertFalse(state.permanentLoss())
        assertFalse(state.waitingForGain)
        assertFalse(state.gain())
    }
}
