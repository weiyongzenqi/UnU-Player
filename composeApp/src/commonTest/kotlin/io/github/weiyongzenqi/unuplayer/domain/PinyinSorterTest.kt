package io.github.weiyongzenqi.unuplayer.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PinyinSorter 纯函数覆盖(C-03 配套): 中英混合/纯中文/空串/非中文字符。
 * 桌面集成测试(DesktopMediaLibraryIntegrationTest)另覆盖 listShows(PINYIN) 端到端序。
 */
class PinyinSorterTest {

    @Test
    fun `纯中文标题转拼音首字母大写`() {
        assertEquals("ZG", PinyinSorter.sortKey("中国"))
        assertEquals("DH", PinyinSorter.sortKey("动画"))
    }

    @Test
    fun `中英混合标题中文取首字母其余转小写`() {
        assertEquals("DHabc12", PinyinSorter.sortKey("动画ABc12"))
    }

    @Test
    fun `纯英文标题整体小写`() {
        assertEquals("abc", PinyinSorter.sortKey("ABC"))
    }

    @Test
    fun `空标题排序键为空串`() {
        assertEquals("", PinyinSorter.sortKey(""))
    }

    @Test
    fun `非中文字符无拼音首字母`() {
        assertNull(PinyinSorter.initial('a'))
        assertNull(PinyinSorter.initial('1'))
        assertNull(PinyinSorter.initial('　'))
        assertEquals('Z', PinyinSorter.initial('中'))
        assertEquals('A', PinyinSorter.initial('啊'))
    }

    @Test
    fun `排序键可按字典序比较中文标题`() {
        // 啊(A) < 中(Z): 首字母序; 同首字母再逐位比(动画 DH < 电脑 DN)
        assertTrue(PinyinSorter.sortKey("啊") < PinyinSorter.sortKey("中国"))
        assertTrue(PinyinSorter.sortKey("动画") < PinyinSorter.sortKey("电脑"))
    }
}
