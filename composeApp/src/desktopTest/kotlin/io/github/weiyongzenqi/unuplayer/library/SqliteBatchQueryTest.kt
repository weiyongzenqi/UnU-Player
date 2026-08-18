package io.github.weiyongzenqi.unuplayer.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqliteBatchQueryTest {
    @Test
    fun `IN候选去重后按500项分块并完整合并`() {
        val chunkSizes = mutableListOf<Int>()
        val values = (0..1_200).map { "show-$it" } + listOf("show-0", "show-500")

        val result = queryDistinctInChunks(values) { chunk ->
            chunkSizes += chunk.size
            assertTrue(chunk.size <= SQLITE_SAFE_IN_CHUNK_SIZE)
            chunk
        }

        assertEquals(listOf(500, 500, 201), chunkSizes)
        assertEquals(1_201, result.size)
        assertEquals("show-0", result.first())
        assertEquals("show-1200", result.last())
    }

    @Test
    fun `空候选不执行查询`() {
        var calls = 0
        val result = queryDistinctInChunks<String, String>(emptyList()) {
            calls++
            it
        }

        assertEquals(0, calls)
        assertTrue(result.isEmpty())
    }
}
