package io.github.weiyongzenqi.unuplayer.core.gl

import com.sun.jna.Pointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WglSharedContextTest {

    @Test
    fun `share失败只删除新建context且不解绑owner`() {
        val fake = FakeWgl(currentContext = pointer(10), currentDc = pointer(20), shareSucceeds = false)

        assertNull(createSharedWglContext(fake))
        assertEquals(listOf<Long>(30), fake.deletedContexts.map { Pointer.nativeValue(it) })
        assertTrue(fake.makeCurrentCalls.isEmpty())
    }

    @Test
    fun `销毁非current的shared context不会误解绑owner`() {
        val fake = FakeWgl(currentContext = pointer(10), currentDc = pointer(20))
        val shared = assertNotNull(createSharedWglContext(fake))

        shared.destroy()

        assertEquals(listOf<Long>(30), fake.deletedContexts.map { Pointer.nativeValue(it) })
        assertTrue(fake.makeCurrentCalls.isEmpty())
    }

    @Test
    fun `销毁当前shared context会先解绑再且只删除一次`() {
        val fake = FakeWgl(currentContext = pointer(10), currentDc = pointer(20))
        val shared = assertNotNull(createSharedWglContext(fake))
        fake.currentContext = shared.context

        shared.destroy()
        shared.destroy()

        assertEquals(1, fake.makeCurrentCalls.size)
        assertNull(fake.makeCurrentCalls.single().first)
        assertNull(fake.makeCurrentCalls.single().second)
        assertEquals(listOf<Long>(30), fake.deletedContexts.map { Pointer.nativeValue(it) })
    }

    @Test
    fun `share调用抛错仍只回滚一次新建context`() {
        val fake = FakeWgl(
            currentContext = pointer(10),
            currentDc = pointer(20),
            shareError = IllegalStateException("fault"),
        )

        assertFailsWith<IllegalStateException> { createSharedWglContext(fake) }
        assertEquals(listOf<Long>(30), fake.deletedContexts.map { Pointer.nativeValue(it) })
    }

    private class FakeWgl(
        var currentContext: Pointer?,
        private val currentDc: Pointer?,
        private val shareSucceeds: Boolean = true,
        private val shareError: Throwable? = null,
    ) : WglLib {
        val makeCurrentCalls = mutableListOf<Pair<Pointer?, Pointer?>>()
        val deletedContexts = mutableListOf<Pointer?>()

        override fun wglGetCurrentContext(): Pointer? = currentContext
        override fun wglGetCurrentDC(): Pointer? = currentDc
        override fun wglMakeCurrent(hdc: Pointer?, hglrc: Pointer?): Boolean {
            makeCurrentCalls += hdc to hglrc
            currentContext = hglrc
            return true
        }
        override fun wglCreateContext(hdc: Pointer?): Pointer? = pointer(30)
        override fun wglDeleteContext(hglrc: Pointer?): Boolean {
            deletedContexts += hglrc
            return true
        }
        override fun wglShareLists(hglrcSrc: Pointer?, hglrcDst: Pointer?): Boolean {
            shareError?.let { throw it }
            return shareSucceeds
        }
        override fun wglGetProcAddress(name: String): Pointer? = null
    }

    private companion object {
        fun pointer(address: Long): Pointer = Pointer(address)
    }
}
