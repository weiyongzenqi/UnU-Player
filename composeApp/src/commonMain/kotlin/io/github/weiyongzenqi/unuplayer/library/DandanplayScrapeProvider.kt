package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayAnime
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayAnimeSummary
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayApi
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayMatch

/**
 * 弹弹play 刮削 provider：Bangumi 无法唯一确认时负责文件名/hash 精确回退与多季映射。
 *
 * 职责: search(文件名→候选季列表, 含季照/首播/简介/评分) + fetchDetail(季级集标题/放送日/季照)
 * + matchSeason(hash 命中单季身份)。所有请求经现有 DandanplayApi(走自建代理或用户直连凭证)。
 * 搜索失败向上传递，由刮削管线与真实空候选分开处理；详情失败返回 incomplete 数据。
 */
class DandanplayScrapeProvider(
    private val api: DandanplayApi,
) : ScrapeProvider {
    override val source: ScrapeSource = ScrapeSource.DANDANPLAY

    override suspend fun search(keyword: String): List<ScrapeCandidate> {
        if (keyword.isBlank()) return emptyList()
        return api.searchAnime(keyword).animes.map { it.toCandidate() }
    }

    /** tmdbId 快速匹配(最可靠, NFO 库有 tmdb_id 时优先): search/episodes 按 tmdb 反查弹弹季列表。 */
    suspend fun searchByTmdb(tmdbId: Long): List<ScrapeCandidate> {
        if (tmdbId <= 0) return emptyList()
        return api.searchEpisodesByTmdb(tmdbId).animes.map { it.toCandidate() }
    }

    override suspend fun fetchDetail(candidate: ScrapeCandidate): ScrapedScrapeData {
        val animeId = candidate.identityId ?: return ScrapedScrapeData()
        val detail = runSuspendCatching { api.bangumi(animeId) }.getOrNull()?.bangumi
            ?: return ScrapedScrapeData(complete = false)
        // 集列表: episodeNumber 是字符串, 只保留正整数主集(跳过 SP/OAD/特典)
        val episodes = detail.episodes.mapNotNull { ep ->
            val num = ep.episodeNumber?.toIntOrNull()
            if (num == null || num <= 0) null
            else ScrapedOnlineEpisode(num, ep.episodeTitle.takeIf { it.isNotBlank() }, ep.airDate)
        }
        return ScrapedScrapeData(
            title = detail.animeTitle.takeIf { it.isNotBlank() },
            remotePosterUrl = api.resolveResourceUrl(detail.imageUrl),
            episodes = episodes,
            complete = true,
        )
    }

    /** 单文件 hash 命中(每季至多 1 次, 见设计 §3 ②); 未命中返回 null(该季停止 hash 回落文件名)。 */
    suspend fun matchSeason(fileName: String, fileHash: String, fileSize: Long): DandanplayMatch? =
        api.match(fileName, fileHash, fileSize)
            .takeIf { it.isMatched }
            ?.matches
            ?.firstOrNull()

    /** 同作品其他季(relateds, 多季映射/补全用): 各带季照/评分; 不含自身。 */
    suspend fun fetchRelated(animeId: Long): List<ScrapeCandidate> =
        api.bangumi(animeId).bangumi?.relateds
            ?.map { rel ->
                ScrapeCandidate(
                    source = ScrapeSource.DANDANPLAY,
                    identityId = rel.animeId.takeIf { it > 0 },
                    title = rel.animeTitle.takeIf { it.isNotBlank() } ?: "未知季",
                    posterUrl = api.resolveResourceUrl(rel.imageUrl),
                    rating = rel.rating,
                    year = rel.startDate?.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull(),
                    date = rel.startDate,
                )
            }
            ?: emptyList()

    private fun DandanplayAnime.toCandidate(): ScrapeCandidate = ScrapeCandidate(
        source = ScrapeSource.DANDANPLAY,
        identityId = animeId.takeIf { it > 0 },
        title = animeTitle.takeIf { it.isNotBlank() } ?: "未知番剧",
        typeDescription = type,
        episodeCount = episodes.size,
    )

    private fun DandanplayAnimeSummary.toCandidate(): ScrapeCandidate = ScrapeCandidate(
        source = ScrapeSource.DANDANPLAY,
        identityId = animeId.takeIf { it > 0 },
        title = animeTitle.takeIf { it.isNotBlank() } ?: "未知番剧",
        year = startDate?.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull(),
        date = startDate,
        typeDescription = typeDescription,
        posterUrl = api.resolveResourceUrl(imageUrl),
        episodeCount = episodeCount,
        rating = rating,
        intro = intro,
        bgmSubjectId = bangumiId?.toLongOrNull(),
    )
}
