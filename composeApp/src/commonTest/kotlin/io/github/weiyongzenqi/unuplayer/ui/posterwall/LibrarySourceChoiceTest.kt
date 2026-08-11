package io.github.weiyongzenqi.unuplayer.ui.posterwall

import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibrarySourceChoiceTest {
    @Test
    fun `添加库来源统一包含本地 WebDAV 和 SMB`() {
        val choices = buildLibrarySourceChoices(
            webDavConnections = listOf(
                WebDavConnection("dav-1", "远程盘", "https://dav.example.test", "user", "password"),
            ),
            smbConnections = listOf(
                SmbConnection("smb-1", "家庭 NAS", "192.0.2.1", share = "anime", username = "user", password = "password"),
            ),
        )

        assertEquals(
            listOf(MediaSourceKind.LOCAL, MediaSourceKind.WEBDAV, MediaSourceKind.SMB),
            choices.map { it.sourceKind },
        )
        assertEquals(listOf(null, "dav-1", "smb-1"), choices.map { it.connectionId })
        assertTrue(choices.all { it.available })
    }

    @Test
    fun `凭据不可用连接保留展示但不可选择`() {
        val choices = buildLibrarySourceChoices(
            webDavConnections = emptyList(),
            smbConnections = listOf(
                SmbConnection(
                    id = "smb-broken",
                    name = "旧 NAS",
                    host = "192.0.2.2",
                    share = "anime",
                    username = "user",
                    password = "",
                    credentialUnavailable = true,
                ),
            ),
        )

        assertTrue(choices.first().available)
        assertFalse(choices.last().available)
        assertTrue(choices.last().displayName.contains("凭据不可用"))
    }

    @Test
    fun `媒体库来源标签覆盖所有枚举值`() {
        assertEquals(MediaSourceKind.entries.size, MediaSourceKind.entries.map(::librarySourceKindLabel).distinct().size)
        assertEquals("SMB", librarySourceKindLabel(MediaSourceKind.SMB))
    }
}
