package io.github.weiyongzenqi.unuplayer.ui.posterwall

import io.github.weiyongzenqi.unuplayer.library.AnimeScraper
import io.github.weiyongzenqi.unuplayer.library.ScrapeCandidate
import io.github.weiyongzenqi.unuplayer.library.ScrapeSource
import io.github.weiyongzenqi.unuplayer.library.TmdbAutoMatchFailureState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnimeDetailThumbFallbackTest {
    @Test
    fun `需要自动匹配时先等待而不生成集照`() {
        assertEquals(
            EpisodeThumbFallbackDecision.WAIT_FOR_ONLINE_MATCH,
            initialEpisodeThumbFallbackDecision(
                needsOnlineScrape = true,
                canRunAutoScrape = true,
            ),
        )
    }

    @Test
    fun `已有在线身份或本轮命中后不生成本地集照`() {
        assertEquals(
            EpisodeThumbFallbackDecision.SKIP_AFTER_ONLINE_MATCH,
            initialEpisodeThumbFallbackDecision(
                needsOnlineScrape = true,
                canRunAutoScrape = false,
            ),
        )
        assertEquals(
            EpisodeThumbFallbackDecision.GENERATE_IF_ENABLED,
            initialEpisodeThumbFallbackDecision(
                needsOnlineScrape = true,
                canRunAutoScrape = false,
                hasMissingEpisodeThumb = true,
            ),
        )
        assertEquals(
            EpisodeThumbFallbackDecision.SKIP_AFTER_ONLINE_MATCH,
            episodeThumbFallbackDecisionAfter(AnimeScraper.AutoScrapeOutcome.Done(1L, 1)),
        )
        assertEquals(
            EpisodeThumbFallbackDecision.GENERATE_IF_ENABLED,
            episodeThumbFallbackDecisionAfter(
                AnimeScraper.AutoScrapeOutcome.Done(1L, 1),
                hasMissingEpisodeThumb = true,
            ),
        )
    }

    @Test
    fun `未命中或待确认后才允许按设置生成集照`() {
        assertEquals(
            EpisodeThumbFallbackDecision.GENERATE_IF_ENABLED,
            episodeThumbFallbackDecisionAfter(AnimeScraper.AutoScrapeOutcome.NoMatch),
        )
        assertEquals(
            EpisodeThumbFallbackDecision.GENERATE_IF_ENABLED,
            episodeThumbFallbackDecisionAfter(
                AnimeScraper.AutoScrapeOutcome.NeedsConfirmation(
                    listOf(ScrapeCandidate(ScrapeSource.BANGUMI, 1L, "候选")),
                ),
            ),
        )
        assertEquals(
            EpisodeThumbFallbackDecision.GENERATE_IF_ENABLED,
            episodeThumbFallbackDecisionAfter(AnimeScraper.AutoScrapeOutcome.Partial(1L, 1)),
        )
        assertEquals(
            EpisodeThumbFallbackDecision.GENERATE_IF_ENABLED,
            episodeThumbFallbackDecisionAfter(AnimeScraper.AutoScrapeOutcome.RetryableFailure),
        )
    }

    @Test
    fun `其他任务正在匹配时继续等待`() {
        assertEquals(
            EpisodeThumbFallbackDecision.WAIT_FOR_ONLINE_MATCH,
            episodeThumbFallbackDecisionAfter(AnimeScraper.AutoScrapeOutcome.Skipped),
        )
    }

    @Test
    fun `自动任务结束不会清除仍在运行的手动忙碌状态`() {
        assertTrue(isOnlineScrapeBusy(automaticScrapeInProgress = true, manualScrapeInProgress = false))
        assertTrue(isOnlineScrapeBusy(automaticScrapeInProgress = false, manualScrapeInProgress = true))
        assertTrue(isOnlineScrapeBusy(automaticScrapeInProgress = true, manualScrapeInProgress = true))
        assertFalse(isOnlineScrapeBusy(automaticScrapeInProgress = false, manualScrapeInProgress = false))
    }

    @Test
    fun `番剧扫描超过两天才在详情页自动深探测`() {
        val twoDays = 2L * 24L * 60L * 60L * 1000L
        val now = twoDays + 10_000_000L
        assertFalse(shouldAutoRescanShow(now - twoDays + 1L, now))
        assertTrue(shouldAutoRescanShow(now - twoDays, now))
        assertTrue(shouldAutoRescanShow(0L, now))
        assertFalse(shouldAutoRescanShow(now + 1L, now))
    }

    @Test
    fun `NFO与在线集照都为空时详情页需要触发在线补全`() = runBlocking {
        assertTrue(
            hasMissingEpisodeThumbCandidate(
                nfoThumbsByEpisode = mapOf(1L to null),
                onlineThumbsByEpisode = emptyMap(),
            ),
        )
        assertFalse(
            hasMissingEpisodeThumbCandidate(
                nfoThumbsByEpisode = mapOf(1L to "/media/episode-thumb.jpg"),
                onlineThumbsByEpisode = emptyMap(),
            ),
        )
    }

    @Test
    fun `普通未命中不直接推断为TMDB失败`() {
        assertEquals(null, candidateDialogSourceAfter(AnimeScraper.AutoScrapeOutcome.NoMatch))
        assertEquals(
            ScrapeSource.BANGUMI,
            candidateDialogSourceAfter(
                AnimeScraper.AutoScrapeOutcome.NeedsConfirmation(
                    listOf(ScrapeCandidate(ScrapeSource.BANGUMI, 1L, "候选")),
                ),
            ),
        )
    }

    @Test
    fun `TMDB真实未命中仅在未抑制且本会话未处理时自动提示`() {
        val activeFailure = TmdbAutoMatchFailureState(failedAt = 1L, promptSuppressed = false)
        assertTrue(
            shouldOpenTmdbFailurePrompt(activeFailure, null, hasTmdb = true, handledInThisDetailSession = false),
        )
        assertFalse(
            shouldOpenTmdbFailurePrompt(activeFailure, 1L, hasTmdb = true, handledInThisDetailSession = false),
        )
        assertFalse(
            shouldOpenTmdbFailurePrompt(activeFailure, null, hasTmdb = false, handledInThisDetailSession = false),
        )
        assertFalse(
            shouldOpenTmdbFailurePrompt(activeFailure, null, hasTmdb = true, handledInThisDetailSession = true),
        )
        assertFalse(
            shouldOpenTmdbFailurePrompt(
                TmdbAutoMatchFailureState(failedAt = 1L, promptSuppressed = true),
                tmdbId = null,
                hasTmdb = true,
                handledInThisDetailSession = false,
            ),
        )
    }
}
