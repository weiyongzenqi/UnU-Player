package io.github.weiyongzenqi.unuplayer.core.media

import java.io.InputStream
import java.io.OutputStream

internal const val MAX_EXTERNAL_SUBTITLE_BYTES: Long = 16L * 1024L * 1024L

internal class ExternalSubtitleTooLargeException : IllegalArgumentException("外挂字幕超过 16 MiB 上限")

/** 复制主体后额外探测一个字节，恰好等于上限可成功，超过一字节必须失败。 */
internal fun InputStream.copyExternalSubtitleTo(output: OutputStream): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (total < MAX_EXTERNAL_SUBTITLE_BYTES) {
        val remaining = (MAX_EXTERNAL_SUBTITLE_BYTES - total).coerceAtMost(buffer.size.toLong()).toInt()
        val read = read(buffer, 0, remaining)
        if (read < 0) return total
        if (read == 0) continue
        output.write(buffer, 0, read)
        total += read
    }
    if (read() >= 0) throw ExternalSubtitleTooLargeException()
    return total
}
