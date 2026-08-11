package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.platform.platformFileExists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 在线刮削 meta 领域模型(与 SQLDelight 生成行类型 ScrapedOnlineMeta 区分)。
 *
 * [ScrapedOnlineMetaEntity] 是 scraped.sq 里 `ScrapedOnlineMeta` 表的生成行类型,
 * 字段为 snake_case(library_id/show_path/...); 本文件的类型是**编码/解码辅助**:
 * episode_json(季级集标题/放送日列表)与 genres/studios(逗号分隔)的序列化边界,
 * 全部收敛在这里, 调用方(AnimeScraper/UI)不接触 JSON 细节。
 *
 * 表结构/生命周期见 scraped.sq 中 ScrapedOnlineMeta 的注释块与
 * .claude/plans/online-scraping-2026-08-06.md §5.2。
 */

/** 在线刮削来源。手动文本来源覆盖 NFO 字段；手动 TMDB 只覆盖身份，不阻止其他来源补文本。 */
enum class ScrapeSource(val storageName: String) {
    NFO("NFO"),
    AUTO_ATTEMPT("AUTO_ATTEMPT"),
    DANDANPLAY("DANDANPLAY"),
    BANGUMI("BANGUMI"),
    TMDB("TMDB"),
    MANUAL_DANDANPLAY("MANUAL_DANDANPLAY"),
    MANUAL_BANGUMI("MANUAL_BANGUMI"),
    MANUAL_TMDB("MANUAL_TMDB");

    val isManual: Boolean get() = this == MANUAL_DANDANPLAY || this == MANUAL_BANGUMI
    val isManualIdentity: Boolean get() = isManual || this == MANUAL_TMDB

    companion object {
        /** 从存储值反解; 未知值回落 NFO(auto 只填空语义最保守)。 */
        fun fromStorage(value: String): ScrapeSource =
            entries.firstOrNull { it.storageName == value } ?: NFO
    }
}

/** 单集在线数据(季级 meta 的 episode_json 元素)。 */
@Serializable
data class ScrapedOnlineEpisode(
    val episodeNumber: Int,
    val title: String? = null,
    val aired: String? = null,
    /** 剧集简介(在线源提供时; 弹弹无, Bangumi episodes desc)。回填 ScrapedEpisode.plot。 */
    val plot: String? = null,
    /** TMDB 剧照下载到 PosterCache 的本地绝对路径；UI 作为 NFO thumb 后的在线回退。 */
    val thumbPath: String? = null,
)

/** 弹弹/Bangumi provider 产出的统一刮削数据(provider 层输出, 落库前用)。 */
data class ScrapedScrapeData(
    val title: String? = null,
    val originalTitle: String? = null,
    val year: Int? = null,
    val plot: String? = null,
    val rating: Double? = null,
    val releaseDate: String? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    /** 季级: remote 季照 URL(下载成功前先存 URL, 由下载器回填 localPosterPath)。 */
    val remotePosterUrl: String? = null,
    val localPosterPath: String? = null,
    /** 季级: 集标题/放送日列表。 */
    val episodes: List<ScrapedOnlineEpisode> = emptyList(),
    /** bgm 条目 id(弹弹 bangumiId 桥或 bgm 自身; 部级元数据来源标记)。 */
    val bgmSubjectId: Long? = null,
    val complete: Boolean = true,
)

/** 识别候选(手动匹配弹窗/自动确认用): 一部番的一个候选季。 */
data class ScrapeCandidate(
    val source: ScrapeSource,
    val identityId: Long?,            // dandanplay animeId 或 bgm subjectId
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val date: String? = null,
    val typeDescription: String? = null,
    val posterUrl: String? = null,
    val episodeCount: Int? = null,
    val rating: Double? = null,
    val intro: String? = null,
    val evidence: String? = null,
    /** bgm 条目 id 桥(弹弹 search/anime 自带 bangumiId; bgm 侧与 identityId 相同)。跨源直连 bgm 元数据用。 */
    val bgmSubjectId: Long? = null,
)

/** 待刮番剧(海报墙批量/扫描后自动用, listScrapePending 结果)。 */
data class ScrapePendingShow(
    val libraryId: Long,
    val showPath: String,
    val showId: Long,
    val title: String,
    val tmdbId: Long?,
)

/** 自动 TMDB 搜索真实未命中状态；抑制位只控制详情页自动提示。 */
data class TmdbAutoMatchFailureState(
    val failedAt: Long,
    val promptSuppressed: Boolean,
)

private val onlineScrapeJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** 编码季级集列表 -> episode_json(落库)。 */
fun encodeOnlineEpisodes(episodes: List<ScrapedOnlineEpisode>): String? =
    if (episodes.isEmpty()) null else onlineScrapeJson.encodeToString(episodes)

/** 解码 episode_json -> 集列表(空/畸形返回空列表, 不抛)。 */
fun decodeOnlineEpisodes(episodeJson: String?): List<ScrapedOnlineEpisode> {
    if (episodeJson.isNullOrBlank()) return emptyList()
    return runCatching {
        onlineScrapeJson.decodeFromString<List<ScrapedOnlineEpisode>>(episodeJson)
    }.getOrDefault(emptyList())
}

/** 逗号分隔存储 -> 列表(空串返回空列表)。 */
fun splitCommaSeparated(value: String?): List<String> =
    value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

/** 列表 -> 逗号分隔存储(空列表返回 null)。 */
fun joinCommaSeparated(values: List<String>): String? =
    values.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",").ifBlank { null }

/** 判断明确标记为本地缓存文件的路径是否已经失效。 */
internal suspend fun isMissingLocalFilePath(path: String?): Boolean = withContext(Dispatchers.IO) {
    val value = path?.trim().orEmpty()
    if (value.isEmpty()) return@withContext false
    !platformFileExists(value)
}

internal suspend fun hasInvalidOnlineImageCache(metas: List<ScrapedOnlineMeta>): Boolean {
    for (meta in metas) {
        if (isMissingLocalFilePath(meta.local_poster_path) || isMissingLocalFilePath(meta.local_fanart_path)) {
            return true
        }
        if (meta.decodedEpisodes.any { episode -> isMissingLocalFilePath(episode.thumbPath) }) return true
    }
    return false
}

/** 扩展: 生成行类型 -> 领域便于使用的解码访问器。 */
val ScrapedOnlineMeta.decodedEpisodes: List<ScrapedOnlineEpisode> get() = decodeOnlineEpisodes(episode_json)
val ScrapedOnlineMeta.genreList: List<String> get() = splitCommaSeparated(genres)
val ScrapedOnlineMeta.studioList: List<String> get() = splitCommaSeparated(studios)
val ScrapedOnlineMeta.source: ScrapeSource get() = ScrapeSource.fromStorage(scrape_source)
