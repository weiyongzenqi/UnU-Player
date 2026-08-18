package io.github.weiyongzenqi.unuplayer.danmaku.source

import io.github.weiyongzenqi.unuplayer.util.Crypto
import java.io.FileInputStream
import java.io.InputStream

/**
 * 桌面(JVM/Linux) [calcDanmakuHash] 实现, 对应 androidMain 的 DanmakuHash.android.kt。
 *
 * 逻辑与 android 版完全一致: 用 1MB 缓冲区流式读取前 16MB,
 * 流式 MD5 累加器累积更新, 最后转 hex 小写。
 * android 版的 calcDanmakuHashFromContentUri(ContentResolver/Uri)是 Android 专属,
 * 桌面端不需要(本地文件直接用路径)。
 */
actual fun calcDanmakuHash(filePath: String): String {
    FileInputStream(filePath).use { return hashFirst16MB(it) }
}

/** 流式读前 16MB 算 MD5 -> 32 位小写 hex(弹弹play 文件哈希)。文件 < 16MB 哈希整个文件。 */
private fun hashFirst16MB(input: InputStream): String {
    val limit = 16 * 1024 * 1024 // 16MB
    val buffer = ByteArray(1024 * 1024) // 1MB
    val accumulator = Crypto.md5Accumulator()
    var remaining = limit
    while (remaining > 0) {
        val toRead = minOf(buffer.size, remaining)
        val read = input.read(buffer, 0, toRead)
        if (read <= 0) break // EOF (文件 < 16MB)
        accumulator.update(buffer, 0, read)
        remaining -= read
    }
    return accumulator.hexDigest()
}
