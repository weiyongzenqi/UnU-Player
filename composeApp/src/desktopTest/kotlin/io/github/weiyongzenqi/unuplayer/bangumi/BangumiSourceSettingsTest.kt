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
    fun `官方与自建网关预设解析为完整端点矩阵`() {
        val official = resolve(BangumiSourcePreset.OFFICIAL)
        assertEquals("https://bgm.tv", official.siteBaseUrl)
        assertEquals("https://api.bgm.tv", official.apiBaseUrl)
        assertEquals("https://next.bgm.tv/p1", official.nextApiBaseUrl)
        assertEquals("https://lain.bgm.tv", official.imageBaseUrl)

        // GATEWAY: api/next 同 base(路由由中性路径区分), 图片走网关 /i; 站点保持官方外链
        val gateway = resolve(BangumiSourcePreset.GATEWAY)
        assertEquals("https://bgm.tv", gateway.siteBaseUrl)
        assertEquals(BangumiGatewayConfig.apiBaseUrl(), gateway.apiBaseUrl)
        assertEquals(gateway.apiBaseUrl, gateway.nextApiBaseUrl)
        assertEquals(BangumiGatewayConfig.imageBaseUrl(), gateway.imageBaseUrl)
        assertTrue(gateway.imageBaseUrl.endsWith("/i"))
        assertEquals("UnU Gateway转发bangumi", gateway.sourceLabel)
        // 头像/内容图片白名单主机自动跟随网关域(网关重写 lain URL 为本域)
        assertEquals(
            setOf(io.ktor.http.Url(gateway.imageBaseUrl).host.lowercase()),
            gateway.allowedAvatarHosts,
        )
        assertTrue(official.identity != gateway.identity)
        // 网关端点注入辅助: 仅 GATEWAY 预设产出端点, 其余预设为 null
        assertTrue(gateway.gatewayEndpointOrNull() != null)
        assertNull(official.gatewayEndpointOrNull())
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

    @Test
    fun `内容图片URL按白名单和协议决定加载策略`() {
        val hosts = setOf("lain.bgm.tv")

        assertEquals(BangumiImageUrlPolicy.AUTO_LOAD, bangumiContentImageUrlPolicy("https://lain.bgm.tv/pic/1.jpg", hosts))
        assertEquals(BangumiImageUrlPolicy.CLICK_TO_LOAD, bangumiContentImageUrlPolicy("http://lain.bgm.tv/pic/1.jpg", hosts))
        assertEquals(BangumiImageUrlPolicy.CLICK_TO_LOAD, bangumiContentImageUrlPolicy("https://other.example.test/pic/1.jpg", hosts))
        assertEquals(BangumiImageUrlPolicy.REJECT, bangumiContentImageUrlPolicy("javascript:alert(1)", hosts))
        assertEquals(BangumiImageUrlPolicy.REJECT, bangumiContentImageUrlPolicy("file:///tmp/a.png", hosts))
        assertEquals(BangumiImageUrlPolicy.REJECT, bangumiContentImageUrlPolicy("https://user:pass@lain.bgm.tv/pic/1.jpg", hosts))
        assertEquals(BangumiImageUrlPolicy.REJECT, bangumiContentImageUrlPolicy("https://lain.bgm.tv/pic/1.jpg#fragment", hosts))
        assertEquals(BangumiImageUrlPolicy.REJECT, bangumiContentImageUrlPolicy(null, hosts))
        assertEquals(BangumiImageUrlPolicy.REJECT, bangumiContentImageUrlPolicy("", hosts))
        assertEquals(BangumiImageUrlPolicy.REJECT, bangumiContentImageUrlPolicy("   ", hosts))
        assertEquals(BangumiImageUrlPolicy.REJECT, bangumiContentImageUrlPolicy("https://lain.bgm.tv/" + "a".repeat(3000), hosts))
    }

    @Test
    fun `旧日历封面地址按数据源升级或改写`() {
        val official = resolve(BangumiSourcePreset.OFFICIAL)
        val gateway = resolve(BangumiSourcePreset.GATEWAY)

        assertEquals(
            "https://lain.bgm.tv/pic/cover/l/test.jpg?r=1",
            official.resolveImageUrl("http://lain.bgm.tv/pic/cover/l/test.jpg?r=1"),
        )
        assertEquals(
            gateway.imageBaseUrl + "/pic/cover/l/test.jpg?r=1",
            gateway.resolveImageUrl("//lain.bgm.tv/pic/cover/l/test.jpg?r=1"),
        )
        assertEquals(
            "https://cdn.example.test/poster.jpg",
            gateway.resolveImageUrl("https://cdn.example.test/poster.jpg"),
        )
    }

    private fun resolve(preset: BangumiSourcePreset): BangumiEndpointConfig = resolveBangumiEndpoints(
        preset = preset,
        customSiteBaseUrl = "https://unused.example.test",
        customApiBaseUrl = "https://unused.example.test",
        customNextApiBaseUrl = "https://unused.example.test/p1",
        customImageBaseUrl = "https://unused.example.test",
    )
}
