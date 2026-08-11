package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.bangumi.BangumiScrapeApi
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiScrapeSubject
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Bangumi 刮削 provider：无 NFO/无 TMDB 身份番剧的首选元数据源。
 *
 * 职责: search(文件名→subject 候选) + fetchDetail(部级元数据 title/原名/简介/评分/标签/制作 +
 * 季照 + 集标题/放送日)。补弹弹没有的 genres(标签)/studios(infobox 制作)/完整简介;
 * 季照用 images.large。bgm 无显式季号, 多季靠日期/标题后缀, 由刮削管线按年份排序 + 用户确认。
 */
class BangumiScrapeProvider(
    private val api: BangumiScrapeApi,
) : ScrapeProvider {
    override val source: ScrapeSource = ScrapeSource.BANGUMI

    override suspend fun search(keyword: String): List<ScrapeCandidate> {
        if (keyword.isBlank()) return emptyList()
        return api.search(keyword).map { it.toCandidate() }
    }

    override suspend fun fetchDetail(candidate: ScrapeCandidate): ScrapedScrapeData {
        val subjectId = candidate.identityId ?: return ScrapedScrapeData()
        val (subject, episodeResult) = coroutineScope {
            val subjectDeferred = async { runSuspendCatching { api.getSubject(subjectId) }.getOrNull() }
            val episodesDeferred = async { runSuspendCatching { api.getEpisodes(subjectId) } }
            subjectDeferred.await() to episodesDeferred.await()
        }
        subject ?: return ScrapedScrapeData(complete = false)
        val episodes = episodeResult.getOrNull()
            ?.mapNotNull { ep -> ScrapedOnlineEpisode(ep.sort.toInt(), ep.title, ep.aired, ep.plot) }
            .orEmpty()
        return subject.toScrapeData(episodes).copy(complete = episodeResult.isSuccess)
    }

    private fun BangumiScrapeSubject.toCandidate(): ScrapeCandidate = ScrapeCandidate(
        source = ScrapeSource.BANGUMI,
        identityId = subjectId,
        title = title,
        originalTitle = originalTitle,
        year = date?.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull(),
        date = date,
        posterUrl = posterUrl,
        episodeCount = episodeCount,
        rating = rating,
        intro = summary,
    )

    private fun BangumiScrapeSubject.toScrapeData(episodes: List<ScrapedOnlineEpisode>): ScrapedScrapeData =
        ScrapedScrapeData(
            title = title,
            originalTitle = originalTitle,
            year = date?.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull(),
            plot = summary,
            rating = rating,
            releaseDate = date,
            genres = tags,
            studios = studios,
            remotePosterUrl = posterUrl,
            episodes = episodes,
        )
}
