package io.github.weiyongzenqi.unuplayer.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 时间格式化统一函数 formatTimeMs 的回归锚(SIMP-03 收编后,
 * 原 PlaybackHistorySlot×2 / FileBrowserContent / formatPlayerTime / formatTime 五处
 * 拷贝已合并到本函数; 本测试钉住边界, 防改显示格式时漂移)。
 */
class TimeFormatUtilsTest {

    @Test
    fun `小于1小时不显示小时位`() {
        assertEquals("00:00", formatTimeMs(0))
        assertEquals("00:00", formatTimeMs(123))          // 亚秒舍去
        assertEquals("00:01", formatTimeMs(1_000))
        assertEquals("01:01", formatTimeMs(61_000))
        assertEquals("59:59", formatTimeMs(3_599_999))
    }

    @Test
    fun `达到1小时显示小时位`() {
        assertEquals("1:00:00", formatTimeMs(3_600_000))
        assertEquals("1:01:01", formatTimeMs(3_661_000))
    }

    @Test
    fun `负数视作0`() {
        // 修复前 android PlayerScreen 私有 formatTime 无 coerceAtLeast(0),
        // ms=-1500 时输出 "00:-1", 与历史记录显示不一致。
        assertEquals("00:00", formatTimeMs(-1_500))
        assertEquals("00:00", formatTimeMs(Long.MIN_VALUE))
    }

    @Test
    fun `小时位可超过1位`() {
        assertEquals("100:00:00", formatTimeMs(360_000_000))
    }
}
