package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuSource

class DesktopAtlasDanmakuEngineTest {
    @Test
    fun `终态释放关闭两个引擎级Paint且保持幂等`() {
        val engine = DesktopAtlasDanmakuEngine(pageSize = 64, maxPages = 1, cacheMax = 16)
        engine.setConfig(DanmakuConfig(fontSize = 12f, strokeWidth = 1f))
        engine.load(listOf(entry(0.0, "A")))
        engine.onFrame(0L, 200f, 40f, 0f)
        assertTrue(engine.atlasPageCount > 0)

        engine.dispose()
        engine.dispose()

        assertEquals(0, engine.atlasPageCount)
        assertEquals(0, engine.cachedRegionCount)
        assertEquals(2, engine.terminalPaintCloseCount)
    }

    @Test
    fun `activate资源租约在异常出口关闭且转移后不代关`() {
        class CountingResource : AutoCloseable {
            var closeCount = 0
            override fun close() {
                closeCount++
            }
        }

        val failedResource = CountingResource()
        val failedLease = DesktopAtlasDanmakuEngine.TransferableResource(failedResource)
        assertFailsWith<IllegalStateException> {
            try {
                error("模拟 ensureRegion 异常")
            } finally {
                failedLease.close()
            }
        }
        failedLease.close()
        assertEquals(1, failedResource.closeCount, "异常出口与重复 finally 只能关闭一次")

        val transferredResource = CountingResource()
        val transferredLease = DesktopAtlasDanmakuEngine.TransferableResource(transferredResource)
        transferredLease.transfer()
        transferredLease.close()
        assertEquals(0, transferredResource.closeCount, "进入 active 后由 DirectTextPayload 持有")
        transferredResource.close()
        assertEquals(1, transferredResource.closeCount)
    }

    @Test
    fun `批次缓冲覆盖 1k 3k 5k 并在峰值后复用数组`() {
        val batch = DesktopAtlasDanmakuEngine.DesktopAtlasQuadBatch()
        intArrayOf(1_000, 3_000, 5_000).forEach { count ->
            batch.reset()
            repeat(count) { index ->
                assertTrue(batch.add(index.toFloat(), 0f, 0, 0, 12, 12, 0xFFFFFF))
            }
            assertEquals(count, batch.quadCount)
            assertTrue(batch.positions.size >= count * 8)
        }
        val positions = batch.positions
        val textureCoordinates = batch.textureCoordinates
        val colors = batch.colors
        val indices = batch.indices
        batch.reset()
        repeat(5_000) { index -> batch.add(index.toFloat(), 0f, 0, 0, 12, 12, 0xFFFFFF) }
        assertSame(positions, batch.positions)
        assertSame(textureCoordinates, batch.textureCoordinates)
        assertSame(colors, batch.colors)
        assertSame(indices, batch.indices)
    }

    @Test
    fun `高峰后提交数组回落到低密度容量桶并保持复用`() {
        val batch = DesktopAtlasDanmakuEngine.DesktopAtlasQuadBatch()
        repeat(5_000) { index -> batch.add(index.toFloat(), 0f, 0, 0, 12, 12, 0xFFFFFF) }
        batch.prepareForDraw(5_000)
        val peakSize = batch.positions.size

        batch.reset()
        repeat(10) { index -> batch.add(index.toFloat(), 0f, 0, 0, 12, 12, 0xFFFFFF) }
        batch.prepareForDraw(10)

        assertTrue(batch.positions.size < peakSize)
        assertEquals(64 * 8, batch.positions.size)
        assertEquals(64 * 4, batch.colors.size)
        val lowPositions = batch.positions
        val lowTextureCoordinates = batch.textureCoordinates
        val lowColors = batch.colors
        val lowIndices = batch.indices

        batch.reset()
        repeat(10) { index -> batch.add(index.toFloat(), 0f, 0, 0, 12, 12, 0xFFFFFF) }
        batch.prepareForDraw(10)
        assertSame(lowPositions, batch.positions)
        assertSame(lowTextureCoordinates, batch.textureCoordinates)
        assertSame(lowColors, batch.colors)
        assertSame(lowIndices, batch.indices)
    }

