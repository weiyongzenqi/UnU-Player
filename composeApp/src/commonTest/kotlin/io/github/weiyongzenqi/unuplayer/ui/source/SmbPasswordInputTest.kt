package io.github.weiyongzenqi.unuplayer.ui.source

import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmbPasswordInputTest {
    @Test
    fun `编辑留空时保留原密码`() {
        assertEquals(
            "stored",
            effectiveSmbPassword(enteredPassword = "", storedPassword = "stored", useEmptyPassword = false),
        )
    }

    @Test
    fun `显式选择空密码时覆盖原密码`() {
        assertEquals(
            "",
            effectiveSmbPassword(enteredPassword = "typed", storedPassword = "stored", useEmptyPassword = true),
        )
    }

    @Test
    fun `凭据失效连接可用空密码修复`() {
        val unavailable = SmbConnection(
            id = "broken",
            name = "NAS",
            host = "192.0.2.1",
            share = "media",
            username = "viewer",
            password = "",
            credentialUnavailable = true,
        )

        assertFalse(smbPasswordReady(unavailable, enteredPassword = "", useEmptyPassword = false))
        assertTrue(smbPasswordReady(unavailable, enteredPassword = "", useEmptyPassword = true))
    }
}
