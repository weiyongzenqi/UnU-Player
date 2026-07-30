package io.github.weiyongzenqi.unuplayer.domain

import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType
import io.github.weiyongzenqi.unuplayer.danmaku.model.toSupportedDanmakuEngineType
import kotlin.test.Test
import kotlin.test.assertEquals

class DanmakuEngineMappingTest {
    @Test
    fun `正式内核保持映射`() {
        assertEquals(DanmakuEngineType.ATLAS, "ATLAS".toSupportedDanmakuEngineType())
        assertEquals(DanmakuEngineType.BITMAP, "BITMAP".toSupportedDanmakuEngineType())
        assertEquals(DanmakuEngineType.COMPOSE, "COMPOSE".toSupportedDanmakuEngineType())
        assertEquals(DanmakuEngineType.GLES, "GLES".toSupportedDanmakuEngineType())
        assertEquals(DanmakuEngineType.GLES_HB, "GLES_HB".toSupportedDanmakuEngineType())
    }

    @Test
    fun `未知值回落Atlas`() {
        listOf("UNKNOWN", null).forEach { value ->
            assertEquals(DanmakuEngineType.ATLAS, value.toSupportedDanmakuEngineType())
            assertEquals(DanmakuEngineType.ATLAS, SettingsState(danmakuEngine = value ?: "UNKNOWN").toDanmakuConfig().engineType)
        }
    }
}
