package io.github.weiyongzenqi.unuplayer.core.security

private val authorizationPattern = Regex(
    pattern = "(?i)(authorization\\s*[:=]\\s*(?:basic|bearer)\\s+)[^\\s,;\\\"']+",
)
private val secretFieldPattern = Regex(
    pattern = "(?i)((?:\\\"?(?:password|appsecret|proxyapikey|x-emby-token|api[_-]?key|access[_-]?token|token|play[_-]?session[_-]?id|device[_-]?id)\\\"?)" +
        "\\s*[:=]\\s*\\\"?)[^\\s,;&#\\\"']+",
)
private val urlUserInfoPattern = Regex(
    pattern = "(?i)(https?://)[^/@\\s]+@",
)
private val mediaServerResourceUrlPattern = Regex(
    pattern = "(?i)https?://[^\\s\\\"'<>]*/(?:videos|items)/[^\\s\\\"'<>]+",
)
private val httpUrlPattern = Regex(
    pattern = "(?i)https?://[^\\s\\\"'<>]+",
)

private fun redactUrlQueryAndFragment(url: String): String {
    val queryIndex = url.indexOf('?').takeIf { it >= 0 } ?: Int.MAX_VALUE
    val fragmentIndex = url.indexOf('#').takeIf { it >= 0 } ?: Int.MAX_VALUE
    val sensitiveSuffixIndex = minOf(queryIndex, fragmentIndex)
    return if (sensitiveSuffixIndex == Int.MAX_VALUE) url else url.substring(0, sensitiveSuffixIndex) + "?<redacted>"
}

/** 日志边界兜底脱敏；不改变实际网络 header，只处理将要持久化的文本。 */
fun redactSensitiveText(text: String): String = text
    .replace(mediaServerResourceUrlPattern, "<redacted-media-url>")
    // 签名字段没有稳定命名空间；日志里保留 origin/path 已足够定位，任意 query/fragment 一律移除。
    .replace(httpUrlPattern) { match -> redactUrlQueryAndFragment(match.value) }
    .replace(authorizationPattern, "$1<redacted>")
    .replace(secretFieldPattern, "$1<redacted>")
    .replace(urlUserInfoPattern, "$1<redacted>@")
