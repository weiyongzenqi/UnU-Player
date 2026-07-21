package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuSource

class DesktopAtlasDanmakuEngineTest {
    @Test
    fun `批次缓冲覆盖 1k 3k 5k 并在峰值后复用数组`() {
        val batch = DesktopAtlasDanmakuEngine.DesktopAtlasQuadBatch()
        intArrayOf(1_000, 3_000, 5_000).forEach { count ->
            batch.reset()
            repeat(count) { index ->
                assertTrue(batch.add(index.toFloat(), 0f, 0, 0, 12, 12))
            }
            assertEquals(count, batch.quadCount)
            assertTrue(batch.positions.size >= count * 8)
        }
        val positions = batch.positions
        val textureCoordinates = batch.textureCoordinates
        val indices = batch.indices
        batch.reset()
        repeat(5_000) { index -> batch.add(index.toFloat(), 0f, 0, 0, 12, 12) }
        assertSame(positions, batch.positions)
        assertSame(textureCoordinates, batch.textureCoordinates)
        assertSame(indices, batch.indices)
    }

    @Test
    fun `高密度文本共享有界 atlas 且清空释放页面`() {
        val engine = DesktopAtlasDanmakuEngine(pageSize = 128, maxPages = 2, cacheMax = 256)
        engine.setConfig(DanmakuConfig(maxOnScreen = 0, fontSize = 12f, strokeWidth = 1f))
        engine.load((0 until 200).map { index -> entry(index * 0.001, "弹幕-$index") })

        engine.onFrame(1_000L, 1_000f, 500f, 0.016f)

        assertTrue(engine.cachedRegionCount > 0)
        assertTrue(engine.atlasPageCount in 1..2)
        assertTrue(engine.atlasPixelBytes <= 2L * 128L * 128L * 4L)
        engine.clear()
        assertEquals(0, engine.cachedRegionCount)
        assertEquals(0, engine.atlasPageCount)
        assertEquals(0L, engine.atlasPixelBytes)
    }

    @Test
    fun `饱和后持续新增走增量淘汰不触发全量重建`() {
        val engine = DesktopAtlasDanmakuEngine(pageSize = 128, maxPages = 2, cacheMax = 4096)
        engine.setConfig(DanmakuConfig(fontSize = 12f, strokeWidth = 1f, maxOnScreen = 0))
        // 统一字号/字数: 两页很快被填满, 之后 200+ 条唯一文本持续走增量淘汰(每帧 0.25s, TOP 轨道来得及周转)。
        val total = 260
        engine.load((0 until total).map { index -> entry(index * 0.25, "弹${index.toString().padStart(3, '0')}") })

        repeat(total) { index -> engine.onFrame((index * 250).toLong(), 800f, 400f, 0.016f) }

        assertEquals(0, engine.fullRebuildCount, "不应再发生全量重建")
        assertTrue(engine.pageCompactCount <= 200, "单页压实次数应有界, 实际 ${engine.pageCompactCount}")
        assertTrue(engine.cachedRegionCount in 1..4096, "缓存条目数应受 cacheMax 约束")
        assertTrue(engine.atlasPageCount in 1..2)
    }

    @Test
    fun `活跃常驻弹幕在饱和淘汰中受保护且可绘制`() {
        val engine = DesktopAtlasDanmakuEngine(pageSize = 128, maxPages = 2, cacheMax = 4096)
        engine.setConfig(DanmakuConfig(fontSize = 12f, strokeWidth = 1f, maxOnScreen = 0))
        val residentText = "常驻弹幕"
        val entries = ArrayList<DanmakuEntry>()
        // 常驻 TOP 弹幕每 3s 再激活一次(缓存命中, active 集合始终持有其 key); 200 条唯一文本以 0.25s 间隔饱和页面。
        repeat(17) { index -> entries.add(entry(index * 3.0, residentText)) }
        repeat(200) { index -> entries.add(entry(0.1 + index * 0.25, "饱和弹${index.toString().padStart(3, '0')}")) }
        engine.load(entries)

        repeat(210) { index -> engine.onFrame((index * 250).toLong(), 800f, 600f, 0.016f) }

        assertEquals(0, engine.fullRebuildCount)
        assertTrue(engine.hasCachedText(residentText, 0xFFFFFF), "活跃常驻弹幕应仍在 atlas 缓存中")
        drawOnce(engine, 800f, 600f)
        assertTrue(engine.lastDrawBatchCount > 0, "draw 后应有批次提交")
    }

