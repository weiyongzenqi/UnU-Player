package io.github.weiyongzenqi.unuplayer.bangumi

import io.github.weiyongzenqi.unuplayer.core.security.GATEWAY_CLIENT_MASK
import io.github.weiyongzenqi.unuplayer.core.security.OBFUSCATED_GATEWAY_API_KEY_HEX
import io.github.weiyongzenqi.unuplayer.core.security.decodeObfuscatedClientValue

/**
 * TMDB Gateway 内置配置。
 *
 * 客户端持有的 API key 可被反编译提取；这里沿用弹弹play代理的混淆方式，目标只是避免
 * 明文被简单 grep 或批量爬取。真正的滥用防护依赖网关侧限流、审计和 key 吊销。
 */
internal object TmdbGatewayConfig {
    private const val OBFUSCATED_BASE_URL_HEX =
        "5f434347440d18184259425056435240564e1907000506070705194f4e4d18435a5355"


    fun baseUrl(): String = decodeObfuscatedClientValue(OBFUSCATED_BASE_URL_HEX, GATEWAY_CLIENT_MASK)

    fun apiKey(): String = decodeObfuscatedClientValue(OBFUSCATED_GATEWAY_API_KEY_HEX, GATEWAY_CLIENT_MASK)
}
