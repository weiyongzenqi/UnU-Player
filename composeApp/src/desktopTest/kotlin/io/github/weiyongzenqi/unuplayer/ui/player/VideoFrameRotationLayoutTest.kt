package io.github.weiyongzenqi.unuplayer.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoFrameRotationLayoutTest {
    @Test
    fun `无旋转时外接矩形即画布放置且角度为零`() {
        val layout = rotatedVideoFrameLayout(1920f, 1200f, 1920, 1080, 0)
        assertEquals(0f, layout.rotationDegrees)
        assertEquals(0f, layout.rotatedBounds.left, 0.001f)
        assertEquals(60f, layout.rotatedBounds.top, 0.001f)
        assertEquals(1920f, layout.rotatedBounds.right, 0.001f)
        assertEquals(1140f, layout.rotatedBounds.bottom, 0.001f)
        assertEquals(-960f, layout.destination.left, 0.001f)
        assertEquals(-540f, layout.destination.top, 0.001f)
        assertEquals(960f, layout.destination.right, 0.001f)
        assertEquals(540f, layout.destination.bottom, 0.001f)
    }

    @Test
    fun `九十度旋转用交换后的源宽高做 fit`() {
        val layout = rotatedVideoFrameLayout(1080f, 1920f, 1920, 1080, 90)
        assertEquals(90f, layout.rotationDegrees)
        // 显示尺寸 1080x1920 恰好填满画布
        assertEquals(0f, layout.rotatedBounds.left, 0.001f)
        assertEquals(0f, layout.rotatedBounds.top, 0.001f)
        assertEquals(1080f, layout.rotatedBounds.right, 0.001f)
        assertEquals(1920f, layout.rotatedBounds.bottom, 0.001f)
        // 绘制矩形用帧自身(未交换)尺寸, 以占位中心为原点
        assertEquals(-960f, layout.destination.left, 0.001f)
        assertEquals(-540f, layout.destination.top, 0.001f)
        assertEquals(960f, layout.destination.right, 0.001f)
        assertEquals(540f, layout.destination.bottom, 0.001f)
    }

    @Test
    fun `一百八十度旋转占位不交换宽高`() {
        val layout = rotatedVideoFrameLayout(1280f, 720f, 1920, 1080, 180)
        assertEquals(180f, layout.rotationDegrees)
        assertEquals(0f, layout.rotatedBounds.left, 0.001f)
        assertEquals(0f, layout.rotatedBounds.top, 0.001f)
        assertEquals(1280f, layout.rotatedBounds.right, 0.001f)
        assertEquals(720f, layout.rotatedBounds.bottom, 0.001f)
    }

    @Test
    fun `二百七十度旋转等同九十度的占位`() {
        val layout = rotatedVideoFrameLayout(1080f, 1920f, 1920, 1080, 270)
        assertEquals(270f, layout.rotationDegrees)
        assertEquals(1080f, layout.rotatedBounds.right, 0.001f)
        assertEquals(1920f, layout.rotatedBounds.bottom, 0.001f)
    }

    @Test
    fun `非法角度归一到最近正交值或零`() {
        assertEquals(90f, rotatedVideoFrameLayout(100f, 100f, 16, 9, 450).rotationDegrees)
        assertEquals(270f, rotatedVideoFrameLayout(100f, 100f, 16, 9, -90).rotationDegrees)
        assertEquals(0f, rotatedVideoFrameLayout(100f, 100f, 16, 9, 45).rotationDegrees)
        assertEquals(0f, rotatedVideoFrameLayout(100f, 100f, 16, 9, 360).rotationDegrees)
    }
}
