package io.github.weiyongzenqi.unuplayer.danmaku.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Shader
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAtlasVertexColorDeviceTest {
    @Test
    fun 单帧最多检查二百五十六条并跨帧继续() {
        val engine = AndroidAtlasDanmakuEngine(pageSize = 64, maxPages = 1, cacheMax = 8)
        engine.setConfig(DanmakuConfig(maxOnScreen = 0, fontSize = 16f))
        engine.load((0 until 600).map { danmakuEntry(0.0, "same") })

        engine.onFrame(1L, 1_000f, 50_000f, 0.016f)
        assertEquals(256, engine.lastActivationCandidateCount)
        assertEquals(256, engine.activeDanmakuCount)
        assertEquals(DanmakuFrameSchedule.Continuous, engine.frameSchedule())

        engine.onFrame(2L, 1_000f, 50_000f, 0.016f)
        assertEquals(256, engine.lastActivationCandidateCount)
        assertEquals(512, engine.activeDanmakuCount)
        engine.onFrame(3L, 1_000f, 50_000f, 0.016f)
        assertEquals(88, engine.lastActivationCandidateCount)
        assertEquals(600, engine.activeDanmakuCount)
    }

    @Test
    fun 轨道满时不光栅化后续唯一文本() {
        val engine = AndroidAtlasDanmakuEngine(pageSize = 64, maxPages = 1, cacheMax = 8)
        engine.setConfig(DanmakuConfig(maxOnScreen = 0, fontSize = 16f))
        engine.load(listOf(danmakuEntry(0.0, "first"), danmakuEntry(0.0, "second")))

        engine.onFrame(1L, 1_000f, 24f, 0.016f)

        assertEquals(1, engine.activeDanmakuCount)
        assertEquals(1, engine.rasterCount)
        assertEquals(1, engine.cachedRegionCount)
    }

    @Test
    fun 超长文本无法进入page时回退而不丢弹幕() {
        val engine = AndroidAtlasDanmakuEngine(pageSize = 64, maxPages = 1, cacheMax = 8)
        engine.setConfig(DanmakuConfig(maxOnScreen = 0, fontSize = 16f))
        engine.load(listOf(danmakuEntry(0.0, "W".repeat(20))))

        assertTrue(engine.onFrame(4_000L, 1_000f, 500f, 0.016f))

        assertEquals(1, engine.activeDanmakuCount)
        assertEquals(0, engine.cachedRegionCount)
        assertEquals(1, engine.atlasInsertionFailureCount)

        val target = Bitmap.createBitmap(1_000, 64, Bitmap.Config.ARGB_8888)
        try {
            target.eraseColor(Color.TRANSPARENT)
            CanvasDrawScope().draw(
                Density(1f),
                LayoutDirection.Ltr,
                ComposeCanvas(target.asImageBitmap()),
                Size(target.width.toFloat(), target.height.toFloat()),
            ) {
                engine.draw(this)
            }

            assertEquals(1, engine.directTextFallbackCount)
            assertTrue(target.containsNonTransparentPixel(), "direct text 回退没有输出任何像素")
        } finally {
            target.recycle()
        }
    }

    @Test
    fun 同一region被多个活跃弹幕引用时不会淘汰() {
        val engine = AndroidAtlasDanmakuEngine(pageSize = 64, maxPages = 1, cacheMax = 1)
        engine.setConfig(DanmakuConfig(maxOnScreen = 0, fontSize = 16f))
        engine.load(
            listOf(
                danmakuEntry(0.0, "same"),
                danmakuEntry(0.0, "same"),
                danmakuEntry(0.0, "other"),
            ),
        )

        engine.onFrame(1L, 1_000f, 500f, 0.016f)

        assertEquals(3, engine.activeDanmakuCount)
        assertEquals(1, engine.cachedRegionCount)
        assertEquals(1, engine.rasterCount)
        assertEquals(0, engine.evictionCount)
        assertEquals(1, engine.atlasInsertionFailureCount)
    }

    @Test
    fun vertexColor把白填充染色且保持黑描边() {
        val atlas = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val target = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            atlas.eraseColor(Color.WHITE)
            repeat(4) { y -> atlas.setPixel(0, y, Color.BLACK) }
            target.eraseColor(Color.TRANSPARENT)
            val batch = AndroidAtlasVertexBatch(maxQuads = 1, initialCapacity = 1)
            batch.add(0f, 0f, 0, 0, 4, 4, 0xE53935)

            drawAndroidAtlasVertices(
                canvas = Canvas(target),
                shader = BitmapShader(atlas, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
                batch = batch,
                paint = Paint(),
            )

            assertEquals(Color.BLACK, target.getPixel(0, 2))
            assertEquals(0xFFE53935.toInt(), target.getPixel(2, 2))
        } finally {
            atlas.recycle()
            target.recycle()
        }
    }

    @Test
    fun hwui硬件Canvas把白填充染色且保持黑描边() {
        val size = 64
        val atlas = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val reader = ImageReader.newInstance(size, size, PixelFormat.RGBA_8888, 2)
        val callbackThread = HandlerThread("atlas-pixel-test").apply { start() }
        val imageReady = CountDownLatch(1)
        var image: Image? = null
        reader.setOnImageAvailableListener({ source ->
            image = source.acquireLatestImage()
            imageReady.countDown()
        }, Handler(callbackThread.looper))
        try {
            atlas.eraseColor(Color.WHITE)
            for (y in 0 until size) {
                for (x in 0 until 16) atlas.setPixel(x, y, Color.BLACK)
            }
            val batch = AndroidAtlasVertexBatch(maxQuads = 1, initialCapacity = 1)
            batch.add(0f, 0f, 0, 0, size, size, 0xE53935)

            val canvas = reader.surface.lockHardwareCanvas()
            assertTrue(canvas.isHardwareAccelerated)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            drawAndroidAtlasVertices(
                canvas = canvas,
                shader = BitmapShader(atlas, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
                batch = batch,
                paint = Paint(),
            )
            reader.surface.unlockCanvasAndPost(canvas)

            assertTrue(imageReady.await(5, TimeUnit.SECONDS), "HWUI 帧未发布到 ImageReader")
            val rendered = assertNotNull(image)
            assertEquals(Color.BLACK, rendered.rgbaPixel(8, 32))
            assertEquals(0xFFE53935.toInt(), rendered.rgbaPixel(40, 32))
        } finally {
            image?.close()
            reader.close()
            callbackThread.quitSafely()
            callbackThread.join(5_000)
            atlas.recycle()
        }
    }

    private fun danmakuEntry(timeSec: Double, text: String) = DanmakuEntry(
        timeSec = timeSec,
        mode = DanmakuMode.SCROLL,
        color = 0xFFFFFF,
        text = text,
        source = DanmakuSource.LOCAL,
    )

    private fun Image.rgbaPixel(x: Int, y: Int): Int {
        assertEquals(PixelFormat.RGBA_8888, format)
        val plane = planes.single()
        val offset = y * plane.rowStride + x * plane.pixelStride
        val buffer = plane.buffer
        val red = buffer.get(offset).toInt() and 0xFF
        val green = buffer.get(offset + 1).toInt() and 0xFF
        val blue = buffer.get(offset + 2).toInt() and 0xFF
        val alpha = buffer.get(offset + 3).toInt() and 0xFF
        return Color.argb(alpha, red, green, blue)
    }

    private fun Bitmap.containsNonTransparentPixel(): Boolean {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.any { Color.alpha(it) != 0 }
    }
}
