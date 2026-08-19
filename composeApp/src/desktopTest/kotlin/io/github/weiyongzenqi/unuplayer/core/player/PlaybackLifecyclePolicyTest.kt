package io.github.weiyongzenqi.unuplayer.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackLifecyclePolicyTest {

    @Test
    fun `周期进度只在播放中且位置变化时写入`() {
        val playing = PlayerState(
            status = PlaybackStatus.PLAYING,
            durationMs = 120_000L,
            paused = false,
        )

        assertTrue(playing.shouldPersistPeriodicPlayback(20_000L, 10_000L))
        assertTrue(playing.shouldPersistPeriodicPlayback(5_000L, 10_000L), "向后 seek 也必须持久化")
        assertFalse(playing.shouldPersistPeriodicPlayback(10_000L, 10_000L))
        assertFalse(playing.copy(paused = true).shouldPersistPeriodicPlayback(20_000L, 10_000L))
        assertFalse(playing.copy(status = PlaybackStatus.PAUSED).shouldPersistPeriodicPlayback(20_000L, 10_000L))
        assertFalse(playing.copy(status = PlaybackStatus.READY).shouldPersistPeriodicPlayback(20_000L, 10_000L))
        assertFalse(playing.copy(status = PlaybackStatus.ERROR).shouldPersistPeriodicPlayback(20_000L, 10_000L))
        assertFalse(playing.copy(status = PlaybackStatus.ENDED).shouldPersistPeriodicPlayback(20_000L, 10_000L))
        assertFalse(playing.copy(eof = true).shouldPersistPeriodicPlayback(20_000L, 10_000L))
        assertFalse(playing.copy(durationMs = 0L).shouldPersistPeriodicPlayback(20_000L, 10_000L))
    }

    @Test
    fun `Windows 晚到 pause 属性不覆盖终态`() {
        val error = PlayerState(status = PlaybackStatus.ERROR, paused = false)
            .withDesktopPauseProperty(paused = true, playbackFileLoaded = true)
        val ended = PlayerState(status = PlaybackStatus.ENDED, paused = false)
            .withDesktopPauseProperty(paused = true, playbackFileLoaded = true)
        val eof = PlayerState(status = PlaybackStatus.PLAYING, paused = false, eof = true)
            .withDesktopPauseProperty(paused = true, playbackFileLoaded = true)

        assertEquals(PlaybackStatus.ERROR, error.status)
        assertEquals(PlaybackStatus.ENDED, ended.status)
        assertEquals(PlaybackStatus.PLAYING, eof.status)
        assertEquals(
            PlaybackStatus.READY,
            PlayerState(status = PlaybackStatus.READY)
                .withDesktopPauseProperty(paused = false, playbackFileLoaded = false)
                .status,
        )
        assertEquals(
            PlaybackStatus.PAUSED,
            PlayerState(status = PlaybackStatus.PLAYING)
                .withDesktopPauseProperty(paused = true, playbackFileLoaded = true)
                .status,
        )
    }
}
