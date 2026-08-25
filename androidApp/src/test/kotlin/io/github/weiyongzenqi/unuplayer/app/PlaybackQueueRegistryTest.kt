package io.github.weiyongzenqi.unuplayer.app

import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.core.media.PlaybackQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackQueueRegistryTest {
    @Test
    fun `注册表通过不透明令牌更新索引并可显式释放`() {
        val items = listOf("one", "two").map { title ->
            PlayableMedia(
                url = "file:///$title.mkv",
                title = title,
                sourceKind = MediaSourceKind.LOCAL,
            )
        }
        val token = PlaybackQueueRegistry.register(PlaybackQueue(items, currentIndex = 0))

        assertEquals(0, PlaybackQueueRegistry.get(token)?.currentIndex)
        assertEquals(1, PlaybackQueueRegistry.select(token, 1)?.currentIndex)
        assertNull(PlaybackQueueRegistry.select(token, 3))

        PlaybackQueueRegistry.remove(token)
        assertNull(PlaybackQueueRegistry.get(token))
    }
}
