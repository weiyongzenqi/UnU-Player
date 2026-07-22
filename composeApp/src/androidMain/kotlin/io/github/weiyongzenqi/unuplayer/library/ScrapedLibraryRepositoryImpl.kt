package io.github.weiyongzenqi.unuplayer.library

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabaseProvider

/**
 * 刮削库仓库 SQLDelight 实现(androidMain)。
 *
 * 单例: 经 [UnuDatabaseProvider] 取进程级共享 driver(同 PlaybackRecord 同库, 共享 WAL/外键配置)。
 * 所有查询走 IO 调度器, 不阻塞 UI。
 *
 * upsertShow 整番剧事务: 命中现有 show(按 library_id+show_path) 则 updateShow 元数据 + 删子表重插
 * season/episode; 不存在则 insertShow + lastInsertRowId 取 id。子表删除依赖 FK ON DELETE CASCADE
 * (PRAGMA foreign_keys=ON, 见 UnuDatabaseProvider)。
 *
 * 生成类位置: ScrapedQueries/ScrapedShow/... 在本包(io.github.weiyongzenqi.unuplayer.library, 由 .sq 目录决定);
 * UnuDatabase 在 io.github.weiyongzenqi.unuplayer.playback 包(packageName 配置), 故 import UnuDatabaseProvider。
 */
