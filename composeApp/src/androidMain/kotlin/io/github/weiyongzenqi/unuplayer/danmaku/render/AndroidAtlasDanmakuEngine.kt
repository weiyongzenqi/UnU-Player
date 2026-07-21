package io.github.weiyongzenqi.unuplayer.danmaku.render

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
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
 * draw 时 [android.graphics.Canvas.drawBitmap] 逐条提交(同 atlas page 的 Bitmap 由硬件加速
 * RenderThread 合并纹理绑定, draw call 从 N 降到 ~page 数), 内存从逐条 Bitmap(可达 48MiB)
 * 降到 atlas page(默认 4×1024×1024×4 = 16MiB)。
 *
 * 蓝本: [DesktopAtlasDanmakuEngine](桌面用 Skia drawVertices 批量提交; Android 用 nativeCanvas
 * .drawBitmap 批提交, 等价语义: 同纹理批量提交让 GPU 合并)。运动模型/轨道/激活逻辑复用
 * [BaseDanmakuEngine](增量式墙钟运动, 不改)。
 *
 * 淘汰/插入增量有界(同桌面):
 * - region 被淘汰时其矩形(含 gutter)归还所属 page 的空闲表并擦除旧字形像素([AtlasPage.release]);
 * - 新插入先走空闲表 first-fit、余量切分回写([AtlasPage.allocateHole]), 不命中再退回 shelf 游标;
 * - 仅当空闲表 + shelf 仍放不下(碎片化)时, 才从 LRU 头部有界淘汰一小批非活跃条目,
 *   并**只压实单个 page**([compactPage], 只重栅该 page 的幸存条目, 活跃 region 优先重建), 其余 page 绝不触碰。
 *
 * 所有可变状态只在 Compose draw 线程(主线程)更新: 不加锁、不跨线程共享。
 */
