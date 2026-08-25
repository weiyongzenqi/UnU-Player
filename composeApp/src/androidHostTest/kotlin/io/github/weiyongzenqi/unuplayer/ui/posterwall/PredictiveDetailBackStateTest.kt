package io.github.weiyongzenqi.unuplayer.ui.posterwall

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PredictiveDetailBackStateTest {
    @Test
    fun `提交返回后保持终点直到下一次打开`() {
        val state = PredictiveDetailBackState()

        state.update(0.72f)
        state.commit()

        assertEquals(1f, state.progress)
        assertTrue(state.skipAnimatedExit)

        state.prepareForOpen()
        assertEquals(0f, state.progress)
        assertFalse(state.skipAnimatedExit)
    }

    @Test
    fun `取消返回只复位位移并保留普通退出模式`() {
        val state = PredictiveDetailBackState()

        state.update(0.55f)
        state.cancel()

        assertEquals(0f, state.progress)
        assertFalse(state.skipAnimatedExit)
    }
}
