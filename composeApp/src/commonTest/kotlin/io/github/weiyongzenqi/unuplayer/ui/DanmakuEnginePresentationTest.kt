package io.github.weiyongzenqi.unuplayer.ui

import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DanmakuEnginePresentationTest {
    @Test
    fun `界面清单完整覆盖五个内核且没有重复`() {
        val types = danmakuEnginePresentations.map { it.type }

        assertEquals(DanmakuEngineType.entries.toSet(), types.toSet())
        assertEquals(types.size, types.distinct().size)
    }

    @Test
    fun `位图和两路 GLES 明确标记实验性`() {
        val experimental = danmakuEnginePresentations
            .filter(DanmakuEnginePresentation::experimental)
            .map(DanmakuEnginePresentation::type)
            .toSet()

        assertEquals(
            setOf(DanmakuEngineType.BITMAP, DanmakuEngineType.GLES, DanmakuEngineType.GLES_HB),
            experimental,
        )
        danmakuEnginePresentations.filter { it.experimental }.forEach { option ->
            assertTrue("实验性" in option.label, "${option.type} 的界面标签缺少实验性标识")
        }
    }
}
