package io.github.weiyongzenqi.unuplayer.library

import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeThumbTlsPolicyTest {
    @Test
    fun `Android 默认 CA 缺失时拒绝而非降级`() {
        assertEquals(
            EpisodeThumbTlsPolicy.Reject,
            resolveEpisodeThumbTlsPolicy(true, false, true, null),
        )
    }

    @Test
    fun `用户授权后两平台均关闭验证`() {
        assertEquals(
            EpisodeThumbTlsPolicy.Insecure,
            resolveEpisodeThumbTlsPolicy(true, true, true, "ignored.pem"),
        )
        assertEquals(
            EpisodeThumbTlsPolicy.Insecure,
            resolveEpisodeThumbTlsPolicy(true, true, false, null),
        )
    }

    @Test
    fun `Android 有 CA 与桌面系统 CA 均保持验证`() {
        assertEquals(
            EpisodeThumbTlsPolicy.Verify("system.pem"),
            resolveEpisodeThumbTlsPolicy(true, false, true, "system.pem"),
        )
        assertEquals(
            EpisodeThumbTlsPolicy.Verify(null),
            resolveEpisodeThumbTlsPolicy(true, false, false, null),
        )
    }

    @Test
    fun `非 HTTPS 不设置 TLS 选项`() {
        assertEquals(
            EpisodeThumbTlsPolicy.NotHttps,
            resolveEpisodeThumbTlsPolicy(false, true, true, null),
        )
    }
}
