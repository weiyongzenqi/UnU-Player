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

    private data class TextMetrics(
        val width: Int,
        val height: Int,
        val ascent: Int,
        val padding: Int,
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
    internal var rasterCount: Int = 0
        private set

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
        if (e.text.isEmpty()) {
            trimCache()
            return false
        }
        val fontPx = effectiveFontSp() * fontScalePx
        val key = CacheKey(e.text, e.color, fontPx.toRawBits(), config.strokeWidth.toRawBits())
        val cached = cache[key]
        val paint = if (cached == null) textPaint(fontPx) else null
        val metrics = cached?.let { TextMetrics(it.bmpW, it.bmpH, 0, 0) }
            ?: measure(key, checkNotNull(paint))
        if (metrics.width <= 0 || metrics.height <= 0) {
            trimCache()
            return false
        }
        val width = metrics.width.toFloat()

        // 与 Atlas 保持同一事务顺序：轨道只查询，确认可见后才光栅化；载荷成功后才提交轨道。
        // finally 在轨道满、载荷失败和成功出口都整理预算，旧失败缓存不会继续增长到 live hard cap。
        return runDanmakuActivationTransaction(
            findLane = {
                when (e.mode) {
                    DanmakuMode.SCROLL -> scrollAllocator.findAvailableLane(e.timeSec, baseSpeed)
                    DanmakuMode.TOP -> topAllocator.findAvailableLane(e.timeSec)
                    DanmakuMode.BOTTOM -> bottomAllocator.findAvailableLane(e.timeSec)
                    else -> -1
                }
            },
            preparePayload = { cached ?: renderAndCache(key, checkNotNull(paint), metrics) },
            commit = { lane, payload ->
                when (e.mode) {
                    DanmakuMode.SCROLL -> scrollAllocator.occupy(lane, e.timeSec, width)
                    DanmakuMode.TOP -> topAllocator.occupy(lane, e.timeSec, FIXED_DURATION)
                    DanmakuMode.BOTTOM -> bottomAllocator.occupy(lane, e.timeSec, FIXED_DURATION)
                    else -> error("不支持的弹幕模式: ${e.mode}")
                }
                val x = if (e.mode == DanmakuMode.TOP || e.mode == DanmakuMode.BOTTOM) {
                    (screenW - width) / 2f
                } else {
                    (screenW - (posSec - e.timeSec) * baseSpeed).toFloat()
                }
                active.add(ActiveDanmaku(e, lane, width, x, payload))
                payload.activeUsers++
            },
            afterAttempt = ::trimCache,
        )
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

    private fun measure(key: CacheKey, paint: TextPaint): TextMetrics {
        val padding = ceil(config.strokeWidth.coerceAtLeast(0f)).toInt() + 1
        val fontMetrics = paint.fontMetrics
        val ascent = -ceil(fontMetrics.ascent.toDouble()).toInt()
        val descent = ceil(fontMetrics.descent.toDouble()).toInt()
        return TextMetrics(
            width = (ceil(paint.measureText(key.text).toDouble()).toInt() + padding * 2).coerceAtLeast(1),
            height = (ascent + descent + padding * 2).coerceAtLeast(1),
            ascent = ascent,
            padding = padding,
        )
    }

    private fun renderAndCache(key: CacheKey, paint: TextPaint, metrics: TextMetrics): BitmapPayload? {
        val estimatedBytes = metrics.width.toLong() * metrics.height * BYTES_PER_PIXEL
        if (estimatedBytes > MAX_LIVE_BITMAP_BYTES || liveBitmapBytes + estimatedBytes > MAX_LIVE_BITMAP_BYTES) {
            return null
        }
        val bitmap = Bitmap.createBitmap(metrics.width, metrics.height, Bitmap.Config.ARGB_8888)
        rasterCount++
        val canvas = AndroidCanvas(bitmap)
        val textX = metrics.padding.toFloat()
        val baseline = (metrics.padding + metrics.ascent).toFloat()
        if (Float.fromBits(key.strokeBits) > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Float.fromBits(key.strokeBits)
            paint.color = android.graphics.Color.BLACK
            canvas.drawText(key.text, textX, baseline, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = rgbToAndroid(key.color)
        canvas.drawText(key.text, textX, baseline, paint)

        return BitmapPayload(bitmap, bitmap.asImageBitmap(), metrics.width, metrics.height).also { payload ->
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

/**
 * 需要生成 native 载荷的弹幕内核共用的最小激活事务：查询无副作用，载荷成功后才提交占用。
 * [afterAttempt] 放在 finally，确保失败出口也执行有界缓存整理。
 */
internal inline fun <T : Any> runDanmakuActivationTransaction(
    findLane: () -> Int,
    preparePayload: () -> T?,
    commit: (lane: Int, payload: T) -> Unit,
    afterAttempt: () -> Unit,
): Boolean {
    try {
        val lane = findLane()
        if (lane < 0) return false
        val payload = preparePayload() ?: return false
        commit(lane, payload)
        return true
    } finally {
        afterAttempt()
    }
}
