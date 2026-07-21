package io.github.weiyongzenqi.unuplayer.ui.settings

import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
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

    private fun connection(id: String, baseUrl: String) = WebDavConnection(
        id = id,
        name = id,
        baseUrl = baseUrl,
        username = "user",
        password = "secret",
    )
}
