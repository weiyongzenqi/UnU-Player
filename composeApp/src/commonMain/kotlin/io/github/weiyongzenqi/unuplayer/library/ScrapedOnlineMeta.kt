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

/**
 * 在线季照/海报来源优先级(数值越大越优先): Bangumi > 弹弹 > 其余(TMDB/NFO/AUTO_ATTEMPT 等)。
 *
 * 用户拍板的封面优先级「本地锚点/NFO > Bangumi > 弹弹 > TMDB」中, 本地部分由 UI 回退链保证;
 * 在线 meta 内部按此排名合并: 低优先级自动源不得顶掉高优先级存量海报对(见 upsertOnlineMeta)。
 */
internal fun ScrapeSource.onlinePosterPriority(): Int = when (this) {
    ScrapeSource.BANGUMI, ScrapeSource.MANUAL_BANGUMI -> 3
    ScrapeSource.DANDANPLAY, ScrapeSource.MANUAL_DANDANPLAY -> 2
    ScrapeSource.NFO, ScrapeSource.AUTO_ATTEMPT, ScrapeSource.TMDB, ScrapeSource.MANUAL_TMDB -> 1
}

/** 在线剧集文本的原始来源坐标；与 TMDB 集照坐标分开保存。 */
@Serializable
enum class EpisodeCatalogProvider {
    BANGUMI,
    DANDANPLAY,
}

@Serializable
data class EpisodeCatalogCoordinates(
    val provider: EpisodeCatalogProvider,
    /** provider 内的季/条目身份：Bangumi subject ID 或弹弹 animeId。 */
    val seriesId: Long,
    /** provider 内单集 ID；无法取得时为 null。 */
    val episodeId: Long? = null,
    /** provider 当前季/条目内的集号。 */
    val episodeNumber: Int,
    /** 跨分段连续集号；Bangumi 为 sort，仅作映射证据。 */
    val absoluteEpisodeNumber: Int? = null,
    /** 弹弹条目可携带的 Bangumi 桥接身份。 */
    val bangumiSubjectId: Long? = null,
)

/** TMDB 集照的实际远端坐标。 */
@Serializable
data class TmdbEpisodeCoordinates(
    val seasonNumber: Int,
    val episodeNumber: Int,
)

/**
 * 正漂移(offset > 0)时本地前 offset 集为「被忽略集」(先行篇/第 0 话): Ani-RSS 按 TMDB 坐标
 * 生成的 NFO 文本整体错位一集, 各在线源的话数体系又互不一致, 任何在线纠错都不可靠。
 * 这些集不参与在线文本回填与集照下载判定, UI 一律显示原始文件名(video_name)与同文件名
 * NFO 集照, 显示号按本地集号-offset 落到第 0..offset-1 集, 弹幕匹配强制优先文件哈希。
 */
internal fun isOffsetIgnoredEpisode(bangumiOffset: Long, localEpisodeNumber: Long): Boolean =
    bangumiOffset > 0L && localEpisodeNumber in 1L..bangumiOffset

/**
 * 只有扫描季的精确 Bangumi 身份、非零 offset 与单集来源坐标同时成立时，在线文本才足以
 * 纠正 Ani-RSS/TMDB 按错误坐标生成的 NFO。TMDB/NFO 占位行不具备这项覆盖资格。
 *
 * 旧数据没有 [EpisodeCatalogCoordinates]；仅保留原本已确认的 Bangumi/弹弹整行语义作一次兼容，
 * 一旦 TMDB 曾把整行 source 覆盖便不再猜测，由自动分支重新获取精确坐标。
 */
internal fun isVerifiedShiftedEpisodeText(
    scannedBangumiId: Long?,
    bangumiOffset: Int,
    onlineBangumiId: Long?,
    source: ScrapeSource?,
    episode: ScrapedOnlineEpisode,
    localEpisodeNumber: Long,
): Boolean {
    val subjectId = scannedBangumiId?.takeIf { it > 0L } ?: return false
    if (bangumiOffset == 0 || onlineBangumiId != subjectId || episode.episodeNumber.toLong() != localEpisodeNumber) {
        return false
    }
    val coordinates = episode.catalogCoordinates
    if (coordinates != null) {
        return when (coordinates.provider) {
            EpisodeCatalogProvider.BANGUMI ->
                coordinates.seriesId == subjectId &&
                    coordinates.bangumiSubjectId == subjectId &&
                    coordinates.episodeNumber == localEpisodeNumber.toInt()
            EpisodeCatalogProvider.DANDANPLAY ->
                coordinates.seriesId > 0L &&
                    (coordinates.bangumiSubjectId == null || coordinates.bangumiSubjectId == subjectId)
        }
    }
    return when (source) {
        ScrapeSource.DANDANPLAY,
        ScrapeSource.BANGUMI,
        ScrapeSource.MANUAL_DANDANPLAY,
        ScrapeSource.MANUAL_BANGUMI,
        -> true
        else -> false
    }
}

