package io.github.weiyongzenqi.unuplayer.local

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidTextFileReaderTest {

    @Test
    fun `文本大小等于上限时完整读取`() {
        assertEquals(8 * 1024 * 1024, MAX_ANDROID_TEXT_FILE_BYTES)
        val bytes = "测试".repeat(1024).encodeToByteArray()

        val result = ByteArrayInputStream(bytes).readUtf8TextLimited(maxBytes = bytes.size)

        assertEquals(bytes.decodeToString(), result)
    }

    @Test
    fun `文本超过上限一个字节时拒绝`() {
        val bytes = ByteArray(1025) { 'a'.code.toByte() }

        assertNull(ByteArrayInputStream(bytes).readUtf8TextLimited(maxBytes = 1024))
    }

    @Test
    fun `UTF8 BOM 不进入解析文本`() {
        val body = "标题=示例".encodeToByteArray()
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body

        assertEquals("标题=示例", ByteArrayInputStream(bytes).readUtf8TextLimited())
    }
}
