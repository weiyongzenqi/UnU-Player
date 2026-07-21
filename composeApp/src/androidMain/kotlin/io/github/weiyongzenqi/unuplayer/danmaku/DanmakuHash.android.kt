package io.github.weiyongzenqi.unuplayer.danmaku.source

import android.content.ContentResolver
import android.net.Uri
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Android 端 [calcDanmakuHash] 实现。
 *
 * 用 1MB 缓冲区流式读取前 16MB, MessageDigest("MD5") 累积更新, 最后转 hex 小写。
 */
actual fun calcDanmakuHash(filePath: String): String {
    FileInputStream(filePath).use { return hashFirst16MB(it) }
}

/**
 * 从 content:// URI 算弹幕哈希(前 16MB MD5 + fileSize)。
 *
 * 用于本地 content:// 视频：播放器保留原始 URI，引擎内部临时转 fdclose://，无法用 [calcDanmakuHash]
 * 当作普通文件读取。这里直接用 [ContentResolver] 开 fd 读前 16MB，
 * 与文件路径版算法一致([hashFirst16MB]), 保证同一文件无论从路径还是 content uri 算哈希值相同。
 *
 * @return (fileSize, hashHex); 失败返回 null(调用方走文件名搜索回落)
 */
fun calcDanmakuHashFromContentUri(resolver: ContentResolver, uri: Uri): Pair<Long, String>? = runCatching {
    resolver.openFileDescriptor(uri, "r")?.use { pfd ->
        val size = pfd.statSize
        FileInputStream(pfd.fileDescriptor).use { fis ->
            Pair(size, hashFirst16MB(fis))
        }
    }
}.getOrNull()

/** 流式读前 16MB 算 MD5 -> 32 位小写 hex(弹弹play 文件哈希)。文件 < 16MB 哈希整个文件。 */
private fun hashFirst16MB(input: InputStream): String {
    val limit = 16 * 1024 * 1024 // 16MB
    val buffer = ByteArray(1024 * 1024) // 1MB
    val digest = MessageDigest.getInstance("MD5")
    var remaining = limit
    while (remaining > 0) {
        val toRead = minOf(buffer.size, remaining)
        val read = input.read(buffer, 0, toRead)
        if (read <= 0) break // EOF (文件 < 16MB)
        digest.update(buffer, 0, read)
        remaining -= read
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
