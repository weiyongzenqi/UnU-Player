package io.github.weiyongzenqi.unuplayer.bangumi

import io.ktor.http.URLProtocol
import io.ktor.http.Url

enum class BangumiSourcePreset {
    OFFICIAL,
    BANGUMI_LOL,
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
            BangumiSourcePreset.BANGUMI_LOL -> "bangumi.lol（第三方）"
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
    BangumiSourcePreset.BANGUMI_LOL -> BANGUMI_LOL_ENDPOINTS
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

val BANGUMI_LOL_ENDPOINTS = BangumiEndpointConfig(
    preset = BangumiSourcePreset.BANGUMI_LOL,
    siteBaseUrl = "https://bangumi.lol",
    apiBaseUrl = "https://api.bangumi.lol",
    nextApiBaseUrl = "https://next.bangumi.lol/p1",
    imageBaseUrl = "https://lain.bangumi.lol",
)

private const val MAX_BANGUMI_BASE_URL_LENGTH = 512
private const val MAX_BANGUMI_AVATAR_URL_LENGTH = 1024
