package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink

/** 扫描模式: NFO(tvshow.nfo 在线刮削) / ANCHOR(本地锚点封面+文件夹名, 不刮削元数据)。 */
enum class ScanMode { NFO, ANCHOR }

/** 刮削库源配置(对应 ScrapedLibrary 表, domain model; 表的 data class 用生成的 ScrapedLibrary)。 */
data class LibraryConfig(
    val id: Long,
    val name: String,
    val sourceKind: MediaSourceKind,
    val connectionId: String?,   // WEBDAV / SMB
    val localUri: String?,       // LOCAL
    val rootPath: String,
    val scanDepth: Int,
    val lastScannedAt: Long?,
    val createdAt: Long,
    val scanMode: ScanMode = ScanMode.NFO,
    val anchorFilenames: List<String> = emptyList(),   // ANCHOR 锚点封面候选(大小写不敏感)
)

/**
 * 刮削库仓库(commonMain 接口, androidMain 用 SQLDelight 实现)。
 * 管理 ScrapedLibrary/Show/Season/Episode 的 CRUD 与查询。
 *
 * 生成类位置: io.github.weiyongzenqi.unuplayer.library 包(由 .sq 文件目录 sqldelight/uno/unu/player/library/ 决定), 编译后存在。
 * UnuDatabase 接口/PlaybackRecord 在 io.github.weiyongzenqi.unuplayer.playback 包(packageName 配置)。
 */
interface ScrapedLibraryRepository {
    // === Library 配置 ===
    suspend fun listLibraries(): List<LibraryConfig>
    suspend fun getLibrary(id: Long): LibraryConfig?
    suspend fun addLibrary(
        name: String, sourceKind: MediaSourceKind,
        connectionId: String?, localUri: String?,
        rootPath: String, scanDepth: Int,
        scanMode: ScanMode = ScanMode.NFO,
        anchorFilenames: List<String> = emptyList(),
    ): Long
    suspend fun updateLibraryRoot(id: Long, rootPath: String, scanDepth: Int)
    /** 编辑库元数据(name + root_path + scan_depth); source_kind/connection/local_uri 不可改。 */
    suspend fun updateLibrary(id: Long, name: String, rootPath: String, scanDepth: Int)
    suspend fun deleteLibrary(id: Long)
    suspend fun setLibraryScanned(id: Long, timestampMs: Long)

    // === Show 查询(返回 SQLDelight 生成的 data class) ===
    // listShows 返回 ListShowsByLibrary(含 min_release_date 最早季首播, 海报墙季度分组/排序用)
    suspend fun listShows(libraryId: Long, sortBy: PosterWallSort = PosterWallSort.QUARTER): List<ListShowsByLibrary>
    /** 隐藏段(顶部下拉显示用): is_hidden=1 且未屏蔽的番剧。 */
    suspend fun listHidden(libraryId: Long): List<ListShowsByLibrary>
    suspend fun getShow(showId: Long): ScrapedShow?
    /** 按路径取番剧(在线刮削定位用)。 */
    suspend fun getShowByPath(libraryId: Long, showPath: String): ScrapedShow?
    suspend fun showExists(libraryId: Long, showPath: String): Boolean
    suspend fun listShowPaths(libraryId: Long): List<String>

    /**
     * 搜索番剧(DB LIKE title)。libraryId=null 全局跨库, 非 null 限当前库。
     * keyword 自动转 %keyword% 并转义 % _ \, ESCAPE '\'。
     */
    suspend fun searchShows(keyword: String, libraryId: Long? = null): List<ListShowsByLibrary>

    /**
     * 最近播放番剧(跨库混排): 按番剧最近播放时间倒序。
     * libraryId=null 全库, 非 null 限单库; 含隐藏, 仅过滤屏蔽。
     * 仅返回有播放记录(PlaybackRecord JOIN ScrapedEpisode.media_key 命中)的番剧。
     */
    suspend fun listRecentlyPlayed(libraryId: Long? = null, limit: Int = 100): List<RecentShow>

    // === Season/Episode 查询 ===
    suspend fun listSeasons(showId: Long): List<ScrapedSeason>
    /** 同 tmdbid 跨文件夹检索所有季(详情页横向季切换用)。tmdb_id=null 不应调用(回落 listSeasons)。 */
    suspend fun listSeasonsByTmdb(libraryId: Long, tmdbId: Long): List<ScrapedSeason>
    suspend fun listEpisodes(seasonId: Long): List<ScrapedEpisode>
    suspend fun getEpisodesByMediaKeys(mediaKeys: List<String>): Map<String, ScrapedEpisode>

