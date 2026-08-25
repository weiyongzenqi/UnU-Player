package io.github.weiyongzenqi.unuplayer.ui.posterwall

import io.github.weiyongzenqi.unuplayer.library.AnimeScraper
import io.github.weiyongzenqi.unuplayer.library.ScrapeCandidate
import io.github.weiyongzenqi.unuplayer.library.ScrapeSource
import io.github.weiyongzenqi.unuplayer.library.TmdbAutoMatchFailureState
import io.github.weiyongzenqi.unuplayer.library.ScrapedOnlineEpisode
import io.github.weiyongzenqi.unuplayer.library.TmdbEpisodeMapping
import io.github.weiyongzenqi.unuplayer.library.TmdbEpisodeCoordinates
import io.github.weiyongzenqi.unuplayer.library.isOffsetIgnoredEpisode
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
        assertTrue(
            hasMissingEpisodeThumbCandidate(
                nfoThumbsByEpisode = mapOf(1L to "/media/ani-rss-wrong-e1.jpg"),
                onlineThumbsByEpisode = emptyMap(),
                nfoThumbsTrustworthy = false,
            ),
            "分段映射成立后不能再把 Ani-RSS 按本地同号生成的 NFO 图视为正确集照",
        )
    }

    @Test
    fun `分段映射只显示当前TMDB坐标的在线集照`() {
        val mapping = TmdbEpisodeMapping(seasonNumber = 1, episodeOffset = -11)
        val wrongLegacy = ScrapedOnlineEpisode(
            episodeNumber = 1,
            thumbPath = "/cache/legacy-e1.jpg",
            tmdbStillAvailable = true,
        )
        assertEquals(
            listOf("/cache/local-frame.jpg"),
            episodeImageCandidates(
                nfoThumbPath = "/media/ani-rss-e1.jpg",
                onlineEpisode = wrongLegacy,
                localThumbPath = "/cache/local-frame.jpg",
                tmdbEpisodeMapping = mapping,
            ).map { it.path },
        )

        val corrected = wrongLegacy.copy(
            thumbPath = "/cache/tmdb-e12.jpg",
            tmdbCoordinates = TmdbEpisodeCoordinates(seasonNumber = 1, episodeNumber = 12),
        )
        assertEquals(
            listOf("/cache/tmdb-e12.jpg", "/cache/local-frame.jpg"),
            episodeImageCandidates(
                nfoThumbPath = "/media/ani-rss-e1.jpg",
                onlineEpisode = corrected,
                localThumbPath = "/cache/local-frame.jpg",
                tmdbEpisodeMapping = mapping,
            ).map { it.path },
        )
    }

    @Test
    fun `分段旧映射尚未重新核验时隐藏同号NFO与旧在线集照`() {
        val legacy = ScrapedOnlineEpisode(
            episodeNumber = 1,
            thumbPath = "/cache/legacy-e1.jpg",
            tmdbStillAvailable = true,
        )

        assertEquals(
            listOf("/cache/local-frame.jpg"),
            episodeImageCandidates(
                nfoThumbPath = "/media/wrong-part1-e1.jpg",
                onlineEpisode = legacy,
                localThumbPath = "/cache/local-frame.jpg",
                tmdbEpisodeMapping = null,
                tmdbCoordinatesRequired = true,
            ).map { it.path },
        )
    }

    @Test
    fun `被忽略集只认同文件名NFO集照`() {
        // 正漂移(offset=+1)的 E1 = 先行篇: 在线图按错误 TMDB 坐标生成、抽帧图不进候选,
        // 只保留同文件名 NFO thumb(用户拍板方案: 前置集彻底忽略, 只认本地既有文件)。
        val onlineWithCoordinates = ScrapedOnlineEpisode(
            episodeNumber = 1,
            thumbPath = "/cache/tmdb-e1.jpg",
            tmdbStillAvailable = true,
            tmdbCoordinates = TmdbEpisodeCoordinates(seasonNumber = 2, episodeNumber = 1),
        )
        assertEquals(
            listOf("/media/S02E01-thumb.jpg"),
            episodeImageCandidates(
                nfoThumbPath = "/media/S02E01-thumb.jpg",
                onlineEpisode = onlineWithCoordinates,
                localThumbPath = "/cache/local-frame.jpg",
                tmdbEpisodeMapping = TmdbEpisodeMapping(seasonNumber = 2, episodeOffset = 1),
                ignoredByOffset = true,
            ).map { it.path },
        )
    }

    @Test
    fun `被忽略集无NFO集照时候选为空`() {
        assertEquals(
            emptyList(),
            episodeImageCandidates(
                nfoThumbPath = null,
                onlineEpisode = ScrapedOnlineEpisode(
                    episodeNumber = 1,
                    thumbPath = "/cache/tmdb-e1.jpg",
                    tmdbStillAvailable = true,
                ),
                localThumbPath = "/cache/local-frame.jpg",
                tmdbEpisodeMapping = TmdbEpisodeMapping(seasonNumber = 2, episodeOffset = 1),
                ignoredByOffset = true,
            ),
        )
    }

    @Test
    fun `正漂移前offset集为被忽略集其余不受影响`() {
        assertTrue(isOffsetIgnoredEpisode(bangumiOffset = 1L, localEpisodeNumber = 1L), "offset=+1 的 E1 = 先行篇")
        assertFalse(isOffsetIgnoredEpisode(bangumiOffset = 1L, localEpisodeNumber = 2L), "E2 起为正常集")
        assertTrue(isOffsetIgnoredEpisode(bangumiOffset = 3L, localEpisodeNumber = 3L), "offset=+3 的 E3 仍属被忽略区间")
        assertFalse(isOffsetIgnoredEpisode(bangumiOffset = 3L, localEpisodeNumber = 4L))
        assertFalse(isOffsetIgnoredEpisode(bangumiOffset = 0L, localEpisodeNumber = 1L), "零漂移无被忽略集")
        assertFalse(isOffsetIgnoredEpisode(bangumiOffset = -11L, localEpisodeNumber = 1L), "负漂移(分段后半)不适用")
        assertFalse(isOffsetIgnoredEpisode(bangumiOffset = 1L, localEpisodeNumber = 0L), "第 0 号占位集不适用")
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

    @Test
    fun `TMDB真实未命中提示有24小时冷却`() {
        val cooldown = 24L * 60L * 60L * 1000L
        val now = 10_000_000L
        // 失败在冷却期内: 不提示
        assertFalse(
            shouldOpenTmdbFailurePrompt(
                TmdbAutoMatchFailureState(failedAt = now - cooldown + 1L, promptSuppressed = false),
                tmdbId = null, hasTmdb = true, handledInThisDetailSession = false, now = now,
            ),
        )
        // 恰满 24h: 提示
        assertTrue(
            shouldOpenTmdbFailurePrompt(
                TmdbAutoMatchFailureState(failedAt = now - cooldown, promptSuppressed = false),
                tmdbId = null, hasTmdb = true, handledInThisDetailSession = false, now = now,
            ),
        )
        // 冷却期外 + 永久关闭仍不提示
        assertFalse(
            shouldOpenTmdbFailurePrompt(
                TmdbAutoMatchFailureState(failedAt = now - cooldown - 1L, promptSuppressed = true),
                tmdbId = null, hasTmdb = true, handledInThisDetailSession = false, now = now,
            ),
        )
    }

    @Test
    fun `TMDB刚失败的当场提示一次不受冷却`() {
        val now = 10_000_000L
        // 失败刚写入(1 分钟内): 立即提示, 不受 24h 冷却
        assertTrue(
            shouldOpenTmdbFailurePrompt(
                TmdbAutoMatchFailureState(failedAt = now - 60_000L, promptSuppressed = false),
                tmdbId = null, hasTmdb = true, handledInThisDetailSession = false, now = now,
            ),
        )
        // 刚失败但本会话已处理过: 不重复弹
        assertFalse(
            shouldOpenTmdbFailurePrompt(
                TmdbAutoMatchFailureState(failedAt = now - 60_000L, promptSuppressed = false),
                tmdbId = null, hasTmdb = true, handledInThisDetailSession = true, now = now,
            ),
        )
        // 刚失败但用户已永久关闭: 不弹
        assertFalse(
            shouldOpenTmdbFailurePrompt(
                TmdbAutoMatchFailureState(failedAt = now - 60_000L, promptSuppressed = true),
                tmdbId = null, hasTmdb = true, handledInThisDetailSession = false, now = now,
            ),
        )
    }

    @Test
    fun `刚失败窗口内进程级已提示过则不重复`() {
        val window = 5L * 60L * 1000L
        val now = 10_000_000L
        // 距上次提示 1 分钟(窗口内): 不重复
        assertFalse(shouldRepeatTmdbFailurePrompt(now - 60_000L, now, window))
        // 恰满窗口: 允许再次提示
        assertTrue(shouldRepeatTmdbFailurePrompt(now - window, now, window))
        // 从未提示过(0): 允许
        assertTrue(shouldRepeatTmdbFailurePrompt(0L, now, window))
    }
}
