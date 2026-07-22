package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind

/** 扫描模式: NFO(tvshow.nfo 在线刮削) / ANCHOR(本地锚点封面+文件夹名, 不刮削元数据)。 */
enum class ScanMode { NFO, ANCHOR }

/** 刮削库源配置(对应 ScrapedLibrary 表, domain model; 表的 data class 用生成的 ScrapedLibrary)。 */
data class LibraryConfig(
    val id: Long,
    val name: String,
    val sourceKind: MediaSourceKind,
    val connectionId: String?,   // WEBDAV
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
    suspend fun showExists(libraryId: Long, showPath: String): Boolean
    suspend fun listShowPaths(libraryId: Long): List<String>

    /**
     * 搜索番剧(DB LIKE title)。libraryId=null 全局跨库, 非 null 限当前库。
     * keyword 自动转 %keyword% 并转义 % _ \, ESCAPE '\'。
     */
    suspend fun searchShows(keyword: String, libraryId: Long? = null): List<ListShowsByLibrary>

    // === Season/Episode 查询 ===
    suspend fun listSeasons(showId: Long): List<ScrapedSeason>
    /** 同 tmdbid 跨文件夹检索所有季(详情页横向季切换用)。tmdb_id=null 不应调用(回落 listSeasons)。 */
    suspend fun listSeasonsByTmdb(libraryId: Long, tmdbId: Long): List<ScrapedSeason>
    suspend fun listEpisodes(seasonId: Long): List<ScrapedEpisode>
    suspend fun getEpisodesByMediaKeys(mediaKeys: List<String>): Map<String, ScrapedEpisode>

    // === 写入(扫描器用, 整番剧事务 upsert: 存在则删子表重插) ===
    suspend fun upsertShow(
        libraryId: Long, sourceKind: MediaSourceKind, tmdbId: Long?, folderName: String, showPath: String,
        title: String, originalTitle: String?, year: Int?, plot: String?, rating: Double?, releaseDate: String?,
        genres: List<String>, studios: List<String>,
        posterPath: String?, fanartPath: String?, clearlogoPath: String?, scannedAt: Long,
        seasons: List<SeasonScanData>,
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

    /**
     * 删记录 + 同步屏蔽(事务): 查 show -> insertBlocked -> deleteShow(级联删 season/episode)。
     * 用于"删除番剧(仅记录/删文件)"流程, 防重扫回来。
     * @return cacheKey(供 UI 清图片缓存) 或 null(show 不存在)
     */
    suspend fun deleteShowAndBlock(showId: Long): String?

    /** 清某番剧图片缓存(单番剧刷新前清, 防集标题变后旧 SxxExx 旧标题.jpg 残留)。 */
    suspend fun clearShowCache(showId: Long)

    suspend fun deleteAllScrapedData()

    // === 统计/维护 ===
    suspend fun countShows(libraryId: Long): Int
    suspend fun countEpisodes(libraryId: Long): Int
    suspend fun checkpointTruncate()
}
