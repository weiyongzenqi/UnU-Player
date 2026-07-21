package io.github.weiyongzenqi.unuplayer.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavUrlPolicyTest {

    @Test
    fun `HTTPS 地址有效且不需要明文确认`() {
        val result = validateWebDavBaseUrl("  https://example.invalid/dav/  ")

        assertTrue(result.isValid)
        assertEquals("https://example.invalid/dav", result.normalizedUrl)
        assertFalse(result.requiresCleartextConfirmation)
        assertNull(result.errorMessage)
    }

    @Test
    fun `HTTP 地址有效但必须单独确认`() {
        val result = validateWebDavBaseUrl("http://192.168.1.20:8080/webdav/")

        assertTrue(result.isValid)
        assertEquals("http://192.168.1.20:8080/webdav", result.normalizedUrl)
        assertTrue(result.requiresCleartextConfirmation)
    }

    @Test
    fun `本地主机与 IPv6 地址可用`() {
        assertTrue(validateWebDavBaseUrl("https://localhost/dav").isValid)
        assertTrue(validateWebDavBaseUrl("http://[fd00::1]:8080/dav").isValid)
    }

    @Test
    fun `空地址和缺少主机的地址无效`() {
        assertFalse(validateWebDavBaseUrl(" ").isValid)
        assertFalse(validateWebDavBaseUrl("https://").isValid)
    }

    @Test
    fun `缺少协议和非 HTTP 协议无效`() {
        assertFalse(validateWebDavBaseUrl("example.invalid/dav").isValid)
        assertFalse(validateWebDavBaseUrl("ftp://example.invalid/dav").isValid)
    }

    @Test
    fun `URL userInfo 始终拒绝`() {
        val result = validateWebDavBaseUrl("https://user:secret@example.invalid/dav")

        assertFalse(result.isValid)
        assertTrue(result.errorMessage.orEmpty().contains("user:password@"))
    }

    @Test
    fun `查询参数和片段不属于 WebDAV 基址`() {
        assertFalse(validateWebDavBaseUrl("https://example.invalid/dav?token=value").isValid)
        assertFalse(validateWebDavBaseUrl("https://example.invalid/dav#directory").isValid)
    }
}
