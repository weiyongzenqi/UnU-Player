package io.github.weiyongzenqi.unuplayer.core.network

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.github.weiyongzenqi.unuplayer.util.Crypto

/**
 * 流式读取最多 [limit] 字节并计算 MD5(32 位小写 hex), 始终取消未消费的响应体。
 *
 * 与"整块 ByteArray(limit) 读完再整体摘要"不同: 按 1MB 分块 update、末次 digest,
 * 峰值内存仅单块缓冲(旧实现对 16MB 前缀整块分配)。分块粒度与本地哈希
 * (DanmakuHash 各端实现)一致, 同一文件本地/远程算出的哈希相同。
 * Range 被服务端忽略(返回完整文件)时也只读 [limit] 字节即取消, 避免继续下载完整响应。
 */
internal suspend fun hashPrefixMd5AndCancel(
    channel: ByteReadChannel,
    limit: Int,
): String {
    require(limit >= 0) { "limit must be non-negative" }
    return try {
        val accumulator = Crypto.md5Accumulator()
        val buffer = ByteArray(1024 * 1024) // 1MB
        var remaining = limit
        while (remaining > 0) {
            val read = channel.readAvailable(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break // EOF(文件 < limit)
            accumulator.update(buffer, 0, read)
            remaining -= read
        }
        accumulator.hexDigest()
    } finally {
        channel.cancel(null)
    }
}
