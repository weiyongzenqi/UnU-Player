package io.github.weiyongzenqi.unuplayer.danmaku.source

/**
 * 手动匹配缓存 key 选取(纯函数, 供 [io.github.weiyongzenqi.unuplayer.ui.player.PlayerScreen] 三处共用,
 * 并可 desktopTest 直接验证)。
 *
 * 三档优先级:
 * 1. 媒体服务器播放 -> 用 [recordKey](稳定, = MediaKeys.mediaServer 解析出的 key)。
 *    媒体服务器 stream URL 含每次都变的 PlaySessionId, 用 playUrl 做 key 会跨会话必失效 + 污染 LRU。
 * 2. WebDAV(http) -> 用 [playUrl](稳定, 不算 hash 省成本)。
 * 3. 本地(file/content) -> 用 [localHash](前 16MB MD5, 文件指纹稳定; 不依赖引擎内部临时 fdclose://)。
 *
 * [isMediaServer] 优先于 [isWebDav]: 媒体服务器 URL 也是 http 开头, 但需走 recordKey 分支。
 *
 * @param isMediaServer 是否媒体服务器播放(用 [recordKey])
 * @param isWebDav 是否 WebDAV(http) 播放(用 [playUrl])
 * @param recordKey 稳定播放记录 key(媒体服务器场景用)
 * @param playUrl 当前播放 URL(WebDAV 场景用)
 * @param localHash 本地文件前 16MB MD5(本地场景用; null=尚未算出)
 * @return 缓存 key; null=无法生成(本地 hash 未就绪且非 http/媒体服务器)
 */
internal fun danmakuManualCacheKey(
    isMediaServer: Boolean,
    isWebDav: Boolean,
    recordKey: String,
    playUrl: String,
    localHash: String?,
): String? = when {
    isMediaServer -> recordKey
    isWebDav -> playUrl
    else -> localHash
}
