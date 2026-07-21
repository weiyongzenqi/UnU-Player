package io.github.weiyongzenqi.unuplayer.core.player

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopHwdecPolicyTest {
    @Test
    fun `直出模式映射为 sw render 可用的 copy 模式`() {
        assertEquals("auto-copy", effectiveDesktopHwdec("auto"))
        assertEquals("nvdec-copy", effectiveDesktopHwdec("nvdec"))
        assertEquals("d3d11va-copy", effectiveDesktopHwdec("d3d11va"))
    }

    @Test
    fun `copy 和软件模式保持不变`() {
        assertEquals("auto-copy", effectiveDesktopHwdec("auto-copy"))
        assertEquals("nvdec-copy", effectiveDesktopHwdec("nvdec-copy"))
        assertEquals("d3d11va-copy", effectiveDesktopHwdec("d3d11va-copy"))
        assertEquals("no", effectiveDesktopHwdec("no"))
    }

    @Test
    fun `未知直出模式回退为自动 copy`() {
        assertEquals("auto-copy", effectiveDesktopHwdec("unknown-gpu-mode"))
    }
}
