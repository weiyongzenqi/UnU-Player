package io.github.weiyongzenqi.unuplayer.app

internal data class ExternalPlaybackUriParts(
    val scheme: String?,
    val rawUri: String,
    val path: String?,
    val lastPathSegment: String?,
    val encodedUserInfo: String?,
)

internal data class ExternalPlaybackTarget(
    val url: String,
    val title: String,
    val contentUri: String?,
)

/** 外部拉起只允许本地 content/file 与 HTTP(S)，且网络 URL 不得携带 userInfo 凭据。 */
internal fun resolveExternalPlaybackTarget(
    parts: ExternalPlaybackUriParts,
    displayName: String = "",
): ExternalPlaybackTarget? {
    val scheme = parts.scheme?.lowercase()
    if ((scheme == "http" || scheme == "https") && parts.encodedUserInfo != null) return null
    val url = when (scheme) {
        "content" -> parts.rawUri
        "file" -> parts.path ?: parts.rawUri
        "http", "https" -> parts.rawUri
        else -> return null
    }
    return ExternalPlaybackTarget(
        url = url,
        title = displayName.ifBlank { parts.lastPathSegment.orEmpty() },
        contentUri = if (scheme == "content") parts.rawUri else null,
    )
}
