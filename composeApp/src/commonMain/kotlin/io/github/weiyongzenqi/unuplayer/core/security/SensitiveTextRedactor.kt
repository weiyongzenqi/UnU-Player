package io.github.weiyongzenqi.unuplayer.core.security

private val authorizationPattern = Regex(
    pattern = "(?i)(authorization\\s*[:=]\\s*(?:basic|bearer)\\s+)[^\\s,;\\\"']+",
)
private val secretFieldPattern = Regex(
    pattern = "(?i)((?:\\\"?(?:password|appsecret|proxyapikey|x-emby-token|api[_-]?key|access[_-]?token|token|play[_-]?session[_-]?id)\\\"?)" +
        "\\s*[:=]\\s*\\\"?)[^\\s,;&#\\\"']+",
)
private val urlUserInfoPattern = Regex(
    pattern = "(?i)(https?://)[^/@\\s]+@",
)
private val mediaServerResourceUrlPattern = Regex(
    pattern = "(?i)https?://[^\\s\\\"'<>]*/(?:videos|items)/[^\\s\\\"'<>]+",
)

/** 日志边界兜底脱敏；不改变实际网络 header，只处理将要持久化的文本。 */
fun redactSensitiveText(text: String): String = text
    .replace(mediaServerResourceUrlPattern, "<redacted-media-url>")
    .replace(authorizationPattern, "$1<redacted>")
    .replace(secretFieldPattern, "$1<redacted>")
    .replace(urlUserInfoPattern, "$1<redacted>@")
