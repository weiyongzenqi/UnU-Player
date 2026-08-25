package io.github.weiyongzenqi.unuplayer.bangumi

import io.ktor.http.URLProtocol
import io.ktor.http.Url

enum class BangumiSourcePreset {
    OFFICIAL,
    GATEWAY,
    CUSTOM,
}

data class BangumiEndpointConfig(
    val preset: BangumiSourcePreset,
    val siteBaseUrl: String,
    val apiBaseUrl: String,
    val nextApiBaseUrl: String,
    val imageBaseUrl: String,
) {
    val sourceLabel: String
        get() = when (preset) {
            BangumiSourcePreset.OFFICIAL -> "Bangumi 官方"
            BangumiSourcePreset.GATEWAY -> "UnU Gateway转发bangumi"
            BangumiSourcePreset.CUSTOM -> "自定义 Bangumi 镜像"
        }

    val identity: String
        get() = listOf(preset.name, siteBaseUrl, apiBaseUrl, nextApiBaseUrl, imageBaseUrl).joinToString("|")

    val allowedAvatarHosts: Set<String>
        get() = setOfNotNull(parseHttpsBaseUrl(imageBaseUrl).normalizedUrl?.let { Url(it).host.lowercase() })
}

data class BangumiBaseUrlValidation(
    val normalizedUrl: String? = null,
    val errorMessage: String? = null,
) {
    val isValid: Boolean get() = normalizedUrl != null
}

fun resolveBangumiEndpoints(
    preset: BangumiSourcePreset,
    customSiteBaseUrl: String,
    customApiBaseUrl: String,
    customNextApiBaseUrl: String,
    customImageBaseUrl: String,
): BangumiEndpointConfig = when (preset) {
    BangumiSourcePreset.OFFICIAL -> OFFICIAL_BANGUMI_ENDPOINTS
    BangumiSourcePreset.GATEWAY -> GATEWAY_BANGUMI_ENDPOINTS
    BangumiSourcePreset.CUSTOM -> BangumiEndpointConfig(
        preset = preset,
        siteBaseUrl = requireValidBangumiBaseUrl(customSiteBaseUrl, "站点地址"),
        apiBaseUrl = requireValidBangumiBaseUrl(customApiBaseUrl, "v0 API 地址"),
        nextApiBaseUrl = requireValidBangumiBaseUrl(customNextApiBaseUrl, "Next API 地址"),
        imageBaseUrl = requireValidBangumiBaseUrl(customImageBaseUrl, "图片地址"),
    )
}

fun validateBangumiCustomEndpoints(
    siteBaseUrl: String,
    apiBaseUrl: String,
    nextApiBaseUrl: String,
    imageBaseUrl: String,
): String? = listOf(
    "站点地址" to siteBaseUrl,
    "v0 API 地址" to apiBaseUrl,
    "Next API 地址" to nextApiBaseUrl,
    "图片地址" to imageBaseUrl,
).firstNotNullOfOrNull { (label, value) ->
    parseHttpsBaseUrl(value).errorMessage?.let { "$label：$it" }
}

fun parseHttpsBaseUrl(value: String): BangumiBaseUrlValidation {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return BangumiBaseUrlValidation(errorMessage = "不能为空")
    if (trimmed.length > MAX_BANGUMI_BASE_URL_LENGTH) {
        return BangumiBaseUrlValidation(errorMessage = "长度不能超过 $MAX_BANGUMI_BASE_URL_LENGTH 个字符")
    }
    val parsed = runCatching { Url(trimmed) }.getOrNull()
        ?: return BangumiBaseUrlValidation(errorMessage = "格式无效")
    if (parsed.protocolOrNull != URLProtocol.HTTPS) {
        return BangumiBaseUrlValidation(errorMessage = "必须使用 https://")
    }
    if (parsed.host.isBlank()) return BangumiBaseUrlValidation(errorMessage = "缺少主机名")
    if (parsed.user != null || parsed.password != null) {
        return BangumiBaseUrlValidation(errorMessage = "不能包含 user:password@")
    }
    if (parsed.encodedQuery.isNotEmpty() || parsed.trailingQuery || '#' in trimmed) {
        return BangumiBaseUrlValidation(errorMessage = "不能包含查询参数或片段")
    }
    return BangumiBaseUrlValidation(normalizedUrl = trimmed.trimEnd('/'))
}

