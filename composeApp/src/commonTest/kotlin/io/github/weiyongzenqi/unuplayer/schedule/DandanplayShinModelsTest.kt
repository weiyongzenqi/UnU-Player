package io.github.weiyongzenqi.unuplayer.schedule

import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayShinResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DandanplayShinModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `弹弹新番响应兼容已知列表字段并稳定去重`() {
        val response = json.decodeFromString<DandanplayShinResponse>(
            """
            {
              "bangumiList":[{"animeId":123,"bangumiId":"400602","isOnAir":true}],
              "items":[
                {"animeId":123,"bangumiId":"400602","isOnAir":true},
                {"animeId":456,"bangumiId":"500001","isOnAir":false}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf(123L, 456L), response.allItems().map { it.animeId })
    }
}
