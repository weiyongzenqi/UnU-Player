package io.github.weiyongzenqi.unuplayer.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `无认证临时地址使用有限重定向选项`() {
        val config = PlayerConfig(httpRedirectPolicy = HttpRedirectPolicy.FOLLOW_LIMITED)

        assertEquals("max_redirects=5", config.streamLavfOptions())
    }

    @Test
    fun `媒体服务器认证头强制拒绝重定向`() {
        listOf(
            mapOf("Authorization" to "MediaBrowser Token=secret"),
            mapOf("authorization" to "Emby Token=secret"),
            mapOf("AUTHORIZATION" to "  mediabrowser Token=secret"),
            mapOf("X-Emby-Token" to "secret"),
            mapOf("x-mediabrowser-token" to "secret"),
            mapOf("X-Emby-Authorization" to "MediaBrowser Token=secret"),
            mapOf("x-mediabrowser-authorization" to "Emby Token=secret"),
        ).forEach { headers ->
            // 修复前: require 抛异常; 修复后: 生效策略无条件 DENY(不崩溃、播放安全)
            val config = PlayerConfig(httpHeaders = headers, httpRedirectPolicy = HttpRedirectPolicy.FOLLOW)
            assertEquals("max_redirects=0", config.streamLavfOptions(), "认证头存在即拒绝重定向")
        }
    }

    @Test
    fun `WebDAV Basic 认证强制拒绝重定向`() {
        val config = PlayerConfig(httpHeaders = mapOf("Authorization" to "Basic secret"))
        assertEquals("max_redirects=0", config.streamLavfOptions(), "WebDAV Basic 口令不得随重定向转发")
        assertFalse(config.toString().contains("secret"))
    }

    @Test
    fun `无认证头时保留显式重定向策略`() {
        // 无敏感头: 默认 FOLLOW(null), 显式 DENY 才映射
        assertNull(PlayerConfig().streamLavfOptions())
        assertEquals("max_redirects=0", PlayerConfig(httpRedirectPolicy = HttpRedirectPolicy.DENY).streamLavfOptions())
    }

    @Test
    fun `集照与播放器共享认证头序列化和重定向选项`() {
        val options = buildMpvHttpOptions(
            headers = mapOf("Authorization" to "Basic abc,def"),
            redirectPolicy = HttpRedirectPolicy.FOLLOW,
        )
        assertEquals("Authorization: %13%Basic abc,def", options.headerFields)
        assertEquals("max_redirects=0", options.streamLavfOptions)
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
