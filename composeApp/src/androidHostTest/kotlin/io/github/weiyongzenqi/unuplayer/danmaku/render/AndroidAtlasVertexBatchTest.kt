package io.github.weiyongzenqi.unuplayer.danmaku.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidAtlasVertexBatchTest {
    @Test
    fun `批次覆盖一千三千五千并在峰值后复用数组`() {
        val batch = AndroidAtlasVertexBatch(initialCapacity = 1)
        intArrayOf(1_000, 3_000, 5_000).forEach { count ->
            batch.reset()
            repeat(count) { index ->
                assertTrue(batch.add(index.toFloat(), 0f, 0, 0, 12, 12, 0xFFFFFF))
            }
            assertEquals(count, batch.quadCount)
            assertTrue(batch.capacity >= count)
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
    fun `顶点纹理颜色和索引语义正确`() {
        val batch = AndroidAtlasVertexBatch(maxQuads = 2, initialCapacity = 1)

        assertTrue(batch.add(10f, 20f, 30, 40, 50, 60, 0x12AB34))

        assertEquals(listOf(10f, 20f, 60f, 20f, 60f, 80f, 10f, 80f), batch.positions.take(8))
        assertEquals(listOf(30f, 40f, 80f, 40f, 80f, 100f, 30f, 100f), batch.textureCoordinates.take(8))
        assertEquals(List(4) { 0xFF12AB34.toInt() }, batch.colors.take(4))
        assertEquals(listOf<Short>(0, 1, 2, 0, 2, 3), batch.indices.take(6))
        assertEquals(8, batch.vertexFloatCount)
        assertEquals(6, batch.indexCount)

        assertTrue(batch.add(0f, 0f, 0, 0, 1, 1, 0))
        assertFalse(batch.add(0f, 0f, 0, 0, 1, 1, 0))
        assertEquals(2, batch.capacity)
    }
}
