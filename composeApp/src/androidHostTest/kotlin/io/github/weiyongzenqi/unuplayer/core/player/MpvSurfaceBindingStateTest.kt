package io.github.weiyongzenqi.unuplayer.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
    fun `init 前到达的尺寸在 Surface attach 后补应用`() {
        val state = MpvSurfaceBindingState<Any>()
        val surface = Any()

        state.onAvailable(surface, nativeReady = false)
        assertTrue(state.onSizeChanged(1080, 608))
        assertNull(state.pendingSurfaceSize(), "wid 尚未绑定时不能先改输出尺寸")

        state.markAttached(surface)
        assertEquals(MpvSurfaceSize(1080, 608), state.pendingSurfaceSize())
    }

    @Test
    fun `连续 resize 只保留最新尺寸`() {
        val state = attachedState()

        state.onSizeChanged(1080, 608)
        state.onSizeChanged(2400, 1080)

        assertEquals(MpvSurfaceSize(2400, 1080), state.pendingSurfaceSize())
    }

    @Test
    fun `已应用的重复尺寸不再次提交`() {
        val surface = Any()
        val state = attachedState(surface)
        val size = MpvSurfaceSize(2400, 1080)

        assertTrue(state.onSizeChanged(size.width, size.height))
        state.markSurfaceSizeApplied(surface, size)

        assertFalse(state.onSizeChanged(size.width, size.height))
        assertNull(state.pendingSurfaceSize())
    }

    @Test
    fun `零或负尺寸不覆盖最后有效尺寸`() {
        val state = attachedState()
        state.onSizeChanged(1920, 1080)

        assertFalse(state.onSizeChanged(0, 1080))
        assertFalse(state.onSizeChanged(1920, -1))

        assertEquals(MpvSurfaceSize(1920, 1080), state.pendingSurfaceSize())
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
        state.onSizeChanged(1080, 608)

        state.onDestroyed()

        assertNull(state.current)
        assertNull(state.pendingForInitialization())
        assertNull(state.pendingSurfaceSize())
    }

    @Test
    fun `native 就绪后的 Surface 立即返回给调用方绑定`() {
        val state = MpvSurfaceBindingState<Any>()
        val surface = Any()

        assertSame(surface, state.onAvailable(surface, nativeReady = true))
        assertNull(state.pendingForInitialization())
    }

    @Test
    fun `新 Surface 不继承旧 Surface 的尺寸`() {
        val state = MpvSurfaceBindingState<Any>()
        val oldSurface = Any()
        val newSurface = Any()
        state.onAvailable(oldSurface, nativeReady = true)
        state.markAttached(oldSurface)
        state.onSizeChanged(1080, 608)
        state.markSurfaceSizeApplied(oldSurface, MpvSurfaceSize(1080, 608))

        state.onAvailable(newSurface, nativeReady = true)
        state.markAttached(newSurface)

        assertNull(state.pendingSurfaceSize())
    }

    @Test
    fun `init 失败保留当前 Surface 供同一 engine 重试`() {
        val state = MpvSurfaceBindingState<Any>()
        val surface = Any()
        state.onAvailable(surface, nativeReady = true)
        state.markAttached(surface)
        val size = MpvSurfaceSize(1920, 1080)
        state.onSizeChanged(size.width, size.height)
        state.markSurfaceSizeApplied(surface, size)

        state.retainCurrentForRetry()
        assertSame(surface, state.pendingForInitialization())
        assertNull(state.pendingSurfaceSize())

        state.markAttached(surface)
        assertEquals(size, state.pendingSurfaceSize(), "新 native 实例必须重新应用相同尺寸")

        state.clearPendingForDestroy()
        assertEquals(surface, state.current)
        assertNull(state.pendingForInitialization())
        assertNull(state.pendingSurfaceSize())
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
        state.onSizeChanged(2400, 1080)
        state.retainForReinitialization(oldSurface, snapshotGeneration)

        assertSame(newSurface, state.pendingForInitialization())
        state.markAttached(newSurface)
        assertEquals(MpvSurfaceSize(2400, 1080), state.pendingSurfaceSize())
    }

    @Test
    fun `HDR 重建后相同尺寸也必须重新应用`() {
        val state = MpvSurfaceBindingState<Any>()
        val surface = Any()
        val size = MpvSurfaceSize(2400, 1080)
        state.onAvailable(surface, nativeReady = true)
        state.markAttached(surface)
        state.onSizeChanged(size.width, size.height)
        state.markSurfaceSizeApplied(surface, size)
        val generation = state.generation

        state.clearPendingForDestroy()
        state.retainForReinitialization(surface, generation)
        state.markAttached(surface)

        assertEquals(size, state.pendingSurfaceSize())
    }

    @Test
    fun `旧尺寸完成时不能清除期间到达的新尺寸`() {
        val surface = Any()
        val state = attachedState(surface)
        val oldSize = MpvSurfaceSize(1080, 608)
        val newSize = MpvSurfaceSize(2400, 1080)
        state.onSizeChanged(oldSize.width, oldSize.height)
        state.onSizeChanged(newSize.width, newSize.height)

        state.markSurfaceSizeApplied(surface, oldSize)

        assertEquals(newSize, state.pendingSurfaceSize())
    }

    private fun attachedState(surface: Any = Any()): MpvSurfaceBindingState<Any> {
        return MpvSurfaceBindingState<Any>().also { state ->
            state.onAvailable(surface, nativeReady = true)
            state.markAttached(surface)
        }
    }
}
