package io.github.weiyongzenqi.unuplayer.library

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrapedImagePreviewTest {
    @Test
    fun `双击在原始尺寸和两倍之间切换`() {
        assertEquals(2f, nextImagePreviewScale(1f))
        assertEquals(1f, nextImagePreviewScale(2f))
    }

    @Test
    fun `下滑超过视口五分之一才退出`() {
        assertFalse(shouldDismissImagePreview(199f, 1000f))
        assertTrue(shouldDismissImagePreview(200f, 1000f))
        assertFalse(shouldDismissImagePreview(200f, 0f))
    }

    @Test
    fun `下滑时背景透明度平滑下降且保持合法范围`() {
        assertEquals(1f, imagePreviewBackgroundAlpha(0f, 1000f))
        assertTrue(imagePreviewBackgroundAlpha(200f, 1000f) < 1f)
        assertEquals(0f, imagePreviewBackgroundAlpha(1000f, 1000f))
        assertEquals(1f, imagePreviewBackgroundAlpha(100f, 0f))
    }

    @Test
    fun `宽图两倍缩放后只允许按实际横向溢出平移`() {
        assertEquals(
            ImagePreviewPanBounds(maxOffsetX = 500f, maxOffsetY = 0f),
            imagePreviewPanBounds(
                viewportWidthPx = 1000f,
                viewportHeightPx = 1000f,
                imageAspectRatio = 2f,
                scale = 2f,
            ),
        )
    }

    @Test
    fun `长图两倍缩放后只允许按实际纵向溢出平移`() {
        assertEquals(
            ImagePreviewPanBounds(maxOffsetX = 0f, maxOffsetY = 500f),
            imagePreviewPanBounds(
                viewportWidthPx = 1000f,
                viewportHeightPx = 1000f,
                imageAspectRatio = 0.5f,
                scale = 2f,
            ),
        )
    }

    @Test
    fun `原始尺寸或无效视口没有平移空间`() {
        assertEquals(
            ImagePreviewPanBounds(0f, 0f),
            imagePreviewPanBounds(1000f, 1000f, imageAspectRatio = 2f, scale = 1f),
        )
        assertEquals(
            ImagePreviewPanBounds(0f, 0f),
            imagePreviewPanBounds(0f, 1000f, imageAspectRatio = 2f, scale = 2f),
        )
    }

    // ---------- 双指缩放变换 ----------

    @Test
    fun `双指缩放按倍率放大且缩回原始尺寸时转为下滑拖动语义`() {
        // 捏合中心在视口中心时等价于围绕中心缩放
        val zoomIn = imagePreviewPinchTransform(
            viewportWidthPx = 1000f,
            viewportHeightPx = 1000f,
            imageAspectRatio = 1f,
            totalScale = 1f,
            zoom = 2f,
            centroidX = 500f,
            centroidY = 500f,
            panX = 0f,
            panY = 0f,
            currentOffsetX = 0f,
            currentOffsetY = 0f,
        )
        assertEquals(2f, zoomIn.scale)
        assertEquals(0f, zoomIn.offsetX)
        assertEquals(0f, zoomIn.offsetY)

        val zoomOut = imagePreviewPinchTransform(
            viewportWidthPx = 1000f,
            viewportHeightPx = 1000f,
            imageAspectRatio = 1f,
            totalScale = 1.5f,
            zoom = 0.5f,
            centroidX = 500f,
            centroidY = 500f,
            panX = 30f,
            panY = -20f,
            currentOffsetX = 100f,
            currentOffsetY = 50f,
        )
        assertEquals(1f, zoomOut.scale)
        // 缩回 1 倍不再强制清零: 转为下滑拖动语义(Y 继续累积、X 归零), 手势结束由归整统一复位
        assertEquals(0f, zoomOut.offsetX)
        assertEquals(30f, zoomOut.offsetY)
    }

    @Test
    fun `低于有效缩放阈值时保留并累积下滑位移`() {
        // 下滑退出拖动中途第二指落下: 已累积位移不被清零, 双指平移继续累积
        val transform = imagePreviewPinchTransform(
            viewportWidthPx = 1000f,
            viewportHeightPx = 1000f,
            imageAspectRatio = 1f,
            totalScale = 1f,
            zoom = 1f,
            centroidX = 400f,
            centroidY = 600f,
            panX = 8f,
            panY = 30f,
            currentOffsetX = 0f,
            currentOffsetY = 150f,
        )
        assertEquals(1f, transform.scale)
        assertEquals(0f, transform.offsetX)
        assertEquals(180f, transform.offsetY)

        // 向上抹不会把位移拖成负值
        val upward = imagePreviewPinchTransform(
            viewportWidthPx = 1000f,
            viewportHeightPx = 1000f,
            imageAspectRatio = 1f,
            totalScale = 1f,
            zoom = 0.99f,
            centroidX = 500f,
            centroidY = 500f,
            panX = 0f,
            panY = -200f,
            currentOffsetX = 0f,
            currentOffsetY = 150f,
        )
        assertEquals(0f, upward.offsetY)
    }

    @Test
    fun `双指缩放围绕捏合中心保持手指下内容不动`() {
        // 捏合中心 (600,400), 当前偏移 (100,50): 该处图片点相对中心 = (600-500-100, 400-500-50) = (0,-150)
        // 放大 2 倍后该点仍应在 (600,400): 新偏移 = 旧偏移*2 + (中心-视口中心)*(1-2) = (100, 200)
        val transform = imagePreviewPinchTransform(
            viewportWidthPx = 1000f,
            viewportHeightPx = 1000f,
            imageAspectRatio = 1f,
            totalScale = 1f,
            zoom = 2f,
            centroidX = 600f,
            centroidY = 400f,
            panX = 0f,
            panY = 0f,
            currentOffsetX = 100f,
            currentOffsetY = 50f,
        )
        assertEquals(2f, transform.scale)
        assertEquals(100f, transform.offsetX)
        assertEquals(200f, transform.offsetY)
        // 不变点验证: 图片点 (0,-150)*2 + (100,200) + 视口中心 (500,500) = (600,400) 仍在捏合中心下
    }

    @Test
    fun `放大后双指纯平移叠加平移且不改变倍率`() {
        val transform = imagePreviewPinchTransform(
            viewportWidthPx = 1000f,
            viewportHeightPx = 1000f,
            imageAspectRatio = 1f,
            totalScale = 2f,
            zoom = 1f,
            centroidX = 300f,
            centroidY = 300f,
            panX = 30f,
            panY = -20f,
            currentOffsetX = 10f,
            currentOffsetY = 5f,
        )
        assertEquals(2f, transform.scale)
        assertEquals(40f, transform.offsetX)
        assertEquals(-15f, transform.offsetY)
    }

    @Test
    fun `双指缩放超上限封顶到三倍`() {
        val transform = imagePreviewPinchTransform(
            viewportWidthPx = 1000f,
            viewportHeightPx = 1000f,
            imageAspectRatio = 1f,
            totalScale = 2.5f,
            zoom = 2f,
            centroidX = 500f,
            centroidY = 500f,
            panX = 0f,
            panY = 0f,
            currentOffsetX = 0f,
            currentOffsetY = 0f,
        )
        assertEquals(3f, transform.scale)
    }

    @Test
    fun `宽图放大后偏移按实际横向溢出钳制`() {
        // 2:1 宽图在 1000x1000 视口放大到 2 倍: 横向溢出 1000, maxOffsetX = 500; 纵向无溢出
        val transform = imagePreviewPinchTransform(
            viewportWidthPx = 1000f,
            viewportHeightPx = 1000f,
            imageAspectRatio = 2f,
            totalScale = 1f,
            zoom = 2f,
            centroidX = 500f,
            centroidY = 500f,
            panX = 9999f,
            panY = 9999f,
            currentOffsetX = 0f,
            currentOffsetY = 0f,
        )
        assertEquals(2f, transform.scale)
        assertEquals(500f, transform.offsetX)
        assertEquals(0f, transform.offsetY)
    }

    @Test
    fun `长图放大后偏移按实际纵向溢出钳制`() {
        val transform = imagePreviewPinchTransform(
            viewportWidthPx = 1000f,
            viewportHeightPx = 1000f,
            imageAspectRatio = 0.5f,
            totalScale = 1f,
            zoom = 2f,
            centroidX = 500f,
            centroidY = 500f,
            panX = 9999f,
            panY = 9999f,
            currentOffsetX = 0f,
            currentOffsetY = 0f,
        )
        assertEquals(0f, transform.offsetX)
        assertEquals(500f, transform.offsetY)
    }

    // ---------- 手势结束倍率归整 ----------

    @Test
    fun `不足有效放大阈值归整回一倍并要求偏移归零`() {
        assertEquals(ImagePreviewRebaseResult(1f, resetOffset = true), imagePreviewRebase(1f, 1f))
        assertEquals(ImagePreviewRebaseResult(1f, resetOffset = true), imagePreviewRebase(1f, 1.05f))
        assertEquals(ImagePreviewRebaseResult(1f, resetOffset = true), imagePreviewRebase(2f, 0.5f), "捏回一倍")
    }

    @Test
    fun `达到有效放大阈值吸收进布局倍率且偏移保留`() {
        assertEquals(ImagePreviewRebaseResult(1.1f, resetOffset = false), imagePreviewRebase(1f, 1.1f))
        assertEquals(ImagePreviewRebaseResult(2f, resetOffset = false), imagePreviewRebase(1f, 2f))
        assertEquals(ImagePreviewRebaseResult(1.5f, resetOffset = false), imagePreviewRebase(3f, 0.5f))
    }

    @Test
    fun `归整超上限封顶到三倍`() {
        assertEquals(ImagePreviewRebaseResult(3f, resetOffset = false), imagePreviewRebase(2f, 2f))
    }

    // ---------- 下滑跟随缩小 ----------

    @Test
    fun `下滑缩小比例随位移平滑下降且封底`() {
        assertEquals(1f, imagePreviewDismissScale(0f, 1000f))
        assertEquals(1f, imagePreviewDismissScale(-50f, 1000f), "负位移不反向放大")
        assertTrue(abs(imagePreviewDismissScale(750f, 1000f) - 0.65f) < 0.001f, "拖满四分之三视口缩到 0.65")
        assertTrue(abs(imagePreviewDismissScale(1000f, 1000f) - 0.6f) < 0.001f, "拖满保底不缩没")
        assertTrue(abs(imagePreviewDismissScale(2000f, 1000f) - 0.6f) < 0.001f, "越过视口继续封底")
        assertEquals(1f, imagePreviewDismissScale(100f, 0f), "视口无效不缩放")
    }
}
