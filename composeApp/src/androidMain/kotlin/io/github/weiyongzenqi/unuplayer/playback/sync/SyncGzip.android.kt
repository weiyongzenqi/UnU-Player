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

/** gzip 解压: GZIPInputStream 读出 -> UTF-8 解码。 */
actual fun gzipDecompress(bytes: ByteArray): String {
    ByteArrayInputStream(bytes).use { bis ->
        GZIPInputStream(bis).use { gzis ->
            return gzis.readBytes().decodeToString()
        }
    }
}
