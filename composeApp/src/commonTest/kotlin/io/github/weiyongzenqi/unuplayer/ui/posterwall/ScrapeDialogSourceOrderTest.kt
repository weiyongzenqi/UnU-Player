package io.github.weiyongzenqi.unuplayer.ui.posterwall

import io.github.weiyongzenqi.unuplayer.library.ScrapeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrapeDialogSourceOrderTest {
    @Test
    fun `在线刮削来源固定以Bangumi开头`() {
        assertEquals(ScrapeSource.BANGUMI, defaultScrapeDialogSource)
        assertEquals(
            listOf(ScrapeSource.BANGUMI, ScrapeSource.DANDANPLAY, ScrapeSource.TMDB),
            scrapeDialogSourceOrder(hasTmdb = true),
        )
        assertEquals(
            listOf(ScrapeSource.BANGUMI, ScrapeSource.DANDANPLAY),
            scrapeDialogSourceOrder(hasTmdb = false),
        )
    }

    @Test
    fun `分段弹窗按seasonId跟随当前物理目录而不按重复季号覆盖`() {
        val targets = listOf(
            ScrapeSeasonTarget(10, 1, "/library/part-one", "第1季 · 第1部分"),
            ScrapeSeasonTarget(20, 1, "/library/part-two", "第1季 · 第2部分"),
        )
        assertEquals(20L, resolveScrapeDialogInitialSeasonId(targets, 20))
        assertEquals(10L, resolveScrapeDialogInitialSeasonId(targets, null))
        assertEquals(10L, resolveScrapeDialogInitialSeasonId(targets, 30))
        assertEquals(null, resolveScrapeDialogInitialSeasonId(emptyList(), 10))
    }

    @Test
    fun `TMDB不可用时回退默认Bangumi来源`() {
        assertEquals(
            ScrapeSource.BANGUMI,
            resolveScrapeDialogInitialSource(ScrapeSource.TMDB, hasTmdb = false),
        )
    }

    @Test
    fun `TMDB身份确认后下载期间允许关闭弹窗`() {
        assertTrue(
            isScrapeDialogDismissBlocked(
                anySearching = false,
                updatingPromptPreference = false,
                applying = true,
                tmdbIdentityApplied = false,
            ),
        )
        assertFalse(
            isScrapeDialogDismissBlocked(
                anySearching = true,
                updatingPromptPreference = false,
                applying = true,
                tmdbIdentityApplied = true,
            ),
        )
    }

}
