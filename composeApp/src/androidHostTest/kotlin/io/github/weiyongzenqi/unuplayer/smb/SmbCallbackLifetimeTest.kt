package io.github.weiyongzenqi.unuplayer.smb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmbCallbackLifetimeTest {

    @Test
    fun `close waits until active callback is released`() {
        var closed = false
        val callback = Any()
        val lifetime = SmbCallbackLifetime { closed = true }

        lifetime.beginOpen()
        assertTrue(lifetime.finishOpen(callback))
        lifetime.close()
        assertFalse(closed)

        lifetime.release(callback)
        assertTrue(closed)
    }

    @Test
    fun `close rejects an opening that finishes later`() {
        var closeCount = 0
        val lifetime = SmbCallbackLifetime { closeCount += 1 }

        lifetime.beginOpen()
        lifetime.close()
        assertEquals(0, closeCount)

        assertFalse(lifetime.finishOpen(Any()))
        assertEquals(1, closeCount)
        lifetime.close()
        assertEquals(1, closeCount)
        assertFailsWith<IllegalStateException> { lifetime.beginOpen() }
    }

    @Test
    fun `aborted opening completes deferred close`() {
        var closed = false
        val lifetime = SmbCallbackLifetime { closed = true }

        lifetime.beginOpen()
        lifetime.close()
        lifetime.abortOpen()

        assertTrue(closed)
    }
}