fun isAllowedBangumiAvatarUrl(value: String?, allowedHosts: Set<String>): Boolean {
    if (value.isNullOrBlank() || value.length > MAX_BANGUMI_AVATAR_URL_LENGTH || allowedHosts.isEmpty()) return false
    val parsed = runCatching { Url(value) }.getOrNull() ?: return false
    return parsed.protocolOrNull == URLProtocol.HTTPS &&
        parsed.host.lowercase() in allowedHosts &&
        parsed.user == null &&
        parsed.password == null &&
        '#' !in value
}

enum class BangumiImageUrlPolicy { REJECT, CLICK_TO_LOAD, AUTO_LOAD }

/**
 * 讨论版/吐槽箱正文里出现的图片 URL 加载策略:
 * - 无法安全解析(http/https、无凭据、无片段、长度受限)→ REJECT(不展示);
 * - 白名单主机且 https → AUTO_LOAD(直接加载);
 * - 其余可解析的 http(s) URL(含白名单 http 与外链)→ CLICK_TO_LOAD(用户点击才加载)。
 */
fun bangumiContentImageUrlPolicy(value: String?, allowedHosts: Set<String>): BangumiImageUrlPolicy {
    if (value.isNullOrBlank() || value.length > MAX_BANGUMI_CONTENT_IMAGE_URL_LENGTH) return BangumiImageUrlPolicy.REJECT
    val parsed = runCatching { Url(value) }.getOrNull() ?: return BangumiImageUrlPolicy.REJECT
    val protocol = parsed.protocolOrNull
    if (protocol != URLProtocol.HTTP && protocol != URLProtocol.HTTPS) return BangumiImageUrlPolicy.REJECT
    if (parsed.user != null || parsed.password != null) return BangumiImageUrlPolicy.REJECT
    if ('#' in value) return BangumiImageUrlPolicy.REJECT
    return when {
        parsed.host.lowercase() in allowedHosts && protocol == URLProtocol.HTTPS -> BangumiImageUrlPolicy.AUTO_LOAD
        else -> BangumiImageUrlPolicy.CLICK_TO_LOAD
    }
}

private fun requireValidBangumiBaseUrl(value: String, label: String): String {
    val validation = parseHttpsBaseUrl(value)
    return requireNotNull(validation.normalizedUrl) { "$label${validation.errorMessage ?: "无效"}" }
}

val OFFICIAL_BANGUMI_ENDPOINTS = BangumiEndpointConfig(
    preset = BangumiSourcePreset.OFFICIAL,
    siteBaseUrl = "https://bgm.tv",
    apiBaseUrl = "https://api.bgm.tv",
    nextApiBaseUrl = "https://next.bgm.tv/p1",
    imageBaseUrl = "https://lain.bgm.tv",
)

/** GATEWAY 预设: api/next 同走网关 base(路由由各 API 的中性路径区分), 图片走网关 /i 代理(单域名)。 */
val GATEWAY_BANGUMI_ENDPOINTS = BangumiEndpointConfig(
    preset = BangumiSourcePreset.GATEWAY,
    siteBaseUrl = "https://bgm.tv",
    apiBaseUrl = BangumiGatewayConfig.apiBaseUrl(),
    nextApiBaseUrl = BangumiGatewayConfig.apiBaseUrl(),
    imageBaseUrl = BangumiGatewayConfig.imageBaseUrl(),
)

/** GATEWAY 预设注入中性路由端点(其余预设返回 null, 各 API 走官方路径)。 */
fun BangumiEndpointConfig.gatewayEndpointOrNull(): BangumiGatewayEndpoint? =
    if (preset == BangumiSourcePreset.GATEWAY) BangumiGatewayEndpoint(baseUrl = apiBaseUrl) else null

/**
 * 兼容旧 `/cal` 缓存中的 lain 图片地址。Gateway 预设下改写到带鉴权的 `/i`，
 * 官方预设下至少把旧 http 地址升级为 https。
 */
fun BangumiEndpointConfig.resolveImageUrl(value: String?): String? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val normalized = if (raw.startsWith("//")) "https:$raw" else raw
    val parsed = runCatching { Url(normalized) }.getOrNull() ?: return normalized
    if (parsed.host.lowercase() != "lain.bgm.tv") return normalized
    val suffix = buildString {
        append(parsed.encodedPath)
        if (parsed.encodedQuery.isNotEmpty()) append('?').append(parsed.encodedQuery)
    }
    return when (preset) {
        BangumiSourcePreset.GATEWAY -> imageBaseUrl.trimEnd('/') + suffix
        else -> "https://lain.bgm.tv$suffix"
    }
}

private const val MAX_BANGUMI_BASE_URL_LENGTH = 512
private const val MAX_BANGUMI_AVATAR_URL_LENGTH = 1024
const val MAX_BANGUMI_CONTENT_IMAGE_URL_LENGTH = 2048
