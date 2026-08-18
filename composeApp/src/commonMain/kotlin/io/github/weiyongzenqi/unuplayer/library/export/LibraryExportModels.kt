package io.github.weiyongzenqi.unuplayer.library.export

import io.github.weiyongzenqi.unuplayer.library.ScrapedOnlineEpisode
import kotlinx.serialization.Serializable

/**
 * 媒体库导出/导入 DTO(纯数据, kotlinx.serialization)。
 *
 * 导出包 zip 结构:
 *   manifest.json         描述文件(小, 导入先解它做预览/格式校验)
 *   data/library.json     实际数据文件(全部结构化数据)
 *   images/...            可选(图片开关=开), 仅本地缓存图
 *
 * 设计见 docs 计划(媒体库导出/导入)。设备特定字段(本地绝对路径)不导出:
 * local_poster_path/local_fanart_path/local_thumb_path 与 episode_json[].thumbPath
 * 一律丢弃, 图片走 images/ 目录; 元数据只保留 remote_* URL 供未带图时重下。
 */

const val LIBRARY_EXPORT_FORMAT_VERSION = 3

/** 描述文件: 表述这份导出有什么(供导入前预览, 不解析大数据文件)。 */
@Serializable
data class LibraryExportManifest(
    val formatVersion: Int = LIBRARY_EXPORT_FORMAT_VERSION,
    val exportedAt: Long,
    val appVersion: String? = null,
    val connection: ManifestConnection,
    val library: ManifestLibrary,
    val content: ManifestContent,
    val dataFiles: ManifestDataFiles = ManifestDataFiles(),
)

@Serializable
data class ManifestConnection(
    val type: String,   // "WEBDAV" / "SMB"
    val name: String,
)

@Serializable
data class ManifestLibrary(
    val name: String,
    val rootPath: String,
    val scanMode: String,
)

@Serializable
data class ManifestContent(
    val shows: Int = 0,
    val episodes: Int = 0,
    val hasImages: Boolean = false,
    val hasPlayback: Boolean = false,
    val hasOverrides: Boolean = false,
    val hasBlocked: Boolean = false,
    val includePassword: Boolean = false,
)

@Serializable
data class ManifestDataFiles(
    val library: String = "data/library.json",
)

/** 实际数据文件根。 */
@Serializable
data class LibraryExportData(
    val connection: ConnectionExport,
    val library: LibraryExport,
    val shows: List<ShowExport>,
    val blocked: List<BlockedExport> = emptyList(),
    val playback: List<PlaybackExport> = emptyList(),
    val episodeProgress: List<EpisodeProgressExport> = emptyList(),
)

/** 连接信息导出。type 区分 WEBDAV/SMB; 未选中字段为 null。 */
@Serializable
data class ConnectionExport(
    val type: String,          // "WEBDAV" / "SMB"
    val name: String,
    // === WEBDAV ===
    val baseUrl: String? = null,
    // === SMB ===
    val host: String? = null,
    val port: Int? = null,
    val share: String? = null,
    val domain: String? = null,
    val requireEncryption: Boolean? = null,
    // === 通用 ===
    val username: String? = null,
    /** 旧 v1 明文密码字段，仅用于拒绝不安全的历史导出包，不再生成。 */
    val password: String? = null,
    /** 迁移口令保护的连接密码，导入时解开后交给 unu-sec:v1: 仓库。 */
    val passwordEnvelope: String? = null,
    val includePassword: Boolean = false,
) {
    override fun toString(): String =
        "ConnectionExport(type=$type, name=$name, baseUrl=$baseUrl, host=$host, port=$port, share=$share, " +
            "domain=$domain, requireEncryption=$requireEncryption, username=$username, password=<redacted>, " +
            "passwordEnvelope=<redacted>, includePassword=$includePassword)"
}

/** 库配置导出(不含 createdAt/sourceKind——导入时重建; 保留旧库 id 供 show 前缀 identity 重映射)。 */
@Serializable
data class LibraryExport(
    val libraryId: Long? = null,
    val name: String,
    val rootPath: String,
    val scanDepth: Int,
    val scanMode: String,
    val anchorFilenames: List<String> = emptyList(),
    val lastScannedAt: Long? = null,
)

