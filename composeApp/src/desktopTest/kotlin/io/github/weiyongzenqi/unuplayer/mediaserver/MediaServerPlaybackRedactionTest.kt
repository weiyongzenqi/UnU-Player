package io.github.weiyongzenqi.unuplayer.mediaserver

import kotlin.test.Test
import kotlin.test.assertFalse

class MediaServerPlaybackRedactionTest {

    @Test
    fun `播放领域对象字符串不展开会话 URL 与认证头`() {
        val plan = MediaServerPlaybackPlan(
            vendor = MediaServerVendor.JELLYFIN,
            connectionId = "connection-1",
            serverId = "server-1",
            userId = "user-1",
            itemId = "item-1",
            mediaSourceId = "source-1",
            playSessionId = "private-play-session",
            playMethod = MediaServerPlayMethod.DIRECT_PLAY,
            url = "https://private.example.test/video?PlaySessionId=private-play-session",
            headers = mapOf("Authorization" to "private-token"),
            externalSubtitles = listOf(
                MediaServerExternalSubtitle(1, "https://private.example.test/subtitle", "中文", "zh", "srt"),
            ),
            defaultSubtitleStreamIndex = 1,
            initialPositionMs = 1_000L,
        )
        val info = MediaServerPlaybackInfo("private-play-session", mediaSources = emptyList())
        val state = MediaServerPlaybackState(
            itemId = "item-1",
            mediaSourceId = "source-1",
            playSessionId = "private-play-session",
            playMethod = MediaServerPlayMethod.DIRECT_PLAY,
            positionMs = 1_000L,
            isPaused = false,
            isMuted = false,
        )

        listOf(plan.toString(), info.toString(), state.toString()).forEach { rendered ->
            assertFalse(rendered.contains("private-play-session"))
            assertFalse(rendered.contains("private-token"))
            assertFalse(rendered.contains("private.example.test"))
        }
    }
}
