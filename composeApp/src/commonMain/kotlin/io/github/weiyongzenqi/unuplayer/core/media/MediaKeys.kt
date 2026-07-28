package io.github.weiyongzenqi.unuplayer.core.media

/**
 * 播放记录 media_key 生成(浏览层与 source 层共用同一公式, 保证查/写一致)。
 *
 * 以"本地导航位置"区分文件, 不用传给播放器的链接:
 * - WebDAV: 部分 WebDAV(云盘网关)播放时 302 跳转到带签名的直链, 签名会变更,
 *   用 url 作 key 会导致同一文件每次签名变就认成新记录。改用 连接id+浏览路径, 稳定。
 * - 本地: DocumentFile content uri 稳定(同一授权目录下同一文件 uri 不变)。
 */
object MediaKeys {
    /** WebDAV: 连接 id + 导航路径(entry.path = PROPFIND href, 即浏览位置)。 */
    fun webDav(connId: String, path: String): String = "webdav:$connId:$path"

    /** 本地: DocumentFile content uri。 */
    fun local(contentUri: String): String = "local:$contentUri"

    /** 媒体服务器: 厂商 + 连接 id + 服务端 item id；不包含 URL、媒体版本或播放会话。 */
    fun mediaServer(sourceKind: MediaSourceKind, connectionId: String, itemId: String): String {
        val prefix = when (sourceKind) {
            MediaSourceKind.JELLYFIN -> "jellyfin"
            MediaSourceKind.EMBY -> "emby"
            else -> throw IllegalArgumentException("媒体服务器 key 仅支持 Jellyfin/Emby")
        }
        require(connectionId.isNotBlank() && ':' !in connectionId) { "媒体服务器连接 ID 无效" }
        require(itemId.isNotBlank()) { "媒体服务器 item ID 不能为空" }
        return "$prefix:$connectionId:$itemId"
    }

    fun parseMediaServer(mediaKey: String?): MediaServerMediaKey? {
        val value = mediaKey ?: return null
        val sourceKind = when {
            value.startsWith("jellyfin:") -> MediaSourceKind.JELLYFIN
            value.startsWith("emby:") -> MediaSourceKind.EMBY
            else -> return null
        }
        val payload = value.substringAfter(':')
        val separator = payload.indexOf(':')
        if (separator <= 0 || separator == payload.lastIndex) return null
        return MediaServerMediaKey(
            sourceKind = sourceKind,
            connectionId = payload.substring(0, separator),
            itemId = payload.substring(separator + 1),
        )
    }
}

data class MediaServerMediaKey(
    val sourceKind: MediaSourceKind,
    val connectionId: String,
    val itemId: String,
)
