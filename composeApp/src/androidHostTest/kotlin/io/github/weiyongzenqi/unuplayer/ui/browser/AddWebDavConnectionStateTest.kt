package io.github.weiyongzenqi.unuplayer.ui.browser

import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddWebDavConnectionStateTest {

    @Test
    fun `编辑表单保留原连接标识和字段`() {
        val state = AddWebDavConnectionState(
            WebDavConnection(
                id = "existing-id",
                name = "家庭服务器",
                baseUrl = "https://example.invalid/dav",
                username = "user",
                password = "secret",
            ),
        )

        val submission = state.requestSubmit()

        assertEquals("existing-id", submission?.connection?.id)
        assertEquals("家庭服务器", submission?.connection?.name)
        assertEquals("https://example.invalid/dav", submission?.connection?.baseUrl)
        assertEquals("user", submission?.connection?.username)
        assertEquals("secret", submission?.connection?.password)
    }

    @Test
    fun `失效凭据编辑时必须重新输入密码`() {
        val state = AddWebDavConnectionState(
            WebDavConnection(
                id = "broken-id",
                name = "旧服务器",
                baseUrl = "https://example.invalid/dav",
                username = "user",
                password = "",
                credentialUnavailable = true,
            ),
        )

        assertFalse(state.canSubmit)
        state.password = "new-secret"
        assertTrue(state.canSubmit)
        assertEquals("broken-id", state.requestSubmit()?.connection?.id)
    }

    @Test
    fun `HTTPS 表单无需风险确认直接提交`() {
        val state = filledState("https://example.invalid/dav/")

        val submission = state.requestSubmit()

        assertEquals("connection-id", submission?.connection?.id)
        assertEquals("https://example.invalid/dav", submission?.connection?.baseUrl)
        assertFalse(submission?.allowCleartext ?: true)
        assertNull(state.pendingCleartextConnection)
    }

    @Test
    fun `HTTP 表单只能在独立确认后提交授权`() {
        val state = filledState("http://192.168.1.20/dav")

        assertNull(state.requestSubmit())
        assertEquals("http://192.168.1.20/dav", state.pendingCleartextConnection?.baseUrl)

        val submission = state.confirmCleartext()
        assertTrue(submission?.allowCleartext == true)
        assertNull(state.pendingCleartextConnection)
    }

    @Test
    fun `从 HTTP 风险提示返回保留全部表单字段`() {
        val state = filledState("http://192.168.1.20/dav")
        state.requestSubmit()

        state.returnToForm()

        assertEquals("家庭服务器", state.name)
        assertEquals("http://192.168.1.20/dav", state.baseUrl)
        assertEquals("user", state.username)
        assertEquals("secret", state.password)
        assertNull(state.pendingCleartextConnection)
    }

    @Test
    fun `非法地址和空名称不能提交`() {
        val state = filledState("ftp://example.invalid/dav")
        assertFalse(state.canSubmit)
        assertNull(state.requestSubmit())

        state.baseUrl = "https://example.invalid/dav"
        state.name = " "
        assertFalse(state.canSubmit)
        assertNull(state.requestSubmit())
    }

    private fun filledState(baseUrl: String) = AddWebDavConnectionState("connection-id").apply {
        name = "家庭服务器"
        this.baseUrl = baseUrl
        username = "user"
        password = "secret"
    }
}
