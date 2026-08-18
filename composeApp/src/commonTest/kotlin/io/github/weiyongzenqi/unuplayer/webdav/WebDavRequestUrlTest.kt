package io.github.weiyongzenqi.unuplayer.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * buildWebDavRequestUrl 的 URL 构造回归锚。
 *
 * 背景(P1-1): 服务器以绝对 URL href 表示子目录(RFC 4918 合法, 部分 NAS/反代常见)时,
 * 递归搜索曾把绝对 URL 当相对路径拼成 "base + https://host/..." 双层路径致 404,
 * 且 404 被搜索层静默吞错, 用户只见"搜索完成 0 结果"。
 */
class WebDavRequestUrlTest {

    @Test
    fun `绝对同源 href 原样返回不拼接`() {
        val result = buildWebDavRequestUrl(
            baseUrl = "https://host/dav",
            path = "https://host/dav/Anime/",
        )
        assertEquals("https://host/dav/Anime/", result)
    }

    @Test
    fun `绝对同源 href 带显式默认端口视为同源`() {
        val result = buildWebDavRequestUrl(
            baseUrl = "https://host/dav",
            path = "https://host:443/dav/x.mkv",
        )
        assertEquals("https://host:443/dav/x.mkv", result)
    }

    @Test
    fun `绝对异源 href 拒绝以防凭据泄漏`() {
        val error = assertFailsWith<WebDavException> {
            buildWebDavRequestUrl(baseUrl = "https://host/dav", path = "https://evil/dav/x")
        }
        assertTrue(error.message.orEmpty().contains("跨源"), "错误文案应说明跨源拒绝: $error")
    }

    @Test
    fun `绝对 href 协议降级视为异源拒绝`() {
        assertFailsWith<WebDavException> {
            buildWebDavRequestUrl(baseUrl = "https://host/dav", path = "http://host/dav/x")
        }
    }

    @Test
    fun `非 http 绝对 URL 视为异源拒绝`() {
        assertFailsWith<WebDavException> {
            buildWebDavRequestUrl(baseUrl = "https://host/dav", path = "ftp://host/dav/x")
        }
    }

    @Test
    fun `同源 href 带 userinfo 注入拒绝`() {
        // N-3: 同源判定只比 scheme+host+port, userinfo 会被放行并把注入文本带进播放 URL/记录键;
        // 与 MediaServerUrlPolicy 同款做法一律拒绝。
        val error = assertFailsWith<WebDavException> {
            buildWebDavRequestUrl(baseUrl = "https://host/dav", path = "https://injected:token@host/dav/x")
        }
        assertTrue(error.message.orEmpty().contains("凭据"), "错误文案应说明凭据拒绝: $error")
    }

    // 以下为既有行为的回归锚(修复不得改变)

    @Test
    fun `浏览用 mount-relative 路径拼接 base`() {
        assertEquals(
            "https://host/dav/anime/",
            buildWebDavRequestUrl(baseUrl = "https://host/dav", path = "/anime/"),
        )
    }

    @Test
    fun `搜索用服务器绝对路径含 mount 时用 origin 拼接`() {
        assertEquals(
            "https://host/dav/anime/Season 1/",
            buildWebDavRequestUrl(baseUrl = "https://host/dav", path = "/dav/anime/Season 1/"),
        )
    }

    @Test
    fun `相对路径无前导斜杠自动补齐`() {
        assertEquals(
            "https://host/dav/anime",
            buildWebDavRequestUrl(baseUrl = "https://host/dav", path = "anime"),
        )
    }

    @Test
    fun `base 无挂载点时绝对服务器路径用 origin 拼接`() {
        assertEquals(
            "https://host/anime/",
            buildWebDavRequestUrl(baseUrl = "https://host", path = "/anime/"),
        )
    }
}
