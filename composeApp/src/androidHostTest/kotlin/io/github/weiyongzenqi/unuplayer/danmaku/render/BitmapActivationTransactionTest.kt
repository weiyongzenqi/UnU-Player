package io.github.weiyongzenqi.unuplayer.danmaku.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitmapActivationTransactionTest {

    @Test
    fun `轨道饱和时零光栅且仍执行预算整理`() {
        val events = mutableListOf<String>()

        val activated = runDanmakuActivationTransaction(
            findLane = { events += "query"; -1 },
            preparePayload = { events += "raster"; "payload" },
            commit = { _, _ -> events += "occupy" },
            afterAttempt = { events += "trim" },
        )

        assertFalse(activated)
        assertEquals(listOf("query", "trim"), events)
    }

    @Test
    fun `载荷失败不占轨且仍执行预算整理`() {
        val events = mutableListOf<String>()

        val activated = runDanmakuActivationTransaction<String>(
            findLane = { events += "query"; 2 },
            preparePayload = { events += "raster"; null },
            commit = { _, _ -> events += "occupy" },
            afterAttempt = { events += "trim" },
        )

        assertFalse(activated)
        assertEquals(listOf("query", "raster", "trim"), events)
    }

    @Test
    fun `成功激活按查询载荷提交整理顺序执行`() {
        val events = mutableListOf<String>()

        val activated = runDanmakuActivationTransaction(
            findLane = { events += "query"; 1 },
            preparePayload = { events += "raster"; "payload" },
            commit = { lane, payload -> events += "occupy:$lane:$payload" },
            afterAttempt = { events += "trim" },
        )

        assertTrue(activated)
        assertEquals(listOf("query", "raster", "occupy:1:payload", "trim"), events)
    }
}
