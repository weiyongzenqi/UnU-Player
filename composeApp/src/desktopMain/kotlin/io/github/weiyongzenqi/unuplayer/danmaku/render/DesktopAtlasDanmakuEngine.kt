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
 * 桌面批量位图弹幕内核。文本只在缓存 miss 时光栅化到有界 atlas, 逐帧按 atlas page
 * 用 drawVertices(顶点色调制)批量提交; 活跃项不各自持有 Image/TextLine 等 native 对象。
 *
 * 颜色无关缓存(2026-08-15, 与 AndroidAtlasDanmakuEngine ATLAS-NG 同构):
 * - 缓存键只含 (text, fontBits, strokeBits), 不含颜色; region 烘焙"白填充 + 黑描边";
 * - draw 时每 quad 顶点色 = 弹幕色, [BlendMode.MODULATE] 调制——白×弹幕色=弹幕色、
 *   黑×弹幕色=黑(描边不受染色影响)。同一文本任意颜色命中同一 region: 多色场景不重复
 *   光栅化、不重复占 region, 缓存压力与碎片化速度按颜色种类数下降。
 *
 * draw 阶段零重栅(2026-08-15 偶发抽帧根治, 对齐 Android):
 * - 淘汰/插入增量有界: region 回收时矩形(含 gutter)归还所属 page 空闲表并擦除旧字形像素
 *   ([AtlasPage.release]); 新插入先走空闲表 first-fit、余量切分回写([AtlasPage.allocateHole]),
 *   不命中再退回 shelf 游标;
 * - 单次 miss 最多淘汰 [MAX_EVICTIONS_PER_MISS] 条非活跃条目(扫描候选有硬上限),
 *   **不在 draw 线程同步压实/重建整页**; 空闲表与 shelf 仍放不下(极端碎片化)或单条超出
 *   页尺寸时, 该弹幕降级为 [DirectTextPayload] 单条直绘——功能完整、不静默丢弹幕。
 *
 * 页存储与快照(Surface + 脏页快照, Paint 常驻):
 * - page 持有 raster [Surface] 供光栅化/擦除; 自上次 draw 后有写入的页在 draw 时
 *   [Surface.makeImageSnapshot] 换新 image/shader 并挂到常驻 Paint 上(相比旧实现少重建
 *   一个 Paint 对象)。注: skia 的 Bitmap.makeShader 对可变位图是构造时拷贝(SkImage 不可变),
 *   "可变 Bitmap + 常驻 shader + notifyPixelsChanged" 在 skia 不可行(实测证伪, 见
 *   SkiaAtlasBatchTest 语义测试), Surface 快照即 dirty 页的最小成本路径。颜色无关缓存
 *   使 miss 频率按颜色种类数下降, 快照/重传的触发频率随之下降。
 *
 * 单次 shaping: miss 路径 [shape] 产出 [ShapedText](TextLine + 度量), 度量(轨道查询)与
 * 光栅化([AtlasPage.add])共用同一次 shaping, 不再 measure/drawText 各 shape 一遍。
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
        val fontBits: Int,
        val strokeBits: Int,
    )

    private data class TextMetrics(
        val width: Int,
        val height: Int,
        val padding: Int,
    )

    /**
     * 一次 shaping 的产物: 度量供轨道查询/装箱, [line] 供光栅化复用。TextLine 引用
     * typeface 不引用 SkFont, 可独立于创建它的 Font 存续; 从 activate 的 miss 路径
     * 创建；进入直绘 active 时转移所有权，其余出口由 activate 的 finally 统一关闭。
     */
    private class ShapedText(val line: TextLine, val metrics: TextMetrics) : AutoCloseable {
        override fun close() {
            line.close()
        }
    }

    /** activate 期间暂存的 native 资源所有权；进入 active 前的任一出口由 finally 恰好关闭一次。 */
    internal class TransferableResource<T : AutoCloseable>(val value: T) : AutoCloseable {
        private var transferred = false
        private var closed = false

        fun transfer() {
            check(!transferred && !closed) { "资源所有权已结束" }
            transferred = true
        }

        override fun close() {
            if (!transferred && !closed) {
                closed = true
                value.close()
            }
        }
    }

    private class AtlasRegion(
        var page: AtlasPage,
        var left: Int,
        var top: Int,
        var width: Int,
        var height: Int,
        var activeUsers: Int = 0,
    )

    /** Atlas 容量失败的稀有回退; 保持功能完整且不突破 page 像素预算。
     *  持有 [ShapedText](含 TextLine): 直绘复用同一次 shaping(不再每帧重建 Font/TextLine),
     *  移除/清空时由 [onActiveRemoved] 关闭。 */
    private class DirectTextPayload(val shaped: ShapedText) {
        val metrics: TextMetrics get() = shaped.metrics
    }

    private val cache = LinkedHashMap<CacheKey, AtlasRegion>(256, 0.75f, true)
    private val pages = ArrayList<AtlasPage>(maxPages)
    private val vertexBatch = DesktopAtlasQuadBatch()

    /** 直绘回退复用(单 draw 线程): 描边恒黑, 填充按弹幕色; 只逐条改 strokeWidth/color。 */
    private val directStrokePaint = Paint().also {
        it.mode = PaintMode.STROKE
        it.color = 0xFF000000.toInt()
        it.isAntiAlias = true
        it.strokeCap = PaintStrokeCap.ROUND
        it.strokeJoin = PaintStrokeJoin.ROUND
    }
    private val directFillPaint = Paint().also {
        it.mode = PaintMode.FILL
        it.isAntiAlias = true
    }

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
    internal var terminalPaintCloseCount: Int = 0
        private set
    internal var lastDrawBatchCount: Int = 0
        private set
    internal var lastDrawQuadCount: Int = 0
        private set

    /** 累计光栅化次数(每次 [AtlasPage.add] 成功 = 一次光栅化)。 */
    internal var rasterCount: Int = 0
        private set

    /** 累计淘汰次数(慢路径回收非活跃 region)。 */
    internal var evictionCount: Int = 0
        private set

    /** 直绘回退累计提交次数(每帧每条直绘计一次; 换集/清空归零)。 */
    internal var directTextFallbackCount: Int = 0
        private set

    /** 缓存/页容量失败累计次数(每次 miss 未能进 atlas 计一次; 换集/清空归零)。 */
    internal var atlasInsertionFailureCount: Int = 0
        private set

    // C-P2-7: 单帧光栅化预算(对齐 AndroidAtlasDanmakuEngine)——缓存 miss 突发时
    // 限制本帧最大光栅化次数与激活候选数, 剩余 miss 延后到下一帧, 防单帧光栅化尖峰卡顿。
    private var rasterMissesThisFrame = 0

    init {
        require(pageSize >= MIN_PAGE_SIZE) { "atlas page 太小: $pageSize" }
        require(maxPages in 1..MAX_PAGE_COUNT) { "atlas page 数必须在 1..$MAX_PAGE_COUNT" }
        require(cacheMax > 0) { "atlas cacheMax 必须大于 0" }
    }

    override fun engineName(): String = "desktop-atlas"

    override fun onEntriesReplaced() = releaseAtlas()

    override fun onDispose() {
        try {
            directStrokePaint.close()
            terminalPaintCloseCount++
        } finally {
            directFillPaint.close()
            terminalPaintCloseCount++
        }
    }

    override fun onActiveRemoved(item: ActiveDanmaku) {
        when (val payload = item.payload) {
            is AtlasRegion -> payload.activeUsers = (payload.activeUsers - 1).coerceAtLeast(0)
            is DirectTextPayload -> payload.shaped.close()
            else -> Unit
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
        // miss 时才 shaping(单次); 命中缓存直接用 region 度量, 零 shaping。
        val shapedLease = if (cached == null) TransferableResource(shape(key)) else null
        try {
            val metrics = cached?.let { TextMetrics(it.width, it.height, 0) } ?: shapedLease!!.value.metrics
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

            val payload: Any
            if (cached != null) {
                payload = cached
            } else {
                rasterMissesThisFrame++
                val region = ensureRegion(key, shapedLease!!.value)
                if (region != null) {
                    payload = region
                } else {
                    // 直绘回退(超页尺寸/容量失败): 转移 shaped(含 TextLine)给 payload,
                    // draw 复用同一次 shaping, 不再逐帧重建 Font/TextLine; 移除时 onActiveRemoved 关闭
                    payload = DirectTextPayload(shapedLease.value).also { atlasInsertionFailureCount++ }
                }
            }

            val x = if (e.mode == DanmakuMode.TOP || e.mode == DanmakuMode.BOTTOM) {
                (screenW - width) / 2f
            } else {
                (screenW - (posSec - e.timeSec) * baseSpeed).toFloat()
            }
            val item = ActiveDanmaku(e, lane, width, x, payload)
            active.add(item)
            try {
                when (e.mode) {
                    DanmakuMode.SCROLL -> scrollAllocator.occupy(lane, e.timeSec, width)
                    DanmakuMode.TOP -> topAllocator.occupy(lane, e.timeSec, FIXED_DURATION)
                    DanmakuMode.BOTTOM -> bottomAllocator.occupy(lane, e.timeSec, FIXED_DURATION)
                    else -> error("不支持的弹幕模式: ${e.mode}")
                }
            } catch (error: Throwable) {
                check(active.removeAt(active.lastIndex) === item) { "activate 回滚时 active 尾项不一致" }
                throw error
            }
            when (payload) {
                is AtlasRegion -> payload.activeUsers++
                is DirectTextPayload -> shapedLease!!.transfer()
            }
            return true
        } finally {
            shapedLease?.close()
        }
    }

    override fun draw(scope: DrawScope) {
        lastDrawBatchCount = 0
        lastDrawQuadCount = 0
        if (active.isEmpty()) return
        val screenHeight = scope.size.height
        vertexBatch.reset()
        vertexBatch.prepareForDraw(maxContiguousPageRun())
        scope.drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.skiaCanvas
            var currentPage: AtlasPage? = null
            for (index in active.indices) {
                val item = active[index]
                val direct = item.payload as? DirectTextPayload
                if (direct != null) {
                    // 直绘回退打断当前批(z 序保持 active 原顺序), 画完从空批继续。
                    flushVertexBatch(canvas, currentPage)
                    vertexBatch.reset()
                    currentPage = null
                    val y = laneY(item.entry.mode, item.lane, screenHeight) +
                        (laneHeight - direct.metrics.height) / 2f
                    drawDirectText(canvas, item, direct, y)
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
                        item.x,
                        y,
                        region.left,
                        region.top,
                        region.width,
                        region.height,
                        item.entry.color,
                    )
                ) {
                    break
                }
                lastDrawQuadCount++
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
    internal fun hasCachedText(text: String): Boolean {
        val fontPx = effectiveFontSp() * fontScalePx
        return cache.containsKey(CacheKey(text, fontPx.toRawBits(), config.strokeWidth.toRawBits()))
    }

    /** 容量失败的直绘回退: 复用 payload 持有的 TextLine(单次 shaping, draw 阶段零重栅),
     *  只复用引擎级 Paint, 不分配 Bitmap/数组, 不缓存任何像素。 */
    private fun drawDirectText(canvas: Canvas, item: ActiveDanmaku, direct: DirectTextPayload, y: Float) {
        val metrics = direct.metrics
        val strokePx = config.strokeWidth.coerceAtLeast(0f)
        val line = direct.shaped.line
        val baseline = y + metrics.padding - line.ascent
        val x = item.x + metrics.padding
        if (strokePx > 0f) {
            directStrokePaint.strokeWidth = strokePx
            canvas.drawTextLine(line, x, baseline, directStrokePaint)
        }
        directFillPaint.color = (0xFF shl 24) or (item.entry.color and 0xFFFFFF)
        canvas.drawTextLine(line, x, baseline, directFillPaint)
        directTextFallbackCount++
        lastDrawBatchCount++
        lastDrawQuadCount++
    }

    private fun ensureRegion(key: CacheKey, shaped: ShapedText): AtlasRegion? {
        val metrics = shaped.metrics
        // 单条 region 已超出单页尺寸, 无处可放(与 AtlasPage.add 的拒绝条件一致)。
        if (metrics.width + ATLAS_GUTTER * 2 > pageSize || metrics.height + ATLAS_GUTTER * 2 > pageSize) {
            return null
        }

        // 快路径: 缓存未满且某页的空闲表或 shelf 尚有余量。
        if (cache.size < cacheMax) insertDirect(key, shaped)?.let { return it }

        // 慢路径只做固定次数的增量淘汰; 每释放一个矩形就立即重试。禁止在 draw 线程整页重栅,
        // 仍放不下(极端碎片化/候选全活跃)时返回 null, 由 activate 降级单条直绘, 不丢弹幕。
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
            if (cache.size < cacheMax) insertDirect(key, shaped)?.let { return it }
        }
        return null
    }

    private fun insertDirect(key: CacheKey, shaped: ShapedText): AtlasRegion? {
        pages.forEach { page ->
            page.add(key, shaped)?.let { region ->
                rasterCount++
                cache[key] = region
                page.residentKeys.add(key)
                return region
            }
        }
        if (pages.size >= maxPages) return null
        val page = AtlasPage(pages.size, pageSize).also(pages::add)
        val region = page.add(key, shaped) ?: return null
        rasterCount++
        cache[key] = region
        page.residentKeys.add(key)
        return region
    }

    /** shape 一次: Font 即用即关(TextLine 引用 typeface 不引用 SkFont, 可独立存续)。 */
    private fun shape(key: CacheKey): ShapedText {
        val fontPx = Float.fromBits(key.fontBits)
        val strokePx = Float.fromBits(key.strokeBits).coerceAtLeast(0f)
        val padding = ceil(strokePx).toInt() + 1
        return Font(null, fontPx).use { font ->
            TextLine.make(key.text, font).let { line ->
                ShapedText(
                    line = line,
                    metrics = TextMetrics(
                        width = (ceil(line.width.toDouble()).toInt() + padding * 2).coerceAtLeast(1),
                        height = (ceil((line.descent - line.ascent).toDouble()).toInt() + padding * 2)
                            .coerceAtLeast(1),
                        padding = padding,
                    ),
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
        lastDrawQuadCount = 0
        rasterCount = 0
        evictionCount = 0
        directTextFallbackCount = 0
        atlasInsertionFailureCount = 0
    }

    /**
     * Atlas 页: 持有一张 raster [Surface] 供光栅化/擦除, 绘制 Paint 常驻(脏页只换
     * image/shader), 维护空闲表(holes)+ shelf 游标。
     *
     * - [add]: 查 holes(first-fit) -> 否则 shelf 游标 -> drawText 光栅化(白填充+黑描边)
     * - [release]: CLEAR 局部擦除 region 矩形(含 gutter) + holes 归还
     * - [draw]: 本页自上次 draw 后有写入时先 [Surface.makeImageSnapshot] 换 image/shader,
     *   再 drawVertices(顶点色调制)提交当前批
     */
    private class AtlasPage(
        val index: Int,
        private val size: Int,
    ) : AutoCloseable {
        private val surface = Surface.makeRasterN32Premul(size, size).also { it.canvas.clear(0x00000000) }

        /** 常驻绘制 Paint: 脏页快照时只重建 image/shader 并换挂, 不重建 Paint 本身。 */
        private val paint = Paint().also {
            it.blendMode = BlendMode.SRC_OVER
            it.isAntiAlias = true
        }

        /** 擦除像素(回收 region 时): CLEAR 局部擦除字形。 */
        private val clearPaint = Paint().also { it.blendMode = BlendMode.CLEAR }
        private var image: Image? = null
        private var shader: Shader? = null

        /** 光栅化复用: 描边恒黑/填充恒白(颜色无关烘焙), 只有描边宽逐条设置。 */
        private val strokePaint = Paint().also {
            it.mode = PaintMode.STROKE
            it.color = 0xFF000000.toInt()
            it.isAntiAlias = true
            it.strokeCap = PaintStrokeCap.ROUND
            it.strokeJoin = PaintStrokeJoin.ROUND
        }
        private val fillPaint = Paint().also {
            it.mode = PaintMode.FILL
            it.color = 0xFFFFFFFF.toInt()
            it.isAntiAlias = true
        }

        /** 固定容量空闲矩形表，避免长时间唯一文本 churn 创建无界 Hole 对象。 */
        private val holes = IntArray(MAX_HOLES_PER_PAGE * HOLE_COMPONENTS)
        var holeCount: Int = 0
            private set
        private var allocatedHoleLeft = 0
        private var allocatedHoleTop = 0
        /**
         * 当前本页 resident 的 CacheKey 集合; 与 cache 中 region.page === this 的条目一一对应。
         * 由 [add] 成功后的外层 insertDirect 与 [release] 配套的淘汰路径两处同步维护
         * (本引擎无压实换页路径)。供测试 O(页内 resident 数) 校验不变量。
         */
        val residentKeys: MutableSet<CacheKey> = HashSet()
        private var cursorX = ATLAS_GUTTER
        private var cursorY = ATLAS_GUTTER
        private var rowHeight = 0
        private var dirty = false
        private var closed = false

        fun add(key: CacheKey, shaped: ShapedText): AtlasRegion? {
            check(!closed) { "atlas page 已关闭" }
            val metrics = shaped.metrics
            val packedWidth = metrics.width + ATLAS_GUTTER
            val packedHeight = metrics.height + ATLAS_GUTTER
            if (packedWidth + ATLAS_GUTTER > size || packedHeight + ATLAS_GUTTER > size) return null
            // 先查空闲表(first-fit); 命中则不动 shelf 游标。会话内字号基本一致, 命中率极高。
            if (allocateHole(packedWidth, packedHeight)) {
                val region = AtlasRegion(this, allocatedHoleLeft, allocatedHoleTop, metrics.width, metrics.height)
                drawText(key, shaped, region)
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
            drawText(key, shaped, region)
            cursorX += packedWidth
            rowHeight = maxOf(rowHeight, packedHeight)
            dirty = true
            return region
        }

        /**
         * 回收 region: 矩形(含 gutter)归还空闲表, 并擦除原字形像素——否则空闲表复用时
         * 新旧字形在同一表面叠加会透出旧文本。add 的落位检查保证
         * left+packedWidth<=size、top+packedHeight<=size, 空闲块必在页内。
         */
        fun release(region: AtlasRegion) {
            check(!closed) { "atlas page 已关闭" }
            surface.canvas.drawRect(
                Rect(
                    region.left.toFloat(),
                    region.top.toFloat(),
                    (region.left + region.width + ATLAS_GUTTER).toFloat(),
                    (region.top + region.height + ATLAS_GUTTER).toFloat(),
                ),
                clearPaint,
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
                // 容量满时保留面积更大的空闲块: release 后的同尺寸 region 会优先替换小碎片,
                // 下一次 insertDirect 可立即复用, 避免长期 churn 把可用空间静默丢光。
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

        fun draw(target: Canvas, batch: DesktopAtlasQuadBatch): Boolean {
            if (closed) return false
            if (dirty) {
                // raster Surface 快照: 本次 draw 用旧内容的不可变 Image, 页的下次写入触发
                // 写时整页拷贝。先 setShader 再 close 旧对象, 避免 Paint 短暂持有已关闭引用。
                val nextImage = surface.makeImageSnapshot()
                val nextShader = nextImage.makeShader(
                    FilterTileMode.CLAMP,
                    FilterTileMode.CLAMP,
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
                    Matrix33.IDENTITY,
                )
                paint.shader = nextShader
                image?.close()
                shader?.close()
                image = nextImage
                shader = nextShader
                dirty = false
            }
            if (shader == null) return false
            target.drawVertices(
                VertexMode.TRIANGLES,
                batch.positions,
                batch.colors,
                batch.textureCoordinates,
                batch.indices,
                // 顶点色(弹幕色)与 atlas 采样逐分量相乘: 白填充被染成弹幕色, 黑描边保持黑。
                BlendMode.MODULATE,
                paint,
            )
            return true
        }

        /** 白填充 + 黑描边(颜色无关烘焙); 度量与 TextLine 来自同一次 [shape]。 */
        private fun drawText(key: CacheKey, shaped: ShapedText, region: AtlasRegion) {
            val strokePx = Float.fromBits(key.strokeBits).coerceAtLeast(0f)
            val x = region.left + shaped.metrics.padding.toFloat()
            val baseline = region.top + shaped.metrics.padding - shaped.line.ascent
            if (strokePx > 0f) {
                strokePaint.strokeWidth = strokePx
                surface.canvas.drawTextLine(shaped.line, x, baseline, strokePaint)
            }
            surface.canvas.drawTextLine(shaped.line, x, baseline, fillPaint)
        }

        override fun close() {
            if (closed) return
            closed = true
            strokePaint.close()
            fillPaint.close()
            image?.close()
            shader?.close()
            clearPaint.close()
            paint.close()
            surface.close()
        }
    }

    internal class DesktopAtlasQuadBatch {
        var positions = FloatArray(INITIAL_BATCH_QUADS * FLOATS_PER_QUAD) { OFFSCREEN }
            private set
        var textureCoordinates = FloatArray(INITIAL_BATCH_QUADS * FLOATS_PER_QUAD)
            private set

        /** 每顶点 SkColor(int ARGB); [AtlasPage.draw] 以 BlendMode.MODULATE 与 atlas 采样调制。 */
        var colors = IntArray(INITIAL_BATCH_QUADS * VERTICES_PER_QUAD)
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

        fun add(x: Float, y: Float, left: Int, top: Int, width: Int, height: Int, color: Int): Boolean {
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

            val opaqueColor = (0xFF shl 24) or (color and RGB_MASK)
            val colorOffset = quadCount * VERTICES_PER_QUAD
            colors[colorOffset] = opaqueColor
            colors[colorOffset + 1] = opaqueColor
            colors[colorOffset + 2] = opaqueColor
            colors[colorOffset + 3] = opaqueColor
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
            colors = colors.copyOf(capacity * VERTICES_PER_QUAD)
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

        /** C-P2-7: 单帧最大光栅化次数(缓存 miss 预算, 对齐 AndroidAtlasDanmakuEngine)。 */
        const val MAX_RASTER_MISSES_PER_FRAME = 12
        /** C-P2-7: 单帧最大激活候选数(防高密度瞬时处理撑爆本帧)。 */
        const val MAX_ACTIVATION_CANDIDATES_PER_FRAME = 256

        /** 慢路径单次 miss 的淘汰上限(对齐 Android; 淘汰批有界, 剩余留给下一帧)。 */
        const val MAX_EVICTIONS_PER_MISS = 8
        /** 慢路径单次 miss 的淘汰扫描候选上限(全活跃时避免白扫整张 LRU)。 */
        const val MAX_EVICTION_CANDIDATES = 64

        const val ATLAS_GUTTER = 1
        const val BYTES_PER_PIXEL = 4L
        const val MAX_HOLES_PER_PAGE = 256
        const val MAX_HOLE_MERGES_PER_INSERT = 8
        const val HOLE_COMPONENTS = 4
        const val INITIAL_BATCH_QUADS = 64
        const val MAX_BATCH_QUADS = 8191
        const val FLOATS_PER_QUAD = 8
        const val VERTICES_PER_QUAD = 4
        const val INDICES_PER_QUAD = 6
        const val OFFSCREEN = -1_000_000f
        const val RGB_MASK = 0x00FFFFFF

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