    /**
     * 更新某集本地生成集照路径([local_thumb_path])。path=null 清空。
     * 详情页 [EpisodeThumbCoordinator] 抽帧成功后回写, 供下次进详情页直接加载。
     */
    suspend fun updateEpisodeLocalThumb(episodeId: Long, path: String?)

    // === 写入(扫描器用, 整番剧事务 upsert) ===
    // replaceAllSeasons=true 全量替换子表；false 仅替换本次成功扫描到的季，保留读取失败季的旧数据。
    suspend fun upsertShow(
        libraryId: Long, sourceKind: MediaSourceKind, tmdbId: Long?, folderName: String, showPath: String,
        title: String, originalTitle: String?, year: Int?, plot: String?, rating: Double?, releaseDate: String?,
        genres: List<String>, studios: List<String>,
        posterPath: String?, fanartPath: String?, clearlogoPath: String?, scannedAt: Long,
        seasons: List<SeasonScanData>,
        replaceAllSeasons: Boolean = true,
    ): Long

    suspend fun deleteShow(showId: Long)

    // === Show 用户状态(收藏/隐藏/屏蔽) ===
    /** 收藏/取消收藏。favorited_at 自动设为当前时间(收藏)或 null(取消)。 */
    suspend fun setFavorite(showId: Long, favorite: Boolean)
    /** 隐藏/取消隐藏。 */
    suspend fun setHidden(showId: Long, hidden: Boolean)
    /** 屏蔽(软隐藏: 保留 Show 记录, 列表过滤; 设置页可恢复)。 */
    suspend fun blockShow(showId: Long)
    /** 解除屏蔽(设置页恢复用)。 */
    suspend fun unblock(blockedId: Long)
    /** 列某库屏蔽列表(设置页屏蔽管理用)。 */
    suspend fun listBlocked(libraryId: Long): List<ScrapedBlocked>
    /** 查某 show_path 是否屏蔽(scanner 跳过用)。 */
    suspend fun isBlocked(libraryId: Long, showPath: String): Boolean

    // === 本部专属设置覆盖(稀疏 JSON, 按 identity_key 存取; identity 构造见 ShowOverrideIdentity) ===
    /** 读覆盖 JSON; 无记录返回 null。 */
    suspend fun getShowOverrideJson(identityKey: String): String?
    /** 写入/替换覆盖 JSON(INSERT OR REPLACE 幂等)。 */
    suspend fun upsertShowOverride(identityKey: String, overridesJson: String, updatedAt: Long)
    /** 清除本部覆盖(一键恢复全局)。 */
    suspend fun clearShowOverride(identityKey: String)

    // === Bangumi 季度关联(独立于 ScrapedSeason 重扫生命周期) ===
    suspend fun getBangumiSeasonLink(identityKey: String): BangumiSeasonLink?
    suspend fun upsertBangumiSeasonLink(link: BangumiSeasonLink)
    suspend fun clearBangumiSeasonLink(identityKey: String)

