package io.github.weiyongzenqi.unuplayer.core.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveTextRedactorTest {

    @Test
    fun `Authorization 敏感字段与 URL userInfo 会脱敏`() {
        val raw = "Authorization: Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==; " +
            "authorization=Bearer bearer-token password=plain-secret " +
            "AppSecret: app-secret https://user:pass@example.invalid/dav"

        val redacted = redactSensitiveText(raw)

        listOf("QWxhZGRpbjpvcGVuIHNlc2FtZQ==", "bearer-token", "plain-secret", "app-secret", "user:pass")
            .forEach { secret -> assertFalse(redacted.contains(secret), "仍包含敏感文本：$secret") }
        assertTrue(redacted.contains("Authorization: Basic <redacted>"))
        assertTrue(redacted.contains("https://<redacted>@example.invalid/dav"))
    }

    @Test
    fun `JSON 风格敏感字段会保留结构但移除值`() {
        val redacted = redactSensitiveText("{\"password\":\"secret-1\",\"appSecret\":\"secret-2\"}")

        assertFalse(redacted.contains("secret-1"))
        assertFalse(redacted.contains("secret-2"))
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test
    fun `媒体服务器认证头和 URL token 会脱敏`() {
        val raw = "Authorization: MediaBrowser Client=\"UnU\", Token=\"jellyfin-secret\"; " +
            "X-Emby-Token: emby-secret " +
            "https://media.example.test/reverse/Videos/item/stream.mp4?api_key=query-secret&access_token=query-secret-2&" +
            "PlaySessionId=play-session-secret"

        val redacted = redactSensitiveText(raw)

        listOf("jellyfin-secret", "emby-secret", "query-secret", "query-secret-2", "play-session-secret")
            .forEach { secret -> assertFalse(redacted.contains(secret), "仍包含媒体服务器凭据：$secret") }
        assertTrue(redacted.contains("Token=\"<redacted>"))
        assertTrue(redacted.contains("X-Emby-Token: <redacted>"))
        assertTrue(redacted.contains("<redacted-media-url>"))
        assertFalse(redacted.contains("media.example.test"))
    }
}
