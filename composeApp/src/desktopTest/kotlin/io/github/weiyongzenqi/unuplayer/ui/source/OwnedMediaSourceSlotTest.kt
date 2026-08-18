package io.github.weiyongzenqi.unuplayer.ui.source

import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OwnedMediaSourceSlotTest {

    @Test
    fun `替换 source 恰好关闭旧实例且 close 可重复`() {
        val first = FakeSource()
        val second = FakeSource()
        val slot = OwnedMediaSourceSlot()

        assertTrue(slot.replace(first))
        assertTrue(slot.replace(second))
        assertEquals(1, first.closeCount)
        assertSame(second, slot.current)

        slot.close()
        slot.close()
        assertEquals(1, second.closeCount)
        assertNull(slot.current)
    }

    @Test
    fun `页面关闭后迟到的 source 会被立即关闭`() {
        val slot = OwnedMediaSourceSlot()
        slot.close()
        val late = FakeSource()

        assertFalse(slot.replace(late))
        assertEquals(1, late.closeCount)
        assertNull(slot.current)
    }

    private class FakeSource : MediaSource {
        override val kind = MediaSourceKind.SMB
        override val displayName = "fake"
        var closeCount = 0

        override suspend fun listFolder(path: String): List<MediaEntry> = error("未用于测试")
        override suspend fun resolvePlayMedia(entry: MediaEntry): PlayableMedia = error("未用于测试")
        override suspend fun testConnection(): Boolean = error("未用于测试")
        override fun close() {
            closeCount++
        }
    }
}
