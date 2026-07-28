package io.github.weiyongzenqi.unuplayer.mediaserver

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.encodedPath

data class MediaServerUrlValidation(
    val normalizedApiBaseUrl: String? = null,
    val errorMessage: String? = null,
    val requiresCleartextConfirmation: Boolean = false,
) {
    val isValid: Boolean get() = normalizedApiBaseUrl != null
}

/** 媒体服务器只接受无 userInfo/query/fragment 的 HTTP(S) 服务根。 */
fun validateMediaServerBaseUrl(
    value: String,
    vendor: MediaServerVendor,
): MediaServerUrlValidation {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return MediaServerUrlValidation(errorMessage = "请输入服务器地址")
    if (!trimmed.startsWith("http://", ignoreCase = true) &&
        !trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return MediaServerUrlValidation(errorMessage = "服务器地址必须使用 http:// 或 https://")
    }

    val parsed = runCatching { Url(trimmed) }.getOrNull()
        ?: return MediaServerUrlValidation(errorMessage = "服务器地址格式无效")
    val protocol = parsed.protocolOrNull
    if (protocol != URLProtocol.HTTP && protocol != URLProtocol.HTTPS) {
        return MediaServerUrlValidation(errorMessage = "服务器地址必须使用 http:// 或 https://")
    }
    if (parsed.host.isBlank()) {
        return MediaServerUrlValidation(errorMessage = "服务器地址缺少主机名或 IP")
    }
    if (parsed.user != null || parsed.password != null) {
        return MediaServerUrlValidation(errorMessage = "地址中不能包含 user:password@")
    }
    if (parsed.encodedQuery.isNotEmpty() || parsed.trailingQuery || '#' in trimmed) {
        return MediaServerUrlValidation(errorMessage = "服务器地址不能包含查询参数或片段")
    }

    var normalized = trimmed.trimEnd('/')
    if (vendor == MediaServerVendor.EMBY && !parsed.encodedPath.trimEnd('/').endsWith("/emby", ignoreCase = true)) {
        normalized += "/emby"
    }
    return MediaServerUrlValidation(
        normalizedApiBaseUrl = normalized,
        requiresCleartextConfirmation = protocol == URLProtocol.HTTP,
    )
}

internal fun buildMediaServerUrl(
    apiBaseUrl: String,
    pathSegments: List<String>,
    query: Map<String, String?> = emptyMap(),
): String = URLBuilder(apiBaseUrl).apply {
    appendPathSegments(pathSegments)
    query.forEach { (name, value) ->
        if (value != null) parameters.append(name, value)
    }
}.buildString()

/** 服务端返回的播放/字幕 URL 只允许解析到已验证服务根的同源地址。 */
internal fun resolveMediaServerResourceUrl(
    apiBaseUrl: String,
    value: String?,
    accessToken: String,
): String? {
    val candidate = credentialFreeUrlOrNull(value, accessToken)?.trim() ?: return null
    if (candidate.isEmpty() || '#' in candidate || candidate.startsWith("//")) return null

    val base = runCatching { Url(apiBaseUrl) }.getOrNull() ?: return null
    val absolute = candidate.startsWith("http://", ignoreCase = true) ||
        candidate.startsWith("https://", ignoreCase = true)
    if (absolute) {
        val parsed = runCatching { Url(candidate) }.getOrNull() ?: return null
        if (
            parsed.user != null || parsed.password != null ||
            '\\' in candidate ||
            parsed.segments.any { it == "." || it == ".." } ||
            parsed.hasMediaServerCredential(accessToken) ||
            !mediaServerUrlsHaveSameOrigin(base, parsed) ||
            !parsed.isWithinBasePath(base)
        ) {
            return null
        }
        return parsed.toString()
    }
    if (candidate.substringBefore('/').contains(':')) return null

    val relative = runCatching {
        Url("https://relative.invalid/${candidate.trimStart('/')}")
    }.getOrNull() ?: return null
    if (
        '\\' in candidate ||
        relative.segments.any { it == "." || it == ".." } ||
        relative.hasMediaServerCredential(accessToken)
    ) {
        return null
    }
    return URLBuilder(base).apply {
        encodedPath = base.encodedPath.trimEnd('/') + "/" + relative.encodedPath.trimStart('/')
        encodedParameters.clear()
        relative.parameters.forEach { name, values ->
            values.forEach { value -> parameters.append(name, value) }
        }
        trailingQuery = relative.trailingQuery
    }.buildString()
}

internal fun mediaServerUrlsHaveSameOrigin(first: Url, second: Url): Boolean =
    first.protocol == second.protocol &&
        first.host.equals(second.host, ignoreCase = true) &&
        first.port == second.port

private fun Url.isWithinBasePath(base: Url): Boolean {
    val basePath = base.encodedPath.trimEnd('/')
    if (basePath.isEmpty()) return true
    return encodedPath == basePath || encodedPath.startsWith("$basePath/")
}

private fun Url.hasMediaServerCredential(accessToken: String): Boolean =
    parameters.names().any(::isMediaServerCredentialParameter) ||
        (accessToken.isNotBlank() && parameters.entries().any { (_, values) ->
            values.any { it.contains(accessToken) }
        })
