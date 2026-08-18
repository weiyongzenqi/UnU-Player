package io.github.weiyongzenqi.unuplayer.library

import kotlin.test.Test
import kotlin.test.assertEquals

class SavedScrapedImageTest {
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `按魔数识别常见图片格式`() {
        assertEquals(
            "png",
            detectSavedImageFormat(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)).extension,
        )
        assertEquals("jpg", detectSavedImageFormat(bytes(0xFF, 0xD8, 0xFF, 0xE0)).extension)
        assertEquals(
            "webp",
            detectSavedImageFormat(bytes(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50)).extension,
        )
        assertEquals("gif", detectSavedImageFormat(bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)).extension)
        assertEquals("bmp", detectSavedImageFormat(bytes(0x42, 0x4D)).extension)
        assertEquals(
            "avif",
            detectSavedImageFormat(bytes(0, 0, 0, 0, 0x66, 0x74, 0x79, 0x70, 0x61, 0x76, 0x69, 0x66)).extension,
        )
    }

    @Test
    fun `未知魔数回落jpeg且短字节安全`() {
        assertEquals("jpg", detectSavedImageFormat(ByteArray(16)).extension)
        assertEquals("jpg", detectSavedImageFormat(ByteArray(3)).extension)
        assertEquals("jpg", detectSavedImageFormat(ByteArray(0)).extension)
    }

    @Test
    fun `保存文件名清洗非法字符并带时间戳与格式扩展名`() {
        // 既有行为: stem 自带扩展名时不重复清洗(trimEnd 只去末尾单个点/空格), 调用方自行去扩展名
        assertEquals(
            "nice_name_.jpg_42.png",
            savedImageDisplayName("nice/name?.jpg", SavedImageFormat("png", "image/png"), timestamp = 42),
        )
        assertEquals(
            "unknown_42.jpg",
            savedImageDisplayName("", SavedImageFormat("jpg", "image/jpeg"), timestamp = 42),
        )
        assertEquals(
            "unknown_42.jpg",
            savedImageDisplayName("   ", SavedImageFormat("jpg", "image/jpeg"), timestamp = 42),
        )
    }

    @Test
    fun `超长文件名主干截断到九十字符`() {
        val name = savedImageDisplayName(
            "x".repeat(200),
            SavedImageFormat("jpg", "image/jpeg"),
            timestamp = 42,
        )
        assertEquals("${"x".repeat(90)}_42.jpg", name)
    }
}
