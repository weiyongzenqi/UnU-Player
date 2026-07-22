package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind

/** tvshow.nfo 解析结果。 */
data class TvShowNfo(
    val tmdbId: Long?,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val plot: String?,
    val rating: Double?,
    val releaseDate: String?,
    val genres: List<String>,
    val studios: List<String>,
)

/** season.nfo 解析结果。 */
data class SeasonNfo(
    val seasonNumber: Int,
    val title: String?,
    val year: Int?,
    val releaseDate: String?,
)

/** 单集 .nfo 解析结果。 */
data class EpisodeNfo(
    val title: String?,
    val plot: String?,
    val rating: Double?,
    val year: Int?,
    val aired: String?,
    val episode: Int?,
    val season: Int?,
    val runtime: Int?,
)

/** bangumi.ini 解析结果([Bangumi] 段)。文件不存在时调用方传 null。 */
data class BangumiIni(
    val id: Long,
    val offset: Int = 0,
)

/** 扫描运行配置(由 SettingsState 映射)。 */
data class ScanConfig(
    val requestIntervalMs: Int,
    val concurrency: Int,
    val depth: Int,
    val timeoutSeconds: Int,
    val directoryQueueCapacity: Int = 64,
    val maxVisitedDirectories: Int = 100_000,
)

/** 扫描结果统计。 */
data class ScanResult(
    val scannedDirs: Int,
    val foundShows: Int,
    val foundEpisodes: Int,
    val skippedShows: Int,
    val errors: Int,
    val timedOut: Boolean,
    val stopped: Boolean,
    val firstErrorMessage: String? = null,
    val peakQueuedDirs: Int = 0,
    val visitedDirs: Int = 0,
    val directoryLimitReached: Boolean = false,
)

/** 剧集文件信息(扫描时与 EpisodeNfo 配对)。 */
data class EpisodeFile(
    val videoPath: String,
    val videoName: String,
    val thumbPath: String?,
    val mediaKey: String?,
    val fileSize: Long,
)

/** 一季的扫描数据(nfo + bangumi可空 + 路径 + 剧集列表)。 */
data class SeasonScanData(
    val nfo: SeasonNfo,
    val bangumi: BangumiIni?,
    val seasonPath: String,
    val seasonPosterPath: String?,
    val episodes: List<Pair<EpisodeNfo, EpisodeFile>>,
)

/**
 * 最近播放番剧(列表用, 含最近播放时间)。
 * 供"最近播放"Tab 跨库混排展示: 按番剧聚合(每个番剧一行), [lastPlayedAt] 倒序。
 * 含隐藏番剧(仅过滤屏蔽); [cacheKey] 与 ScrapedShow.cacheKey 同公式, 供 PosterCard 海报缓存子目录用。
 */
data class RecentShow(
    val id: Long,
    val libraryId: Long,
    val sourceKind: MediaSourceKind,
    val title: String,
    val showPath: String,
    val posterPath: String?,        // show.poster_path
    val cardPosterPath: String?,    // 卡片用季海报或 poster
    val cardSeasonNumber: Long?,    // 单季番的季号(徽章)
    val lastPlayedAt: Long,
    val cacheKey: String,           // 海报缓存子目录(同 ScrapedShow.cacheKey 公式)
)
