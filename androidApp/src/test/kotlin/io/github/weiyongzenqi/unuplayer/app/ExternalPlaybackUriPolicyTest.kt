package io.github.weiyongzenqi.unuplayer.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalPlaybackUriPolicyTest {

    @Test
    fun `content URI 原样透传并保留 contentUri`() {
        val target = resolveExternalPlaybackTarget(
            parts("content", "content://media/video/1", lastPathSegment = "video.mkv"),
            displayName = "",
        )

        assertEquals("content://media/video/1", target?.url)
        assertEquals("video.mkv", target?.title)
        assertEquals("content://media/video/1", target?.contentUri)
    }

    @Test
    fun `显示名优先于 URI 末段`() {
        val target = resolveExternalPlaybackTarget(
            parts("file", "file:///C:/media/video.mkv", path = "C:/media/video.mkv", lastPathSegment = "video.mkv"),
            displayName = "provider-name.mkv",
        )

        assertEquals("C:/media/video.mkv", target?.url)
        assertEquals("provider-name.mkv", target?.title)
        assertNull(target?.contentUri)
    }

    @Test
    fun `HTTP 和 HTTPS 允许不带 userInfo 的地址`() {
        assertEquals(
            "https://example.invalid/video.mkv",
            resolveExternalPlaybackTarget(
                parts("https", "https://example.invalid/video.mkv", lastPathSegment = "video.mkv"),
            )?.url,
        )
        assertEquals(
            "http://192.168.1.20/video.mkv",
            resolveExternalPlaybackTarget(
                parts("http", "http://192.168.1.20/video.mkv", lastPathSegment = "video.mkv"),
            )?.url,
        )
    }

    @Test
    fun `HTTP(S) userInfo 始终拒绝`() {
        assertNull(
            resolveExternalPlaybackTarget(
                parts("https", "https://user:secret@example.invalid/video.mkv", userInfo = "user:secret"),
            ),
        )
    }

    @Test
    fun `不在白名单的 scheme 拒绝`() {
        assertNull(resolveExternalPlaybackTarget(parts("javascript", "javascript:alert(1)")))
        assertNull(resolveExternalPlaybackTarget(parts(null, "video.mkv")))
    }

    private fun parts(
        scheme: String?,
        rawUri: String,
        path: String? = null,
        lastPathSegment: String? = null,
        userInfo: String? = null,
    ) = ExternalPlaybackUriParts(scheme, rawUri, path, lastPathSegment, userInfo)
}
