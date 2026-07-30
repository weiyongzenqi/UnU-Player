package io.github.weiyongzenqi.unuplayer.danmaku.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import java.util.HashSet
import java.util.LinkedHashMap
import kotlin.math.ceil
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode

/**
 * Android Atlas 批渲染弹幕内核。文本只在缓存 miss 时光栅化到有界 atlas page([Bitmap]),
 * API 29+ 硬件 Canvas 上把原顺序中连续使用同一 page 的弹幕合并为一次
 * [android.graphics.Canvas.drawVertices]；旧系统/软件 Canvas 保留逐条 drawBitmap 兼容路径。
 * 文本像素预算上限为 8×1024×1024×4 = 32 MiB；真机 A/B 证明 16 MiB 不增加重光栅峰值前不收缩。
 *
 * **颜色无关缓存(ATLAS-NG)**: 缓存键只含 (text, fontBits, strokeBits), 不含颜色。region 烘焙
 * "白填充 + 黑描边", draw 时按弹幕色设 [android.graphics.LightingColorFilter] 染色——白×弹幕色=
 * 弹幕色、黑×弹幕色=0(描边保持黑)。同一文本任意颜色命中同一 region, 多色场景不再重复光栅化/重复占
 * region。批绘用 vertex color 调制白色填充并保持黑描边，不再按颜色重排，因此滚动/顶/底重叠时的
 * 原始 active z 序不变。
 *
 * 蓝本: [DesktopAtlasDanmakuEngine]。运动模型/轨道/激活逻辑复用
 * [BaseDanmakuEngine](增量式墙钟运动, 不改)。
 *
 * 淘汰/插入增量有界(同桌面):
 * - region 被淘汰时其矩形(含 gutter)归还所属 page 的空闲表并擦除旧字形像素([AtlasPage.release]);
 * - 新插入先走空闲表 first-fit、余量切分回写([AtlasPage.allocateHole]), 不命中再退回 shelf 游标;
 * - 空闲矩形使用固定容量原生数组并合并相邻块；单次 miss 最多淘汰固定数量非活跃条目；
 * - draw 阶段不做整页压实。极端碎片化或超长文本无法进入 page 时，单条回退原生文本绘制；
 *   不为罕见容量失败同步重建整页，也不静默丢弹幕。
 *
 * 所有可变状态只在 Compose draw 线程(主线程)更新: 不加锁、不跨线程共享。
 */
