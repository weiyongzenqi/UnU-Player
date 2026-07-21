package io.github.weiyongzenqi.unuplayer.playback

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlaybackWriteTimestampTest {

    @Test
    fun `播放记录时间戳严格递增并能越过已有值`() {
        val first = nextPlaybackWriteTimestamp()
        val second = nextPlaybackWriteTimestamp()
        val future = second + 100

        assertTrue(second > first)
        assertTrue(nextPlaybackWriteTimestamp(future) > future)
    }

    @Test
    fun `Long MAX 历史值会明确失败而不是溢出`() {
        assertFailsWith<IllegalArgumentException> {
            nextPlaybackWriteTimestamp(Long.MAX_VALUE)
        }
    }
}
