package io.github.weiyongzenqi.unuplayer.danmaku.source

/**
 * 手动匹配缓存 key 选取(纯函数, 供 [io.github.weiyongzenqi.unuplayer.ui.player.PlayerScreen] 三处共用,
 * 并可 desktopTest 直接验证)。
 *
 * 三档优先级:
 * 1. 媒体服务器播放 -> 用 [recordKey]（基于服务器用户身份的稳定历史键）。
 *    媒体服务器 stream URL 含每次都变的 PlaySessionId，不能用 URL 做 key。
 * 2. WebDAV/SMB 等有稳定远程身份的来源 -> 用 [stableRemoteKey]，避免仅为缓存 key 读取 16 MiB。
 * 3. 本地(file/content) -> 用 [fileHash](前 16 MiB MD5，不依赖引擎内部临时 fdclose://)。
 *
 * @param isMediaServer 是否媒体服务器播放(用 [recordKey])
 * @param recordKey 稳定播放记录 key(媒体服务器场景用)
 * @param stableRemoteKey WebDAV 使用稳定 URL，SMB 使用无凭据 mediaKey；本地来源传 null
 * @param fileHash 本地文件前 16 MiB MD5；null 表示尚未算出
 * @return 缓存 key；null 表示没有稳定远程身份且文件哈希未就绪
 */
internal fun danmakuManualCacheKey(
    isMediaServer: Boolean,
    recordKey: String,
    stableRemoteKey: String?,
    fileHash: String?,
): String? = when {
    isMediaServer -> recordKey
    stableRemoteKey != null -> stableRemoteKey
    else -> fileHash
}
