package io.github.weiyongzenqi.unuplayer.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * HTTP Range 响应文件总大小解析的回归锚。核心契约(注释明确禁止的回归):
 * 206 分片响应绝不回退 Content-Length —— 分片长度被当文件总长会喂错弹弹 match。
 */
class HttpUtilsTest {

    // === resolveRangeTotalSize ===

    @Test
    fun `206 带 Content-Range 用 total`() {
        assertEquals(
            754_553_960L,
            resolveRangeTotalSize("bytes 0-16777215/754553960", "16777216"),
        )
    }

    @Test
    fun `206 total 未知星号时返回 null 不回退 Content-Length`() {
        assertNull(resolveRangeTotalSize("bytes 0-16777215/*", "16777216"))
    }

    @Test
    fun `200 完整响应无 Content-Range 才用 Content-Length`() {
        assertEquals(123L, resolveRangeTotalSize(null, "123"))
    }

    @Test
    fun `无 Content-Range 且 Content-Length 缺失或畸形返回 null`() {
        assertNull(resolveRangeTotalSize(null, null))
        assertNull(resolveRangeTotalSize(null, "abc"))
    }

    // === parseContentRangeTotal ===

    @Test
    fun `正常与边界解析`() {
        assertEquals(754_553_960L, parseContentRangeTotal("bytes 0-16777215/754553960"))
        assertEquals(0L, parseContentRangeTotal("bytes 0-0/0"), "空文件 total=0 保留合法")
        assertNull(parseContentRangeTotal(null))
        assertNull(parseContentRangeTotal("bytes 0-99"), "无斜杠判 null")
        assertNull(parseContentRangeTotal("bytes 0-99/"), "斜杠后为空判 null")
        assertNull(parseContentRangeTotal("bytes 0-99/xyz"), "畸形 total 判 null")
    }

    @Test
    fun `负值 total 属畸形判 null`() {
        // 修复前 "bytes 0-10/-5" 会返回 -5 作为文件总长喂给弹弹 match。
        assertNull(parseContentRangeTotal("bytes 0-10/-5"))
    }
}
