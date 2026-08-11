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

    @Test
    fun `番剧播放上下文默认文本不展开标题`() {
        val media = PlayableMedia(
            url = "https://media.example.test/video",
            title = "显示标题",
            sourceKind = MediaSourceKind.LOCAL,
            animeContext = AnimePlaybackContext(
                seriesTitle = "不应进入日志的系列名",
                episodeTitle = "不应进入日志的集标题",
                episodeDescription = "不应进入日志的本集简介",
                bangumiSubjectId = 623854,
                bangumiEpisodeOffset = 12,
            ),
        )

        val text = media.toString()

        assertFalse(text.contains("不应进入日志的系列名"))
        assertFalse(text.contains("不应进入日志的集标题"))
        assertFalse(text.contains("不应进入日志的本集简介"))
        assertTrue(text.contains("subjectId=623854"))
        assertTrue(text.contains("offset=12"))
    }
}
