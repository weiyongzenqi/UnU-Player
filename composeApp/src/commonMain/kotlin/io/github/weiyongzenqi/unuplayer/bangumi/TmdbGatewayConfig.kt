package io.github.weiyongzenqi.unuplayer.bangumi

import io.github.weiyongzenqi.unuplayer.core.security.decodeObfuscatedClientValue

/**
 * TMDB Gateway 内置配置。
 *
 * 客户端持有的 API key 可被反编译提取；这里沿用弹弹play代理的混淆方式，目标只是避免
 * 明文被简单 grep 或批量爬取。真正的滥用防护依赖网关侧限流、审计和 key 吊销。
 */
internal object TmdbGatewayConfig {
    private const val OBFUSCATED_BASE_URL_HEX =
        "5f434347440d1818435a53555456545f521907000506070705194f4e4d"
    private const val OBFUSCATED_API_KEY_HEX =
        "42594268465844457e524271627f5173687372650e644741415300407179655355030e5a7b02567441435e660e5903"

    /** XOR mask，值为 0x37，但不以单一明文常量保存。 */
    private val mask: Int get() = (0x3 shl 4) or 0x7

    fun baseUrl(): String = decodeObfuscatedClientValue(OBFUSCATED_BASE_URL_HEX, mask)

    fun apiKey(): String = decodeObfuscatedClientValue(OBFUSCATED_API_KEY_HEX, mask)
}
