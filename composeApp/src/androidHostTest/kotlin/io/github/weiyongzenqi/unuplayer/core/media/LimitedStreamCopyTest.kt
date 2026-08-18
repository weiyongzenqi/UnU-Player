package io.github.weiyongzenqi.unuplayer.core.media

import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LimitedStreamCopyTest {

    @Test
    fun `恰好 16 MiB 可以复制`() {
        val output = CountingOutputStream()

        val copied = SizedInputStream(MAX_EXTERNAL_SUBTITLE_BYTES).copyExternalSubtitleTo(output)

        assertEquals(MAX_EXTERNAL_SUBTITLE_BYTES, copied)
        assertEquals(MAX_EXTERNAL_SUBTITLE_BYTES, output.written)
    }

    @Test
    fun `超过上限一个字节会失败且主体不越界`() {
        val output = CountingOutputStream()

        assertFailsWith<ExternalSubtitleTooLargeException> {
            SizedInputStream(MAX_EXTERNAL_SUBTITLE_BYTES + 1L).copyExternalSubtitleTo(output)
        }

        assertEquals(MAX_EXTERNAL_SUBTITLE_BYTES, output.written)
    }

    private class SizedInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            remaining--
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val count = minOf(remaining, length.toLong()).toInt()
            buffer.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }

    private class CountingOutputStream : OutputStream() {
        var written: Long = 0L
            private set

        override fun write(value: Int) {
            written++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            written += length
        }
    }
}