class ScrapedLibraryRepositoryImpl private constructor(
    private val context: Context,
) : ScrapedLibraryRepository {

    private val queries get() = UnuDatabaseProvider.get(context).scrapedQueries

    // === Library 配置 ===

    override suspend fun listLibraries(): List<LibraryConfig> = withContext(Dispatchers.IO) {
        queries.listLibraries().executeAsList().map { it.toConfig() }
    }

    override suspend fun getLibrary(id: Long): LibraryConfig? = withContext(Dispatchers.IO) {
        queries.getLibrary(id).executeAsOneOrNull()?.toConfig()
    }

    override suspend fun addLibrary(
        name: String, sourceKind: MediaSourceKind,
        connectionId: String?, localUri: String?,
        rootPath: String, scanDepth: Int,
        scanMode: ScanMode,
        anchorFilenames: List<String>,
    ): Long = withContext(Dispatchers.IO) {
        queries.transactionWithResult {
            queries.insertLibrary(
                name = name,
                source_kind = sourceKind.name,
                connection_id = connectionId,
                local_uri = localUri,
                root_path = rootPath,
                scan_depth = scanDepth.toLong(),
                scan_mode = scanMode.name,
                anchor_filename = anchorFilenames.takeIf { it.isNotEmpty() }?.joinToString(","),
                created_at = platformTimeMillis(),
            )
            queries.lastInsertRowId().executeAsOne()
        }
    }

    override suspend fun updateLibraryRoot(id: Long, rootPath: String, scanDepth: Int): Unit = withContext(Dispatchers.IO) {
        queries.updateLibraryRootPath(root_path = rootPath, scan_depth = scanDepth.toLong(), id = id)
    }

    override suspend fun updateLibrary(id: Long, name: String, rootPath: String, scanDepth: Int): Unit = withContext(Dispatchers.IO) {
        queries.updateLibraryMeta(name = name, root_path = rootPath, scan_depth = scanDepth.toLong(), id = id)
    }

    override suspend fun deleteLibrary(id: Long): Unit = withContext(Dispatchers.IO) {
        // FK 级联: 删 Library -> Show -> Season -> Episode
        queries.deleteLibrary(id)
    }

    override suspend fun setLibraryScanned(id: Long, timestampMs: Long): Unit = withContext(Dispatchers.IO) {
        queries.updateLibraryLastScanned(last_scanned_at = timestampMs, id = id)
    }

    // === Show 查询 ===

    override suspend fun listShows(libraryId: Long, sortBy: PosterWallSort): List<ListShowsByLibrary> = withContext(Dispatchers.IO) {
        when (sortBy) {
            PosterWallSort.YEAR -> queries.listShowsByLibraryYear(library_id = libraryId).executeAsList().map { it.toListShowsByLibrary() }
            PosterWallSort.RECENT -> queries.listShowsByLibraryRecent(library_id = libraryId).executeAsList().map { it.toListShowsByLibrary() }
            PosterWallSort.QUARTER, PosterWallSort.PINYIN -> queries.listShowsByLibrary(library_id = libraryId).executeAsList()
        }
    }

    override suspend fun listHidden(libraryId: Long): List<ListShowsByLibrary> = withContext(Dispatchers.IO) {
        queries.listHiddenShowsByLibrary(library_id = libraryId).executeAsList().map {
            ListShowsByLibrary(
                it.id, it.library_id, it.source_kind, it.tmdb_id, it.folder_name, it.show_path, it.title, it.original_title,
                it.year, it.plot, it.rating, it.release_date, it.genres, it.studios, it.poster_path, it.fanart_path, it.clearlogo_path,
                it.is_favorite, it.favorited_at, it.favorite_sort_order, it.is_hidden, it.scanned_at, it.min_release_date,
                it.card_poster_path,
                it.card_season_number,
            )
        }
    }

    override suspend fun getShow(showId: Long): ScrapedShow? = withContext(Dispatchers.IO) {
        queries.getShowById(showId).executeAsOneOrNull()
    }

    override suspend fun showExists(libraryId: Long, showPath: String): Boolean = withContext(Dispatchers.IO) {
        queries.getShowByPath(library_id = libraryId, show_path = showPath).executeAsOneOrNull() != null
    }

    override suspend fun listShowPaths(libraryId: Long): List<String> = withContext(Dispatchers.IO) {
        queries.listShowPathsByLibrary(library_id = libraryId).executeAsList()
    }

    override suspend fun searchShows(keyword: String, libraryId: Long?): List<ListShowsByLibrary> = withContext(Dispatchers.IO) {
        // 转义 LIKE 特殊字符(\ % _)再包 %%, ESCAPE '\' 防注入/误匹配
        val escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        queries.listShowsSearch(library_id = libraryId, keyword = "%$escaped%").executeAsList().map { it.toListShowsByLibrary() }
    }

    override suspend fun listRecentlyPlayed(libraryId: Long?, limit: Int): List<RecentShow> = withContext(Dispatchers.IO) {
        queries.listRecentlyPlayedShows(library_id = libraryId, limit = limit.toLong()).executeAsList().map { it.toRecentShow() }
    }

    // === Season/Episode 查询 ===

    override suspend fun listSeasons(showId: Long): List<ScrapedSeason> = withContext(Dispatchers.IO) {
        queries.listSeasonsByShow(show_id = showId).executeAsList()
    }

    override suspend fun listSeasonsByTmdb(libraryId: Long, tmdbId: Long): List<ScrapedSeason> = withContext(Dispatchers.IO) {
        queries.listSeasonsByTmdb(library_id = libraryId, tmdb_id = tmdbId).executeAsList()
    }

    override suspend fun listEpisodes(seasonId: Long): List<ScrapedEpisode> = withContext(Dispatchers.IO) {
        queries.listEpisodesBySeason(season_id = seasonId).executeAsList()
    }

    override suspend fun getEpisodesByMediaKeys(mediaKeys: List<String>): Map<String, ScrapedEpisode> =
        withContext(Dispatchers.IO) {
            // 分块查: SQLite SQLITE_LIMIT_VARIABLE_NUMBER(API26-30=999), 每批 ≤500 合并。
            if (mediaKeys.isEmpty()) emptyMap()
            else mediaKeys.chunked(500).flatMap { batch ->
                queries.getEpisodesByMediaKeys(batch).executeAsList()
            }.filter { !it.media_key.isNullOrEmpty() }.associateBy { it.media_key!! }
        }

    // === 写入(扫描器用, 整番剧事务) ===

    override suspend fun upsertShow(
        libraryId: Long, sourceKind: MediaSourceKind, tmdbId: Long?, folderName: String, showPath: String,
        title: String, originalTitle: String?, year: Int?, plot: String?, rating: Double?, releaseDate: String?,
        genres: List<String>, studios: List<String>,
        posterPath: String?, fanartPath: String?, clearlogoPath: String?, scannedAt: Long,
        seasons: List<SeasonScanData>,
    ): Long = withContext(Dispatchers.IO) {
        val genresStr = genres.joinToString(",")
        val studiosStr = studios.joinToString(",")
        val sk = sourceKind.name
        queries.transactionWithResult {
            val existing = queries.getShowByPath(library_id = libraryId, show_path = showPath).executeAsOneOrNull()
            val showId = if (existing == null) {
                queries.insertShow(
                    library_id = libraryId, source_kind = sk, tmdb_id = tmdbId, folder_name = folderName,
                    show_path = showPath, title = title, original_title = originalTitle,
                    year = year?.toLong(), plot = plot, rating = rating, release_date = releaseDate,
                    genres = genresStr, studios = studiosStr, poster_path = posterPath,
                    fanart_path = fanartPath, clearlogo_path = clearlogoPath, scanned_at = scannedAt,
                )
                queries.lastInsertRowId().executeAsOne()
            } else {
                // 保留 show.id, 刷新元数据 + 删子表重插(season 级联删 episode)
                queries.updateShow(
                    tmdb_id = tmdbId, folder_name = folderName, title = title, original_title = originalTitle,
                    year = year?.toLong(), plot = plot, rating = rating, release_date = releaseDate,
                    genres = genresStr, studios = studiosStr, poster_path = posterPath,
                    fanart_path = fanartPath, clearlogo_path = clearlogoPath, scanned_at = scannedAt,
                    id = existing.id,
                )
                queries.deleteSeasonsByShow(existing.id)
                existing.id
            }
            // 插 seasons + episodes
            for (season in seasons) {
                queries.insertSeason(
                    show_id = showId,
                    season_number = season.nfo.seasonNumber.toLong(),
                    season_path = season.seasonPath,
                    title = season.nfo.title,
                    year = season.nfo.year?.toLong(),
                    release_date = season.nfo.releaseDate,
                    bangumi_id = season.bangumi?.id,
                    bangumi_offset = (season.bangumi?.offset ?: 0).toLong(),
                    season_poster_path = season.seasonPosterPath,
                    episode_count = season.episodes.size.toLong(),
                    scanned_at = scannedAt,
                )
                val seasonId = queries.lastInsertRowId().executeAsOne()
                for ((epNfo, epFile) in season.episodes) {
                    queries.insertEpisode(
                        season_id = seasonId, show_id = showId,
                        episode_number = (epNfo.episode ?: 0).toLong(),
                        title = epNfo.title, plot = epNfo.plot, aired = epNfo.aired,
                        year = epNfo.year?.toLong(), runtime = epNfo.runtime?.toLong(),
                        rating = epNfo.rating,
                        video_path = epFile.videoPath, video_name = epFile.videoName,
                        thumb_path = epFile.thumbPath, media_key = epFile.mediaKey,
                        file_size = epFile.fileSize, scanned_at = scannedAt,
                    )
                }
            }
            showId
        }
    }

    override suspend fun deleteShow(showId: Long): Unit = withContext(Dispatchers.IO) {
        // FK 级联删 season/episode
        queries.deleteShow(showId)
    }

    // === Show 用户状态(收藏/隐藏/屏蔽) ===

    override suspend fun setFavorite(showId: Long, favorite: Boolean): Unit = withContext(Dispatchers.IO) {
        queries.setFavorite(
            is_favorite = if (favorite) 1L else 0L,
            favorited_at = if (favorite) platformTimeMillis() else null,
            id = showId,
        )
    }

    override suspend fun setHidden(showId: Long, hidden: Boolean): Unit = withContext(Dispatchers.IO) {
        queries.setHidden(is_hidden = if (hidden) 1L else 0L, id = showId)
    }

    override suspend fun blockShow(showId: Long): Unit = withContext(Dispatchers.IO) {
        val show = queries.getShowById(showId).executeAsOneOrNull() ?: return@withContext
        queries.insertBlocked(
            library_id = show.library_id,
            show_path = show.show_path,
            title = show.title,
            tmdb_id = show.tmdb_id,
            blocked_at = platformTimeMillis(),
        )
    }

    override suspend fun unblock(blockedId: Long): Unit = withContext(Dispatchers.IO) {
        queries.deleteBlocked(id = blockedId)
    }

    override suspend fun listBlocked(libraryId: Long): List<ScrapedBlocked> = withContext(Dispatchers.IO) {
        queries.listBlockedByLibrary(library_id = libraryId).executeAsList()
    }

    override suspend fun isBlocked(libraryId: Long, showPath: String): Boolean = withContext(Dispatchers.IO) {
        queries.isBlocked(library_id = libraryId, show_path = showPath).executeAsOne()
    }

    override suspend fun deleteShowAndBlock(showId: Long): String? = withContext(Dispatchers.IO) {
        val cacheKey = queries.transactionWithResult {
            val show = queries.getShowById(showId).executeAsOneOrNull() ?: return@transactionWithResult null
            val key = show.cacheKey
            queries.insertBlocked(
                library_id = show.library_id, show_path = show.show_path,
                title = show.title, tmdb_id = show.tmdb_id, blocked_at = platformTimeMillis(),
            )
            queries.deleteShow(showId)  // FK 级联删 season/episode
            key
        }
        // 事务后清该番剧图片缓存(Impl 在 androidMain 可见 PosterCache; UI 层 commonMain 不可见, 故由此清)
        if (cacheKey != null) runSuspendCatching { PosterCache.get(context).clearShow(cacheKey) }
        cacheKey
    }

    override suspend fun clearShowCache(showId: Long): Unit = withContext(Dispatchers.IO) {
        val show = queries.getShowById(showId).executeAsOneOrNull() ?: return@withContext
        val cacheKey = show.cacheKey
        runSuspendCatching { PosterCache.get(context).clearShow(cacheKey) }
    }

    override suspend fun deleteAllScrapedData(): Unit = withContext(Dispatchers.IO) {
        // DELETE FROM ScrapedShow, FK 级联删 season/episode。保留 Library 配置。
        queries.deleteAllScrapedData()
    }

    // === 统计/维护 ===

    override suspend fun countShows(libraryId: Long): Int = withContext(Dispatchers.IO) {
        queries.countShowsByLibrary(library_id = libraryId).executeAsOne().toInt()
    }

    override suspend fun countEpisodes(libraryId: Long): Int = withContext(Dispatchers.IO) {
        queries.countEpisodesByLibrary(library_id = libraryId).executeAsOne().toInt()
    }

    override suspend fun checkpointTruncate() = withContext(Dispatchers.IO) {
        UnuDatabaseProvider.checkpointTruncate()
    }

    // === 内部 ===

    /** 生成的 ScrapedLibrary -> domain LibraryConfig。source_kind 字符串 -> 枚举(异常兜底 WEBDAV)。 */
    private fun ScrapedLibrary.toConfig() = LibraryConfig(
        id = id,
        name = name,
        sourceKind = runCatching { MediaSourceKind.valueOf(source_kind) }.getOrDefault(MediaSourceKind.WEBDAV),
        connectionId = connection_id,
        localUri = local_uri,
        rootPath = root_path,
        scanDepth = scan_depth.toInt(),
        lastScannedAt = last_scanned_at,
        createdAt = created_at,
        scanMode = runCatching { ScanMode.valueOf(scan_mode) }.getOrDefault(ScanMode.NFO),
        anchorFilenames = anchor_filename?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
    )

    // SQLDelight 为每个命名查询生成独立 data class(即使列相同), Year/Recent 查询结果需转回 ListShowsByLibrary
    // 统一返回类型。字段顺序与构造器参数一致(均 SELECT s.* + min_release_date)。
    private fun ListShowsByLibraryYear.toListShowsByLibrary(): ListShowsByLibrary = ListShowsByLibrary(
        id, library_id, source_kind, tmdb_id, folder_name, show_path, title, original_title,
        year, plot, rating, release_date, genres, studios, poster_path, fanart_path, clearlogo_path,
        is_favorite, favorited_at, favorite_sort_order, is_hidden, scanned_at, min_release_date, card_poster_path,
        card_season_number,
    )

    private fun ListShowsByLibraryRecent.toListShowsByLibrary(): ListShowsByLibrary = ListShowsByLibrary(
        id, library_id, source_kind, tmdb_id, folder_name, show_path, title, original_title,
        year, plot, rating, release_date, genres, studios, poster_path, fanart_path, clearlogo_path,
        is_favorite, favorited_at, favorite_sort_order, is_hidden, scanned_at, min_release_date, card_poster_path,
        card_season_number,
    )

    private fun ListShowsSearch.toListShowsByLibrary(): ListShowsByLibrary = ListShowsByLibrary(
        id, library_id, source_kind, tmdb_id, folder_name, show_path, title, original_title,
        year, plot, rating, release_date, genres, studios, poster_path, fanart_path, clearlogo_path,
        is_favorite, favorited_at, favorite_sort_order, is_hidden, scanned_at, min_release_date, card_poster_path,
        card_season_number,
    )

    /** 生成查询结果 -> domain RecentShow。source_kind 字符串 -> 枚举(异常兜底 WEBDAV);
     *  cacheKey 与 ScrapedShow.cacheKey 同公式(sanitizeFileName(title)-tmdb_id?id)。
     *  last_played_at 由 INNER JOIN PlaybackRecord 保证非空, SQLDelight 保守推断聚合为 Long?, ?: 0L 兜底(不会触发)。 */
    private fun ListRecentlyPlayedShows.toRecentShow(): RecentShow = RecentShow(
        id = id,
        libraryId = library_id,
        sourceKind = runCatching { MediaSourceKind.valueOf(source_kind) }.getOrDefault(MediaSourceKind.WEBDAV),
        title = title,
        showPath = show_path,
        posterPath = poster_path,
        cardPosterPath = card_poster_path,
        cardSeasonNumber = card_season_number,
        lastPlayedAt = last_played_at ?: 0L,
        cacheKey = "${sanitizeFileName(title)}-${tmdb_id ?: id}",
    )

    companion object {
        @Volatile private var instance: ScrapedLibraryRepositoryImpl? = null

        /** 进程级单例。首次用 [context] 建库, 后续忽略 context。 */
        fun get(context: Context): ScrapedLibraryRepositoryImpl =
            instance ?: synchronized(this) {
                instance ?: ScrapedLibraryRepositoryImpl(context.applicationContext).also { instance = it }
            }
    }
}