    @Test
    fun `容量桶边界波动不重复分配`() {
        val batch = DesktopAtlasDanmakuEngine.DesktopAtlasQuadBatch()
        repeat(65) { index -> batch.add(index.toFloat(), 0f, 0, 0, 12, 12, 0xFFFFFF) }
        batch.prepareForDraw(65)
        val positions = batch.positions
        val textureCoordinates = batch.textureCoordinates
        val colors = batch.colors
        val indices = batch.indices

        batch.reset()
        batch.prepareForDraw(64)

        assertSame(positions, batch.positions)
        assertSame(textureCoordinates, batch.textureCoordinates)
        assertSame(colors, batch.colors)
        assertSame(indices, batch.indices)
    }

    @Test
    fun `批次缓冲写入不透明顶点色`() {
        val batch = DesktopAtlasDanmakuEngine.DesktopAtlasQuadBatch()
        batch.add(0f, 0f, 0, 0, 10, 10, 0x123456)
        batch.add(0f, 0f, 0, 0, 10, 10, 0x123456)

        assertEquals(0xFF123456.toInt(), batch.colors[0], "弹幕色应转为不透明 SkColor")
        assertEquals(0xFF123456.toInt(), batch.colors[3])
        assertEquals(0xFF123456.toInt(), batch.colors[4], "第二个 quad 从第 4 顶点起")
        assertEquals(0xFF123456.toInt(), batch.colors[7])
    }

    @Test
    fun `跨页绘制按 active 中的连续 page 段提交`() {
        // MA/MB/MA: 第 1、3 条同文本共享 region(p0), 第 2 条不同文本进 p1;
        // 页序 p0,p1,p0 必须提交三个连续段, 不能全局按 page 重排。
        val engine = DesktopAtlasDanmakuEngine(pageSize = 64, maxPages = 2, cacheMax = 16)
        engine.setConfig(DanmakuConfig(fontSize = 28f, strokeWidth = 1f, maxOnScreen = 3))
        engine.load(
            listOf(
                entry(0.0, "MA", mode = DanmakuMode.TOP, color = 0xFF0000),
                entry(0.0, "MB", mode = DanmakuMode.BOTTOM, color = 0x00FF00),
                entry(0.0, "MA", mode = DanmakuMode.SCROLL, color = 0x0000FF),
            ),
        )

        engine.onFrame(0L, 128f, 48f, 0f)
        assertEquals(3, engine.activeDanmakuCount)
        assertEquals(2, engine.atlasPageCount)
        assertEquals(2, engine.cachedRegionCount, "MA 两条共享 region, MB 一条")

        drawOnce(engine, 128f, 48f)

        assertEquals(3, engine.lastDrawBatchCount, "A/B/A page 顺序必须提交三个连续段")
    }

    @Test
    fun `同文本不同颜色共享同一 region`() {
        val engine = DesktopAtlasDanmakuEngine(pageSize = 128, maxPages = 2, cacheMax = 256)
        engine.setConfig(DanmakuConfig(fontSize = 12f, strokeWidth = 1f, maxOnScreen = 0))
        engine.load(
            listOf(
                entry(0.0, "彩色弹幕", mode = DanmakuMode.TOP, color = 0xFF0000),
                entry(0.0, "彩色弹幕", mode = DanmakuMode.BOTTOM, color = 0x00FF00),
                entry(0.0, "彩色弹幕", mode = DanmakuMode.SCROLL, color = 0x0000FF),
            ),
        )

        engine.onFrame(0L, 800f, 400f, 0f)

        assertEquals(3, engine.activeDanmakuCount, "三种颜色都应正常激活")
        assertEquals(1, engine.cachedRegionCount, "颜色无关缓存: 同文本只占一个 region")
        assertEquals(1, engine.rasterCount, "同文本只光栅化一次")
    }

