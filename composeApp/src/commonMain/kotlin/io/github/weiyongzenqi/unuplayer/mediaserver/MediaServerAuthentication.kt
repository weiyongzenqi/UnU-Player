package io.github.weiyongzenqi.unuplayer.mediaserver

internal fun jellyfinAuthorization(
    client: MediaServerClientIdentity,
    accessToken: String = "",
): String = buildList {
    add("MediaBrowser Client=\"${encodeAuthorizationValue(client.clientName)}\"")
    add("Device=\"${encodeAuthorizationValue(client.deviceName)}\"")
    add("DeviceId=\"${encodeAuthorizationValue(client.deviceId)}\"")
    add("Version=\"${encodeAuthorizationValue(client.clientVersion)}\"")
    accessToken.takeIf { it.isNotBlank() }?.let {
        add("Token=\"${encodeAuthorizationValue(it)}\"")
    }
}.joinToString(", ")

/**
 * mpv `http-header-fields` 兼容的 Jellyfin 短形态认证头。
 * mpv 该选项是逗号分隔 STRING_LIST 且无转义: 完整 MediaBrowser 形态(Client/Device/... 逗号分隔)
 * 会被拆成多条无冒号的非法头行, 反代(实测 openresty)直接 400。播放/字幕/图片请求用本短形态,
 * REST 会话调用继续用完整形态。值经 encodeAuthorizationValue 保证不含逗号。
 */
internal fun jellyfinTokenAuthorization(accessToken: String): String =
    "MediaBrowser Token=\"${encodeAuthorizationValue(accessToken)}\""

internal fun embyAuthorization(
    client: MediaServerClientIdentity,
    userId: String? = null,
): String = buildList {
    add("Emby Client=\"${encodeAuthorizationValue(client.clientName)}\"")
    add("Device=\"${encodeAuthorizationValue(client.deviceName)}\"")
    add("DeviceId=\"${encodeAuthorizationValue(client.deviceId)}\"")
    add("Version=\"${encodeAuthorizationValue(client.clientVersion)}\"")
    userId?.takeIf { it.isNotBlank() }?.let { add("UserId=\"${encodeAuthorizationValue(it)}\"") }
}.joinToString(", ")

/** 与 Jellyfin 官方 TypeScript SDK 的 encodeURIComponent 取值范围一致。 */
private fun encodeAuthorizationValue(value: String): String = buildString(value.length) {
    value.encodeToByteArray().forEach { byte ->
        val unsigned = byte.toInt() and 0xFF
        val char = unsigned.toChar()
        if (
            unsigned in 'A'.code..'Z'.code || unsigned in 'a'.code..'z'.code ||
            unsigned in '0'.code..'9'.code || char in AUTH_UNESCAPED
        ) {
            append(char)
        } else {
            append('%')
            append(HEX[unsigned ushr 4])
            append(HEX[unsigned and 0x0F])
        }
    }
}

private const val AUTH_UNESCAPED = "-_.!~*'()"
private const val HEX = "0123456789ABCDEF"
