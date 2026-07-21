package io.github.weiyongzenqi.unuplayer.core.network

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

/**
 * 最多读取 [limit] 字节，并始终取消未消费的响应体。
 *
 * 这用于 Range 被服务端忽略等场景，避免客户端继续下载完整响应。
 */
internal suspend fun readPrefixAndCancel(
    channel: ByteReadChannel,
    limit: Int,
): ByteArray {
    require(limit >= 0) { "limit must be non-negative" }
    return try {
        val bytes = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val read = channel.readAvailable(bytes, total, limit - total)
            if (read <= 0) break
            total += read
        }
        if (total == limit) bytes else bytes.copyOf(total)
    } finally {
        channel.cancel(null)
    }
}
