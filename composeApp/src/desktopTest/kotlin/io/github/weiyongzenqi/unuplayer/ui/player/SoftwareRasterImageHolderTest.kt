package io.github.weiyongzenqi.unuplayer.ui.player

import org.jetbrains.skia.Data
import io.github.weiyongzenqi.unuplayer.core.player.DesktopMpvPlayerEngine
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * CR-069: 验证 SoftwareRasterImageHolder 的 Image 缓存命中。
 *
 * 缺陷: draw finally 每帧 close Image 会置 image=null/pixels=null/version=-1, 下次 draw 必走
 * Image.makeRaster 重建, 击穿 version/pixels 缓存(暂停时每帧重建 ~60/s)。
 * 修复: finally 不 close, imageFor 检测 pixels/version 变化时才 close 旧 Image 换新。
 *
 * 注: ColorType.RGB_888X 是 32 位格式(4 字节/像素), stride 须 >= width*4。
 */
class SoftwareRasterImageHolderTest {

    private fun frame(
        pixels: Data,
        version: Long,
        width: Int = 2,
        height: Int = 2,
        stride: Int = 8,
    ): DesktopMpvPlayerEngine.SoftwareVideoFrame =
        DesktopMpvPlayerEngine.SoftwareVideoFrame(
            pixels = pixels,
            width = width,
            height = height,
            stride = stride,
            version = version,
        )

    @Test
    fun `同帧复用 Image 不重建`() {
        val holder = SoftwareRasterImageHolder()
        val pixels = Data.makeUninitialized(16)
        val frame = frame(pixels, version = 1L)
        try {
            val img1 = holder.imageFor(frame)
            val img2 = holder.imageFor(frame)
            // 同帧(version/pixels/尺寸不变)必须命中缓存, 返回同一 Image 实例, 不重建
            assertSame(img1, img2)
        } finally {
            holder.close()
        }
    }

    @Test
    fun `帧变化时换新 Image`() {
        val holder = SoftwareRasterImageHolder()
        val pixels1 = Data.makeUninitialized(16)
        val frame1 = frame(pixels1, version = 1L)
        val pixels2 = Data.makeUninitialized(16)
        val frame2 = frame(pixels2, version = 2L)
        try {
            val img1 = holder.imageFor(frame1)
            val img2 = holder.imageFor(frame2)
            // 帧变化(version/pixels 不同)必须 close 旧 Image 换新, 返回不同实例
            assertNotSame(img1, img2)
        } finally {
            holder.close()
        }
    }

    @Test
    fun `close 后下次 imageFor 重建 Image`() {
        val holder = SoftwareRasterImageHolder()
        val pixels = Data.makeUninitialized(16)
        val frame = frame(pixels, version = 1L)
        val img1 = holder.imageFor(frame)
        // 模拟旧实现 finally 每帧 close 的场景: close 后 image=null, 下次必重建
        holder.close()
        val img2 = holder.imageFor(frame)
        try {
            assertNotSame(img1, img2)
        } finally {
            holder.close()
        }
    }
}
