package io.github.weiyongzenqi.unuplayer.library

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [isBlankFrame](androidMain 集照生成器空白帧检测, internal)单测。
 * 与桌面端 DesktopEpisodeThumbBlankFrameTest 对称, 纯 JVM 计算不依赖 Android 环境。
 *
 * 布局参数对齐生成器实际用法: outW=32 / outH=16, stride 64 字节对齐后 = 128(= outW*4)。
 */
class AndroidEpisodeThumbBlankFrameTest {

    private val outW = 32
    private val outH = 16
    private val stride = 128   // ((32*4 + 63) / 64) * 64, 与生成器 stride 算法一致

    private fun solidFrame(r: Int, g: Int, b: Int): ByteArray {
        val raw = ByteArray(stride * outH)
        for (row in 0 until outH) {
            for (col in 0 until outW) {
                val idx = row * stride + col * 4
                raw[idx] = r.toByte()
                raw[idx + 1] = g.toByte()
                raw[idx + 2] = b.toByte()
                raw[idx + 3] = 0   // X 通道, 检测不读
            }
        }
        return raw
    }

    @Test
    fun `纯黑帧判空白`() {
        assertTrue(isBlankFrame(solidFrame(0, 0, 0), stride, outW, outH))
    }

    @Test
    fun `纯白帧判空白`() {
        assertTrue(isBlankFrame(solidFrame(255, 255, 255), stride, outW, outH))
    }

    @Test
    fun `全同色非黑白帧判空白`() {
        assertTrue(isBlankFrame(solidFrame(10, 200, 30), stride, outW, outH))
    }

    @Test
    fun `随机噪声帧不判空白`() {
        val raw = ByteArray(stride * outH)
        Random(seed = 42).nextBytes(raw)   // 固定种子, 亮度方差远大于阈值 25
        assertFalse(isBlankFrame(raw, stride, outW, outH))
    }
}
