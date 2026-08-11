package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import java.util.LinkedHashMap
import kotlin.math.ceil
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Font
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextLine
import org.jetbrains.skia.VertexMode
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode

/**
 * 桌面批量位图弹幕内核。文本只在缓存 miss 时光栅化到有界 atlas，逐帧按 atlas page
 * 使用 drawVertices 批量提交；活跃项不各自持有 Image/TextLine 等 native 对象。
 *
 * 淘汰/插入增量有界(PERF-006 后续修复, 全量重建已废除):
 * - region 被淘汰时其矩形(含 gutter)归还所属 page 的空闲表并擦除旧字形像素([AtlasPage.release]);
 * - 新插入先走空闲表 first-fit、余量切分回写([AtlasPage.allocateHole]), 不命中再退回 shelf 游标;
 * - 仅当空闲表 + shelf 仍放不下(碎片化)时, 才从 LRU 头部有界淘汰一小批非活跃条目,
 *   并**只压实单个 page**([compactPage], 只重栅该 page 的幸存条目, 活跃 region 优先重建), 其余 page 绝不触碰。
 *
 * 所有可变状态只在 Compose draw 线程更新: 不加锁、不跨线程共享。
 */
internal class DesktopAtlasDanmakuEngine(
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val cacheMax: Int = DEFAULT_CACHE_MAX,
) : BaseDanmakuEngine() {
    private data class CacheKey(
        val text: String,
        val color: Int,
        val fontBits: Int,
        val strokeBits: Int,
    )

    private data class TextMetrics(
        val width: Int,
        val height: Int,
        val padding: Int,
    )

    private data class AtlasRegion(
        var page: AtlasPage,
        var left: Int,
        var top: Int,
        var width: Int,
        var height: Int,
        var activeUsers: Int = 0,
    )

    private val cache = LinkedHashMap<CacheKey, AtlasRegion>(256, 0.75f, true)
    private val pages = ArrayList<AtlasPage>(maxPages)
    private val vertexBatch = DesktopAtlasQuadBatch()

    internal val cachedRegionCount: Int get() = cache.size
    internal val atlasPageCount: Int get() = pages.size
    /**
     * 全部 page 上 residentKeys 规模之和; 与 [cachedRegionCount] 应恒等(不变量):
     * 每个 cache 条目的 region.page.residentKeys 必须包含该 key, 反之每个 resident key 必在 cache 中。
     * 供测试断言 residentKeys 同步点未遗漏。生产代码不依赖此值。
     */
    internal val residentKeyTotal: Int get() = pages.sumOf { it.residentKeys.size }
    internal val atlasPixelBytes: Long get() = pages.size.toLong() * pageSize * pageSize * BYTES_PER_PIXEL
    internal val maxHoleCount: Int get() = pages.maxOfOrNull { it.holeCount } ?: 0
    internal var lastDrawBatchCount: Int = 0
        private set

    /** 全量 atlas 重建次数: 新策略(增量淘汰 + 单页压实)下恒为 0, 保留字段只为文档化"永不全量重建"不变量。 */
    internal var fullRebuildCount: Int = 0
        private set

    /** 单页压实次数(碎片化兜底路径; 每次压实只重栅一个 page 的幸存条目)。 */
    internal var pageCompactCount: Int = 0
        private set

    /** 累计光栅化次数(每次 [AtlasPage.add] 成功 = 一次 shaping + 光栅化)。 */
    internal var rasterCount: Int = 0
        private set

    init {
        require(pageSize >= MIN_PAGE_SIZE) { "atlas page 太小: $pageSize" }
        require(maxPages in 1..MAX_PAGE_COUNT) { "atlas page 数必须在 1..$MAX_PAGE_COUNT" }
        require(cacheMax > 0) { "atlas cacheMax 必须大于 0" }
    }

    override fun engineName(): String = "desktop-atlas"

    override fun onEntriesReplaced() = releaseAtlas()

    override fun onActiveRemoved(item: ActiveDanmaku) {
        val region = item.payload as? AtlasRegion ?: return
        region.activeUsers = (region.activeUsers - 1).coerceAtLeast(0)
    }

    // C-P2-7: 单帧光栅化预算(对齐 AndroidAtlasDanmakuEngine)——缓存 miss 突发时
    // 限制本帧最大光栅化次数与激活候选数, 剩余 miss 延后到下一帧, 防单帧光栅化尖峰卡顿。
    private var rasterMissesThisFrame = 0

    override fun onFrameStarted() {
        rasterMissesThisFrame = 0
    }

    override fun shouldDeferActivation(entry: DanmakuEntry): Boolean {
        if (rasterMissesThisFrame < MAX_RASTER_MISSES_PER_FRAME) return false
        val fontPx = effectiveFontSp() * fontScalePx
        val key = CacheKey(entry.text, entry.color, fontPx.toRawBits(), config.strokeWidth.toRawBits())
        return !cache.containsKey(key)
    }

    override fun activationCandidateBudgetPerFrame(): Int = MAX_ACTIVATION_CANDIDATES_PER_FRAME

    override fun activate(e: DanmakuEntry, posSec: Double, screenW: Float, baseSpeed: Float): Boolean {
        if (e.text.isEmpty()) return false
        val fontPx = effectiveFontSp() * fontScalePx
        val key = CacheKey(e.text, e.color, fontPx.toRawBits(), config.strokeWidth.toRawBits())
        val cached = cache[key]
        val metrics = cached?.let { TextMetrics(it.width, it.height, 0) } ?: measure(key)
        if (metrics.width <= 0 || metrics.height <= 0) return false
        val width = metrics.width.toFloat()
        // 先查询轨道，确认可见后才光栅化；载荷成功后再提交轨道。全部状态只在 draw 线程串行，
        // 查询与提交间无竞态，且轨道饱和时不会用唯一文本污染 atlas/LRU。
        val lane = when (e.mode) {
            DanmakuMode.SCROLL -> scrollAllocator.findAvailableLane(e.timeSec, baseSpeed)
            DanmakuMode.TOP -> topAllocator.findAvailableLane(e.timeSec)
            DanmakuMode.BOTTOM -> bottomAllocator.findAvailableLane(e.timeSec)
            else -> -1
        }
        if (lane < 0) return false

        val region = cached ?: run {
            rasterMissesThisFrame++
            ensureRegion(key, metrics) ?: return false
        }
        when (e.mode) {
            DanmakuMode.SCROLL -> scrollAllocator.occupy(lane, e.timeSec, width)
            DanmakuMode.TOP -> topAllocator.occupy(lane, e.timeSec, FIXED_DURATION)
            DanmakuMode.BOTTOM -> bottomAllocator.occupy(lane, e.timeSec, FIXED_DURATION)
            else -> return false
        }

        val x = if (e.mode == DanmakuMode.TOP || e.mode == DanmakuMode.BOTTOM) {
            (screenW - region.width) / 2f
        } else {
            (screenW - (posSec - e.timeSec) * baseSpeed).toFloat()
        }
        region.activeUsers++
        active.add(ActiveDanmaku(e, lane, region.width.toFloat(), x, region))
        return true
    }

    override fun draw(scope: DrawScope) {
        lastDrawBatchCount = 0
        if (active.isEmpty() || pages.isEmpty()) return
        val screenHeight = scope.size.height
        vertexBatch.reset()
        vertexBatch.prepareForDraw(maxContiguousPageRun())
        scope.drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.skiaCanvas
            var currentPage: AtlasPage? = null
            active.forEach { item ->
                val region = item.payload as? AtlasRegion ?: return@forEach
                if (currentPage !== region.page || vertexBatch.quadCount >= MAX_BATCH_QUADS) {
                    flushVertexBatch(canvas, currentPage)
                    vertexBatch.reset()
                    currentPage = region.page
                }
                val y = laneY(item.entry.mode, item.lane, screenHeight) + (laneHeight - region.height) / 2f
                vertexBatch.add(
                    item.x,
                    y,
                    region.left,
                    region.top,
                    region.width,
                    region.height,
                )
            }
            flushVertexBatch(canvas, currentPage)
        }
    }

    private fun flushVertexBatch(canvas: Canvas, page: AtlasPage?) {
        if (page == null || vertexBatch.quadCount == 0) return
        if (page.draw(canvas, vertexBatch)) lastDrawBatchCount++
    }

    /**
     * Skia 的 drawVertices 没有有效元素 count 参数，因此提交数组必须跟随当前连续 page 段收缩。
     * 先计算本帧最大连续段，批次数组只调整一次，避免 page 交替时在同一帧反复扩缩。
     */
    private fun maxContiguousPageRun(): Int {
        var currentPage: AtlasPage? = null
        var currentCount = 0
        var maximum = 0
        active.forEach { item ->
            val page = (item.payload as? AtlasRegion)?.page ?: return@forEach
            if (currentPage === page) {
                currentCount++
            } else {
                currentPage = page
                currentCount = 1
            }
            maximum = maxOf(maximum, currentCount)
        }
        return maximum
    }

    /** 测试观测: 当前配置字号/描边下的文本是否仍在 atlas 缓存中(containsKey 不改动 access-order 热度)。 */
    internal fun hasCachedText(text: String, color: Int): Boolean {
        val fontPx = effectiveFontSp() * fontScalePx
        return cache.containsKey(CacheKey(text, color, fontPx.toRawBits(), config.strokeWidth.toRawBits()))
    }

    private fun ensureRegion(key: CacheKey, metrics: TextMetrics): AtlasRegion? {
        // 单条 region 已超出单页尺寸, 无处可放(与 AtlasPage.add 的拒绝条件一致)。
        if (metrics.width + ATLAS_GUTTER * 2 > pageSize || metrics.height + ATLAS_GUTTER * 2 > pageSize) {
            return null
        }

        // 快路径: 缓存未满且某页的空闲表或 shelf 尚有余量。
        if (cache.size < cacheMax) insertDirect(key, metrics)?.let { return it }

        // 慢路径: 增量淘汰 + 有界单页压实, 永不全量重建。
        val compacted = HashSet<Int>(pages.size)  // CR-075: 跟踪 page index 而非实例; compactPage 内 pages[index]=fresh 替换实例, 跟踪实例会导致 pages.firstOrNull{it!in compacted} 永命中 fresh -> return null 永不可达 -> 死循环

        while (true) {
            // 1) 从 LRU 头部(access-order 头 = 最旧)淘汰有界批量的非活跃条目;
            //    被回收矩形立即擦除字形并归还所属 page 的空闲表。
            var removed = 0
            val target = maxOf(1, cache.size / COMPACT_EVICTION_FRACTION)
            val affected = HashSet<AtlasPage>(2)
            val iterator = cache.entries.iterator()
            while (iterator.hasNext() && removed < target) {
                val node = iterator.next()
                val region = node.value
                if (region.activeUsers > 0) continue
                region.page.release(region)
                region.page.residentKeys.remove(node.key)
                affected.add(region.page)
                iterator.remove()
                removed++
            }

            // 2) 淘汰后优先用空闲表/shelf 直接落位: 同尺寸字形命中空闲表, 无需重栅任何幸存条目。
            if (cache.size < cacheMax) insertDirect(key, metrics)?.let { return it }

            // 3a) 落位失败源于缓存上限: 继续淘汰即可; 已无可淘汰条目(全活跃)则放弃。
            if (cache.size >= cacheMax) {
                if (removed == 0) return null
                continue
            }

            // 3b) 落位失败源于页内空间耗尽(碎片化): 压实单个 page, 优先选本轮淘汰命中的页。
            //     本次调用内每个 page 至多压实一次; 全部压实仍放不下说明该 region 任何单页都容不下, 放弃。
            val victim = affected.firstOrNull { it.index !in compacted }
                ?: pages.firstOrNull { it.index !in compacted }
                ?: return null
            compactPage(victim)
            compacted.add(victim.index)
            // 压实后立刻重试, 避免回到循环顶部多淘汰一批: 新页 shelf 通常直接容得下。
            if (cache.size < cacheMax) insertDirect(key, metrics)?.let { return it }
        }
    }

    private fun insertDirect(key: CacheKey, metrics: TextMetrics): AtlasRegion? {
        pages.forEach { page ->
            page.add(key, metrics)?.let { region ->
                rasterCount++
                cache[key] = region
                page.residentKeys.add(key)
                return region
            }
        }
        if (pages.size >= maxPages) return null
        val page = AtlasPage(pages.size, pageSize).also(pages::add)
        val region = page.add(key, metrics) ?: return null
        rasterCount++
        cache[key] = region
        page.residentKeys.add(key)
        return region
    }

    /**
     * 单页压实: 只 close + 重建 [page], 把该页幸存 region(含活跃项)重新测量并插入新页; 其余 page 绝不触碰。
     * 仅在碎片化兜底路径调用。幸存条目按"活跃优先、再按原 region 高/宽降序"排列: 最大化新页 shelf
     * 装箱成功率, 且理论不可达的兜底丢弃发生时优先保留活跃 region。
     */
    private fun compactPage(page: AtlasPage) {
        pageCompactCount++
        // 幸存者取自 page.residentKeys(O(页内 resident 数)), 不再扫全局 cache(O(cache.size)≤4096)。
        // 不变量: residentKeys 与 cache 中 region.page === page 的条目一一对应, 由 add/release/淘汰三处同步维护。
        val survivors = page.residentKeys.toMutableList()
        survivors.sortWith(
            compareByDescending<CacheKey> { cache.getValue(it).activeUsers > 0 }
                .thenByDescending { survivor -> cache.getValue(survivor).height }
                .thenByDescending { survivor -> cache.getValue(survivor).width },
        )
        val index = page.index
        val fresh = AtlasPage(index, pageSize)
        val replacements = ArrayList<AtlasRegion>(survivors.size)
        for (survivor in survivors) {
            val nextRegion = fresh.add(survivor, measure(survivor))
            if (nextRegion == null) {
                // 排序后的 shelf 装箱通常更紧凑，但不能拿该假设冒险关闭仍被 active 引用的页。
                // 失败时丢弃临时页并保持旧 page/cache/region 完整，下轮可尝试其他 page。
                fresh.close()
                return
            }
            rasterCount++
            replacements.add(nextRegion)
        }

        page.close()
        pages[index] = fresh
        fresh.residentKeys.addAll(survivors)
        survivors.forEachIndexed { survivorIndex, survivor ->
            val currentRegion = cache.getValue(survivor)
            val nextRegion = replacements[survivorIndex]
            currentRegion.page = fresh
            currentRegion.left = nextRegion.left
            currentRegion.top = nextRegion.top
            currentRegion.width = nextRegion.width
            currentRegion.height = nextRegion.height
        }
    }

    private fun measure(key: CacheKey): TextMetrics {
        val fontPx = Float.fromBits(key.fontBits)
        val strokePx = Float.fromBits(key.strokeBits).coerceAtLeast(0f)
        val padding = ceil(strokePx).toInt() + 1
        return Font(null, fontPx).use { font ->
            TextLine.make(key.text, font).use { line ->
                TextMetrics(
                    width = (ceil(line.width.toDouble()).toInt() + padding * 2).coerceAtLeast(1),
                    height = (ceil((line.descent - line.ascent).toDouble()).toInt() + padding * 2)
                        .coerceAtLeast(1),
                    padding = padding,
                )
            }
        }
    }

    private fun releaseAtlas() {
        cache.clear()
        pages.forEach(AtlasPage::close)
        pages.clear()
        vertexBatch.clear()
        lastDrawBatchCount = 0
        fullRebuildCount = 0
        pageCompactCount = 0
        rasterCount = 0
    }

    private class AtlasPage(
        val index: Int,
        private val size: Int,
    ) : AutoCloseable {
        private val surface = Surface.makeRasterN32Premul(size, size).also { it.canvas.clear(0x00000000) }
        /** 固定容量空闲矩形表，避免长时间唯一文本 churn 创建无界 Hole 对象。 */
        private val holes = IntArray(MAX_HOLES_PER_PAGE * HOLE_COMPONENTS)
        var holeCount = 0
            private set
        private var allocatedHoleLeft = 0
        private var allocatedHoleTop = 0
        /**
         * 当前本页 resident 的 CacheKey 集合; 与 cache 中 region.page === this 的条目一一对应。
         * 由 [add] 成功后的外层 insertDirect、[release] 配套的淘汰路径、[compactPage] 的换页路径三处同步维护。
         * 供 compactPage O(页内 resident 数) 取幸存者, 避免扫全局 cache(LRU 上限 DEFAULT_CACHE_MAX=4096)。
         */
        val residentKeys: MutableSet<CacheKey> = HashSet()
        private var image: Image? = null
        private var shader: Shader? = null
        private var paint: Paint? = null
        private var clearPaint: Paint? = null
        private var cursorX = ATLAS_GUTTER
        private var cursorY = ATLAS_GUTTER
        private var rowHeight = 0
        private var dirty = false
        private var closed = false

        fun add(key: CacheKey, metrics: TextMetrics): AtlasRegion? {
            check(!closed) { "atlas page 已关闭" }
            val packedWidth = metrics.width + ATLAS_GUTTER
            val packedHeight = metrics.height + ATLAS_GUTTER
            if (packedWidth + ATLAS_GUTTER > size || packedHeight + ATLAS_GUTTER > size) return null
            // 先查空闲表(first-fit); 命中则不动 shelf 游标。会话内字号基本一致, 命中率极高。
            if (allocateHole(packedWidth, packedHeight)) {
                val region = AtlasRegion(this, allocatedHoleLeft, allocatedHoleTop, metrics.width, metrics.height)
                drawText(key, metrics, region)
                dirty = true
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
            dirty = true
            return region
        }

        /**
         * 回收 region: 矩形(含 gutter)归还空闲表, 并擦除原字形像素——否则空闲表复用时
         * 新旧字形在同一表面 SRC_OVER 叠加会透出旧文本。add 的落位检查保证
         * left+packedWidth<=size、top+packedHeight<=size, 空闲块必在页内。
         */
        fun release(region: AtlasRegion) {
            check(!closed) { "atlas page 已关闭" }
            val eraser = clearPaint ?: Paint().also {
                it.blendMode = BlendMode.CLEAR
                clearPaint = it
            }
            surface.canvas.drawRect(
                Rect(
                    region.left.toFloat(),
                    region.top.toFloat(),
                    (region.left + region.width + ATLAS_GUTTER).toFloat(),
                    (region.top + region.height + ATLAS_GUTTER).toFloat(),
                ),
                eraser,
            )
            addHole(region.left, region.top, region.width + ATLAS_GUTTER, region.height + ATLAS_GUTTER)
            dirty = true
        }

        /**
         * 空闲表 first-fit: 命中即取出, 余量按断头台式切分回写——右块只占本行高度、
         * 底块占满空闲块全宽, 两块互不重叠, 也不与已分配区域重叠。
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
                var smallestIndex = 0
                var smallestArea = Int.MAX_VALUE
                for (index in 0 until holeCount) {
                    val offset = index * HOLE_COMPONENTS
                    val area = holes[offset + 2] * holes[offset + 3]
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

        fun draw(canvas: Canvas, batch: DesktopAtlasQuadBatch): Boolean {
            val currentPaint = preparePaint() ?: return false
            canvas.drawVertices(
                VertexMode.TRIANGLES,
                batch.positions,
                null,
                batch.textureCoordinates,
                batch.indices,
                BlendMode.SRC_OVER,
                currentPaint,
            )
            return true
        }

        private fun drawText(key: CacheKey, metrics: TextMetrics, region: AtlasRegion) {
            val fontPx = Float.fromBits(key.fontBits)
            val strokePx = Float.fromBits(key.strokeBits).coerceAtLeast(0f)
            Font(null, fontPx).use { font ->
                TextLine.make(key.text, font).use { line ->
                    Paint().use { strokePaint ->
                        strokePaint.mode = PaintMode.STROKE
                        strokePaint.color = 0xFF000000.toInt()
                        strokePaint.strokeWidth = strokePx
                        strokePaint.isAntiAlias = true
                        strokePaint.strokeCap = PaintStrokeCap.ROUND
                        strokePaint.strokeJoin = PaintStrokeJoin.ROUND
                        Paint().use { fillPaint ->
                            fillPaint.mode = PaintMode.FILL
                            fillPaint.color = (0xFF shl 24) or (key.color and 0xFFFFFF)
                            fillPaint.isAntiAlias = true
                            val x = region.left + metrics.padding.toFloat()
                            val baseline = region.top + metrics.padding - line.ascent
                            if (strokePx > 0f) surface.canvas.drawTextLine(line, x, baseline, strokePaint)
                            surface.canvas.drawTextLine(line, x, baseline, fillPaint)
                        }
                    }
                }
            }
        }

        private fun preparePaint(): Paint? {
            if (!dirty) return paint
            paint?.close()
            shader?.close()
            image?.close()
            val nextImage = surface.makeImageSnapshot()
            val nextShader = nextImage.makeShader(
                FilterTileMode.CLAMP,
                FilterTileMode.CLAMP,
                FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
                Matrix33.IDENTITY,
            )
            val nextPaint = Paint().also {
                it.shader = nextShader
                it.blendMode = BlendMode.SRC_OVER
                it.isAntiAlias = true
            }
            image = nextImage
            shader = nextShader
            paint = nextPaint
            dirty = false
            return nextPaint
        }

        override fun close() {
            if (closed) return
            closed = true
            paint?.close()
            shader?.close()
            image?.close()
            clearPaint?.close()
            surface.close()
            paint = null
            shader = null
            image = null
            clearPaint = null
        }
    }

    internal class DesktopAtlasQuadBatch {
        var positions = FloatArray(INITIAL_BATCH_QUADS * FLOATS_PER_QUAD) { OFFSCREEN }
            private set
        var textureCoordinates = FloatArray(INITIAL_BATCH_QUADS * FLOATS_PER_QUAD)
            private set
        var indices = quadIndices(INITIAL_BATCH_QUADS)
            private set
        var quadCount = 0
            private set

        fun reset() {
            positions.fill(OFFSCREEN, 0, quadCount * FLOATS_PER_QUAD)
            quadCount = 0
        }

        fun clear() {
            reset()
            if (indices.size / INDICES_PER_QUAD != INITIAL_BATCH_QUADS) resize(INITIAL_BATCH_QUADS)
        }

        /**
         * Skia 没有 drawVertices 的有效元素 count 参数，按容量桶收缩提交数组，
         * 避免一次高峰后低密度帧仍提交完整历史容量。仅跨容量桶时重新分配。
         */
        fun prepareForDraw(required: Int) {
            val target = capacityFor(maxOf(required, quadCount))
            val current = indices.size / INDICES_PER_QUAD
            // 扩容立即执行；缩容要求至少跨过一个完整容量桶，避免 64/65 等边界附近逐帧来回分配。
            if (target > current || target * 2 < current) resize(target)
        }

        fun add(x: Float, y: Float, left: Int, top: Int, width: Int, height: Int): Boolean {
            if (!ensureCapacity(quadCount + 1)) return false
            val offset = quadCount * FLOATS_PER_QUAD
            val right = x + width
            val bottom = y + height
            positions[offset] = x
            positions[offset + 1] = y
            positions[offset + 2] = right
            positions[offset + 3] = y
            positions[offset + 4] = right
            positions[offset + 5] = bottom
            positions[offset + 6] = x
            positions[offset + 7] = bottom

            val textureRight = (left + width).toFloat()
            val textureBottom = (top + height).toFloat()
            textureCoordinates[offset] = left.toFloat()
            textureCoordinates[offset + 1] = top.toFloat()
            textureCoordinates[offset + 2] = textureRight
            textureCoordinates[offset + 3] = top.toFloat()
            textureCoordinates[offset + 4] = textureRight
            textureCoordinates[offset + 5] = textureBottom
            textureCoordinates[offset + 6] = left.toFloat()
            textureCoordinates[offset + 7] = textureBottom
            quadCount++
            return true
        }

        private fun ensureCapacity(required: Int): Boolean {
            if (required <= indices.size / INDICES_PER_QUAD) return true
            if (required > MAX_BATCH_QUADS) return false
            resize(capacityFor(required))
            return true
        }

        private fun resize(capacity: Int) {
            positions = positions.copyOf(capacity * FLOATS_PER_QUAD).also {
                it.fill(OFFSCREEN, quadCount * FLOATS_PER_QUAD)
            }
            textureCoordinates = textureCoordinates.copyOf(capacity * FLOATS_PER_QUAD)
            indices = quadIndices(capacity)
        }

        private fun capacityFor(required: Int): Int {
            if (required <= 0) return INITIAL_BATCH_QUADS
            var capacity = INITIAL_BATCH_QUADS
            while (capacity < required) capacity = minOf(capacity * 2, MAX_BATCH_QUADS)
            return capacity
        }
    }

    private companion object {
        const val MIN_PAGE_SIZE = 64
        const val DEFAULT_PAGE_SIZE = 1024
        const val DEFAULT_MAX_PAGES = 4
        const val MAX_PAGE_COUNT = 8
        const val DEFAULT_CACHE_MAX = 4096

        /** 兜底每轮淘汰的缓存比例(access-order LRU 头部): 有界批量, 避免单帧大批量淘汰与压实。 */
        const val COMPACT_EVICTION_FRACTION = 64
        /** C-P2-7: 单帧最大光栅化次数(缓存 miss 预算, 对齐 AndroidAtlasDanmakuEngine)。 */
        const val MAX_RASTER_MISSES_PER_FRAME = 12
        /** C-P2-7: 单帧最大激活候选数(防高密度瞬时处理撑爆本帧)。 */
        const val MAX_ACTIVATION_CANDIDATES_PER_FRAME = 256
        const val ATLAS_GUTTER = 1
        const val BYTES_PER_PIXEL = 4L
        const val MAX_HOLES_PER_PAGE = 256
        const val MAX_HOLE_MERGES_PER_INSERT = 8
        const val HOLE_COMPONENTS = 4
        const val INITIAL_BATCH_QUADS = 64
        const val MAX_BATCH_QUADS = 8191
        const val FLOATS_PER_QUAD = 8
        const val INDICES_PER_QUAD = 6
        const val OFFSCREEN = -1_000_000f

        fun quadIndices(capacity: Int): ShortArray = ShortArray(capacity * INDICES_PER_QUAD).also { result ->
            repeat(capacity) { quad ->
                val vertex = quad * 4
                val index = quad * INDICES_PER_QUAD
                result[index] = vertex.toShort()
                result[index + 1] = (vertex + 1).toShort()
                result[index + 2] = (vertex + 2).toShort()
                result[index + 3] = vertex.toShort()
                result[index + 4] = (vertex + 2).toShort()
                result[index + 5] = (vertex + 3).toShort()
            }
        }
    }
}
