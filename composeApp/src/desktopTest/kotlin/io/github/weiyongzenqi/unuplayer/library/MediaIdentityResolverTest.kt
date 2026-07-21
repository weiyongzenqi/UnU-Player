package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaIdentityResolverTest {
    @Test
    fun `WebDAV key包含连接和导航路径`() {
        assertEquals(
            "webdav:conn:/anime/S01/E01.mkv",
            MediaIdentityResolver.mediaKey(MediaSourceKind.WEBDAV, "conn", "/anime/S01/E01.mkv"),
        )
    }

    @Test
    fun `本地 key使用稳定路径`() {
        assertEquals(
            "local:content://tree/document/video",
            MediaIdentityResolver.mediaKey(MediaSourceKind.LOCAL, null, "content://tree/document/video"),
        )
    }

    @Test
    fun `未知来源不伪造key`() {
        assertNull(MediaIdentityResolver.mediaKey(MediaSourceKind.JELLYFIN, "server", "item:42"))
        assertNull(MediaIdentityResolver.mediaKey(MediaSourceKind.SMB, null, "share/video.mkv"))
    }
}