    @Test
    fun `淘汰释放的空闲矩形被 free-list 复用不触发单页压实`() {
        val engine = DesktopAtlasDanmakuEngine(pageSize = 128, maxPages = 2, cacheMax = 4096)
        engine.setConfig(DanmakuConfig(fontSize = 12f, strokeWidth = 1f, maxOnScreen = 0))
        // 统一字号/字数: 淘汰产生的空闲矩形可被同尺寸新条目精确复用(无碎片)。
        val warmup = 60
        val extra = 50
        engine.load((0 until warmup + extra).map { index -> entry(index * 0.25, "复用弹${index.toString().padStart(3, '0')}") })

        repeat(warmup) { index -> engine.onFrame((index * 250).toLong(), 800f, 600f, 0.016f) }
        assertTrue(engine.cachedRegionCount > 0)
        val saturated = engine.cachedRegionCount
        val compactBefore = engine.pageCompactCount
        val rasterBefore = engine.rasterCount

        repeat(extra) { index -> engine.onFrame(((warmup + index) * 250).toLong(), 800f, 600f, 0.016f) }

        assertEquals(compactBefore, engine.pageCompactCount, "复用空闲矩形不应触发单页压实")
        assertEquals(saturated, engine.cachedRegionCount, "稳态淘汰后缓存条目数应稳定")
        assertEquals(extra, engine.rasterCount - rasterBefore, "每次新增只光栅化新条目自身, 不重栅幸存条目")
        assertEquals(0, engine.fullRebuildCount)
    }

    @Test
    fun `增量淘汰路径下 residentKeys 与 cache 全程一致`() {
        // 统一字形短文本: 淘汰产生的空闲矩形被 free-list 精确复用, 主要走淘汰路径(非压实);
        // 每帧后校验 pages 上所有 residentKeys 之和 == cache.size, 确保淘汰同步点未遗漏。
        val engine = DesktopAtlasDanmakuEngine(pageSize = 128, maxPages = 2, cacheMax = 4096)
        engine.setConfig(DanmakuConfig(fontSize = 12f, strokeWidth = 1f, maxOnScreen = 0))
        val total = 200
        engine.load((0 until total).map { index -> entry(index * 0.25, "弹${index.toString().padStart(3, '0')}") })

        repeat(total) { index ->
            engine.onFrame((index * 250).toLong(), 800f, 400f, 0.016f)
            assertEquals(
                engine.cachedRegionCount, engine.residentKeyTotal,
                "frame=$index 后 residentKeys(${engine.residentKeyTotal}) 与 cache(${engine.cachedRegionCount}) 不一致",
            )
        }

        assertEquals(0, engine.fullRebuildCount, "不应发生全量重建")
    }

    @Test
    fun `compactPage 路径下 residentKeys 与 cache 全程一致`() {
        // 变长文本制造碎片化, 强制压实路径触发; 每帧后校验 residentKeys 与 cache 一致,
        // 即压实换页后 fresh.residentKeys 与 cache 中指向 fresh 的条目一一对应, 旧 page residentKeys 不残留。
        val engine = DesktopAtlasDanmakuEngine(pageSize = 128, maxPages = 2, cacheMax = 256)
        engine.setConfig(DanmakuConfig(fontSize = 12f, strokeWidth = 1f, maxOnScreen = 0))
        val total = 220
        engine.load((0 until total).map { index ->
            val repeated = "弹".repeat(index % 8 + 1) // 1..8 个"弹"字, 制造不同字形宽度引发碎片化
            entry(index * 0.1, "$repeated${index.toString().padStart(3, '0')}")
        })

        repeat(total) { index ->
            engine.onFrame((index * 100).toLong(), 800f, 400f, 0.016f)
            assertEquals(
                engine.cachedRegionCount, engine.residentKeyTotal,
                "frame=$index 后 residentKeys(${engine.residentKeyTotal}) 与 cache(${engine.cachedRegionCount}) 不一致",
            )
        }

        assertEquals(0, engine.fullRebuildCount, "不应发生全量重建")
        assertTrue(engine.pageCompactCount > 0, "应至少触发一次单页压实以验证压实路径下的一致性, 实际 ${engine.pageCompactCount}")
    }

    /** 用 ImageBitmap 承载的 Compose Canvas 在无窗口环境真实走一遍 draw, 验证绘制提交链。 */
    private fun drawOnce(engine: DesktopAtlasDanmakuEngine, width: Float, height: Float) {
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(ImageBitmap(width.toInt(), height.toInt())),
            Size(width, height),
        ) {
            engine.draw(this)
        }
    }

    private fun entry(timeSec: Double, text: String) = DanmakuEntry(
        timeSec = timeSec,
        mode = DanmakuMode.TOP,
        color = 0xFFFFFF,
        text = text,
        source = DanmakuSource.LOCAL,
    )
}
