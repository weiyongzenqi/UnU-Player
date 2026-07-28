package io.github.weiyongzenqi.unuplayer.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MediaKeysTest {

    @Test
    fun `媒体服务器 key 只包含厂商连接和 item 定位`() {
        val jellyfin = MediaKeys.mediaServer(MediaSourceKind.JELLYFIN, "connection-1", "item:episode-1")
        val emby = MediaKeys.mediaServer(MediaSourceKind.EMBY, "connection-2", "42")

        assertEquals("jellyfin:connection-1:item:episode-1", jellyfin)
        assertEquals(
            MediaServerMediaKey(MediaSourceKind.JELLYFIN, "connection-1", "item:episode-1"),
            MediaKeys.parseMediaServer(jellyfin),
        )
        assertEquals(
            MediaServerMediaKey(MediaSourceKind.EMBY, "connection-2", "42"),
            MediaKeys.parseMediaServer(emby),
        )
    }

    @Test
    fun `无效或非媒体服务器 key 不会被误解析`() {
        assertNull(MediaKeys.parseMediaServer(null))
        assertNull(MediaKeys.parseMediaServer("webdav:connection:/video"))
        assertNull(MediaKeys.parseMediaServer("jellyfin::item"))
        assertNull(MediaKeys.parseMediaServer("emby:connection:"))
        assertFailsWith<IllegalArgumentException> {
            MediaKeys.mediaServer(MediaSourceKind.WEBDAV, "connection", "item")
        }
    }
}