    @Test
    fun `draw 输出被顶点色调制的弹幕色像素`() {
        // 白填充烘焙 × 顶点色红(MODULATE) = 红色字形像素; 验证 Bitmap 常驻 shader +
        // colors + drawVertices 全链路在 raster 后端按预期合成。
        val engine = DesktopAtlasDanmakuEngine(pageSize = 128, maxPages = 2, cacheMax = 64)
        engine.setConfig(DanmakuConfig(fontSize = 16f, strokeWidth = 0f, maxOnScreen = 0))
        engine.load(listOf(entry(0.0, "红", mode = DanmakuMode.TOP, color = 0xFF0000)))

        engine.onFrame(0L, 200f, 40f, 0f)
        assertEquals(1, engine.activeDanmakuCount)

        val image = drawOnce(engine, 200f, 40f)
        val pixels = image.toPixelMap().buffer
        val visible = pixels.count { it ushr 24 > 0 }
        assertTrue(visible > 30, "应渲染出可见字形像素, 实际 $visible")
        val red = pixels.count {
            (it ushr 24) > 0 && ((it shr 16) and 0xFF) > 150 && ((it shr 8) and 0xFF) < 100 && (it and 0xFF) < 100
        }
        assertTrue(red > 10, "顶点色调制应产出红色字形像素, 实际 $red")
    }

    @Test
    fun `超出页尺寸的长文本降级直绘不丢弃`() {
        val engine = DesktopAtlasDanmakuEngine(pageSize = 64, maxPages = 2, cacheMax = 64)
        engine.setConfig(DanmakuConfig(fontSize = 24f, strokeWidth = 1f, maxOnScreen = 0))
        engine.load(listOf(entry(0.0, "这是一条远超页尺寸的超长弹幕文本", mode = DanmakuMode.TOP)))

        engine.onFrame(0L, 800f, 48f, 0f)

        assertEquals(1, engine.activeDanmakuCount, "容量失败必须降级直绘而不是丢弃弹幕")
        assertEquals(1, engine.atlasInsertionFailureCount)
        assertEquals(0, engine.cachedRegionCount)
        assertEquals(0, engine.atlasPageCount)

        drawOnce(engine, 800f, 48f)
        assertEquals(1, engine.directTextFallbackCount, "draw 应走直绘回退路径")
        assertEquals(0, engine.cachedRegionCount)
        engine.clear()
        assertEquals(0, engine.activeDanmakuCount, "直绘载荷在清空时应从 active 移除并释放")
    }

