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
            ?.mapNotNull { episode ->
                val seasonEpisodeNumber = episode.episode
                    ?.takeIf { it > 0.0 && it <= Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }
                    ?.toInt()
                val fallbackSort = episode.sort
                    .takeIf { it > 0.0 && it <= Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }
                    ?.toInt()
                val number = seasonEpisodeNumber ?: fallbackSort ?: return@mapNotNull null
                ScrapedOnlineEpisode(
                    episodeNumber = number,
                    title = episode.title,
                    aired = episode.aired,
                    plot = episode.plot,
                    catalogCoordinates = seasonEpisodeNumber?.let { localNumber ->
                        EpisodeCatalogCoordinates(
                            provider = EpisodeCatalogProvider.BANGUMI,
                            seriesId = subjectId,
                            episodeId = episode.id.takeIf { it > 0L },
                            episodeNumber = localNumber,
                            absoluteEpisodeNumber = fallbackSort,
                            bangumiSubjectId = subjectId,
                        )
                    },
                )
            }
            .orEmpty()
        return subject.toScrapeData(episodes).copy(complete = episodeResult.isSuccess)
    }

    /** TMDB 合并季映射只读证据；保留 Bangumi 全系列 sort、标题与播出日。 */
    internal suspend fun fetchEpisodeEvidence(subjectId: Long): List<BangumiEpisodeEvidence> =
        api.getEpisodes(subjectId).mapNotNull { episode ->
            val sort = episode.sort.toInt()
            if (episode.sort != sort.toDouble()) return@mapNotNull null
            val episodeNumber = episode.episode?.toInt()?.takeIf { episode.episode == it.toDouble() && it > 0 }
            BangumiEpisodeEvidence(sort, episode.title, episode.aired, episodeNumber)
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

internal data class BangumiEpisodeEvidence(
    val sort: Int,
    val title: String?,
    val aired: String?,
    /** 当前 Bangumi subject 内的季内集号；与跨分段连续的 [sort] 分开保存。 */
    val episodeNumber: Int? = null,
)
