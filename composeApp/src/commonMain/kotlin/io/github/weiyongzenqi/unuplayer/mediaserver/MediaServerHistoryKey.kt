package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.core.media.MediaKeys
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.danmaku.Crypto

/** 可跨安装同步的媒体服务器历史定位；连接身份是 vendor/serverId/userId 的无凭据摘要。 */
data class MediaServerHistoryKey(
    val sourceKind: MediaSourceKind,
    val connectionId: String?,
    val stableConnectionKey: String?,
    val itemId: String,
)

fun mediaServerHistoryConnectionKey(
    vendor: MediaServerVendor,
    serverId: String,
    userId: String,
): String {
    require(serverId.isNotBlank()) { "服务器 ID 不能为空" }
    require(userId.isNotBlank()) { "用户 ID 不能为空" }
    return Crypto.sha256Base64(
        listOf(vendor.name, serverId, userId).joinToString(HISTORY_IDENTITY_SEPARATOR),
    ).replace('+', '-').replace('/', '_').trimEnd('=')
}

fun mediaServerHistoryMediaKey(
    vendor: MediaServerVendor,
    serverId: String,
    userId: String,
    itemId: String,
): String {
    require(itemId.isNotBlank()) { "媒体 item ID 不能为空" }
    val prefix = vendor.sourceKind.historyPrefix()
    return "$prefix:$STABLE_KEY_VERSION:${mediaServerHistoryConnectionKey(vendor, serverId, userId)}:$itemId"
}

fun parseMediaServerHistoryKey(mediaKey: String?): MediaServerHistoryKey? {
    val value = mediaKey ?: return null
    val sourceKind = when {
        value.startsWith("jellyfin:") -> MediaSourceKind.JELLYFIN
        value.startsWith("emby:") -> MediaSourceKind.EMBY
        else -> return null
    }
    val payload = value.substringAfter(':')
    val stablePrefix = "$STABLE_KEY_VERSION:"
    if (payload.startsWith(stablePrefix)) {
        val stablePayload = payload.removePrefix(stablePrefix)
        val separator = stablePayload.indexOf(':')
        if (separator <= 0 || separator == stablePayload.lastIndex) return null
        return MediaServerHistoryKey(
            sourceKind = sourceKind,
            connectionId = null,
            stableConnectionKey = stablePayload.substring(0, separator),
            itemId = stablePayload.substring(separator + 1),
        )
    }
    val legacy = MediaKeys.parseMediaServer(value) ?: return null
    return MediaServerHistoryKey(
        sourceKind = legacy.sourceKind,
        connectionId = legacy.connectionId,
        stableConnectionKey = null,
        itemId = legacy.itemId,
    )
}

fun resolveMediaServerHistoryConnectionId(
    key: MediaServerHistoryKey,
    connections: List<MediaServerConnectionSummary>,
): String? = key.connectionId?.let { legacyId ->
    connections.firstOrNull { it.vendor.sourceKind == key.sourceKind && it.id == legacyId }?.id
} ?: key.stableConnectionKey?.let { stableKey ->
    val matches = connections.filter { summary ->
        summary.vendor.sourceKind == key.sourceKind && summary.historyConnectionKey == stableKey
    }
    matches.firstOrNull { !it.credentialUnavailable }?.id ?: matches.firstOrNull()?.id
}

val MediaServerConnectionSummary.historyConnectionKey: String
    get() = mediaServerHistoryConnectionKey(vendor, serverId, userId)

val MediaServerPlaybackPlan.historyMediaKey: String
    get() = mediaServerHistoryMediaKey(vendor, serverId, userId, itemId)

private fun MediaSourceKind.historyPrefix(): String = when (this) {
    MediaSourceKind.JELLYFIN -> "jellyfin"
    MediaSourceKind.EMBY -> "emby"
    else -> throw IllegalArgumentException("媒体服务器 key 仅支持 Jellyfin/Emby")
}

private const val STABLE_KEY_VERSION = "v2"
private const val HISTORY_IDENTITY_SEPARATOR = "\u001f"
