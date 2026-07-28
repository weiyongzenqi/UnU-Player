package io.github.weiyongzenqi.unuplayer.core.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayableMediaSecurityTest {

    @Test
    fun `播放定位默认文本不展开 URL content URI 与认证头`() {
        val media = PlayableMedia(
            url = "https://media.example.test/video",
            headers = mapOf("Authorization" to "secret-token"),
            title = "第一集",
            sourceKind = MediaSourceKind.JELLYFIN,
            contentUri = "content://secret-document",
            mediaKey = "jellyfin:connection:item",
        )

        val text = media.toString()

        listOf("media.example.test", "secret-token", "secret-document").forEach { secret ->
            assertFalse(text.contains(secret))
        }
        assertTrue(text.contains("headers=<redacted>"))
        assertTrue(text.contains("mediaKey=jellyfin:connection:item"))
    }
}
