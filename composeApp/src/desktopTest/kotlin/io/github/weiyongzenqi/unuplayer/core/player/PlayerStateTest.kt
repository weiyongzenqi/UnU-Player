package io.github.weiyongzenqi.unuplayer.core.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerStateTest {
    @Test
    fun `仅播放中保持屏幕常亮`() {
        assertTrue(PlayerState(status = PlaybackStatus.PLAYING, paused = false).shouldKeepScreenOn())
        assertFalse(PlayerState(status = PlaybackStatus.PAUSED, paused = true).shouldKeepScreenOn())
        assertFalse(PlayerState(status = PlaybackStatus.PLAYING, paused = true).shouldKeepScreenOn())
        assertFalse(PlayerState(status = PlaybackStatus.READY, paused = false).shouldKeepScreenOn())
        assertFalse(PlayerState(status = PlaybackStatus.PLAYING, paused = false, eof = true).shouldKeepScreenOn())
        assertFalse(PlayerState(status = PlaybackStatus.ENDED, paused = false, eof = true).shouldKeepScreenOn())
    }
}