    // === 在线刮削 meta(独立于扫描生命周期, 持久 source of truth) ===
    // 弹弹/Bangumi 刮削结果写此表；文本与身份经 [reapplyOnlineMeta] 回填，图片由 UI 直接读取 meta。
    // 扫描器 upsertShow 删季重插后再次重放，防重扫抹掉在线文本与身份。生命周期:
    // 仅删番剧(deleteShowAndBlock)/删库(deleteAllScrapedData)删除; 重扫/清缓存不删。
    suspend fun upsertOnlineMeta(
        libraryId: Long, showPath: String, seasonNumber: Int,
        source: ScrapeSource, overwriteTitle: Boolean,
        dandanplayId: Long?, bangumiId: Long?,
        remotePosterUrl: String?, localPosterPath: String?,
        title: String?, originalTitle: String?, year: Int?, plot: String?, rating: Double?,
        releaseDate: String?, genres: List<String>, studios: List<String>,
        episodes: List<ScrapedOnlineEpisode>, scrapedAt: Long,
    )
    /** TMDB 增强: 部级宽幅头图(远程 URL + 本地绝对路径)。独立于主 upsert, 重刮不覆盖。 */
    suspend fun updateOnlineMetaFanart(
        libraryId: Long, showPath: String, remoteFanartUrl: String?, localFanartPath: String?,
    )
    /** TMDB 增强: 季级剧集剧照(整体替换 episode_json, thumbPath 已含本地绝对路径)。 */
    suspend fun updateOnlineMetaEpisodes(
        libraryId: Long, showPath: String, seasonNumber: Int, episodes: List<ScrapedOnlineEpisode>,
    )
    /** 持久化 TMDB 身份到部级在线 meta 与 ScrapedShow，保证 ANCHOR 重扫后仍可恢复。 */
    suspend fun persistTmdbId(
        libraryId: Long, showPath: String, tmdbId: Long, source: ScrapeSource, scrapedAt: Long,
    )
    /** 已有 NFO TMDB 身份不写在线 meta，仅迁移 show: 季关联到稳定的 tmdb: key。 */
    suspend fun migrateBangumiSeasonLinksToTmdb(libraryId: Long, showPath: String, tmdbId: Long)
    /** 手动整部换源前清除旧 TMDB 在线头图/剧照；ANCHOR 可同时清除在线派生的显示表 TMDB 身份。 */
    suspend fun resetOnlineTmdbEnrichment(libraryId: Long, showPath: String, clearShowTmdbId: Boolean)
    suspend fun getOnlineMeta(libraryId: Long, showPath: String, seasonNumber: Int): ScrapedOnlineMeta?
    suspend fun listOnlineMeta(libraryId: Long, showPath: String): List<ScrapedOnlineMeta>
    /** 记录自动刮削尝试时间；未命中也参与懒触发节流，避免每次进入详情页重复请求。 */
    suspend fun recordAutoScrapeAttempt(libraryId: Long, showPath: String, attemptedAt: Long)
    /** 部分成功或临时失败时解除 24 小时节流，不删除真实在线元数据。 */
    suspend fun markAutoScrapeRetryable(libraryId: Long, showPath: String)
    /** 是否存在内部自动刮削重试标记；业务 meta 列表不会暴露该存储行。 */
    suspend fun hasAutoScrapeRetryMarker(libraryId: Long, showPath: String): Boolean
    /** 部级最近刮削时间(懒触发节流用); 无记录返回 null。 */
    suspend fun lastOnlineScrapeAt(libraryId: Long, showPath: String): Long?
    /** 仅自动 TMDB 搜索成功完成但没有可接受候选时记录；重试失败不改写既有状态。 */
    suspend fun recordTmdbAutoMatchFailure(libraryId: Long, showPath: String, failedAt: Long)
    suspend fun getTmdbAutoMatchFailure(libraryId: Long, showPath: String): TmdbAutoMatchFailureState?
    /** 只关闭该番剧的自动 TMDB 提示，手动搜索保持可用。 */
    suspend fun suppressTmdbAutoMatchPrompt(libraryId: Long, showPath: String)
    suspend fun clearTmdbAutoMatchFailure(libraryId: Long, showPath: String)
    /** 待刮番剧；只有 TMDB 通道可用时才把缺失 tmdb_id 视为待补身份。 */
    suspend fun listScrapePending(
        libraryId: Long?,
        anchorOnly: Boolean,
        requireTmdbIdentity: Boolean = false,
    ): List<ScrapePendingShow>
    suspend fun deleteOnlineMetaByShow(libraryId: Long, showPath: String)
    /** 扫描器 upsertShow 后重放在线文本/身份，并清理旧版图片字段污染(幂等, 可重复调用)。 */
    suspend fun reapplyOnlineMeta(libraryId: Long, showPath: String)

    /**
     * 删记录 + 同步屏蔽(事务): 查 show -> insertBlocked -> deleteShow(级联删 season/episode)。
     * 用于"删除番剧(仅记录/删文件)"流程, 防重扫回来。
     * @return cacheKey(供 UI 清图片缓存) 或 null(show 不存在)
     */
    suspend fun deleteShowAndBlock(showId: Long): String?

    /** 清某番剧图片缓存(单番剧刷新前清, 防集标题变后旧 SxxExx 旧标题.jpg 残留)。 */
    suspend fun clearShowCache(showId: Long)

    /** NFO 已成功重扫后，原子删除在线 meta 与本地抽帧路径，并清理该番剧图片缓存。 */
    suspend fun restoreNfoState(showId: Long)

    suspend fun deleteAllScrapedData()

    // === 统计/维护 ===
    suspend fun countShows(libraryId: Long): Int
    suspend fun countEpisodes(libraryId: Long): Int
    suspend fun checkpointTruncate()
}
