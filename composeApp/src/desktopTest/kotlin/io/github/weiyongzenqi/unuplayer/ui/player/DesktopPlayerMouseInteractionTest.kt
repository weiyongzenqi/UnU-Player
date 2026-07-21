package io.github.weiyongzenqi.unuplayer.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPlayerMouseInteractionTest {
    @Test
    fun `滚轮向上按固定步进提高音量`() {
        assertEquals(55, desktopVolumeAfterScroll(50, -1f))
        assertEquals(55, desktopVolumeAfterScroll(50, -120f))
    }

    @Test
    fun `滚轮向下按固定步进降低音量`() {
        assertEquals(45, desktopVolumeAfterScroll(50, 1f))
        assertEquals(45, desktopVolumeAfterScroll(50, 120f))
    }

    @Test
    fun `滚轮音量限制在有效范围`() {
        assertEquals(100, desktopVolumeAfterScroll(98, -1f))
        assertEquals(0, desktopVolumeAfterScroll(2, 1f))
        assertEquals(100, desktopVolumeAfterScroll(120, 0f))
        assertEquals(0, desktopVolumeAfterScroll(-20, 0f))
    }

    @Test
    fun `无效滚轮输入不改变音量`() {
        assertEquals(50, desktopVolumeAfterScroll(50, 0f))
        assertEquals(50, desktopVolumeAfterScroll(50, Float.NaN))
        assertEquals(50, desktopVolumeAfterScroll(50, Float.POSITIVE_INFINITY))
        assertEquals(50, desktopVolumeAfterScroll(50, -1f, step = 0))
    }

    @Test
    fun `右方向键短按前进十秒并限制在片尾`() {
        assertEquals(30_000L, desktopForwardSeekTarget(20_000L, 90_000L))
        assertEquals(90_000L, desktopForwardSeekTarget(85_000L, 90_000L))
        assertEquals(30_000L, desktopForwardSeekTarget(20_000L, 0L))
    }

    @Test
    fun `临时倍速提示去掉无意义小数`() {
        assertEquals("2", formatDesktopSpeed(2f))
        assertEquals("1.25", formatDesktopSpeed(1.25f))
    }
}
