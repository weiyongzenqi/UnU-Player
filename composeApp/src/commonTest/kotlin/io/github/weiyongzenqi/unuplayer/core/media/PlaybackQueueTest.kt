package io.github.weiyongzenqi.unuplayer.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackQueueTest {
    @Test
    fun `播放队列保持当前项并移除认证头和递归引用`() {
        val media = (0 until 620).map { index ->
            PlayableMedia(
                url = "https://media.example.test/$index.mkv",
                headers = mapOf("Authorization" to "secret"),
                title = "第 ${index + 1} 集",
                sourceKind = MediaSourceKind.WEBDAV,
                mediaKey = "webdav:test:/$index.mkv",
            )
        }

        val current = media[610].withPlaybackQueue(media, currentIndex = 610, maxItems = 500)
        val queue = requireNotNull(current.playbackQueue)

        assertEquals(500, queue.items.size)
        assertEquals("webdav:test:/610.mkv", queue.items[queue.currentIndex].mediaKey)
        assertTrue(queue.items.all { it.headers.isEmpty() })
        assertTrue(queue.items.all { it.playbackQueue == null })
        assertNull(queue.items.first().playbackQueue)
    }
}
