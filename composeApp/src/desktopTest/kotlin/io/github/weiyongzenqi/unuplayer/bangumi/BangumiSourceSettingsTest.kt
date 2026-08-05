package io.github.weiyongzenqi.unuplayer.bangumi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BangumiSourceSettingsTest {
    @Test
    fun `HTTPS 基础地址会去除空白和末尾斜杠`() {
        val result = parseHttpsBaseUrl("  https://mirror.example.test/p1///  ")

        assertEquals("https://mirror.example.test/p1", result.normalizedUrl)
        assertNull(result.errorMessage)
    }

    @Test
    fun `基础地址拒绝不安全或含请求参数的值`() {
        listOf(
            "http://mirror.example.test",
            "https://user:password@mirror.example.test",
            "https://mirror.example.test/p1?token=secret",
            "https://mirror.example.test/p1#fragment",
            "",
        ).forEach { value ->
            assertFalse(parseHttpsBaseUrl(value).isValid, value)
        }
    }

    @Test
    fun `官方和 bangumi lol 预设解析为完整端点矩阵`() {
        val official = resolve(BangumiSourcePreset.OFFICIAL)
        assertEquals("https://bgm.tv", official.siteBaseUrl)
        assertEquals("https://api.bgm.tv", official.apiBaseUrl)
        assertEquals("https://next.bgm.tv/p1", official.nextApiBaseUrl)
        assertEquals("https://lain.bgm.tv", official.imageBaseUrl)

        val mirror = resolve(BangumiSourcePreset.BANGUMI_LOL)
        assertEquals("https://bangumi.lol", mirror.siteBaseUrl)
        assertEquals("https://api.bangumi.lol", mirror.apiBaseUrl)
        assertEquals("https://next.bangumi.lol/p1", mirror.nextApiBaseUrl)
        assertEquals("https://lain.bangumi.lol", mirror.imageBaseUrl)
        assertTrue(official.identity != mirror.identity)
    }

    @Test
    fun `头像允许白名单 HTTPS 主机的缓存参数但拒绝跨主机和不安全 URL`() {
        val hosts = setOf("lain.bgm.tv")

        assertTrue(isAllowedBangumiAvatarUrl("https://lain.bgm.tv/pic/user/s/1.jpg?r=1&hd=1", hosts))
        assertTrue(isAllowedBangumiAvatarUrl("https://LAIN.BGM.TV/pic/user/s/1.jpg", hosts))
        assertFalse(isAllowedBangumiAvatarUrl("http://lain.bgm.tv/pic/user/s/1.jpg", hosts))
        assertFalse(isAllowedBangumiAvatarUrl("https://evil.example.test/pic/user/s/1.jpg", hosts))
        assertFalse(isAllowedBangumiAvatarUrl("https://user@lain.bgm.tv/pic/user/s/1.jpg", hosts))
        assertFalse(isAllowedBangumiAvatarUrl("https://lain.bgm.tv/pic/user/s/1.jpg#fragment", hosts))
    }

    private fun resolve(preset: BangumiSourcePreset): BangumiEndpointConfig = resolveBangumiEndpoints(
        preset = preset,
        customSiteBaseUrl = "https://unused.example.test",
        customApiBaseUrl = "https://unused.example.test",
        customNextApiBaseUrl = "https://unused.example.test/p1",
        customImageBaseUrl = "https://unused.example.test",
    )
}
