package io.github.weiyongzenqi.unuplayer.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PlayerConfigTest {

    @Test
    fun `默认保留现有重定向行为`() {
        assertNull(PlayerConfig().streamLavfOptions())
    }

    @Test
    fun `拒绝重定向映射到 FFmpeg 零次重定向选项`() {
        val config = PlayerConfig(httpRedirectPolicy = HttpRedirectPolicy.DENY)

        assertEquals("max_redirects=0", config.streamLavfOptions())
    }

    @Test
    fun `媒体服务器认证头不能配置为跟随重定向`() {
        listOf(
            mapOf("Authorization" to "MediaBrowser Token=secret"),
            mapOf("authorization" to "Emby Token=secret"),
            mapOf("AUTHORIZATION" to "  mediabrowser Token=secret"),
            mapOf("X-Emby-Token" to "secret"),
            mapOf("x-mediabrowser-token" to "secret"),
            mapOf("X-Emby-Authorization" to "MediaBrowser Token=secret"),
            mapOf("x-mediabrowser-authorization" to "Emby Token=secret"),
        ).forEach { headers ->
            val error = assertFailsWith<IllegalArgumentException> {
                PlayerConfig(httpHeaders = headers)
            }
            assertEquals("媒体服务器认证头必须拒绝 HTTP 重定向", error.message)
        }
    }

    @Test
    fun `WebDAV Basic 认证保留现有重定向策略`() {
        val config = PlayerConfig(httpHeaders = mapOf("Authorization" to "Basic secret"))

        assertNull(config.streamLavfOptions())
        assertFalse(config.toString().contains("secret"))
    }

    @Test
    fun `媒体服务器配置默认文本不展开认证头`() {
        val config = PlayerConfig(
            httpHeaders = mapOf("Authorization" to "MediaBrowser Token=canary-token"),
            httpRedirectPolicy = HttpRedirectPolicy.DENY,
        )

        assertFalse(config.toString().contains("canary-token"))
    }
}
