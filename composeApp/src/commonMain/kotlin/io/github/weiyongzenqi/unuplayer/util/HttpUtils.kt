package io.github.weiyongzenqi.unuplayer.util

/**
 * 解析 HTTP Content-Range "bytes 0-16777215/754553960" 的 total(754553960)。
 * 头部缺失、无 "/"、或 total 未知(斜杠后为星号)/畸形 => null。
 */
internal fun parseContentRangeTotal(contentRange: String?): Long? {
    if (contentRange == null) return null
    val slash = contentRange.indexOf('/')
    if (slash < 0 || slash == contentRange.length - 1) return null
    // 负值 total(如 "bytes 0-10/-5")属畸形响应, 判 null; 0(空文件)保留合法。
    return contentRange.substring(slash + 1).trim().toLongOrNull()?.takeIf { it >= 0 }
}

/**
 * 从 Range GET 响应头解析文件总大小, 统一 206/200 两种响应的取值行为
 * (RemoteDanmakuHash 与 WebDavClient.fetchRangeForHash 共用, 避免取值规则漂移):
 *
 * - 有 Content-Range(206 分片): 只认真实 total; total 未知(斜杠后为 * 号)或畸形返回 null,
 *   绝不回退 Content-Length —— 206 的 Content-Length 只是分片长度, 回退会把分片长度当文件总长喂错弹弹 match。
 * - 无 Content-Range(200 完整响应): 才用 Content-Length。
 */
internal fun resolveRangeTotalSize(contentRange: String?, contentLength: String?): Long? {
    if (contentRange != null) return parseContentRangeTotal(contentRange)
    return contentLength?.toLongOrNull()
}
