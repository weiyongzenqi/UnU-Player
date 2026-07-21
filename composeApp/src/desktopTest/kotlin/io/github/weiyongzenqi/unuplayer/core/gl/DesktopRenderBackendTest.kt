package io.github.weiyongzenqi.unuplayer.core.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopRenderBackendTest {
    @Test
    fun `环境变量优先于平台自动选择`() {
        val selected = DesktopRenderBackend.selectConfiguration(
            environmentApi = "SOFTWARE",
            propertyApi = "DIRECT3D",
            isWindows = true,
            remoteSession = false,
        )

        assertEquals("SOFTWARE", selected.requestedApi)
        assertNull(selected.remoteSession)
    }

    @Test
    fun `本地 Windows 默认使用 Direct3D`() {
        val selected = DesktopRenderBackend.selectConfiguration(null, null, true, false)

        assertEquals("DIRECT3D", selected.requestedApi)
        assertFalse(selected.remoteSession ?: true)
    }

    @Test
    fun `远程 Windows 使用软件兼容后端`() {
        val selected = DesktopRenderBackend.selectConfiguration(null, null, true, true)

        assertEquals("SOFTWARE", selected.requestedApi)
        assertTrue(selected.remoteSession ?: false)
    }

    @Test
    fun `远程会话探测失败保守使用软件后端`() {
        val selected = DesktopRenderBackend.selectConfiguration(null, null, true, null)

        assertEquals("SOFTWARE", selected.requestedApi)
        assertNull(selected.remoteSession)
    }

    @Test
    fun `非 Windows 不覆盖 Skiko 默认后端`() {
        val selected = DesktopRenderBackend.selectConfiguration(null, null, false, null)

        assertNull(selected.requestedApi)
    }
}
