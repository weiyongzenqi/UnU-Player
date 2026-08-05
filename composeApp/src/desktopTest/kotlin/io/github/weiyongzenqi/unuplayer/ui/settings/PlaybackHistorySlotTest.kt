package io.github.weiyongzenqi.unuplayer.ui.settings

import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerConnectionSummary
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerPlaybackLocator
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerVendor
import io.github.weiyongzenqi.unuplayer.mediaserver.mediaServerHistoryMediaKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackHistorySlotTest {

    @Test
    fun `WebDAV mediaKey 只分割连接 id 后的第一个冒号`() {
        assertEquals(
            ParsedWebDavKey("connection-id", "/动漫/标题:特别篇 + [01].mkv"),
            parseWebDavMediaKey("webdav:connection-id:/动漫/标题:特别篇 + [01].mkv"),
        )
        assertNull(parseWebDavMediaKey("webdav:connection-id:"))
    }

    @Test
    fun `历史 URL 会移除凭据并保留编码路径查询和片段`() {
        assertEquals(
            "https://example.com:8443/webdav/%E5%8A%A8%E6%BC%AB.mkv?token=x#part",
            removeUrlCredentials(
                "https://user:secret@example.com:8443/webdav/%E5%8A%A8%E6%BC%AB.mkv?token=x#part",
            ),
        )
        assertNull(removeUrlCredentials("file:///C:/Anime/test.mkv"))
    }

    @Test
    fun `URL 回退选择最长匹配挂载点`() {
        val root = connection("root", "https://example.com")
        val webdav = connection("webdav", "https://example.com/webdav")
        assertEquals(
            webdav,
            findConnectionForUrl(listOf(root, webdav), "https://example.com/webdav/Anime/E01.mkv"),
        )
    }

    @Test
    fun `Jellyfin 历史记录用稳定媒体键重建无秘密播放定位`() {
        val connection = mediaServerConnection(id = "current-connection-id")
        assertEquals(
            MediaServerPlaybackLocator(
                connectionId = connection.id,
                itemId = "item:episode-01",
                title = "第一集",
                startPositionMs = 42_000L,
            ),
            desktopJellyfinPlaybackLocator(
                mediaKey = mediaServerHistoryMediaKey(
                    MediaServerVendor.JELLYFIN,
                    connection.serverId,
                    connection.userId,
                    "item:episode-01",
                ),
                title = "第一集",
                positionMs = 42_000L,
                completed = false,
                connections = listOf(connection),
            ),
        )
    }

    @Test
    fun `Jellyfin 已完成记录从头播放且桌面仍不开放 Emby`() {
        assertEquals(
            0L,
            desktopJellyfinPlaybackLocator(
                mediaKey = "jellyfin:connection-id:item-id",
                title = "已完成",
                positionMs = 90_000L,
                completed = true,
                connections = listOf(mediaServerConnection(id = "connection-id")),
            )?.startPositionMs,
        )
        assertNull(
            desktopJellyfinPlaybackLocator(
                mediaKey = "emby:connection-id:item-id",
                title = "Emby",
                positionMs = 42_000L,
                completed = false,
                connections = emptyList(),
            ),
        )
    }

    private fun connection(id: String, baseUrl: String) = WebDavConnection(
        id = id,
        name = id,
        baseUrl = baseUrl,
        username = "user",
        password = "secret",
    )

    private fun mediaServerConnection(id: String) = MediaServerConnectionSummary(
        id = id,
        vendor = MediaServerVendor.JELLYFIN,
        name = "Jellyfin",
        baseUrl = "https://media.example.test",
        serverId = "server-id",
        serverVersion = "10.11.11",
        userId = "user-id",
        username = "user",
        credentialUnavailable = false,
    )
}
