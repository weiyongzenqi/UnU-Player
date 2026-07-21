package io.github.weiyongzenqi.unuplayer.danmaku.render

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.text.TextPaint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.ceil
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode

/** Android 位图弹幕：唯一文本预光栅化，逐帧只提交 ImageBitmap。 */
class BitmapDanmakuEngine : BaseDanmakuEngine() {

    private data class CacheKey(
        val text: String,
        val color: Int,
        val fontBits: Int,
        val strokeBits: Int,
    )

    private class BitmapPayload(
        val bitmap: Bitmap,
        val image: ImageBitmap,
        val bmpW: Int,
        val bmpH: Int,
    ) {
        val estimatedBytes: Long = bitmap.allocationByteCount.toLong().coerceAtLeast(1L)
        var activeUsers: Int = 0
        var cached: Boolean = true
        private var recycled = false

        fun recycleIfUnused(): Boolean {
            if (!cached && activeUsers == 0 && !recycled) {
                recycled = true
                bitmap.recycle()
                return true
            }
            return false
        }
    }

    private val cache = LinkedHashMap<CacheKey, BitmapPayload>(64, 0.75f, true)
    private var cacheBytes = 0L
    private var liveBitmapBytes = 0L

    internal val cachedBitmapBytes: Long get() = cacheBytes
    internal val cachedBitmapCount: Int get() = cache.size
    internal val liveBitmapPixelBytes: Long get() = liveBitmapBytes

    override fun engineName(): String = "bitmap"

    override fun onEntriesReplaced() {
        cache.values.forEach { payload ->
            payload.cached = false
            recyclePayloadIfUnused(payload)
        }
        cache.clear()
        cacheBytes = 0L
    }

    override fun onActiveRemoved(item: ActiveDanmaku) {
        (item.payload as? BitmapPayload)?.let { payload ->
            payload.activeUsers = (payload.activeUsers - 1).coerceAtLeast(0)
            recyclePayloadIfUnused(payload)
        }
    }

    override fun activate(e: DanmakuEntry, posSec: Double, screenW: Float, baseSpeed: Float): Boolean {
        if (e.text.isEmpty()) return false
        val fontPx = effectiveFontSp() * fontScalePx
        val key = CacheKey(e.text, e.color, fontPx.toRawBits(), config.strokeWidth.toRawBits())
        val cached = cache[key]
        val padding = ceil(config.strokeWidth.coerceAtLeast(0f)).toInt() + 1
        val paint = if (cached == null) textPaint(fontPx) else null
        val width = cached?.bmpW ?: (ceil(paint!!.measureText(e.text).toDouble()).toInt() + padding * 2)
            .coerceAtLeast(1)

        val placement = when (e.mode) {
            DanmakuMode.SCROLL -> {
                val lane = scrollAllocator.allocate(e.timeSec, width.toFloat(), baseSpeed)
                if (lane < 0) null else lane to (screenW - (posSec - e.timeSec) * baseSpeed).toFloat()
            }
            DanmakuMode.TOP -> {
                val lane = topAllocator.allocate(e.timeSec, FIXED_DURATION)
                if (lane < 0) null else lane to (screenW - width) / 2f
            }
            DanmakuMode.BOTTOM -> {
                val lane = bottomAllocator.allocate(e.timeSec, FIXED_DURATION)
                if (lane < 0) null else lane to (screenW - width) / 2f
            }
            else -> null
        } ?: return false

        val payload = cached ?: renderAndCache(key, paint!!, padding) ?: return false
        val x = if (e.mode == DanmakuMode.TOP || e.mode == DanmakuMode.BOTTOM) {
            (screenW - payload.bmpW) / 2f
        } else {
            placement.second
        }
        active.add(ActiveDanmaku(e, placement.first, payload.bmpW.toFloat(), x, payload))
        payload.activeUsers++
        trimCache()
        return true
    }

    override fun draw(scope: DrawScope) {
        if (active.isEmpty()) return
        val screenHeight = scope.size.height
        active.forEach { item ->
            val payload = item.payload as? BitmapPayload ?: return@forEach
            val laneTop = laneY(item.entry.mode, item.lane, screenHeight)
            val offsetY = (laneHeight - payload.bmpH) / 2f
            scope.drawImage(payload.image, topLeft = Offset(item.x, laneTop + offsetY))
        }
    }

    private fun renderAndCache(key: CacheKey, paint: TextPaint, padding: Int): BitmapPayload? {
        val fontMetrics = paint.fontMetrics
        val ascent = -ceil(fontMetrics.ascent.toDouble()).toInt()
        val descent = ceil(fontMetrics.descent.toDouble()).toInt()
        val width = (ceil(paint.measureText(key.text).toDouble()).toInt() + padding * 2).coerceAtLeast(1)
        val height = (ascent + descent + padding * 2).coerceAtLeast(1)
        val estimatedBytes = width.toLong() * height * BYTES_PER_PIXEL
        if (estimatedBytes > MAX_LIVE_BITMAP_BYTES || liveBitmapBytes + estimatedBytes > MAX_LIVE_BITMAP_BYTES) {
            return null
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val textX = padding.toFloat()
        val baseline = (padding + ascent).toFloat()
        if (Float.fromBits(key.strokeBits) > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Float.fromBits(key.strokeBits)
            paint.color = android.graphics.Color.BLACK
            canvas.drawText(key.text, textX, baseline, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = rgbToAndroid(key.color)
        canvas.drawText(key.text, textX, baseline, paint)

        return BitmapPayload(bitmap, bitmap.asImageBitmap(), width, height).also { payload ->
            if (liveBitmapBytes + payload.estimatedBytes > MAX_LIVE_BITMAP_BYTES) {
                payload.cached = false
                payload.recycleIfUnused()
                return null
            }
            cache[key] = payload
            cacheBytes += payload.estimatedBytes
            liveBitmapBytes += payload.estimatedBytes
        }
    }

    private fun trimCache() {
        while (cache.size > CACHE_MAX || cacheBytes > CACHE_MAX_BYTES) {
            val iterator = cache.entries.iterator()
            if (!iterator.hasNext()) return
            val entry = iterator.next()
            iterator.remove()
            cacheBytes = (cacheBytes - entry.value.estimatedBytes).coerceAtLeast(0L)
            entry.value.cached = false
            recyclePayloadIfUnused(entry.value)
        }
    }

    private fun recyclePayloadIfUnused(payload: BitmapPayload) {
        if (payload.recycleIfUnused()) {
            liveBitmapBytes = (liveBitmapBytes - payload.estimatedBytes).coerceAtLeast(0L)
        }
    }

    private fun textPaint(fontPx: Float): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontPx
        typeface = android.graphics.Typeface.DEFAULT
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private fun rgbToAndroid(rgb: Int): Int = android.graphics.Color.rgb(
        (rgb shr 16) and 0xFF,
        (rgb shr 8) and 0xFF,
        rgb and 0xFF,
    )

    private companion object {
        const val CACHE_MAX = 300
        const val CACHE_MAX_BYTES = 16L * 1024L * 1024L
        const val MAX_LIVE_BITMAP_BYTES = 32L * 1024L * 1024L
        const val BYTES_PER_PIXEL = 4L
    }
}
