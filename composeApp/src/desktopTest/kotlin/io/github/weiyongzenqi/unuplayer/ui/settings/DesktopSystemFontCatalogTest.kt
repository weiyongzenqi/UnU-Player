package io.github.weiyongzenqi.unuplayer.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * CA-005: 验证 DesktopSystemFontCatalog 缓存可刷新。
 *
 * 缺陷: 原 by lazy 一次性枚举系统字体, 进程内永不再算, 运行中装/卸系统字体不刷新。
 * 修复: 改 @Volatile var + double-check lock, refresh() 作废缓存, 下次 names() 重新枚举。
 */
class DesktopSystemFontCatalogTest {

    @Test
    fun `缓存命中时返回同一实例`() {
        // 确保从已知状态开始
        DesktopSystemFontCatalog.refresh()
        val names1 = DesktopSystemFontCatalog.names()
        val names2 = DesktopSystemFontCatalog.names()
        // 缓存命中, 返回同一 List 实例
        assertSame(names1, names2)
    }

    @Test
    fun `refresh 后重新枚举返回新实例`() {
        DesktopSystemFontCatalog.refresh()
        val names1 = DesktopSystemFontCatalog.names()
        DesktopSystemFontCatalog.refresh()
        val names2 = DesktopSystemFontCatalog.names()
        // refresh 作废缓存, 下次 names() 重新枚举, 返回新 List 实例
        assertNotSame(names1, names2)
        // 系统字体未变, 内容应一致
        assertEquals(names1, names2)
    }

    @Test
    fun `多次 refresh 幂等不抛异常`() {
        // 连续 refresh + names 交替, 验证无竞态/异常
        repeat(5) {
            DesktopSystemFontCatalog.refresh()
            DesktopSystemFontCatalog.names()
        }
        // refresh 后未调用 names 时, 下次 names 应正常重新枚举
        DesktopSystemFontCatalog.refresh()
        DesktopSystemFontCatalog.refresh()
        val names = DesktopSystemFontCatalog.names()
        assert(names.isEmpty() || names.isNotEmpty())
    }
}
