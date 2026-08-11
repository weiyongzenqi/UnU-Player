package io.github.weiyongzenqi.unuplayer.ui

import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DanmakuEnginePresentationTest {
    @Test
    fun `界面清单完整覆盖三个内核且没有重复`() {
        val types = danmakuEnginePresentations.map { it.type }

        assertEquals(DanmakuEngineType.entries.toSet(), types.toSet())
        assertEquals(types.size, types.distinct().size)
    }

    @Test
    fun `仅位图内核标记实验性`() {
        val experimental = danmakuEnginePresentations
            .filter(DanmakuEnginePresentation::experimental)
            .map(DanmakuEnginePresentation::type)
            .toSet()

        assertEquals(setOf(DanmakuEngineType.BITMAP), experimental)
        danmakuEnginePresentations.filter { it.experimental }.forEach { option ->
            assertTrue("实验性" in option.label, "${option.type} 的界面标签缺少实验性标识")
        }
    }
}
