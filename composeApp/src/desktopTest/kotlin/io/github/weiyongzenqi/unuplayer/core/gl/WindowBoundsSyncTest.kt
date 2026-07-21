package io.github.weiyongzenqi.unuplayer.core.gl

import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowBoundsSyncTest {
    @Test
    fun `backing 尺寸沿用 Skiko 的 DPI 半像素补偿`() {
        assertEquals(1280, skiaBackingSize(1280, 1.5f))
        assertEquals(1282, skiaBackingSize(1281, 1.5f))
    }

    @Test
    fun `全屏退出后把宿主尺寸传播到面板和渲染层`() {
        val root = JPanel(null).apply { setSize(1280, 720) }
        val panel = JPanel(null).apply { setBounds(0, 0, 1920, 1080) }
        val layer = LayoutCountingPanel().apply { setBounds(0, 0, 1920, 1080) }
        root.add(panel)
        panel.add(layer)
        var reshaped = IntArray(0)
        var layoutBeforeReshape = false

        propagateComposeBounds(root, panel, layer) { x, y, width, height ->
            layoutBeforeReshape = layer.layoutCount == 0
            reshaped = intArrayOf(x, y, width, height)
        }

        assertEquals(1280, panel.width)
        assertEquals(720, panel.height)
        assertEquals(listOf(0, 0, 1280, 720), reshaped.toList())
        assertEquals(1, layer.layoutCount)
        assertTrue(layoutBeforeReshape)
    }

    private class LayoutCountingPanel : JPanel() {
        var layoutCount = 0

        override fun doLayout() {
            layoutCount++
            super.doLayout()
        }
    }
}
