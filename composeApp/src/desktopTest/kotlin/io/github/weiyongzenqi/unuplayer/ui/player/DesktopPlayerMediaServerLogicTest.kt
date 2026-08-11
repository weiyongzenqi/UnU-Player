package io.github.weiyongzenqi.unuplayer.ui.player

import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerExternalSubtitle
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerPlaybackPlan
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerPlayMethod
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerVendor
import io.github.weiyongzenqi.unuplayer.mediaserver.historyMediaKey
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.core.player.HttpRedirectPolicy
import io.github.weiyongzenqi.unuplayer.core.player.PlayerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopPlayerMediaServerLogicTest {

    @Test
    fun `本地未完成记录优先于 Jellyfin 远端续播位置`() {
        assertEquals(
            42_000L,
            desktopResumePosition(
                recordPositionMs = 42_000L,
                recordCompleted = false,
                initialPositionMs = 18_000L,
            ),
        )
        assertEquals(
            18_000L,
            desktopResumePosition(
                recordPositionMs = 42_000L,
                recordCompleted = true,
                initialPositionMs = 18_000L,
            ),
        )
        assertNull(desktopResumePosition(null, recordCompleted = false, initialPositionMs = 5_000L))
    }

    @Test
    fun `Jellyfin 播放记录不保存带会话参数的直放 URL`() {
        val url = "https://example.test/Videos/item/stream?PlaySessionId=secret"

        assertEquals("", desktopPlaybackRecordUrl(url, isMediaServer = true))
        assertEquals(url, desktopPlaybackRecordUrl(url, isMediaServer = false))
    }

    @Test
    fun `EOF 后位置归零时优先用最后有效时长收尾`() {
        assertEquals(
            90_000L,
            desktopFinalPlaybackPosition(
                currentPositionMs = 0L,
                playbackEnded = true,
                lastValidPositionMs = 84_000L,
                lastValidDurationMs = 90_000L,
            ),
        )
        assertEquals(
            84_000L,
            desktopFinalPlaybackPosition(
                currentPositionMs = 0L,
                playbackEnded = true,
                lastValidPositionMs = 84_000L,
                lastValidDurationMs = 0L,
            ),
        )
        assertEquals(
            21_000L,
            desktopFinalPlaybackPosition(21_000L, true, 84_000L, 90_000L),
        )
    }

    @Test
    fun `Jellyfin 默认外挂字幕最后加载并选中`() {
        val first = subtitle(1)
        val default = subtitle(2)
        val loads = desktopMediaServerSubtitleLoads(plan(listOf(default, first), defaultIndex = 2))

        assertEquals(listOf(first, default), loads.map { it.subtitle })
        assertEquals(listOf(false, true), loads.map { it.select })
    }

    @Test
    fun `Jellyfin 无可用默认外挂时全部缓存且不抢选轨`() {
        val subtitles = listOf(subtitle(1), subtitle(2))

        assertEquals(
            listOf(false, false),
            desktopMediaServerSubtitleLoads(plan(subtitles, defaultIndex = null)).map { it.select },
        )
        assertEquals(
            listOf(false, false),
            desktopMediaServerSubtitleLoads(plan(subtitles, defaultIndex = 99)).map { it.select },
        )
    }

    @Test
    fun `媒体服务器计划不一致会在创建引擎前返回错误`() {
        val plan = plan(emptyList(), defaultIndex = null)
        val media = PlayableMedia(
            url = plan.url,
            headers = plan.headers,
            title = "测试视频",
            sourceKind = MediaSourceKind.JELLYFIN,
            mediaKey = plan.historyMediaKey,
        )

        assertEquals(
            "媒体服务器播放必须拒绝 HTTP 重定向",
            desktopMediaServerPlanMismatch(media, plan, PlayerConfig()),
        )
        assertNull(
            desktopMediaServerPlanMismatch(
                media,
                plan,
                PlayerConfig(httpRedirectPolicy = HttpRedirectPolicy.DENY),
            ),
        )
    }

    private fun subtitle(index: Int) = MediaServerExternalSubtitle(
        streamIndex = index,
        url = "https://media.example.test/subtitle/$index",
        title = "字幕 $index",
        language = "zh",
        codec = "srt",
    )

    private fun plan(
        subtitles: List<MediaServerExternalSubtitle>,
        defaultIndex: Int?,
    ) = MediaServerPlaybackPlan(
        vendor = MediaServerVendor.JELLYFIN,
        connectionId = "connection-id",
        serverId = "server-id",
        userId = "user-id",
        itemId = "item-id",
        mediaSourceId = "source-id",
        playSessionId = "play-session-id",
        playMethod = MediaServerPlayMethod.DIRECT_PLAY,
        url = "https://media.example.test/video",
        headers = emptyMap(),
        externalSubtitles = subtitles,
        defaultSubtitleStreamIndex = defaultIndex,
        initialPositionMs = 0L,
    )
}
