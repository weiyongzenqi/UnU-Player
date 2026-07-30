package io.github.weiyongzenqi.unuplayer.danmaku.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer

/**
 * 离屏 GL 渲染 → 可复用 Bitmap 桥接。
 *
 * 替代 HardwareBuffer 方案（后者在 SDK stubs 中存在 API 兼容问题，留待后续优化）。
 *
 * 流程：
 * 1. GL 线程渲染弹幕到 FBO（RGBA8 纹理，宽度 64-对齐）。
 * 2. `glReadPixels` → 每帧新建的 direct ByteBuffer → 复用的 [bitmap]。
 * 3. Compose Canvas 用 `nativeCanvas.drawBitmap` 提交整层位图。
 *
 * 该链路存在全屏 GPU→CPU 回读和 direct buffer 分配，只用于 GLES_HB 实验路径；
 * 不能据此宣称零分配、零拷贝、预测性返回不掉帧或功耗优于 Atlas。
 *
 * 线程：构造 + swapBuffers 在 GL 线程；bitmap/imageBitmap 可在任意线程读。
 */
internal class OffscreenGLBridge(
    screenWidth: Int,
    screenHeight: Int,
) {
    val alignedWidth: Int = ((screenWidth + 63) / 64) * 64
    val height: Int = screenHeight

    val fbo: Int
    val fboTexture: Int

    /** 复用的 ARGB_8888 Bitmap；回读使用的 direct ByteBuffer 当前仍逐帧分配。 */
    val bitmap: Bitmap = Bitmap.createBitmap(alignedWidth, height, Bitmap.Config.ARGB_8888)

    /** Compose ImageBitmap（从 [bitmap] 创建，每帧 glReadPixels 后调用 [copyPixelsToBitmap] 更新）。 */
    val imageBitmap: ImageBitmap get() = bitmap.asImageBitmap()

    init {
        // 创建 FBO 颜色纹理
        val texArr = IntArray(1)
        GLES30.glGenTextures(1, texArr, 0)
        fboTexture = texArr[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, alignedWidth, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)

        // 创建 FBO
        val fboArr = IntArray(1)
        GLES30.glGenFramebuffers(1, fboArr, 0)
        fbo = fboArr[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTexture, 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) throw RuntimeException("FBO incomplete: 0x${status.toString(16)}")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        Log.d("OffscreenGL", "Created ${alignedWidth}x$height (aligned from $screenWidth)")
    }

    fun bindFbo() { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo) }
    fun unbindFbo() { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0) }

    /**
     * glReadPixels → [bitmap]（复用 Bitmap 实例，覆盖写入）。
     * 必须在 FBO 仍绑定、且 GL 渲染已完成（glFlush 后）时调用。
     */
    fun copyPixelsToBitmap() {
        val buf = ByteBuffer.allocateDirect(alignedWidth * height * 4)
        GLES30.glReadPixels(0, 0, alignedWidth, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        bitmap.copyPixelsFromBuffer(buf)
    }

    fun destroy() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(fboTexture), 0)
        bitmap.recycle()
    }
}
