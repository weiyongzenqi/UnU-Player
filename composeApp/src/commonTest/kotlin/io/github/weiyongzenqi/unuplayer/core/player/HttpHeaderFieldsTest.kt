package io.github.weiyongzenqi.unuplayer.core.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * mpv http-header-fields 序列化回归锚(D-2)。
 *
 * mpv keyvalue list 解析器无反斜杠转义; 值内分隔符用 read_subparam 的 %len%
 * 字面量形式无损表达。现有 Jellyfin 头(含 '=' 与中间 '"')实机验证不破坏解析,
 * 故不转义——本测试同时钉住"现有输出逐字节不变"。
 */
class HttpHeaderFieldsTest {

    @Test
    fun `空表输出空串`() {
        assertEquals("", serializeHttpHeaderFields(emptyMap()))
    }

    @Test
    fun `普通值原样输出`() {
        assertEquals(
            "Authorization: Bearer abc",
            serializeHttpHeaderFields(mapOf("Authorization" to "Bearer abc")),
        )
    }

    @Test
    fun `含等号与中间引号不转义(实机 Jellyfin 锚)`() {
        assertEquals(
            "Authorization: MediaBrowser Token=\"abc123\"",
            serializeHttpHeaderFields(mapOf("Authorization" to "MediaBrowser Token=\"abc123\"")),
        )
    }

    @Test
    fun `值含逗号用百分号字面量转义`() {
        assertEquals(
            "Cookie: %7%a=1,b=2",
            serializeHttpHeaderFields(mapOf("Cookie" to "a=1,b=2")),
        )
    }

    @Test
    fun `值含冒号转义防被当键值分隔`() {
        assertEquals(
            "X-Test: %5%a:b:c",
            serializeHttpHeaderFields(mapOf("X-Test" to "a:b:c")),
        )
    }

    @Test
    fun `前导引号与百分号转义防触发 read_subparam 模式`() {
        assertEquals(
            "X-Test: %7%\"quoted",
            serializeHttpHeaderFields(mapOf("X-Test" to "\"quoted")),
        )
        assertEquals(
            "X-Test: %6%%a,b=c",
            serializeHttpHeaderFields(mapOf("X-Test" to "%a,b=c")),
        )
    }

    @Test
    fun `多条目逗号连接且互不污染`() {
        assertEquals(
            "Authorization: Bearer abc,Cookie: %7%a=1,b=2",
            serializeHttpHeaderFields(
                mapOf(
                    "Authorization" to "Bearer abc",
                    "Cookie" to "a=1,b=2",
                ),
            ),
        )
    }

    @Test
    fun `多字节字符按字节长度转义`() {
        // "中,文": 中=3 字节 + ','=1 + 文=3 = 7 字节
        assertEquals(
            "X-Test: %7%中,文",
            serializeHttpHeaderFields(mapOf("X-Test" to "中,文")),
        )
    }

    @Test
    fun `空值保持空`() {
        assertEquals("X-Test: ", serializeHttpHeaderFields(mapOf("X-Test" to "")))
    }
}
