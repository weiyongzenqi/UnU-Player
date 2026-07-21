package io.github.weiyongzenqi.unuplayer.core.security

private val authorizationPattern = Regex(
    pattern = "(?i)(authorization\\s*[:=]\\s*(?:basic|bearer)\\s+)[^\\s,;\\\"']+",
)
private val secretFieldPattern = Regex(
    pattern = "(?i)((?:\\\"?(?:password|appsecret|proxyapikey)\\\"?)\\s*[:=]\\s*\\\"?)[^\\s,;&\\\"']+",
)
private val urlUserInfoPattern = Regex(
    pattern = "(?i)(https?://)[^/@\\s]+@",
)

/** 日志边界兜底脱敏；不改变实际网络 header，只处理将要持久化的文本。 */
fun redactSensitiveText(text: String): String = text
    .replace(authorizationPattern, "$1<redacted>")
    .replace(secretFieldPattern, "$1<redacted>")
    .replace(urlUserInfoPattern, "$1<redacted>@")
