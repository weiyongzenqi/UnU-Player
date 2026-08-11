package io.github.weiyongzenqi.unuplayer.playback.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** gzip 压缩: 文本 -> UTF-8 字节 -> GZIPOutputStream 压缩。 */
actual fun gzipCompress(text: String): ByteArray {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { it.write(text.encodeToByteArray()) }
    return bos.toByteArray()
}

/**
 * gzip 解压: GZIPInputStream 读出 -> UTF-8 解码。
 * E-P2-5: 限制解压输出上限(推送侧按未压缩 JSON ≤8MiB LRU 截断, 解压侧放宽到 2× 兜底)。
 * 防恶意/异常服务器返回高压缩比载荷解出数倍内存(压缩 8MiB 可展开几百 MiB)。
 */
actual fun gzipDecompress(bytes: ByteArray): String {
    ByteArrayInputStream(bytes).use { bis ->
        GZIPInputStream(bis).use { gzis ->
            val bos = ByteArrayOutputStream(1024)
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val n = gzis.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw IllegalStateException("gzip 解压超过 ${MAX_DECOMPRESSED_BYTES / 1024 / 1024}MiB 上限")
                }
                bos.write(chunk, 0, n)
            }
            return bos.toString(Charsets.UTF_8)
        }
    }
}

/** E-P2-5: 解压输出上限 = 推送侧未压缩上限(8MiB)的 2 倍。 */
private const val MAX_DECOMPRESSED_BYTES = 16 * 1024 * 1024
