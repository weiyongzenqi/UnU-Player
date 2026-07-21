package io.github.weiyongzenqi.unuplayer.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlCodecTest {

    @Test
    fun `解码 UTF8 百分号编码`() {
        assertEquals("中文/第1集", decodeUrlComponentPreservingPlus("%E4%B8%AD%E6%96%87%2F%E7%AC%AC1%E9%9B%86"))
    }

    @Test
    fun `保留字面量加号`() {
        assertEquals("A+B", decodeUrlComponentPreservingPlus("A+B"))
    }

    @Test
    fun `非法百分号编码原样保留`() {
        assertEquals("bad%2Gvalue", decodeUrlComponentPreservingPlus("bad%2Gvalue"))
    }
}
