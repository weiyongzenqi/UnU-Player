package io.github.weiyongzenqi.unuplayer.webdav

import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import kotlin.test.Test
import kotlin.test.assertEquals

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
