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
    fun `多季弹窗默认跟随当前季度且不提供整部语义`() {
        assertEquals(2, resolveScrapeDialogInitialSeasonNumber(listOf(1, 2, 3), 2))
        assertEquals(1, resolveScrapeDialogInitialSeasonNumber(listOf(1, 2, 3), null))
        assertEquals(1, resolveScrapeDialogInitialSeasonNumber(listOf(1, 2, 3), 4))
        assertEquals(null, resolveScrapeDialogInitialSeasonNumber(listOf(1), 1))
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

    @Test
    fun `多季应用路径跟随季度所属文件夹且缺失时拒绝回退`() {
        assertEquals(
            "/library/season-two",
            resolveScrapeDialogApplicationShowPath(
                showPath = "/library/season-one",
                seasonShowPaths = mapOf(1 to "/library/season-one", 2 to "/library/season-two"),
                seasonNumber = 2,
            ),
        )
        assertEquals(
            "/library/season-one",
            resolveScrapeDialogApplicationShowPath(
                showPath = "/library/season-one",
                seasonShowPaths = emptyMap(),
                seasonNumber = null,
            ),
        )
        assertEquals(
            null,
            resolveScrapeDialogApplicationShowPath(
                showPath = "/library/season-one",
                seasonShowPaths = emptyMap(),
                seasonNumber = 2,
            ),
        )
    }
}