/** 在线季身份发生明确冲突时，旧 episode_json 属于另一季度，不能与新结果按集号合并。 */
internal fun hasOnlineEpisodeIdentityChanged(
    existingDandanplayId: Long?,
    existingBangumiId: Long?,
    incomingDandanplayId: Long?,
    incomingBangumiId: Long?,
): Boolean =
    (incomingDandanplayId != null && existingDandanplayId != null && incomingDandanplayId != existingDandanplayId) ||
        (incomingBangumiId != null && existingBangumiId != null && incomingBangumiId != existingBangumiId)

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
    /** 标题/放送日/简介来自哪个在线条目与哪一集。 */
    val catalogCoordinates: EpisodeCatalogCoordinates? = null,
    /** null=尚未确认，true=TMDB 有剧照，false=TMDB 已确认无剧照。 */
    val tmdbStillAvailable: Boolean? = null,
    /** 生成 [thumbPath]/[tmdbStillAvailable] 时实际请求的 TMDB 季/集；旧数据为 null。 */
    val tmdbCoordinates: TmdbEpisodeCoordinates? = null,
)

/**
 * 当前 TMDB 坐标是否与已缓存的剧照证据一致。
 *
 * 旧数据没有来源坐标；只有零偏移的同号季可以安全沿用。分段映射必须重新下载一次并写入来源坐标，
 * 防止曾按本地 E01 下载的第一部分图片在映射纠正为远端 E12 后继续被当成有效缓存。
 */
internal fun ScrapedOnlineEpisode.matchesTmdbStillCoordinates(
    mapping: TmdbEpisodeMapping,
): Boolean {
    val remoteEpisode = mapping.remoteEpisodeNumber(episodeNumber.toLong())
        ?.takeIf { it <= Int.MAX_VALUE.toLong() }
        ?.toInt()
        ?: return false
    val coordinates = tmdbCoordinates
    if (coordinates == null) {
        return mapping.episodeOffset == 0
    }
    return coordinates.seasonNumber == mapping.seasonNumber && coordinates.episodeNumber == remoteEpisode
}

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

/** TMDB 独立季集坐标；远端集号按 `localEpisode - episodeOffset` 计算。 */
data class TmdbEpisodeMapping(
    val seasonNumber: Int,
    val episodeOffset: Int,
) {
    fun remoteEpisodeNumber(localEpisodeNumber: Long): Long? {
        val mapped = localEpisodeNumber - episodeOffset.toLong()
        return mapped.takeIf { it > 0L }
    }
}

/**
 * 建立 TMDB 映射时使用的 Bangumi 分段证据。
 *
 * 旧数据库只有 mapping、没有这份来源证据；当当前季度带非零 Ani-RSS offset 时，旧映射必须
 * 重新核验一次，不能仅因本地/TMDB 季号相同就永久复用错误的 S1E1。
 */
@Serializable
data class TmdbEpisodeMappingEvidence(
    val version: Int = 1,
    val bangumiSubjectId: Long,
    val bangumiOffset: Int,
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

internal fun encodeTmdbEpisodeMappingEvidence(evidence: TmdbEpisodeMappingEvidence?): String? =
    evidence?.let { onlineScrapeJson.encodeToString(it) }

internal fun decodeTmdbEpisodeMappingEvidence(value: String?): TmdbEpisodeMappingEvidence? {
    if (value.isNullOrBlank()) return null
    return runCatching { onlineScrapeJson.decodeFromString<TmdbEpisodeMappingEvidence>(value) }
        .getOrNull()
        ?.takeIf { evidence ->
            evidence.version == 1 && evidence.bangumiSubjectId > 0L &&
                evidence.bangumiOffset in -100_000..100_000
        }
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
val ScrapedOnlineMeta.tmdbEpisodeMapping: TmdbEpisodeMapping?
    get() {
        val seasonNumber = tmdb_season_number?.takeIf { it in 0L..999L }?.toInt() ?: return null
        val episodeOffset = tmdb_episode_offset
            ?.takeIf { it in -100_000L..100_000L }
            ?.toInt()
            ?: return null
        return TmdbEpisodeMapping(seasonNumber, episodeOffset)
    }
val ScrapedOnlineMeta.tmdbEpisodeMappingEvidence: TmdbEpisodeMappingEvidence?
    get() = decodeTmdbEpisodeMappingEvidence(tmdb_mapping_evidence)

/** 只向 UI、播放记录和集照缓存暴露与当前扫描季度身份一致的持久映射。 */
internal fun ScrapedOnlineMeta.validatedTmdbEpisodeMapping(
    localSeasonNumber: Int,
    localEpisodeNumbers: List<Int>,
    bangumiId: Long?,
    bangumiOffset: Int,
): TmdbEpisodeMapping? = tmdbEpisodeMapping?.takeIf { mapping ->
    isTmdbEpisodeMappingCompatible(
        mapping = mapping,
        localSeasonNumber = localSeasonNumber,
        localEpisodeNumbers = localEpisodeNumbers,
        bangumiId = bangumiId,
        bangumiOffset = bangumiOffset,
        evidence = tmdbEpisodeMappingEvidence,
    )
}
