package io.github.weiyongzenqi.unuplayer.core.media

import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchMethod
import io.github.weiyongzenqi.unuplayer.danmaku.source.isDanmakuShortcutCompatible
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `TMDB 合并季坐标不会覆盖弹幕本地第三季第七集`() {
        val context = AnimePlaybackContext(
            seriesTitle = "第三季",
            localSeasonNumber = 3,
            localEpisodeNumber = 7,
        )

        assertEquals(3, resolveDanmakuSeasonHint(context, mediaSeasonNumber = 1, fallbackSeasonNumber = 1))
        assertEquals(7, resolveDanmakuEpisodeHint(context, mediaEpisodeNumber = 31, fallbackEpisodeNumber = 31))
    }

    @Test
    fun `播放器评论从TMDB第二十四集还原本地第二季第十三集`() {
        val context = AnimePlaybackContext(
            seriesTitle = "我推的孩子",
            bangumiSubjectId = 443428,
            bangumiEpisodeOffset = -11,
            localSeasonNumber = 2,
            localEpisodeNumber = 13,
        )

        assertEquals(13, resolveDanmakuEpisodeHint(context, mediaEpisodeNumber = 24))
    }

    @Test
    fun `季度身份确定后拒绝旧自动缓存但保留手动选择`() {
        assertFalse(
            isDanmakuShortcutCompatible(
                savedAnimeId = 100,
                savedMatchMethod = DanmakuMatchMethod.TMDB_DATABASE.name,
                expectedAnimeId = 300,
                identityConstrained = true,
            ),
        )
        assertTrue(
            isDanmakuShortcutCompatible(
                savedAnimeId = 100,
                savedMatchMethod = DanmakuMatchMethod.MANUAL.name,
                expectedAnimeId = 300,
                identityConstrained = true,
            ),
        )
        assertFalse(
            isDanmakuShortcutCompatible(
                savedAnimeId = 300,
                savedMatchMethod = DanmakuMatchMethod.DANDANPLAY_DATABASE.name,
                expectedAnimeId = 300,
                identityConstrained = true,
                savedEpisodeOrdinal = null,
                expectedEpisodeOrdinal = 12,
            ),
            "旧自动缓存没有记录季度内顺序，offset 番剧不能继续复用",
        )
        assertTrue(
            isDanmakuShortcutCompatible(
                savedAnimeId = 300,
                savedMatchMethod = DanmakuMatchMethod.DANDANPLAY_DATABASE.name,
                expectedAnimeId = 300,
                identityConstrained = true,
                savedEpisodeOrdinal = 12,
                expectedEpisodeOrdinal = 12,
            ),
        )
    }
}
