package io.github.weiyongzenqi.unuplayer.webdav

import io.ktor.http.URLProtocol
import io.ktor.http.Url

internal data class WebDavUrlValidation(
    val normalizedUrl: String? = null,
    val errorMessage: String? = null,
    val requiresCleartextConfirmation: Boolean = false,
) {
    val isValid: Boolean get() = normalizedUrl != null
}

/** WebDAV 端点只接受带主机的 HTTP(S)，凭据必须走独立字段。 */
internal fun validateWebDavBaseUrl(value: String): WebDavUrlValidation {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return WebDavUrlValidation(errorMessage = "请输入服务器地址")

    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) {
        return WebDavUrlValidation(errorMessage = "服务器地址必须使用 http:// 或 https://")
    }
    val rawScheme = trimmed.substring(0, schemeEnd)
    if (!rawScheme.equals("http", ignoreCase = true) && !rawScheme.equals("https", ignoreCase = true)) {
        return WebDavUrlValidation(errorMessage = "服务器地址必须使用 http:// 或 https://")
    }
    val authorityStart = schemeEnd + 3
    val authorityEnd = sequenceOf(
        trimmed.indexOf('/', authorityStart),
        trimmed.indexOf('?', authorityStart),
        trimmed.indexOf('#', authorityStart),
    ).filter { it >= 0 }.minOrNull() ?: trimmed.length
    if (authorityStart == authorityEnd) {
        return WebDavUrlValidation(errorMessage = "服务器地址缺少主机名或 IP")
    }

    val parsed = runCatching { Url(trimmed) }.getOrNull()
        ?: return WebDavUrlValidation(errorMessage = "服务器地址格式无效")
    val protocol = parsed.protocolOrNull
    if (protocol != URLProtocol.HTTP && protocol != URLProtocol.HTTPS) {
        return WebDavUrlValidation(errorMessage = "服务器地址必须使用 http:// 或 https://")
    }
    if (parsed.host.isBlank()) {
        return WebDavUrlValidation(errorMessage = "服务器地址缺少主机名或 IP")
    }
    if (parsed.user != null || parsed.password != null) {
        return WebDavUrlValidation(errorMessage = "地址中不能包含 user:password@，请使用下方凭据字段")
    }
    if (parsed.encodedQuery.isNotEmpty() || parsed.trailingQuery || '#' in trimmed) {
        return WebDavUrlValidation(errorMessage = "服务器地址不能包含查询参数或片段")
    }

    return WebDavUrlValidation(
        normalizedUrl = trimmed.trimEnd('/'),
        requiresCleartextConfirmation = protocol == URLProtocol.HTTP,
    )
}
