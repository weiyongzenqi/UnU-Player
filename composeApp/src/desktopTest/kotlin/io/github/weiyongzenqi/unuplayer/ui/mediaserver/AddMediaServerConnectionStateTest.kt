package io.github.weiyongzenqi.unuplayer.ui.mediaserver

import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerVendor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddMediaServerConnectionStateTest {

    @Test
    fun `HTTPS 表单生成规范化 Jellyfin 提交且密码不进入状态文本`() {
        val state = AddMediaServerConnectionState(MediaServerVendor.JELLYFIN).apply {
            name = " 家庭库 "
            baseUrl = "https://media.example.test/jellyfin/"
            username = " alice "
            password = "password-secret"
        }

        val submission = requireNotNull(state.requestSubmit())

        assertEquals("家庭库", submission.name)
        assertEquals("https://media.example.test/jellyfin", submission.baseUrl)
        assertEquals("alice", submission.username)
        assertFalse(submission.allowCleartext)
        assertFalse(state.toString().contains("password-secret"))
        assertFalse(submission.toString().contains("password-secret"))
        assertFalse(submission.toString().contains("media.example.test"))
    }

    @Test
    fun `HTTP 必须二次确认且无效字段不能提交`() {
        val state = AddMediaServerConnectionState(MediaServerVendor.JELLYFIN)
        assertFalse(state.canSubmit)
        assertNull(state.requestSubmit())

        state.name = "家庭库"
        state.username = "alice"
        state.baseUrl = "http://192.168.1.20:8096"

        assertTrue(state.canSubmit)
        assertNull(state.requestSubmit())
        assertTrue(state.awaitingCleartextConfirmation)
        assertTrue(requireNotNull(state.confirmCleartext()).allowCleartext)
        assertFalse(state.awaitingCleartextConfirmation)
    }
}
