package io.github.weiyongzenqi.unuplayer.local

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal const val MAX_ANDROID_TEXT_FILE_BYTES = 8 * 1024 * 1024
private const val TEXT_READ_BUFFER_BYTES = 64 * 1024
private const val UTF8_BOM_BYTES = 3

/** SAF 小文本流式读取；超过硬上限立即返回 null，不继续扩大内存缓冲。 */
internal fun InputStream.readUtf8TextLimited(
    maxBytes: Int = MAX_ANDROID_TEXT_FILE_BYTES,
): String? {
    val output = ByteArrayOutputStream()
    val prefix = ByteArray(UTF8_BOM_BYTES)
    var prefixSize = 0
    var total = 0

    while (prefixSize < prefix.size) {
        val count = read(prefix, prefixSize, prefix.size - prefixSize)
        if (count < 0) break
        if (count == 0) continue
        if (total > maxBytes - count) return null
        total += count
        prefixSize += count
    }

    val hasUtf8Bom = prefixSize == UTF8_BOM_BYTES &&
        prefix[0] == 0xEF.toByte() &&
        prefix[1] == 0xBB.toByte() &&
        prefix[2] == 0xBF.toByte()
    if (!hasUtf8Bom && prefixSize > 0) output.write(prefix, 0, prefixSize)

    val buffer = ByteArray(TEXT_READ_BUFFER_BYTES)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (total > maxBytes - count) return null
        total += count
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}
