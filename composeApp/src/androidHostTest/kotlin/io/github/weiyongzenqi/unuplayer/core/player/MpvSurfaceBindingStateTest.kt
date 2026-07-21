package io.github.weiyongzenqi.unuplayer.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MpvSurfaceBindingStateTest {

    @Test
    fun `Surface 先于 init 时只在发布前绑定一次`() {
        val state = MpvSurfaceBindingState<Any>()
        val surface = Any()

        assertNull(state.onAvailable(surface, nativeReady = false))
        assertSame(surface, state.current)
        assertSame(surface, state.pendingForInitialization())

        state.markAttached(surface)
        assertNull(state.pendingForInitialization())
    }

    @Test
    fun `init 前多个 Surface 只保留最后一个`() {
        val state = MpvSurfaceBindingState<Any>()
        val first = Any()
        val latest = Any()

        state.onAvailable(first, nativeReady = false)
        state.onAvailable(latest, nativeReady = false)

        assertSame(latest, state.current)
        assertSame(latest, state.pendingForInitialization())

        state.markAttached(first)
        assertSame(latest, state.pendingForInitialization())
    }

    @Test
    fun `Surface 销毁发生在 init 前时不得补绑旧引用`() {
        val state = MpvSurfaceBindingState<Any>()
        state.onAvailable(Any(), nativeReady = false)

        state.onDestroyed()

        assertNull(state.current)
        assertNull(state.pendingForInitialization())
    }

    @Test
    fun `native 就绪后的 Surface 立即返回给调用方绑定`() {
        val state = MpvSurfaceBindingState<Any>()
        val surface = Any()

        assertSame(surface, state.onAvailable(surface, nativeReady = true))
        assertNull(state.pendingForInitialization())
    }

    @Test
    fun `init 失败保留当前 Surface 供同一 engine 重试`() {
        val state = MpvSurfaceBindingState<Any>()
        val surface = Any()
        state.onAvailable(surface, nativeReady = true)

        state.retainCurrentForRetry()
        assertSame(surface, state.pendingForInitialization())

        state.clearPendingForDestroy()
        assertEquals(surface, state.current)
        assertNull(state.pendingForInitialization())
    }

    @Test
    fun `HDR 重建期间到达的新 Surface 优先于旧快照`() {
        val state = MpvSurfaceBindingState<Any>()
        val oldSurface = Any()
        val newSurface = Any()
        state.onAvailable(oldSurface, nativeReady = true)
        val snapshotGeneration = state.generation

        state.onDestroyed()
        state.onAvailable(newSurface, nativeReady = false)
        state.retainForReinitialization(oldSurface, snapshotGeneration)

        assertSame(newSurface, state.pendingForInitialization())
    }
}
