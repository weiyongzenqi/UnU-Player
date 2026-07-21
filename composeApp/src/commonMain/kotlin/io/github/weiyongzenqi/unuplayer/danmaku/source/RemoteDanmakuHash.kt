package io.github.weiyongzenqi.unuplayer.danmaku.source

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import io.github.weiyongzenqi.unuplayer.core.network.readPrefixAndCancel
import io.github.weiyongzenqi.unuplayer.danmaku.Crypto
import io.github.weiyongzenqi.unuplayer.webdav.createHttpClient
import io.github.weiyongzenqi.unuplayer.webdav.parseContentRangeTotal

/**
 * 远程文件(WebDAV/HTTP)前 16MB 哈希: Range GET 拉 16MB + MD5。
 * 用于 WebDAV 视频的弹幕哈希匹配回落(本地文件用 [calcDanmakuHash])。
 *
 * @param url 完整 URL(如 WebDAV 播放 URL)
 * @param authHeader Authorization 头(如 "Basic xxx"), 空=匿名
 * @return (fileSize, fileHash); null=失败
 */
suspend fun remoteHashForUrl(url: String, authHeader: String): Pair<Long, String>? =
    remoteHashForUrl(url, authHeader, createHttpClient())

internal suspend fun remoteHashForUrl(
    url: String,
    authHeader: String,
    httpClient: HttpClient,
): Pair<Long, String>? = try {
    val limit = 16 * 1024 * 1024
    httpClient.prepareGet(url) {
        header("Range", "bytes=0-${limit - 1}")
        if (authHeader.isNotEmpty()) header("Authorization", authHeader)
    }.execute { resp ->
        val channel = resp.bodyAsChannel()
        try {
            // 206(Partial)或 200(不支持 Range、返回完整文件)均可接受。
            if (!resp.status.isSuccess()) {
                null
            } else {
                // 有 Content-Range(206 分片)只认真实 total; total 未知("bytes 0-N/*")或畸形返回 null,
                // 绝不回退 Content-Length(206 里它只是分片长度), 否则喂错 fileSize 给弹弹 match。
                // 无 Content-Range(200 完整响应)才用 Content-Length。解析逻辑与 WebDavClient 完全一致。
                val contentRange = resp.headers["Content-Range"]
                val size = if (contentRange != null) {
                    parseContentRangeTotal(contentRange)
                } else {
                    resp.headers["Content-Length"]?.toLongOrNull()
                }
                if (size == null) {
                    null
                } else {
                    val bytes = readPrefixAndCancel(channel, limit)
                    Pair(size, Crypto.md5Hex(bytes))
                }
            }
        } finally {
            channel.cancel(null)
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}