    @Test
    fun `轨道饱和时不光栅化或缓存不可见唯一文本`() {
        val engine = DesktopAtlasDanmakuEngine(pageSize = 128, maxPages = 2, cacheMax = 256)
        engine.setConfig(DanmakuConfig(fontSize = 12f, strokeWidth = 1f, maxOnScreen = 0))
        engine.load(
            listOf(
                entry(0.0, "可见弹幕"),
                entry(0.0, "轨道满后不应光栅化"),
            ),
        )

        engine.onFrame(0L, 800f, 18f, 0f)

        assertEquals(1, engine.activeDanmakuCount)
        assertEquals(1, engine.cachedRegionCount)
        assertEquals(1, engine.rasterCount)
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
    fun `饱和后持续新增走增量淘汰且零整页重栅`() {
        val engine = DesktopAtlasDanmakuEngine(pageSize = 128, maxPages = 2, cacheMax = 4096)
        engine.setConfig(DanmakuConfig(fontSize = 12f, strokeWidth = 1f, maxOnScreen = 0))
        // 统一字号/字数: 两页很快被填满, 之后 200+ 条唯一文本持续走增量淘汰(每帧 0.25s, TOP 轨道来得及周转)。
        // 零整页重栅的直接证据: 每条文本至多光栅化一次(rasterCount <= total), 旧实现压实会重栅幸存者。
        val total = 260
        engine.load((0 until total).map { index -> entry(index * 0.25, "弹${index.toString().padStart(3, '0')}") })

        repeat(total) { index -> engine.onFrame((index * 250).toLong(), 800f, 400f, 0.016f) }

        assertTrue(engine.rasterCount <= total, "每条至多光栅化一次, 实际 ${engine.rasterCount}/$total")
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

        assertTrue(engine.hasCachedText(residentText), "活跃常驻弹幕应仍在 atlas 缓存中")
        drawOnce(engine, 800f, 600f)
        assertTrue(engine.lastDrawBatchCount > 0, "draw 后应有批次提交")
    }

    @Test
    fun `淘汰释放的空闲矩形被 free-list 复用且不重栅幸存条目`() {
        val engine = DesktopAtlasDanmakuEngine(pageSize = 128, maxPages = 2, cacheMax = 4096)
        engine.setConfig(DanmakuConfig(fontSize = 12f, strokeWidth = 1f, maxOnScreen = 0))
        // 统一字号/字数: 淘汰产生的空闲矩形可被同尺寸新条目精确复用(无碎片)。
        val warmup = 60
        val extra = 50
        engine.load((0 until warmup + extra).map { index -> entry(index * 0.25, "复用弹${index.toString().padStart(3, '0')}") })

        repeat(warmup) { index -> engine.onFrame((index * 250).toLong(), 800f, 600f, 0.016f) }
        assertTrue(engine.cachedRegionCount > 0)
        val saturated = engine.cachedRegionCount
        val rasterBefore = engine.rasterCount

        repeat(extra) { index -> engine.onFrame(((warmup + index) * 250).toLong(), 800f, 600f, 0.016f) }

        assertEquals(saturated, engine.cachedRegionCount, "稳态淘汰后缓存条目数应稳定")
        assertEquals(extra, engine.rasterCount - rasterBefore, "每次新增只光栅化新条目自身, 不重栅幸存条目")
        assertEquals(engine.cachedRegionCount, engine.residentKeyTotal, "free-list 复用路径 residentKeys 与 cache 一致")
    }

    @Test
    fun `增量淘汰路径下 residentKeys 与 cache 全程一致`() {
        // 统一字形短文本: 淘汰产生的空闲矩形被 free-list 精确复用, 主要走淘汰路径;
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
    }

    @Test
    fun `碎片化路径下 residentKeys 一致且容量失败降级直绘`() {
        // 变长文本制造碎片化; 本引擎不做整页压实(零重栅), 空闲表/shelf 放不下时该条降级
        // 单条直绘(不静默丢弹幕)。每帧后校验 residentKeys 与 cache 一致。
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

        assertTrue(engine.rasterCount <= total, "碎片化下也不重栅幸存条目, 实际 ${engine.rasterCount}/$total")
        assertTrue(engine.cachedRegionCount <= 256, "缓存条目数应受 cacheMax 约束")
        assertTrue(engine.maxHoleCount <= 256, "空闲矩形表必须保持硬上限, 实际 ${engine.maxHoleCount}")
    }

    /** 用 ImageBitmap 承载的 Compose Canvas 在无窗口环境真实走一遍 draw, 返回位图供像素断言。 */
    private fun drawOnce(engine: DesktopAtlasDanmakuEngine, width: Float, height: Float): ImageBitmap {
        val image = ImageBitmap(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1))
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(image),
            Size(image.width.toFloat(), image.height.toFloat()),
        ) {
            engine.draw(this)
        }
        return image
    }

    private fun entry(
        timeSec: Double,
        text: String,
        mode: DanmakuMode = DanmakuMode.TOP,
        color: Int = 0xFFFFFF,
    ) = DanmakuEntry(
        timeSec = timeSec,
        mode = mode,
        color = color,
        text = text,
        source = DanmakuSource.LOCAL,
    )
}
