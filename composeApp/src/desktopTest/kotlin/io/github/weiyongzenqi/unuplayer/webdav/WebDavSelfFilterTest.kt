package io.github.weiyongzenqi.unuplayer.webdav

import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class WebDavSelfFilterTest {

    @Test
    fun `self 不在首项时只删除真正的请求目录`() {
        val entries = listOf(
            entry("第一部番剧", "/webdav/Anime/第一部番剧/"),
            entry("Anime", "/webdav/Anime/"),
            entry("第二部番剧", "/webdav/Anime/第二部番剧/"),
        )

        val filtered = filterWebDavSelfEntry("https://example.com/webdav", "/Anime", entries)

        assertEquals(listOf("第一部番剧", "第二部番剧"), filtered.map { it.name })
    }

    @Test
    fun `服务器省略 self 时不会丢掉第一个真实条目`() {
        val entries = listOf(
            entry("第一部番剧", "/webdav/Anime/第一部番剧/"),
            entry("第二部番剧", "/webdav/Anime/第二部番剧/"),
        )

        val filtered = filterWebDavSelfEntry("https://example.com/webdav", "/Anime", entries)

        assertEquals(entries, filtered)
    }

    @Test
    fun `兼容绝对 URL 与中文 percent encoding 的 self`() {
        val entries = listOf(
            entry("动漫", "HTTPS://EXAMPLE.COM/webdav/%E5%8A%A8%E6%BC%AB/?ignored=1"),
            entry("番剧 A", "/webdav/%E5%8A%A8%E6%BC%AB/A/"),
        )

        val filtered = filterWebDavSelfEntry("https://example.com/webdav", "/动漫/", entries)

        assertEquals(listOf("番剧 A"), filtered.map { it.name })
    }

    @Test
    fun `跨源绝对 href 被同源校验拒绝`() {
        val entries = listOf(
            entry("异源", "http://evil.example/x.mkv"),
        )

        // E-P1-2: 跨源绝对 href 直接抛 WebDavException, 保护 Basic 凭据不发往第三方/明文链路。
        assertFailsWith<WebDavException> {
            filterWebDavSelfEntry("https://example.com/webdav", "/动漫/", entries)
        }
    }

    @Test
    fun `显式默认端口与省略默认端口视为同源`() {
        val entries = listOf(
            entry("动漫", "https://example.com:443/webdav/%E5%8A%A8%E6%BC%AB/"),
            entry("番剧 A", "/webdav/%E5%8A%A8%E6%BC%AB/A/"),
        )

        val filtered = filterWebDavSelfEntry("https://example.com/webdav", "/动漫/", entries)

        assertEquals(listOf("番剧 A"), filtered.map { it.name })
    }

    @Test
    fun `跨源异常不回显服务端敏感 href`() {
        val sensitiveHref = "https://user:secret@evil.example/x.mkv?token=secret-token"

        val error = assertFailsWith<WebDavException> {
            filterWebDavSelfEntry(
                "https://example.com/webdav",
                "/动漫/",
                listOf(entry("异源", sensitiveHref)),
            )
        }

        assertFalse(error.message.orEmpty().contains("secret"))
        assertFalse(error.message.orEmpty().contains("token"))
    }

    @Test
    fun `挂载点根目录的相对 self 可以识别`() {
        val entries = listOf(
            entry("根目录", "/"),
            entry("Anime", "/webdav/Anime/"),
        )

        val filtered = filterWebDavSelfEntry("https://example.com/webdav", "/", entries)

        assertEquals(listOf("Anime"), filtered.map { it.name })
    }

    private fun entry(name: String, path: String) = MediaEntry(
        name = name,
        path = path,
        isDirectory = true,
    )
}
