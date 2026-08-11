package io.github.weiyongzenqi.unuplayer.ui.player

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class VideoScreenshotCommandTest {
    @Test
    fun `截图命令明确排除字幕和OSD`() {
        assertContentEquals(
            arrayOf("screenshot-to-file", "C:/Pictures/frame.png", "video"),
            videoScreenshotCommand("C:/Pictures/frame.png"),
        )
    }

    @Test
    fun `截图输出路径不能为空`() {
        assertFailsWith<IllegalArgumentException> { videoScreenshotCommand(" ") }
    }
}
