package io.github.weiyongzenqi.unuplayer.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SavedScrapedImageTest {
    @Test
    fun `识别常见图片格式`() {
        assertEquals("png", detectSavedImageFormat(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)).extension)
        assertEquals("jpg", detectSavedImageFormat(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())).extension)
        assertEquals(
            "webp",
            detectSavedImageFormat("RIFF0000WEBP".encodeToByteArray()).extension,
        )
        assertEquals("gif", detectSavedImageFormat("GIF89a".encodeToByteArray()).extension)
    }

    @Test
    fun `保存文件名经过清理并保留检测格式`() {
        val name = savedImageDisplayName("番剧:/S01E01 剧照", SavedImageFormat("png", "image/png"), 123L)

        assertTrue(name.endsWith("_123.png"))
        assertTrue(':' !in name && '/' !in name)
    }
}
