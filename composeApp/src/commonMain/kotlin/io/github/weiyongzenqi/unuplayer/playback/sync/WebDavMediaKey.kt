package io.github.weiyongzenqi.unuplayer.playback.sync

/**
 * WebDAV media_key 解析(commonMain)。media_key 格式 = "webdav:{connId}:{path}"
 * (MediaKeys.webDav)。connId 跨设备不稳定(重装后连接 id 变), 但 path(服务器绝对路径)稳定。
 * pull 合并时用 path + 当前连接 baseUrl 重算 url, 不依赖原 connId。
 *
 * 只在 connId 后第一个冒号处分割, 后续路径中的冒号/中文/特殊字符原样保留
 * (对齐 desktopMain parseWebDavMediaKey 语义)。
 */
internal fun parseWebDavMediaKeyPath(mediaKey: String): String? {
    if (!mediaKey.startsWith("webdav:")) return null
    val payload = mediaKey.removePrefix("webdav:")
    val separator = payload.indexOf(':')
    if (separator <= 0 || separator == payload.lastIndex) return null
    return payload.substring(separator + 1)
}
