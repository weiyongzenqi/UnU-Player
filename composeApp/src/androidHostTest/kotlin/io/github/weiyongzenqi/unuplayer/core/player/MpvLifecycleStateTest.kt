package io.github.weiyongzenqi.unuplayer.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvLifecycleStateTest {

    @Test
    fun `初始化完整发布后才能对外视为 ready`() {
        val state = MpvLifecycleState()

        state.beginInitialization()
        assertFalse(state.isReady)
        assertTrue(state.callbacksEnabled)
        assertFalse(state.publishReady())
        assertTrue(state.isReady)
    }

    @Test
    fun `初始化期间 destroy 会延迟到发布后 capture`() {
        val state = MpvLifecycleState()

        state.beginInitialization()
        assertEquals(MpvDestroyDecision.DEFERRED, state.requestDestroy())
        assertTrue(state.publishReady())
        state.markCaptured()

        assertFalse(state.isReady)
        assertFalse(state.callbacksEnabled)
        assertEquals(MpvDestroyDecision.NONE, state.requestDestroy())
    }

    @Test
    fun `失败清理完成前禁止重试且旧回调失效`() {
        val state = MpvLifecycleState()

        state.beginInitialization()
        state.beginFailedCleanup()
        assertFalse(state.callbacksEnabled)
        assertEquals(MpvDestroyDecision.DEFERRED, state.requestDestroy())
        assertFailsWith<IllegalStateException> { state.beginInitialization() }

        state.finishFailedCleanup()
        state.beginInitialization()
        assertFalse(state.publishReady())
        assertTrue(state.isReady)
    }

    @Test
    fun `ready 实例由调用方 capture 后禁止旧事件回写`() {
        val state = MpvLifecycleState()
        state.beginInitialization()
        state.publishReady()

        assertEquals(MpvDestroyDecision.CAPTURE_READY, state.requestDestroy())
        state.markCaptured()

        assertFalse(state.isReady)
        assertFalse(state.callbacksEnabled)
    }

    @Test
    fun `快速退出可在 READY 发布前中止初始化`() {
        val state = MpvLifecycleState()
        state.beginInitialization()

        state.abortInitialization()

        assertFalse(state.isReady)
        assertFalse(state.callbacksEnabled)
        assertEquals(MpvDestroyDecision.NONE, state.requestDestroy())
        state.beginInitialization()
    }
}