/** 番剧导出(含完整季/集层级 + 关联)。 */
@Serializable
data class ShowExport(
    val sourceKind: String = "WEBDAV",
    val tmdbId: Long? = null,
    val folderName: String,
    val showPath: String,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val plot: String? = null,
    val rating: Double? = null,
    val releaseDate: String? = null,
    val genres: String? = null,
    val studios: String? = null,
    val posterPath: String? = null,
    val fanartPath: String? = null,
    val clearlogoPath: String? = null,
    val isFavorite: Long = 0,
    val favoritedAt: Long? = null,
    val favoriteSortOrder: Long = 0,
    val isHidden: Long = 0,
    val scannedAt: Long = 0L,
    /** 导出时的图片缓存目录 key(导入时按此匹配置定位图片还原目标)。 */
    val exportShowCacheKey: String? = null,
    /** 导出时的在线刮削缓存子目录 key(online-scrape/<库id>-<showPath>)。 */
    val exportOnlineCacheKey: String? = null,
    val seasons: List<SeasonExport>,
    val onlineMeta: OnlineMetaExport? = null,
    val bangumiLinks: List<BangumiLinkExport> = emptyList(),
    val overrideJson: String? = null,
    /** 覆盖快照的逻辑更新时间；旧 v2 包缺失时按 0 处理，不覆盖目标端更新值。 */
    val overrideUpdatedAt: Long? = null,
)

@Serializable
data class SeasonExport(
    val seasonNumber: Int,
    val seasonPath: String,
    val title: String? = null,
    val year: Int? = null,
    val releaseDate: String? = null,
    val bangumiId: Long? = null,
    val bangumiOffset: Int = 0,
    val seasonPosterPath: String? = null,
    val episodeCount: Long = 0,
    val episodes: List<EpisodeExport>,
    val onlineMeta: OnlineMetaExport? = null,
)

@Serializable
data class EpisodeExport(
    val episodeNumber: Int,
    val title: String? = null,
    val plot: String? = null,
    val aired: String? = null,
    val year: Int? = null,
    val runtime: Long? = null,
    val rating: Double? = null,
    val videoPath: String,
    val videoName: String,
    val thumbPath: String? = null,
    val mediaKey: String? = null,
    val fileSize: Long? = null,
)

/** 在线刮削 meta 导出(部级/季级)。设备路径置空，TMDB still 可用状态跨设备保留。 */
@Serializable
data class OnlineMetaExport(
    val seasonNumber: Int,
    val scrapeSource: String,
    val overwriteTitle: Boolean = false,
    val tmdbId: Long? = null,
    val dandanplayId: Long? = null,
    val bangumiId: Long? = null,
    val remotePosterUrl: String? = null,
    val posterSource: String? = null,
    val title: String? = null,
    val originalTitle: String? = null,
    val year: Int? = null,
    val plot: String? = null,
    val rating: Double? = null,
    val releaseDate: String? = null,
    val genres: String? = null,
    val studios: String? = null,
    val episodes: List<ScrapedOnlineEpisode> = emptyList(),
    val remoteFanartUrl: String? = null,
    val scrapedAt: Long,
)

@Serializable
data class BangumiLinkExport(
    val identityKey: String,
    val subjectId: Long? = null,
    val state: String,
    val source: String,
    val evidence: String? = null,
    val updatedAt: Long,
    val verifiedAt: Long? = null,
)

@Serializable
data class BlockedExport(
    val showPath: String,
    val title: String? = null,
    val tmdbId: Long? = null,
    val blockedAt: Long,
)

/** 播放记录导出(media_key 为旧连接前缀, 导入时重映射)。 */
@Serializable
data class PlaybackExport(
    val mediaKey: String,
    val sourceKind: String,
    val url: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val watchProgress: Double,
    val isCompleted: Long,
    val tmdbId: Long? = null,
    val seasonNumber: Long? = null,
    val episodeNumber: Long? = null,
    val danmakuEpisodeId: Long? = null,
    val danmakuAnimeId: Long? = null,
    val danmakuAnimeTitle: String? = null,
    val danmakuEpisodeTitle: String? = null,
    val danmakuMatchMethod: String? = null,
    val danmakuSyncVersion: Long = 0,
    val danmakuUpdatedAt: Long = 0,
    val lastPlayedAt: Long,
    val syncStatus: Long = 0,
    val syncVersion: Long = 0,
)

@Serializable
data class EpisodeProgressExport(
    val tmdbId: Long,
    val seasonNumber: Long,
    val episodeNumber: Long,
    val mediaKey: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val watchProgress: Double,
    val isCompleted: Long,
    val lastPlayedAt: Long,
    val syncStatus: Long = 0,
    val syncVersion: Long = 0,
)