internal class AndroidAtlasDanmakuEngine(
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val cacheMax: Int = DEFAULT_CACHE_MAX,
) : BaseDanmakuEngine() {

    /**
     * 颜色无关缓存键：同一文本(同字号/描边)任意颜色命中同一 region。
     * region 烘焙"白填充 + 黑描边"，draw 时由 [LightingColorFilter] 按弹幕色实时染色——
     * 白×弹幕色=弹幕色、黑×弹幕色=黑(描边保持黑)。多色场景同文本不再重复光栅化/重复占 region。
     */
    private data class CacheKey(
        val text: String,
        val fontBits: Int,
        val strokeBits: Int,
    )

    private data class TextMetrics(
        val width: Int,
        val height: Int,
        val padding: Int,
    )

    private data class AtlasRegion(
        val page: AtlasPage,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        var activeUsers: Int = 0,
    )

    /** Atlas 容量失败时的稀有回退；保持功能完整且不突破 page 像素预算。 */
    private data class DirectTextPayload(val metrics: TextMetrics)

    private val cache = LinkedHashMap<CacheKey, AtlasRegion>(256, 0.75f, true)
    private val pages = ArrayList<AtlasPage>(maxPages)

    /** API 26-28/软件 Canvas 兼容路径复用的 src/dst 矩形。 */
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val directTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val vertexBatch = AndroidAtlasVertexBatch(maxQuads = MAX_BATCH_QUADS)

    /** 旧设备兼容路径的染色缓存；生产批绘走 vertex color。 */
    private val fallbackColorFilters = object : LinkedHashMap<Int, LightingColorFilter>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, LightingColorFilter>?): Boolean =
            size > MAX_FALLBACK_COLOR_FILTERS
    }
    private var rasterMissesThisFrame = 0

    /** measure 复用的 TextPaint(单线程; 与 [AtlasPage.textPaint] 配置一致, 保证度量一致)。 */
    private val measurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    internal val cachedRegionCount: Int get() = cache.size
    internal val atlasPageCount: Int get() = pages.size
    /**
     * 全部 page 上 residentKeys 规模之和; 与 [cachedRegionCount] 应恒等(不变量):
     * 每个 cache 条目的 region.page.residentKeys 必须包含该 key, 反之每个 resident key 必在 cache 中。
     */
    internal val residentKeyTotal: Int get() = pages.sumOf { it.residentKeys.size }
    internal val atlasPixelBytes: Long get() = pages.size.toLong() * pageSize * pageSize * BYTES_PER_PIXEL
    internal val vertexCapacity: Int get() = vertexBatch.capacity
    internal val maxHoleCount: Int get() = pages.maxOfOrNull { it.holeCount } ?: 0
    internal var lastDrawBatchCount: Int = 0
        private set
    internal var lastDrawQuadCount: Int = 0
        private set
    internal var rasterCount: Int = 0
        private set
    internal var evictionCount: Int = 0
        private set
    internal var directTextFallbackCount: Int = 0
        private set
    internal var atlasInsertionFailureCount: Int = 0
        private set

    init {
        require(pageSize >= MIN_PAGE_SIZE) { "atlas page 太小: $pageSize" }
        require(maxPages in 1..MAX_PAGE_COUNT) { "atlas page 数必须在 1..$MAX_PAGE_COUNT" }
        require(cacheMax > 0) { "atlas cacheMax 必须大于 0" }
    }

    override fun engineName(): String = "android-atlas"

    override fun onEntriesReplaced() = releaseAtlas()

    override fun onActiveRemoved(item: ActiveDanmaku) {
        (item.payload as? AtlasRegion)?.let { region ->
            region.activeUsers = (region.activeUsers - 1).coerceAtLeast(0)
        }
    }

    override fun onFrameStarted() {
        rasterMissesThisFrame = 0
    }

    override fun shouldDeferActivation(entry: DanmakuEntry): Boolean {
        if (rasterMissesThisFrame < MAX_RASTER_MISSES_PER_FRAME) return false
        val fontPx = effectiveFontSp() * fontScalePx
        val key = CacheKey(entry.text, fontPx.toRawBits(), config.strokeWidth.toRawBits())
        return !cache.containsKey(key)
    }

    override fun activationCandidateBudgetPerFrame(): Int = MAX_ACTIVATION_CANDIDATES_PER_FRAME

    override fun activate(e: DanmakuEntry, posSec: Double, screenW: Float, baseSpeed: Float): Boolean {
        if (e.text.isEmpty()) return false
        val fontPx = effectiveFontSp() * fontScalePx
        val key = CacheKey(e.text, fontPx.toRawBits(), config.strokeWidth.toRawBits())
        val cached = cache[key]
        val metrics = cached?.let { TextMetrics(it.width, it.height, 0) } ?: measure(key)
        if (metrics.width <= 0 || metrics.height <= 0) return false
        val width = metrics.width.toFloat()
        // 先只查询轨道，确认可见后才光栅化；载荷准备完成再提交轨道，避免“轨道满仍写 atlas”
        // 和“先占轨道后载荷失败”的幽灵占位。全部状态在同一 draw 线程串行，查询与提交间无竞态。
        val lane = when (e.mode) {
            DanmakuMode.SCROLL -> scrollAllocator.findAvailableLane(e.timeSec, baseSpeed)
            DanmakuMode.TOP -> topAllocator.findAvailableLane(e.timeSec)
            DanmakuMode.BOTTOM -> bottomAllocator.findAvailableLane(e.timeSec)
            else -> -1
        }
        if (lane < 0) return false

        val region = cached ?: run {
            rasterMissesThisFrame++
            ensureRegion(key, metrics)
        }
        val payload: Any = region?.also { it.activeUsers++ } ?: DirectTextPayload(metrics).also {
            atlasInsertionFailureCount++
        }

        when (e.mode) {
            DanmakuMode.SCROLL -> scrollAllocator.occupy(lane, e.timeSec, width)
            DanmakuMode.TOP -> topAllocator.occupy(lane, e.timeSec, FIXED_DURATION)
            DanmakuMode.BOTTOM -> bottomAllocator.occupy(lane, e.timeSec, FIXED_DURATION)
            else -> return false
        }

        val x = if (e.mode == DanmakuMode.TOP || e.mode == DanmakuMode.BOTTOM) {
            (screenW - width) / 2f
        } else {
            (screenW - (posSec - e.timeSec) * baseSpeed).toFloat()
        }
        active.add(ActiveDanmaku(e, lane, width, x, payload))
        return true
    }

    override fun draw(scope: DrawScope) {
        lastDrawBatchCount = 0
        lastDrawQuadCount = 0
        if (active.isEmpty()) return
        val screenHeight = scope.size.height
        scope.drawIntoCanvas { composeCanvas ->
            val nativeCanvas = composeCanvas.nativeCanvas
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && nativeCanvas.isHardwareAccelerated) {
                drawVertexBatches(nativeCanvas, screenHeight)
            } else {
                drawBitmapFallback(nativeCanvas, screenHeight)
            }
        }
    }

    /**
     * 按 active 原顺序扫描，只合并连续使用同一 atlas page 的区段。相比按 page/颜色全局分组，
     * 这不会改变跨类型重叠时的 z 序；Atlas 按时间顺序装页，正常播放下同页通常天然连续。
     */
    private fun drawVertexBatches(canvas: AndroidCanvas, screenHeight: Float) {
        drawPaint.colorFilter = null
        vertexBatch.reset()
        var currentPage: AtlasPage? = null
        for (index in active.indices) {
            val item = active[index]
            val direct = item.payload as? DirectTextPayload
            if (direct != null) {
                flushVertexBatch(canvas, currentPage)
                vertexBatch.reset()
                currentPage = null
                val y = laneY(item.entry.mode, item.lane, screenHeight) +
                    (laneHeight - direct.metrics.height) / 2f
                drawDirectText(canvas, item, direct.metrics, y)
                continue
            }
            val region = item.payload as? AtlasRegion ?: continue
            if (currentPage !== region.page || vertexBatch.quadCount >= MAX_BATCH_QUADS) {
                flushVertexBatch(canvas, currentPage)
                vertexBatch.reset()
                currentPage = region.page
            }
            val y = laneY(item.entry.mode, item.lane, screenHeight) + (laneHeight - region.height) / 2f
            if (!vertexBatch.add(
                    x = item.x,
                    y = y,
                    srcLeft = region.left,
                    srcTop = region.top,
                    width = region.width,
                    height = region.height,
                    color = item.entry.color,
                )
            ) break
            lastDrawQuadCount++
        }
        flushVertexBatch(canvas, currentPage)
    }

    private fun flushVertexBatch(canvas: AndroidCanvas, page: AtlasPage?) {
        if (page == null || vertexBatch.quadCount == 0) return
        page.drawVertices(canvas, vertexBatch, drawPaint)
        lastDrawBatchCount++
    }

    /** API 26-28 和软件 Canvas 的功能兼容路径；保持 active 顺序且所有临时对象有硬上限。 */
    private fun drawBitmapFallback(canvas: AndroidCanvas, screenHeight: Float) {
        drawPaint.shader = null
        for (index in active.indices) {
            val item = active[index]
            val direct = item.payload as? DirectTextPayload
            if (direct != null) {
                val y = laneY(item.entry.mode, item.lane, screenHeight) +
                    (laneHeight - direct.metrics.height) / 2f
                drawDirectText(canvas, item, direct.metrics, y)
                continue
            }
            val region = item.payload as? AtlasRegion ?: continue
            val colorFilter = fallbackColorFilters.getOrPut(item.entry.color) {
                LightingColorFilter(item.entry.color, 0)
            }
            if (drawPaint.colorFilter !== colorFilter) drawPaint.colorFilter = colorFilter
            val y = laneY(item.entry.mode, item.lane, screenHeight) + (laneHeight - region.height) / 2f
            srcRect.set(region.left, region.top, region.left + region.width, region.top + region.height)
            dstRect.set(item.x, y, item.x + region.width, y + region.height)
            canvas.drawBitmap(region.page.bitmap, srcRect, dstRect, drawPaint)
            lastDrawBatchCount++
            lastDrawQuadCount++
        }
    }

    /** 容量失败的功能回退；只复用 Paint，不分配 Bitmap、数组或每帧临时对象。 */
    private fun drawDirectText(canvas: AndroidCanvas, item: ActiveDanmaku, metrics: TextMetrics, y: Float) {
        val fontPx = effectiveFontSp() * fontScalePx
        val strokePx = config.strokeWidth.coerceAtLeast(0f)
        directTextPaint.textSize = fontPx
        val ascent = -ceil(directTextPaint.ascent().toDouble()).toInt()
        val baseline = y + metrics.padding + ascent
        val textX = item.x + metrics.padding
        if (strokePx > 0f) {
            directTextPaint.style = Paint.Style.STROKE
            directTextPaint.strokeWidth = strokePx
            directTextPaint.color = android.graphics.Color.BLACK
            canvas.drawText(item.entry.text, textX, baseline, directTextPaint)
        }
        directTextPaint.style = Paint.Style.FILL
        directTextPaint.color = rgbToAndroid(item.entry.color)
        canvas.drawText(item.entry.text, textX, baseline, directTextPaint)
        directTextFallbackCount++
        lastDrawBatchCount++
        lastDrawQuadCount++
    }

    private fun rgbToAndroid(rgb: Int): Int = android.graphics.Color.rgb(
        (rgb shr 16) and 0xFF,
        (rgb shr 8) and 0xFF,
        rgb and 0xFF,
    )

    private fun ensureRegion(key: CacheKey, metrics: TextMetrics): AtlasRegion? {
        // 单条 region 已超出单页尺寸, 无处可放(与 AtlasPage.add 的拒绝条件一致)。
        if (metrics.width + ATLAS_GUTTER * 2 > pageSize || metrics.height + ATLAS_GUTTER * 2 > pageSize) {
            return null
        }

        // 快路径: 缓存未满且某页的空闲表或 shelf 尚有余量。
        if (cache.size < cacheMax) insertDirect(key, metrics)?.let { return it }

        // 慢路径只做固定次数的增量淘汰；每释放一个矩形就立即重试。禁止在 draw 中整页重栅。
        var removed = 0
        var scanned = 0
        val iterator = cache.entries.iterator()
        while (iterator.hasNext() && removed < MAX_EVICTIONS_PER_MISS && scanned < MAX_EVICTION_CANDIDATES) {
            val node = iterator.next()
            scanned++
            val region = node.value
            if (region.activeUsers > 0) continue
            region.page.release(region)
            region.page.residentKeys.remove(node.key)
            iterator.remove()
            removed++
            evictionCount++
            if (cache.size < cacheMax) insertDirect(key, metrics)?.let { return it }
        }
        return null
    }

    private fun insertDirect(key: CacheKey, metrics: TextMetrics): AtlasRegion? {
        pages.forEach { page ->
            page.add(key, metrics)?.let { region ->
                cache[key] = region
                page.residentKeys.add(key)
                rasterCount++
                return region
            }
        }
        if (pages.size >= maxPages) return null
        val page = AtlasPage(pages.size, pageSize).also(pages::add)
        val region = page.add(key, metrics) ?: return null
        cache[key] = region
        page.residentKeys.add(key)
        rasterCount++
        return region
    }

    private fun measure(key: CacheKey): TextMetrics {
        val fontPx = Float.fromBits(key.fontBits)
        val strokePx = Float.fromBits(key.strokeBits).coerceAtLeast(0f)
        val padding = ceil(strokePx).toInt() + 1
        measurePaint.textSize = fontPx
        // ascent/descent 计算与 AtlasPage.drawText 一致(同配置 paint), 保证 region 高度容纳实际文本。
        // ascent() 返回负数, -ceil(ascent) 得正数距离(基线到文本顶)。
        val ascent = -ceil(measurePaint.ascent().toDouble()).toInt()
        val descent = ceil(measurePaint.descent().toDouble()).toInt()
        val width = (ceil(measurePaint.measureText(key.text).toDouble()).toInt() + padding * 2).coerceAtLeast(1)
        val height = (ascent + descent + padding * 2).coerceAtLeast(1)
        return TextMetrics(width, height, padding)
    }

    private fun releaseAtlas() {
        drawPaint.shader = null
        drawPaint.colorFilter = null
        fallbackColorFilters.clear()
        cache.clear()
        pages.forEach(AtlasPage::close)
        pages.clear()
        vertexBatch.reset()
        directTextFallbackCount = 0
        atlasInsertionFailureCount = 0
    }

    /**
     * Atlas 页: 持有一张 [Bitmap] + [AndroidCanvas] 用于光栅化文本, 维护空闲表(holes)+ shelf 游标。
     *
     * - [add]: 查 holes(first-fit) -> 否则 shelf 游标 -> drawText 光栅化 -> 返回 region
     * - [release]: eraserPaint(PorterDuff CLEAR) 擦除 region 矩形(含 gutter) + holes.add
     * - [close]: bitmap.recycle() 释放 native 内存(Android Bitmap 是 native 内存, 主动回收)
     */
    private class AtlasPage(
        val index: Int,
        private val size: Int,
    ) {
        val bitmap: Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { it.eraseColor(0) }
        private val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        private val canvas = AndroidCanvas(bitmap)
        private val holes = IntArray(MAX_HOLES_PER_PAGE * HOLE_COMPONENTS)
        var holeCount: Int = 0
            private set
        private var allocatedHoleLeft = 0
        private var allocatedHoleTop = 0
        /**
         * 当前本页 resident 的 CacheKey 集合; 与 cache 中 region.page === this 的条目一一对应。
         * 由 [add] 成功后的外层 [insertDirect] 与 [release] 配套的淘汰路径同步维护。
         * 淘汰通过 region.activeUsers 判断活跃引用，不再扫描 active 或为整页压实创建幸存者副本。
         */
        val residentKeys: MutableSet<CacheKey> = HashSet()
        private var cursorX = ATLAS_GUTTER
        private var cursorY = ATLAS_GUTTER
        private var rowHeight = 0
        private var closed = false

        /** 光栅化文本用 TextPaint(同 [BitmapDanmakuEngine] 风格, 复用避免每条 new)。 */
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            typeface = Typeface.DEFAULT
        }

        /** 擦除像素(回收 region 时): PorterDuff CLEAR 局部擦除字形。 */
        private val eraserPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

        fun add(key: CacheKey, metrics: TextMetrics): AtlasRegion? {
            check(!closed) { "atlas page 已关闭" }
            val packedWidth = metrics.width + ATLAS_GUTTER
            val packedHeight = metrics.height + ATLAS_GUTTER
            if (packedWidth + ATLAS_GUTTER > size || packedHeight + ATLAS_GUTTER > size) return null
            // 先查空闲表(first-fit); 命中则不动 shelf 游标。会话内字号基本一致, 命中率极高。
            if (allocateHole(packedWidth, packedHeight)) {
                val region = AtlasRegion(this, allocatedHoleLeft, allocatedHoleTop, metrics.width, metrics.height)
                drawText(key, metrics, region)
                return region
            }
            // 再走 shelf 游标分配(与空闲表共存: 游标只前进, 空闲块只在游标已划过的区域)。
            if (cursorX + packedWidth > size) {
                cursorX = ATLAS_GUTTER
                cursorY += rowHeight
                rowHeight = 0
            }
            if (cursorY + packedHeight > size) return null
            val region = AtlasRegion(this, cursorX, cursorY, metrics.width, metrics.height)
            drawText(key, metrics, region)
            cursorX += packedWidth
            rowHeight = maxOf(rowHeight, packedHeight)
            return region
        }

        /**
         * 回收 region: 矩形(含 gutter)归还空闲表, 并擦除原字形像素--否则空闲表复用时
         * 新旧字形在同一 Bitmap SRC_OVER 叠加会透出旧文本。add 的落位检查保证
         * left+packedWidth<=size、top+packedHeight<=size, 空闲块必在页内。
         */
        fun release(region: AtlasRegion) {
            check(!closed) { "atlas page 已关闭" }
            canvas.drawRect(
                region.left.toFloat(),
                region.top.toFloat(),
                (region.left + region.width + ATLAS_GUTTER).toFloat(),
                (region.top + region.height + ATLAS_GUTTER).toFloat(),
                eraserPaint,
            )
            addHole(region.left, region.top, region.width + ATLAS_GUTTER, region.height + ATLAS_GUTTER)
        }

        /**
         * 固定容量空闲表 first-fit。命中后按断头台式切分，释放时合并水平/垂直相邻矩形；
         * 全程只改 IntArray，不为每次淘汰创建 Hole 对象。
         */
        private fun allocateHole(packedWidth: Int, packedHeight: Int): Boolean {
            for (index in 0 until holeCount) {
                val offset = index * HOLE_COMPONENTS
                val left = holes[offset]
                val top = holes[offset + 1]
                val width = holes[offset + 2]
                val height = holes[offset + 3]
                if (width < packedWidth || height < packedHeight) continue
                allocatedHoleLeft = left
                allocatedHoleTop = top
                removeHoleAt(index)
                val rightWidth = width - packedWidth
                val bottomHeight = height - packedHeight
                if (rightWidth > 0) addHole(left + packedWidth, top, rightWidth, packedHeight)
                if (bottomHeight > 0) addHole(left, top + packedHeight, width, bottomHeight)
                return true
            }
            return false
        }

        private fun addHole(initialLeft: Int, initialTop: Int, initialWidth: Int, initialHeight: Int) {
            if (initialWidth <= 0 || initialHeight <= 0) return
            var left = initialLeft
            var top = initialTop
            var width = initialWidth
            var height = initialHeight
            var merged: Boolean
            var mergeCount = 0
            do {
                merged = false
                for (index in 0 until holeCount) {
                    val offset = index * HOLE_COMPONENTS
                    val otherLeft = holes[offset]
                    val otherTop = holes[offset + 1]
                    val otherWidth = holes[offset + 2]
                    val otherHeight = holes[offset + 3]
                    val horizontal = top == otherTop && height == otherHeight &&
                        (left + width == otherLeft || otherLeft + otherWidth == left)
                    val vertical = left == otherLeft && width == otherWidth &&
                        (top + height == otherTop || otherTop + otherHeight == top)
                    if (!horizontal && !vertical) continue
                    if (horizontal) {
                        left = minOf(left, otherLeft)
                        width += otherWidth
                    } else {
                        top = minOf(top, otherTop)
                        height += otherHeight
                    }
                    removeHoleAt(index)
                    merged = true
                    mergeCount++
                    break
                }
            } while (merged && mergeCount < MAX_HOLE_MERGES_PER_INSERT)
            val targetIndex = if (holeCount < MAX_HOLES_PER_PAGE) {
                holeCount++
                holeCount - 1
            } else {
                // 容量满时保留面积更大的空闲块。release 后的同尺寸 region 会优先替换小碎片，
                // 下一次 insertDirect 可立即复用，避免长期 churn 把可用空间静默丢光。
                var smallestIndex = 0
                var smallestArea = Int.MAX_VALUE
                for (index in 0 until holeCount) {
                    val candidate = index * HOLE_COMPONENTS
                    val area = holes[candidate + 2] * holes[candidate + 3]
                    if (area < smallestArea) {
                        smallestArea = area
                        smallestIndex = index
                    }
                }
                if (width * height <= smallestArea) return
                smallestIndex
            }
            val offset = targetIndex * HOLE_COMPONENTS
            holes[offset] = left
            holes[offset + 1] = top
            holes[offset + 2] = width
            holes[offset + 3] = height
        }

        private fun removeHoleAt(index: Int) {
            val last = holeCount - 1
            if (index != last) {
                val target = index * HOLE_COMPONENTS
                val source = last * HOLE_COMPONENTS
                holes[target] = holes[source]
                holes[target + 1] = holes[source + 1]
                holes[target + 2] = holes[source + 2]
                holes[target + 3] = holes[source + 3]
            }
            holeCount = last
        }

        fun drawVertices(target: AndroidCanvas, batch: AndroidAtlasVertexBatch, paint: Paint) {
            drawAndroidAtlasVertices(target, shader, batch, paint)
        }

        /**
         * 光栅化文本到 atlas page(描边黑 + 填充弹幕色, 同 [BitmapDanmakuEngine.renderAndCache] 路径)。
         * baseline = region.top + padding + ascent(ascent 为基线到文本顶的正距离)。
         */
        private fun drawText(key: CacheKey, metrics: TextMetrics, region: AtlasRegion) {
            val fontPx = Float.fromBits(key.fontBits)
            val strokePx = Float.fromBits(key.strokeBits).coerceAtLeast(0f)
            textPaint.textSize = fontPx
            val ascent = -ceil(textPaint.ascent().toDouble()).toInt()
            val baseline = (region.top + metrics.padding + ascent).toFloat()
            val textX = (region.left + metrics.padding).toFloat()
            // 描边(黑)在前 -> 填充(弹幕色)覆盖内部, 留黑边
            if (strokePx > 0f) {
                textPaint.style = Paint.Style.STROKE
                textPaint.strokeWidth = strokePx
                textPaint.color = android.graphics.Color.BLACK
                canvas.drawText(key.text, textX, baseline, textPaint)
            }
            textPaint.style = Paint.Style.FILL
            // 颜色无关烘焙：填充固定白，draw 时 LightingColorFilter 染成弹幕色。
            // 白×弹幕色/255 = 弹幕色；黑描边×弹幕色 = 0(保持黑)。
            textPaint.color = android.graphics.Color.WHITE
            canvas.drawText(key.text, textX, baseline, textPaint)
        }

        fun close() {
            if (closed) return
            closed = true
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private companion object {
        const val MIN_PAGE_SIZE = 64
        const val DEFAULT_PAGE_SIZE = 1024
        const val DEFAULT_MAX_PAGES = 8
        const val MAX_PAGE_COUNT = 8
        const val DEFAULT_CACHE_MAX = 4096
        const val MAX_RASTER_MISSES_PER_FRAME = 12
        const val MAX_ACTIVATION_CANDIDATES_PER_FRAME = 256
        const val MAX_EVICTIONS_PER_MISS = 8
        const val MAX_EVICTION_CANDIDATES = 64
        const val MAX_FALLBACK_COLOR_FILTERS = 64
        const val MAX_HOLES_PER_PAGE = 256
        const val MAX_HOLE_MERGES_PER_INSERT = 8
        const val HOLE_COMPONENTS = 4
        const val MAX_BATCH_QUADS = BaseDanmakuEngine.MAX_ON_SCREEN_HARD_LIMIT
        const val ATLAS_GUTTER = 1
        const val BYTES_PER_PIXEL = 4L
    }
}

