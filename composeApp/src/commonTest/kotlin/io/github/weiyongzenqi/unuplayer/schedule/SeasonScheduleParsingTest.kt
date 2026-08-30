package io.github.weiyongzenqi.unuplayer.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 历史季度(/sn 聚合)解析与首播日期推星期的纯函数测试。 */
class SeasonScheduleParsingTest {

    @Test
    fun `iso 日期按锚点推星期`() {
        assertEquals(7, isoDateToScheduleWeekday("2025-07-06")) // 周日(CITY THE ANIMATION 首播日, 与网关实测数据互证)
        assertEquals(1, isoDateToScheduleWeekday("2025-07-07")) // 周一
        assertEquals(2, isoDateToScheduleWeekday("2025-07-08")) // 周二
        assertEquals(4, isoDateToScheduleWeekday("2026-01-01")) // 周四
        assertEquals(4, isoDateToScheduleWeekday("2024-02-29")) // 闰年周四
    }

    @Test
    fun `非法日期不产生星期`() {
        assertNull(isoDateToScheduleWeekday(""))
        assertNull(isoDateToScheduleWeekday("not-a-date"))
        assertNull(isoDateToScheduleWeekday("2025-7-6"))
        assertNull(isoDateToScheduleWeekday("2025-13-01"))
        assertNull(isoDateToScheduleWeekday("2025-00-10"))
        assertNull(isoDateToScheduleWeekday("2025-01-00"))
        assertNull(isoDateToScheduleWeekday("2025-01-32"))
    }

    @Test
    fun `季度起始月约定与归一互补`() {
        assertEquals(setOf(1, 4, 7, 10), QUARTER_START_MONTHS)
        QUARTER_START_MONTHS.forEach { month ->
            assertEquals(month, quarterStartMonth(month))
        }
    }

    @Test
    fun `季度响应解析映射卡片字段并关联观看标记`() {
        val body = """
            {"data":[
              {"id":514358,"name":"CITY THE ANIMATION","name_cn":"小城日常","date":"2025-07-06",
               "platform":"TV","images":{"large":"https://gw.example/i/pic/cover/l/1.jpg","common":"https://gw.example/i/pic/cover/c/1.jpg"},
               "rating":{"score":8.2,"rank":120,"total":50},"collection":{"doing":300,"wish":100},
               "eps":12,"nsfw":false,"type":2,"summary":"网关已裁掉的大字段"},
              {"id":2,"name":"NSFW Entry","name_cn":"","date":"2025-07-01","nsfw":true},
              {"id":3,"name":"No Date","name_cn":"","platform":"TV"},
              {"id":0,"name":"Bad Id","name_cn":"","date":"2025-07-02"},
              {"id":4,"name":"Name Only","name_cn":"","date":"2025-07-08"}
            ],"total":5,"truncated":true}
        """.trimIndent()
        val watches = listOf(
            ScheduleWatch(
                subjectId = 514358L, title = "小城日常", airWeekday = 7,
                animeId = 900L, tmdbId = null, watchedAt = 1L, status = ScheduleStatus.WATCHING,
            ),
        )

        val snapshot = parseSeasonSubjects(body, watches, year = 2025, quarterMonth = 7, refreshedAt = 100L)

        assertEquals(2025, snapshot.year)
        assertEquals(7, snapshot.quarterMonth)
        assertEquals(5, snapshot.total)
        assertTrue(snapshot.truncated)
        // nsfw / 无首播日期 / 非法 id 的条目剔除, 保留 2 条
        assertEquals(listOf(514358L, 4L), snapshot.entries.map { it.subjectId })

        val city = snapshot.entries[0]
        assertEquals("小城日常", city.title)
        assertEquals("CITY THE ANIMATION", city.originalTitle)
        assertEquals(7, city.weekday)
        assertEquals("2025-07-06", city.airDate)
        assertEquals("https://gw.example/i/pic/cover/l/1.jpg", city.posterUrl)
        assertEquals(8.2, city.rating)
        assertEquals(120, city.rank)
        assertEquals(300, city.watchingCount)
        assertEquals(900L, city.animeId)
        assertTrue(city.watched)
        assertEquals(ScheduleStatus.WATCHING, city.status)
        assertNull(city.tmdbId)
        assertNull(city.broadcastTime)

        val nameOnly = snapshot.entries[1]
        assertEquals("Name Only", nameOnly.title)
        assertNull(nameOnly.originalTitle)
        assertNull(nameOnly.rating)
        assertNull(nameOnly.rank)
        assertNull(nameOnly.watchingCount)
        assertEquals(2, nameOnly.weekday)
        assertEquals(ScheduleStatus.NONE, nameOnly.status)
    }

    @Test
    fun `空季度数据产生空快照`() {
        val snapshot = parseSeasonSubjects(
            """{"data":[],"total":0,"truncated":false}""",
            watches = emptyList(),
            year = 2015,
            quarterMonth = 1,
            refreshedAt = 0L,
        )
        assertTrue(snapshot.entries.isEmpty())
        assertEquals(0, snapshot.total)
        assertTrue(!snapshot.truncated)
    }

    @Test
    fun `零值评分排名与在看人数归一为 null`() {
        val snapshot = parseSeasonSubjects(
            """{"data":[{"id":9,"name":"Zero","name_cn":"","date":"2025-10-05",
                "rating":{"score":0.0,"rank":0},"collection":{"doing":0}}],"total":1}""",
            watches = emptyList(),
            year = 2025,
            quarterMonth = 10,
            refreshedAt = 0L,
        )
        val entry = snapshot.entries.single()
        assertNull(entry.rating)
        assertNull(entry.rank)
        assertNull(entry.watchingCount)
    }
}