internal class AndroidAtlasDanmakuEngine(
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
        val page: AtlasPage,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    private val cache = LinkedHashMap<CacheKey, AtlasRegion>(256, 0.75f, true)
    private val pages = ArrayList<AtlasPage>(maxPages)

    /** draw 时复用的 src/dst 矩形 + Paint, 避免每条弹幕分配。单线程(主线程)安全。 */
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val drawPaint = Paint()

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

    init {
        require(pageSize >= MIN_PAGE_SIZE) { "atlas page 太小: $pageSize" }
        require(maxPages in 1..MAX_PAGE_COUNT) { "atlas page 数必须在 1..$MAX_PAGE_COUNT" }
        require(cacheMax > 0) { "atlas cacheMax 必须大于 0" }
    }

    override fun engineName(): String = "android-atlas"

    override fun onEntriesReplaced() = releaseAtlas()

    override fun activate(e: DanmakuEntry, posSec: Double, screenW: Float, baseSpeed: Float): Boolean {
        if (e.text.isEmpty()) return false
        val fontPx = effectiveFontSp() * fontScalePx
        val key = CacheKey(e.text, e.color, fontPx.toRawBits(), config.strokeWidth.toRawBits())
        val cached = cache[key]
        val metrics = cached?.let { TextMetrics(it.width, it.height, 0) } ?: measure(key)
        if (metrics.width <= 0 || metrics.height <= 0) return false
        val width = metrics.width.toFloat()
        val placement = when (e.mode) {
            DanmakuMode.SCROLL -> {
                val lane = scrollAllocator.allocate(e.timeSec, width, baseSpeed)
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

        val region = cached ?: ensureRegion(key, metrics) ?: return false
        val x = if (e.mode == DanmakuMode.TOP || e.mode == DanmakuMode.BOTTOM) {
            (screenW - region.width) / 2f
        } else {
            placement.second
        }
        active.add(ActiveDanmaku(e, placement.first, region.width.toFloat(), x, key))
        return true
    }

    override fun draw(scope: DrawScope) {
        if (active.isEmpty() || pages.isEmpty()) return
        val screenHeight = scope.size.height
        scope.drawIntoCanvas { composeCanvas ->
            val nativeCanvas = composeCanvas.nativeCanvas
            active.forEach { item ->
                val key = item.payload as? CacheKey ?: return@forEach
                val region = cache[key] ?: return@forEach  // 被淘汰则跳过该弹幕(下帧 activate 重试)
                val y = laneY(item.entry.mode, item.lane, screenHeight) + (laneHeight - region.height) / 2f
                srcRect.set(region.left, region.top, region.left + region.width, region.top + region.height)
                dstRect.set(item.x, y, item.x + region.width, y + region.height)
                // 同 atlas page 的连续 drawBitmap 由硬件加速 RenderThread 合并(同纹理批量提交)
                nativeCanvas.drawBitmap(region.page.bitmap, srcRect, dstRect, drawPaint)
            }
        }
    }

    private fun ensureRegion(key: CacheKey, metrics: TextMetrics): AtlasRegion? {
        // 单条 region 已超出单页尺寸, 无处可放(与 AtlasPage.add 的拒绝条件一致)。
        if (metrics.width + ATLAS_GUTTER * 2 > pageSize || metrics.height + ATLAS_GUTTER * 2 > pageSize) {
            return null
        }

        // 快路径: 缓存未满且某页的空闲表或 shelf 尚有余量。
        if (cache.size < cacheMax) insertDirect(key, metrics)?.let { return it }

        // 慢路径: 增量淘汰 + 有界单页压实, 永不全量重建。
        val activeKeys = HashSet<CacheKey>(active.size)
        active.forEach { (it.payload as? CacheKey)?.let(activeKeys::add) }
        val compacted = HashSet<Int>(pages.size)

        while (true) {
            // 1) 从 LRU 头部(access-order 头 = 最旧)淘汰有界批量的非活跃条目;
            //    被回收矩形立即擦除字形并归还所属 page 的空闲表。
            var removed = 0
            val target = maxOf(1, cache.size / COMPACT_EVICTION_FRACTION)
            val affected = HashSet<AtlasPage>(2)
            val iterator = cache.entries.iterator()
            while (iterator.hasNext() && removed < target) {
                val node = iterator.next()
                if (node.key in activeKeys) continue
                val region = node.value
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
            compactPage(victim, activeKeys)
            compacted.add(victim.index)
            // 压实后立刻重试, 避免回到循环顶部多淘汰一批: 新页 shelf 通常直接容得下。
            if (cache.size < cacheMax) insertDirect(key, metrics)?.let { return it }
        }
    }

    private fun insertDirect(key: CacheKey, metrics: TextMetrics): AtlasRegion? {
        pages.forEach { page ->
            page.add(key, metrics)?.let { region ->
                cache[key] = region
                page.residentKeys.add(key)
                return region
            }
        }
        if (pages.size >= maxPages) return null
        val page = AtlasPage(pages.size, pageSize).also(pages::add)
        val region = page.add(key, metrics) ?: return null
        cache[key] = region
        page.residentKeys.add(key)
        return region
    }

    /**
     * 单页压实: 只 recycle + 重建 [page], 把该页幸存 region(含活跃项)重新测量并插入新页; 其余 page 绝不触碰。
     * 仅在碎片化兜底路径调用。幸存条目按"活跃优先、再按原 region 高/宽降序"排列: 最大化新页 shelf
     * 装箱成功率, 且理论不可达的兜底丢弃发生时优先保留活跃 region。
     */
    private fun compactPage(page: AtlasPage, activeKeys: Set<CacheKey>) {
        val survivors = page.residentKeys.toMutableList()
        survivors.sortWith(
            compareByDescending<CacheKey> { it in activeKeys }
                .thenByDescending { survivor -> cache.getValue(survivor).height }
                .thenByDescending { survivor -> cache.getValue(survivor).width },
        )
        val index = page.index
        page.close()  // bitmap.recycle() 释放 native 内存
        val fresh = AtlasPage(index, pageSize)
        pages[index] = fresh
        survivors.forEach { survivor ->
            val region = fresh.add(survivor, measure(survivor))
            if (region != null) {
                cache[survivor] = region
                fresh.residentKeys.add(survivor)
            } else {
                // 理论不可达: 幸存条目原本就装得下同尺寸页, 空页 shelf 必然容得下。
                // 兜底丢弃该缓存条目; draw() 对解析不到 region 的 key 直接跳过, 不影响其他弹幕。
                cache.remove(survivor)
            }
        }
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
        cache.clear()
        pages.forEach(AtlasPage::close)
        pages.clear()
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
        private data class Hole(val left: Int, val top: Int, val width: Int, val height: Int)

        val bitmap: Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { it.eraseColor(0) }
        private val canvas = AndroidCanvas(bitmap)
        private val holes = ArrayList<Hole>()
        /**
         * 当前本页 resident 的 CacheKey 集合; 与 cache 中 region.page === this 的条目一一对应。
         * 由 [add] 成功后的外层 [insertDirect]、[release] 配套的淘汰路径、[compactPage] 的换页路径三处同步维护。
         * 供 compactPage O(页内 resident 数) 取幸存者, 避免扫全局 cache(LRU 上限 4096)。
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
            allocateHole(packedWidth, packedHeight)?.let { hole ->
                val region = AtlasRegion(this, hole.left, hole.top, metrics.width, metrics.height)
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
            holes.add(Hole(region.left, region.top, region.width + ATLAS_GUTTER, region.height + ATLAS_GUTTER))
        }

        /**
         * 空闲表 first-fit: 命中即取出, 余量按断头台式切分回写--右块只占本行高度、
         * 底块占满空闲块全宽, 两块互不重叠, 也不与已分配区域重叠。
         */
        private fun allocateHole(packedWidth: Int, packedHeight: Int): Hole? {
            val found = holes.indexOfFirst { it.width >= packedWidth && it.height >= packedHeight }
            if (found < 0) return null
            val hole = holes.removeAt(found)
            val rightWidth = hole.width - packedWidth
            val bottomHeight = hole.height - packedHeight
            if (rightWidth > 0) holes.add(Hole(hole.left + packedWidth, hole.top, rightWidth, packedHeight))
            if (bottomHeight > 0) holes.add(Hole(hole.left, hole.top + packedHeight, hole.width, bottomHeight))
            return hole
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
            // key.color 是 0xRRGGBB(无 alpha); Paint.color 要 0xAARRGGBB, 补 alpha=255 防填充透明
            textPaint.color = (0xFF shl 24) or (key.color and 0xFFFFFF)
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
        const val DEFAULT_MAX_PAGES = 4
        const val MAX_PAGE_COUNT = 8
        const val DEFAULT_CACHE_MAX = 4096

        /** 兜底每轮淘汰的缓存比例(access-order LRU 头部): 有界批量, 避免单帧大批量淘汰与压实。 */
        const val COMPACT_EVICTION_FRACTION = 64
        const val ATLAS_GUTTER = 1
        const val BYTES_PER_PIXEL = 4L
    }
}