/**
 * Android Canvas.drawVertices 的有界复用缓冲。每个 quad 使用 4 个顶点和 6 个索引；
 * 数组只在历史峰值提高时按 2 倍扩容，达到 [maxQuads] 后拒绝继续增长。
 */
internal class AndroidAtlasVertexBatch(
    private val maxQuads: Int = BaseDanmakuEngine.MAX_ON_SCREEN_HARD_LIMIT,
    initialCapacity: Int = 256,
) {
    var positions = FloatArray(0)
        private set
    var textureCoordinates = FloatArray(0)
        private set
    var colors = IntArray(0)
        private set
    var indices = ShortArray(0)
        private set
    var quadCount: Int = 0
        private set
    val capacity: Int get() = colors.size / VERTICES_PER_QUAD
    val vertexFloatCount: Int get() = quadCount * POSITION_FLOATS_PER_QUAD
    val indexCount: Int get() = quadCount * INDICES_PER_QUAD

    init {
        require(maxQuads in 1..MAX_INDEXED_QUADS) { "quad 上限必须在 1..$MAX_INDEXED_QUADS" }
        ensureCapacity(initialCapacity.coerceIn(1, maxQuads))
    }

    fun reset() {
        quadCount = 0
    }

    fun add(
        x: Float,
        y: Float,
        srcLeft: Int,
        srcTop: Int,
        width: Int,
        height: Int,
        color: Int,
    ): Boolean {
        if (quadCount >= maxQuads) return false
        ensureCapacity(quadCount + 1)

        val positionOffset = quadCount * POSITION_FLOATS_PER_QUAD
        val right = x + width
        val bottom = y + height
        positions[positionOffset] = x
        positions[positionOffset + 1] = y
        positions[positionOffset + 2] = right
        positions[positionOffset + 3] = y
        positions[positionOffset + 4] = right
        positions[positionOffset + 5] = bottom
        positions[positionOffset + 6] = x
        positions[positionOffset + 7] = bottom

        val srcRight = (srcLeft + width).toFloat()
        val srcBottom = (srcTop + height).toFloat()
        textureCoordinates[positionOffset] = srcLeft.toFloat()
        textureCoordinates[positionOffset + 1] = srcTop.toFloat()
        textureCoordinates[positionOffset + 2] = srcRight
        textureCoordinates[positionOffset + 3] = srcTop.toFloat()
        textureCoordinates[positionOffset + 4] = srcRight
        textureCoordinates[positionOffset + 5] = srcBottom
        textureCoordinates[positionOffset + 6] = srcLeft.toFloat()
        textureCoordinates[positionOffset + 7] = srcBottom

        val colorOffset = quadCount * VERTICES_PER_QUAD
        val opaqueColor = OPAQUE_ALPHA or (color and RGB_MASK)
        colors[colorOffset] = opaqueColor
        colors[colorOffset + 1] = opaqueColor
        colors[colorOffset + 2] = opaqueColor
        colors[colorOffset + 3] = opaqueColor
        quadCount++
        return true
    }

    private fun ensureCapacity(required: Int) {
        if (required <= capacity) return
        var next = maxOf(1, capacity)
        while (next < required) next = minOf(maxQuads, next * 2)
        val oldCapacity = capacity
        positions = positions.copyOf(next * POSITION_FLOATS_PER_QUAD)
        textureCoordinates = textureCoordinates.copyOf(next * POSITION_FLOATS_PER_QUAD)
        colors = colors.copyOf(next * VERTICES_PER_QUAD)
        indices = indices.copyOf(next * INDICES_PER_QUAD)
        for (quad in oldCapacity until next) {
            val vertex = quad * VERTICES_PER_QUAD
            val offset = quad * INDICES_PER_QUAD
            indices[offset] = vertex.toShort()
            indices[offset + 1] = (vertex + 1).toShort()
            indices[offset + 2] = (vertex + 2).toShort()
            indices[offset + 3] = vertex.toShort()
            indices[offset + 4] = (vertex + 2).toShort()
            indices[offset + 5] = (vertex + 3).toShort()
        }
    }

    private companion object {
        const val VERTICES_PER_QUAD = 4
        const val POSITION_FLOATS_PER_QUAD = VERTICES_PER_QUAD * 2
        const val INDICES_PER_QUAD = 6
        const val MAX_INDEXED_QUADS = Short.MAX_VALUE.toInt() / VERTICES_PER_QUAD
        const val OPAQUE_ALPHA = -0x1000000
        const val RGB_MASK = 0x00FFFFFF
    }
}

/** 生产与设备像素测试共用的 Canvas.drawVertices 提交点。 */
internal fun drawAndroidAtlasVertices(
    canvas: AndroidCanvas,
    shader: Shader,
    batch: AndroidAtlasVertexBatch,
    paint: Paint,
) {
    paint.shader = shader
    canvas.drawVertices(
        AndroidCanvas.VertexMode.TRIANGLES,
        batch.vertexFloatCount,
        batch.positions,
        0,
        batch.textureCoordinates,
        0,
        batch.colors,
        0,
        batch.indices,
        0,
        batch.indexCount,
        paint,
    )
}
