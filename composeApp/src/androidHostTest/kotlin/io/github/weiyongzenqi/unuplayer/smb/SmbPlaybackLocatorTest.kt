package io.github.weiyongzenqi.unuplayer.smb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmbPlaybackLocatorTest {
    @Test
    fun `locator 不携带凭据且可往返特殊路径`() {
        val locator = SmbPlaybackLocator("conn-1", "动画/第 01 集 [1080p].mkv")

        val encoded = locator.toUrl()

        assertEquals(locator, SmbPlaybackLocator.parse(encoded))
        assert(!encoded.contains("conn-1"))
        assert(!encoded.contains("password"))
        assert(!encoded.contains("@"))
    }

    @Test
    fun `locator 拒绝错误 scheme 和损坏编码`() {
        assertNull(SmbPlaybackLocator.parse("smb://server/share/file.mkv"))
        assertNull(SmbPlaybackLocator.parse("smbfd://bad/%"))
        assertNull(SmbPlaybackLocator.parse("smbfd:///path"))
    }

    @Test
    fun `弹幕匹配使用解码后的 SMB 路径`() {
        val url = SmbPlaybackLocator("conn-1", "TMDB-12345/Season 1/S01E02.mkv").toUrl()

        assertEquals("TMDB-12345/Season 1/S01E02.mkv", smbDanmakuMatchPath(url))
        assertEquals("file:///anime/S01E02.mkv", smbDanmakuMatchPath("file:///anime/S01E02.mkv"))
    }
}
