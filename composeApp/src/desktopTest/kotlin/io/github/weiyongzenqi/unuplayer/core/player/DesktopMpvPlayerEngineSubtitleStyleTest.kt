package io.github.weiyongzenqi.unuplayer.core.player

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopMpvPlayerEngineSubtitleStyleTest {

    @Test
    fun `清空字幕字体设置会显式恢复mpv默认值`() {
        assertEquals(DESKTOP_DEFAULT_SUBTITLE_FONT, desktopSubtitleFontValue(""))
        assertEquals(DESKTOP_DEFAULT_SUBTITLE_FONT, desktopSubtitleFontValue("   "))
        assertEquals("Noto Sans CJK SC", desktopSubtitleFontValue(" Noto Sans CJK SC "))
        assertEquals("", desktopSubtitleFontDirectoryValue(null))
        assertEquals("", desktopSubtitleFontDirectoryValue("   "))
        assertEquals("C:\\fonts", desktopSubtitleFontDirectoryValue(" C:\\fonts "))
    }
}
