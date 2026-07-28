package io.github.weiyongzenqi.unuplayer.danmaku.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * [DanmakuMatcher.isValidTmdbMatchPattern] 与 [DanmakuMatcher.extractTmdbId] 的 D-V04 ReDoS 兜底测试。
 *
 * 纯函数路径, 不起 HTTP server: 校验器静态调用; extractTmdbId 只用 DanmakuMatcher 实例的正则逻辑,
 * 构造 DandanplayApi 仅持有 HttpClient 不发请求。
 */
class DanmakuMatcherPatternValidationTest {

    @Test
    fun `isValidTmdbMatchPattern 长度超限判无效`() {
        val overLimit = "a".repeat(DanmakuMatcher.TMDB_PATTERN_MAX_LENGTH + 1)
        assertFalse(DanmakuMatcher.isValidTmdbMatchPattern(overLimit))
    }

    @Test
    fun `isValidTmdbMatchPattern 编译失败判无效`() {
        // 括号不匹配, Regex 编译抛异常
        assertFalse(DanmakuMatcher.isValidTmdbMatchPattern("tmdb(id[=-](\\d+)"))
    }

    @Test
    fun `isValidTmdbMatchPattern 正常与边界长度通过`() {
        assertTrue(DanmakuMatcher.isValidTmdbMatchPattern("tmdb(id)?[=-](\\d+)"))
        // 恰好 64 字符(59 个字面量 + 5 字符捕获组): 边界值应通过
        val boundary = "a".repeat(DanmakuMatcher.TMDB_PATTERN_MAX_LENGTH - 5) + "(\\d+)"
        assertEquals(DanmakuMatcher.TMDB_PATTERN_MAX_LENGTH, boundary.length)
        assertTrue(DanmakuMatcher.isValidTmdbMatchPattern(boundary))
    }

    @Test
    fun `extractTmdbId 超长 pattern 直接返回 null 不挂起`() {
        val matcher = newMatcher()
        val overLimit = "a".repeat(DanmakuMatcher.TMDB_PATTERN_MAX_LENGTH + 1)
        assertNull(matcher.extractTmdbId("tmdb=12345", overLimit))
    }

    @Test
    fun `extractTmdbId 正常提取语义不变`() {
        val matcher = newMatcher()
        assertEquals(
            12345L,
            matcher.extractTmdbId("https://dav.example.com/动漫/tmdb=12345/番名 S01E01.mkv", "tmdb(id)?[=-](\\d+)"),
        )
        assertNull(matcher.extractTmdbId("无 id 的普通文件名.mkv", "tmdb(id)?[=-](\\d+)"))
    }

    @Test
    fun `extractTmdbId 超长输入截断后仍命中首段 tmdbId`() {
        val matcher = newMatcher()
        // tmdbId 在首段, 其后 300 字符噪声; 截断到前 256 字符不影响命中(截断无损功能)
        val input = "folder-tmdb=42/" + "x".repeat(300)
        assertEquals(42L, matcher.extractTmdbId(input, "tmdb[=-](\\d+)"))
    }

    @Test
    fun `extractTmdbId 病态表达式在线性时间内返回`() {
        val matcher = newMatcher()
        val elapsed = measureTime {
            assertNull(matcher.extractTmdbId("a".repeat(255) + "!", "(a+)+$"))
        }
        assertTrue(elapsed.inWholeMilliseconds < 1_000, "匹配耗时 $elapsed")
    }

    private fun newMatcher(): DanmakuMatcher =
        DanmakuMatcher(DandanplayApi(appId = "test", appSecret = "secret", baseUrl = "http://127.0.0.1:9"))
}
