package io.github.weiyongzenqi.unuplayer.library

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkSource
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkState
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonIdentity
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.bangumi.preferredBangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.bangumi.shouldReplaceBangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.domain.PinyinSorter
import io.github.weiyongzenqi.unuplayer.library.export.BangumiLinkExport
import io.github.weiyongzenqi.unuplayer.library.export.BlockedExport
import io.github.weiyongzenqi.unuplayer.library.export.OnlineMetaExport
import io.github.weiyongzenqi.unuplayer.library.export.SeasonExport
import io.github.weiyongzenqi.unuplayer.library.export.ShowExport
import io.github.weiyongzenqi.unuplayer.library.export.toBangumiSeasonLinkOrNull
import io.github.weiyongzenqi.unuplayer.library.export.validatedTmdbEpisodeMapping
import io.github.weiyongzenqi.unuplayer.library.export.validatedTmdbEpisodeMappingEvidence
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideIdentity
import io.github.weiyongzenqi.unuplayer.local.AndroidLocalDirectoryRepository
import io.github.weiyongzenqi.unuplayer.local.AndroidPersistableUriGrantCoordinator
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabaseProvider
import io.github.weiyongzenqi.unuplayer.platform.AndroidStorage
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleLibraryMatch
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleWatch

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
    private val localStorage = AndroidStorage(context)
    private val scheduleWatchInvalidationVersion = MutableStateFlow(0L)

    override suspend fun listScheduleWatches(): List<ScheduleWatch> = queries.scheduleWatches()

    override fun observeScheduleWatches() = combine(
        queries.observeScheduleWatchRows(),
        scheduleWatchInvalidationVersion,
    ) { _, _ -> queries.scheduleWatches() }

    override suspend fun upsertScheduleWatch(watch: ScheduleWatch): Unit = queries.upsertScheduleWatchRow(watch)

    override suspend fun deleteScheduleWatch(subjectId: Long): Unit = queries.deleteScheduleWatchRow(subjectId)

    override suspend fun listScheduleWatchDeletions() = queries.scheduleWatchDeletions()

    override suspend fun invalidateScheduleWatchObservers() {
        scheduleWatchInvalidationVersion.update { version -> if (version == Long.MAX_VALUE) 0L else version + 1L }
    }

    override suspend fun findScheduleLibraryMatches(
        subjectIds: Set<Long>,
        tmdbIds: Set<Long>,
        animeIds: Set<Long>,
    ): List<ScheduleLibraryMatch> = queries.scheduleLibraryMatches(subjectIds, tmdbIds, animeIds)

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
        require(sourceKind != MediaSourceKind.LOCAL || !localUri.isNullOrBlank()) {
            "LOCAL 媒体库必须提供非空 SAF URI"
        }
        val insert: suspend () -> Long = {
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
        if (sourceKind == MediaSourceKind.LOCAL) {
            val uri = requireNotNull(localUri)
            AndroidPersistableUriGrantCoordinator.addReference(
                context = context,
                uri = uri,
                hasAnyReference = { hasAnyReference(uri) },
                mutation = insert,
            )
        } else {
            insert()
        }
    }

    override suspend fun updateLibraryRoot(id: Long, rootPath: String, scanDepth: Int): Unit = withContext(Dispatchers.IO) {
        queries.updateLibraryRootPath(root_path = rootPath, scan_depth = scanDepth.toLong(), id = id)
    }

    override suspend fun updateLibrary(id: Long, name: String, rootPath: String, scanDepth: Int): Unit = withContext(Dispatchers.IO) {
        queries.updateLibraryMeta(name = name, root_path = rootPath, scan_depth = scanDepth.toLong(), id = id)
    }

    override suspend fun deleteLibrary(id: Long): Unit = withContext(Dispatchers.IO) {
        val localUri = queries.getLibrary(id).executeAsOneOrNull()?.local_uri
        val onlineCacheKeys = queries.listShowPathsByLibrary(library_id = id).executeAsList()
            .map { showPath -> onlineScrapeCacheKey(id, showPath) }
        val delete: suspend () -> Unit = {
            queries.transaction {
                queries.deleteOnlineMetaByLibrary(library_id = id)
            // B-4: 覆盖设置与 Bangumi 季关联按 identity_key 前缀键控, 无 FK 级联; 不清理则
            // 删库后遗留孤儿行(AUTOINCREMENT id 不复用, 仅数据垃圾; tmdb: 前缀还可能跨库影响)。
                queries.deleteShowOverrideByLibrary(library_id = id.toString())
                queries.deleteBangumiSeasonLinkByLibrary(library_id = id.toString())
                queries.deleteLibrary(id)
            }
        }
        if (!localUri.isNullOrBlank()) {
            AndroidPersistableUriGrantCoordinator.removeReference(
                context = context,
                uri = localUri,
                hasAnyReference = { hasAnyReference(localUri) },
                mutation = delete,
            )
        } else {
            delete()
        }
        onlineCacheKeys.forEach { key -> runSuspendCatching { PosterCache.get(context).clearShow(key) } }
    }

    private suspend fun hasAnyReference(uri: String): Boolean {
        if (queries.listLibraries().executeAsList().any { it.local_uri == uri }) return true
        return AndroidLocalDirectoryRepository(localStorage, context).loadAll().any { it.uri == uri }
    }

    override suspend fun setLibraryScanned(id: Long, timestampMs: Long): Unit = withContext(Dispatchers.IO) {
        queries.updateLibraryLastScanned(last_scanned_at = timestampMs, id = id)
    }

    // === Show 查询 ===

    override suspend fun listShows(libraryId: Long, sortBy: PosterWallSort): List<ListShowsByLibrary> = withContext(Dispatchers.IO) {
        when (sortBy) {
            PosterWallSort.YEAR -> queries.listShowsByLibraryYear(library_id = libraryId).executeAsList().map { it.toListShowsByLibrary() }
            PosterWallSort.RECENT -> queries.listShowsByLibraryRecent(library_id = libraryId).executeAsList().map { it.toListShowsByLibrary() }
            PosterWallSort.QUARTER -> queries.listShowsByLibrary(library_id = libraryId).executeAsList()
            // C-03: 原实现 PINYIN 直接落季度序 SQL, 用户选拼音无效果。与桌面端逐条对齐:
            // 季度序结果上做内存拼音排序(排序键/稳定性同 desktopMain sortedByPinyin)。
            PosterWallSort.PINYIN -> queries.listShowsByLibrary(library_id = libraryId).executeAsList().sortedByPinyin()
        }
    }

    override suspend fun listVisibleShowTitles(): List<LibraryShowTitle> = queries.visibleShowTitles()

    override suspend fun listHidden(libraryId: Long): List<ListShowsByLibrary> = withContext(Dispatchers.IO) {
        queries.listHiddenShowsByLibrary(library_id = libraryId).executeAsList().map {
            ListShowsByLibrary(
                it.id, it.library_id, it.source_kind, it.tmdb_id, it.folder_name, it.show_path, it.title, it.original_title,
                it.year, it.plot, it.rating, it.release_date, it.genres, it.studios, it.poster_path, it.fanart_path, it.clearlogo_path,
                it.is_favorite, it.favorited_at, it.favorite_sort_order, it.is_hidden, it.scanned_at, it.min_release_date,
                it.card_poster_path,
                it.card_online_poster_path,
                it.card_online_fanart_path,
                it.card_remote_poster_url,
                it.card_remote_poster_season,
                it.card_poster_path_kind,
                it.card_season_number,
            )
        }
    }

    override suspend fun getShow(showId: Long): ScrapedShow? = withContext(Dispatchers.IO) {
        queries.getShowById(showId).executeAsOneOrNull()
    }

    override suspend fun getShowByPath(libraryId: Long, showPath: String): ScrapedShow? = withContext(Dispatchers.IO) {
        queries.getShowByPath(library_id = libraryId, show_path = showPath).executeAsOneOrNull()
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

    override suspend fun updateSeasonBangumiOffset(
        libraryId: Long,
        showPath: String,
        tmdbId: Long?,
        seasonId: Long,
        seasonNumber: Long,
        newOffset: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        queries.transactionWithResult {
            val season = queries.getSeasonById(seasonId).executeAsOneOrNull()
                ?: return@transactionWithResult false
            val oldOffset = season.bangumi_offset
            if (oldOffset == newOffset) return@transactionWithResult true
            val oldKey = BangumiSeasonIdentity.keyFor(tmdbId, libraryId, showPath, seasonNumber, oldOffset)
            val newKey = BangumiSeasonIdentity.keyFor(tmdbId, libraryId, showPath, seasonNumber, newOffset)
            queries.updateSeasonBangumiOffset(bangumi_offset = newOffset, id = seasonId)
            // 旧键关联复制到新键(按统一仲裁口径 禁用>手动>冲突>自动, 同级取新决定保留哪行)。
            // 旧键行保留不删: 重新扫描把漂移改回 ini 值时旧键重新生效, 手动选择不丢;
            // 键不含目录身份, 删行会波及共享同键的其它目录。
            if (oldKey != newKey) {
                val migrated = loadBangumiSeasonLink(oldKey)
                if (migrated != null) {
                    val newExisting = loadBangumiSeasonLink(newKey)
                    if (newExisting == null || shouldReplaceBangumiSeasonLink(newExisting, migrated)) {
                        saveBangumiSeasonLink(migrated.copy(identityKey = newKey))
                    }
                }
            }
            true
        }
    }

    override suspend fun getEpisodesByMediaKeys(mediaKeys: List<String>): Map<String, ScrapedEpisode> =
        withContext(Dispatchers.IO) {
            // 分块查: SQLite SQLITE_LIMIT_VARIABLE_NUMBER(API26-30=999), 每批 ≤500 合并。
            if (mediaKeys.isEmpty()) emptyMap()
            else mediaKeys.chunked(500).flatMap { batch ->
                queries.getEpisodesByMediaKeys(batch).executeAsList()
            }.filter { !it.media_key.isNullOrEmpty() }.associateBy { it.media_key!! }
        }

    override suspend fun updateEpisodeLocalThumb(episodeId: Long, path: String?): Unit = withContext(Dispatchers.IO) {
        queries.updateEpisodeLocalThumb(path = path, id = episodeId)
    }

    // === 写入(扫描器用, 整番剧事务) ===

    override suspend fun upsertShow(
        libraryId: Long, sourceKind: MediaSourceKind, tmdbId: Long?, folderName: String, showPath: String,
        title: String, originalTitle: String?, year: Int?, plot: String?, rating: Double?, releaseDate: String?,
        genres: List<String>, studios: List<String>,
        posterPath: String?, fanartPath: String?, clearlogoPath: String?, scannedAt: Long,
        seasons: List<SeasonScanData>,
        replaceAllSeasons: Boolean,
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
                // 保留 show.id 并刷新元数据。全量扫描成功时删全部子表重插；
                // 部分季读取失败时只替换成功季，失败季旧数据继续保留。
                if (existing.tmdb_id != tmdbId) {
                    queries.clearOnlineMetaTmdbEpisodeMappings(library_id = libraryId, show_path = showPath)
                }
                queries.updateShow(
                    tmdb_id = tmdbId, folder_name = folderName, title = title, original_title = originalTitle,
                    year = year?.toLong(), plot = plot, rating = rating, release_date = releaseDate,
                    genres = genresStr, studios = studiosStr, poster_path = posterPath,
                    fanart_path = fanartPath, clearlogo_path = clearlogoPath, scanned_at = scannedAt,
                    id = existing.id,
                )
                if (replaceAllSeasons) {
                    queries.deleteSeasonsByShow(existing.id)
                } else {
                    seasons.forEach { season ->
                        queries.deleteSeasonByShowAndNumber(
                            show_id = existing.id,
                            season_number = season.nfo.seasonNumber.toLong(),
                        )
                    }
                }
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
                        thumb_path = epFile.thumbPath, local_thumb_path = null,
                        media_key = epFile.mediaKey,
                        file_size = epFile.fileSize, scanned_at = scannedAt,
                    )
                }
            }
            showId
        }
    }

    override suspend fun deleteShow(showId: Long): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            val show = queries.getShowById(showId).executeAsOneOrNull() ?: return@transaction
            queries.deleteOnlineMetaByShow(library_id = show.library_id, show_path = show.show_path)
            queries.deleteShow(showId) // FK 级联删 season/episode
        }
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

    // === 本部专属设置覆盖(稀疏 JSON, 按 identity_key 存取) ===

    override suspend fun getShowOverrideJson(identityKey: String): String? = withContext(Dispatchers.IO) {
        queries.getShowOverrideJson(identity_key = identityKey).executeAsOneOrNull()
    }

    override suspend fun upsertShowOverride(identityKey: String, overridesJson: String, updatedAt: Long): Unit = withContext(Dispatchers.IO) {
        queries.upsertShowOverride(identity_key = identityKey, overrides_json = overridesJson, updated_at = updatedAt)
    }

    override suspend fun clearShowOverride(identityKey: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteShowOverride(identity_key = identityKey)
    }

    override suspend fun getBangumiSeasonLink(identityKey: String): BangumiSeasonLink? = withContext(Dispatchers.IO) {
        loadBangumiSeasonLink(identityKey)
    }

    override suspend fun upsertBangumiSeasonLink(link: BangumiSeasonLink): Unit = withContext(Dispatchers.IO) {
        saveBangumiSeasonLink(link)
    }

    override suspend fun clearBangumiSeasonLink(identityKey: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteBangumiSeasonLink(identity_key = identityKey)
    }

    private fun loadBangumiSeasonLink(identityKey: String): BangumiSeasonLink? =
        queries.getBangumiSeasonLink(identity_key = identityKey).executeAsOneOrNull()?.let { entity ->
            BangumiSeasonLink(
                identityKey = entity.identity_key,
                subjectId = entity.bangumi_subject_id,
                state = runCatching { BangumiLinkState.valueOf(entity.state) }.getOrDefault(BangumiLinkState.CONFLICT),
                source = runCatching { BangumiLinkSource.valueOf(entity.source) }.getOrDefault(BangumiLinkSource.AUTO),
                evidence = entity.evidence,
                updatedAt = entity.updated_at,
                verifiedAt = entity.verified_at,
            )
        }

    private fun saveBangumiSeasonLink(link: BangumiSeasonLink) {
        queries.upsertBangumiSeasonLink(
            identity_key = link.identityKey,
            bangumi_subject_id = link.subjectId,
            state = link.state.name,
            source = link.source.name,
            evidence = link.evidence,
            updated_at = link.updatedAt,
            verified_at = link.verifiedAt,
        )
    }

    // === 在线刮削 meta(独立于扫描生命周期) ===

    override suspend fun upsertOnlineMeta(
        libraryId: Long, showPath: String, seasonNumber: Int,
        source: ScrapeSource, overwriteTitle: Boolean,
        dandanplayId: Long?, bangumiId: Long?,
        remotePosterUrl: String?, localPosterPath: String?,
        title: String?, originalTitle: String?, year: Int?, plot: String?, rating: Double?,
        releaseDate: String?, genres: List<String>, studios: List<String>,
        episodes: List<ScrapedOnlineEpisode>, scrapedAt: Long,
    ): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            val existing = queries.getOnlineMeta(
                library_id = libraryId, show_path = showPath, season_number = seasonNumber.toLong(),
            ).executeAsOneOrNull()
            if (existing?.source?.isManual == true && !source.isManual) return@transaction
            val imageOnlyTmdbUpdate = source == ScrapeSource.TMDB &&
                dandanplayId == null && bangumiId == null && episodes.isEmpty() &&
                title.isNullOrBlank() && originalTitle.isNullOrBlank() && year == null && plot.isNullOrBlank() &&
                rating == null && releaseDate.isNullOrBlank() && genres.isEmpty() && studios.isEmpty()
            val effectiveSource = when {
                existing?.source == ScrapeSource.MANUAL_TMDB && !source.isManualIdentity -> ScrapeSource.MANUAL_TMDB
                imageOnlyTmdbUpdate && existing != null -> existing.source
                else -> source
            }
            val effectiveDandanplayId = if (source.isManual) dandanplayId else dandanplayId ?: existing?.dandanplay_id
            val effectiveBangumiId = if (source.isManual) bangumiId else bangumiId ?: existing?.bangumi_id
            val episodeIdentityChanged = hasOnlineEpisodeIdentityChanged(
                existingDandanplayId = existing?.dandanplay_id,
                existingBangumiId = existing?.bangumi_id,
                incomingDandanplayId = dandanplayId,
                incomingBangumiId = bangumiId,
            )
            val effectiveEpisodes = when {
                source.isManual || episodeIdentityChanged -> episodes
                else -> mergeOnlineEpisodes(existing?.decodedEpisodes.orEmpty(), episodes)
            }
            val effectiveGenres = if (source.isManual) joinCommaSeparated(genres) else joinCommaSeparated(genres) ?: existing?.genres
            val effectiveStudios = if (source.isManual) joinCommaSeparated(studios) else joinCommaSeparated(studios) ?: existing?.studios
            // 海报对(remote_poster_url + local_poster_path 是同一张图的两个表示)成对接受/保留:
            // 存量对存在 = URL/local 任一非空(与注释「URL/local 任一非空即算」一致);
            // 只带单字段的传入(如下载失败只剩 URL)只有在存量对另一字段也空缺时才自洽,
            //   否则会与存量另一字段拼出「新URL+旧本地图」错配对 → 整体拒收保留存量对;
            // 自动源仅当来源优先级更高(Bangumi > 弹弹 > TMDB/NFO, 见 onlinePosterPriority)或存量对为空,
            //   或同级 full pair 补齐存量单腿(失败恢复: 首次下载失败留下的 URL-only 行可被同源重刮补 local)
            //   才接受传入海报; 拒收时 scrape_source 与 poster_source 都保留, 来源标签不与海报真实归属脱钩。
            // poster_source 列独立记录海报对归属: 文本/身份来源(scrape_source)与海报来源解耦,
            //   防止低优先级源借标签降级(MANUAL_TMDB pin / 文本填充)让后续合法优先级比较失效。
            val incomingPosterUrl = remotePosterUrl?.takeIf { it.isNotBlank() }
            val incomingPosterPath = localPosterPath?.takeIf { it.isNotBlank() }
            val existingPosterUrl = existing?.remote_poster_url?.takeIf { it.isNotBlank() }
            val existingPosterPath = existing?.local_poster_path?.takeIf { it.isNotBlank() }
            val hasIncomingPoster = incomingPosterUrl != null || incomingPosterPath != null
            val existingPairPresent = existingPosterUrl != null || existingPosterPath != null
            val existingPairIncomplete = existingPosterUrl == null || existingPosterPath == null
            val incomingFullPair = incomingPosterUrl != null && incomingPosterPath != null
            val incomingCoherent = incomingFullPair ||
                (incomingPosterUrl != null && existingPosterPath == null) ||
                (incomingPosterPath != null && existingPosterUrl == null)
            val existingPosterPriority = existing?.poster_source
                ?.let(ScrapeSource::fromStorage)?.onlinePosterPriority()
                ?: existing?.source?.onlinePosterPriority() ?: 0
            val incomingPosterPriority = source.onlinePosterPriority()
            val acceptIncomingPoster = hasIncomingPoster && incomingCoherent && (
                source.isManual ||
                    !existingPairPresent ||
                    incomingPosterPriority > existingPosterPriority ||
                    (incomingPosterPriority == existingPosterPriority && incomingFullPair && existingPairIncomplete)
                )
            val newPosterUrl = if (acceptIncomingPoster) incomingPosterUrl ?: existingPosterUrl else existingPosterUrl
            val newPosterPath = if (acceptIncomingPoster) incomingPosterPath ?: existingPosterPath else existingPosterPath
            val newPosterSource = when {
                newPosterUrl == null && newPosterPath == null -> null
                acceptIncomingPoster -> source.storageName
                else -> existing?.poster_source ?: existing?.source?.storageName
            }
            queries.upsertOnlineMeta(
                library_id = libraryId, show_path = showPath, season_number = seasonNumber.toLong(),
                scrape_source = effectiveSource.storageName,
                poster_source = newPosterSource,
                overwrite_title = if (overwriteTitle) 1L else existing?.overwrite_title ?: 0L,
                tmdb_id = existing?.tmdb_id,
                dandanplay_id = effectiveDandanplayId,
                bangumi_id = effectiveBangumiId,
                remote_poster_url = newPosterUrl,
                local_poster_path = newPosterPath,
                title = if (source.isManual) title else title ?: existing?.title,
                original_title = if (source.isManual) originalTitle else originalTitle ?: existing?.original_title,
                year = if (source.isManual) year?.toLong() else year?.toLong() ?: existing?.year,
                plot = if (source.isManual) plot else plot ?: existing?.plot,
                rating = if (source.isManual) rating else rating ?: existing?.rating,
                release_date = if (source.isManual) releaseDate else releaseDate ?: existing?.release_date,
                genres = effectiveGenres, studios = effectiveStudios,
                episode_json = encodeOnlineEpisodes(effectiveEpisodes),
                tmdb_season_number = existing?.tmdb_season_number,
                tmdb_episode_offset = existing?.tmdb_episode_offset,
                tmdb_mapping_evidence = existing?.tmdb_mapping_evidence,
                remote_fanart_url = existing?.remote_fanart_url,
                local_fanart_path = existing?.local_fanart_path,
                scraped_at = scrapedAt,
            )
        }
    }

    override suspend fun updateOnlineMetaFanart(
        libraryId: Long, showPath: String, remoteFanartUrl: String?, localFanartPath: String?,
    ): Unit = withContext(Dispatchers.IO) {
        queries.updateOnlineMetaFanart(
            library_id = libraryId, show_path = showPath,
            remote_fanart_url = remoteFanartUrl, local_fanart_path = localFanartPath,
        )
    }

    override suspend fun updateOnlineMetaEpisodes(
        libraryId: Long, showPath: String, seasonNumber: Int, episodes: List<ScrapedOnlineEpisode>,
        scrapedAt: Long?,
    ): Unit = withContext(Dispatchers.IO) {
        val episodeJson = encodeOnlineEpisodes(episodes)
        if (scrapedAt == null) {
            queries.updateOnlineMetaEpisodes(
                library_id = libraryId, show_path = showPath, season_number = seasonNumber.toLong(),
                episode_json = episodeJson,
            )
        } else {
            queries.updateOnlineMetaEpisodesAndScrapedAt(
                library_id = libraryId, show_path = showPath, season_number = seasonNumber.toLong(),
                episode_json = episodeJson, scraped_at = scrapedAt,
            )
        }
    }

    override suspend fun updateOnlineMetaTmdbEpisodeMapping(
        libraryId: Long,
        showPath: String,
        seasonNumber: Int,
        mapping: TmdbEpisodeMapping?,
        evidence: TmdbEpisodeMappingEvidence?,
    ): Unit = withContext(Dispatchers.IO) {
        queries.updateOnlineMetaTmdbEpisodeMapping(
            library_id = libraryId,
            show_path = showPath,
            season_number = seasonNumber.toLong(),
            tmdb_season_number = mapping?.seasonNumber?.toLong(),
            tmdb_episode_offset = mapping?.episodeOffset?.toLong(),
            tmdb_mapping_evidence = encodeTmdbEpisodeMappingEvidence(evidence.takeIf { mapping != null }),
        )
    }

    override suspend fun mergeOnlineMetaEpisodeThumbs(
        libraryId: Long,
        showPath: String,
        seasonNumber: Int,
        thumbPaths: Map<Int, String>,
    ): Set<Int> = withContext(Dispatchers.IO) {
        if (thumbPaths.isEmpty()) return@withContext emptySet()
        queries.transactionWithResult {
            val meta = queries.getOnlineMeta(
                library_id = libraryId,
                show_path = showPath,
                season_number = seasonNumber.toLong(),
            ).executeAsOneOrNull() ?: return@transactionWithResult emptySet()
            val episodes = meta.decodedEpisodes
            val applied = episodes.mapNotNullTo(linkedSetOf()) { episode ->
                episode.episodeNumber.takeIf(thumbPaths::containsKey)
            }
            if (applied.isEmpty()) return@transactionWithResult emptySet()
            val updated = episodes.map { episode ->
                thumbPaths[episode.episodeNumber]?.let { path ->
                    episode.copy(thumbPath = path, tmdbStillAvailable = true)
                } ?: episode
            }
            queries.updateOnlineMetaEpisodes(
                library_id = libraryId,
                show_path = showPath,
                season_number = seasonNumber.toLong(),
                episode_json = encodeOnlineEpisodes(updated),
            )
            applied
        }
    }

    override suspend fun updateOnlineMetaLocalPoster(
        libraryId: Long, showPath: String, seasonNumber: Int, localPosterPath: String?,
    ): Unit = withContext(Dispatchers.IO) {
        queries.updateOnlineMetaLocalPoster(
            library_id = libraryId, show_path = showPath,
            season_number = seasonNumber.toLong(), local_poster_path = localPosterPath,
        )
    }

    override suspend fun persistTmdbId(
        libraryId: Long, showPath: String, tmdbId: Long, source: ScrapeSource, scrapedAt: Long,
    ): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            queries.insertOnlineMetaTmdbId(
                library_id = libraryId,
                show_path = showPath,
                scrape_source = source.storageName,
                tmdb_id = tmdbId,
                scraped_at = scrapedAt,
            )
            queries.updateOnlineMetaTmdbId(library_id = libraryId, show_path = showPath, tmdb_id = tmdbId)
            queries.markOnlineMetaTmdbSource(
                library_id = libraryId,
                show_path = showPath,
                scrape_source = source.storageName,
            )
            queries.updateShowTmdbId(library_id = libraryId, show_path = showPath, tmdb_id = tmdbId)
            queries.deleteTmdbAutoMatchFailure(library_id = libraryId, show_path = showPath)
            migrateBangumiSeasonLinksToTmdbInTransaction(libraryId, showPath, tmdbId)
            migrateShowOverrideToTmdbInTransaction(libraryId, showPath, tmdbId)
        }
    }

    /**
     * 在线刮削识别出 tmdb_id 后, 把 ANCHOR 时期存于 "show:<libraryId>:<showPath>" 键的本部覆盖设置
     * 迁移到 "tmdb:<tmdbId>" 键, 否则详情页/播放器换键后旧设置变孤儿丢失(与 Bangumi 关联迁移同理)。
     * 冲突保护: 目标 tmdb: 键已存在且不旧于来源时保留目标(只清来源孤儿键), 避免用旧 ANCHOR 设置
     * 覆盖另一库已保存的更新设置。
     */
    private fun migrateShowOverrideToTmdbInTransaction(libraryId: Long, showPath: String, tmdbId: Long) {
        val legacyKey = ShowOverrideIdentity.anchor(libraryId, showPath)
        val legacy = queries.getShowOverrideRow(identity_key = legacyKey).executeAsOneOrNull() ?: return
        val tmdbKey = ShowOverrideIdentity.tmdb(tmdbId)
        val existing = queries.getShowOverrideRow(identity_key = tmdbKey).executeAsOneOrNull()
        if (existing == null || existing.updated_at < legacy.updated_at) {
            queries.upsertShowOverride(
                identity_key = tmdbKey,
                overrides_json = legacy.overrides_json,
                updated_at = legacy.updated_at,
            )
        }
        queries.deleteShowOverride(identity_key = legacyKey)
    }

    override suspend fun migrateBangumiSeasonLinksToTmdb(
        libraryId: Long,
        showPath: String,
        tmdbId: Long,
    ): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            migrateBangumiSeasonLinksToTmdbInTransaction(libraryId, showPath, tmdbId)
        }
    }

    override suspend fun resetOnlineTmdbEnrichment(
        libraryId: Long,
        showPath: String,
        clearShowTmdbId: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            val metas = queries.listOnlineMetaByShow(library_id = libraryId, show_path = showPath).executeAsList()
            queries.clearOnlineMetaTmdbEnrichment(library_id = libraryId, show_path = showPath)
            queries.clearOnlineMetaTmdbEpisodeMappings(library_id = libraryId, show_path = showPath)
            metas.asSequence()
                .filter { it.season_number > 0L }
                .forEach { meta ->
                    val episodes = meta.decodedEpisodes
                    val cleared = episodes.map { episode ->
                        episode.copy(thumbPath = null, tmdbStillAvailable = null)
                    }
                    if (cleared != episodes) {
                        queries.updateOnlineMetaEpisodes(
                            library_id = libraryId,
                            show_path = showPath,
                            season_number = meta.season_number,
                            episode_json = encodeOnlineEpisodes(cleared),
                        )
                    }
                }
            if (clearShowTmdbId) {
                queries.clearShowTmdbId(library_id = libraryId, show_path = showPath)
            }
            queries.deleteTmdbAutoMatchFailure(library_id = libraryId, show_path = showPath)
        }
    }

    private fun migrateBangumiSeasonLinksToTmdbInTransaction(
        libraryId: Long,
        showPath: String,
        tmdbId: Long,
    ) {
        val show = queries.getShowByPath(library_id = libraryId, show_path = showPath).executeAsOneOrNull()
            ?: return
        for (season in queries.listSeasonsByShow(show_id = show.id).executeAsList()) {
            val legacyKey = BangumiSeasonIdentity.keyFor(
                tmdbId = null,
                libraryId = libraryId,
                showPath = showPath,
                seasonNumber = season.season_number,
            )
            val legacy = loadBangumiSeasonLink(legacyKey) ?: continue
            val tmdbKey = BangumiSeasonIdentity.keyFor(
                tmdbId = tmdbId,
                libraryId = libraryId,
                showPath = showPath,
                seasonNumber = season.season_number,
                bangumiOffset = season.bangumi_offset,
            )
            val preferred = preferredBangumiSeasonLink(loadBangumiSeasonLink(tmdbKey), legacy) ?: continue
            saveBangumiSeasonLink(preferred.copy(identityKey = tmdbKey))
            queries.deleteBangumiSeasonLink(identity_key = legacyKey)
        }
    }

    override suspend fun getOnlineMeta(libraryId: Long, showPath: String, seasonNumber: Int): ScrapedOnlineMeta? =
        withContext(Dispatchers.IO) {
            queries.getOnlineMeta(library_id = libraryId, show_path = showPath, season_number = seasonNumber.toLong())
                .executeAsOneOrNull()
        }

    override suspend fun listOnlineMeta(libraryId: Long, showPath: String): List<ScrapedOnlineMeta> =
        withContext(Dispatchers.IO) {
            queries.listOnlineMetaByShow(library_id = libraryId, show_path = showPath).executeAsList()
        }

    override suspend fun recordAutoScrapeAttempt(
        libraryId: Long,
        showPath: String,
        attemptedAt: Long,
    ): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            queries.deleteAutoScrapeRetryMarker(library_id = libraryId, show_path = showPath)
            queries.insertAutoScrapeAttempt(
                library_id = libraryId,
                show_path = showPath,
                attempted_at = attemptedAt,
            )
            queries.updateAutoScrapeAttemptAt(
                library_id = libraryId,
                show_path = showPath,
                attempted_at = attemptedAt,
            )
        }
    }

    override suspend fun markAutoScrapeRetryable(libraryId: Long, showPath: String): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            queries.deleteAutoScrapeAttempt(library_id = libraryId, show_path = showPath)
            queries.upsertAutoScrapeRetryMarker(
                library_id = libraryId,
                show_path = showPath,
                attempted_at = platformTimeMillis(),
            )
        }
    }

    override suspend fun hasAutoScrapeRetryMarker(libraryId: Long, showPath: String): Boolean =
        withContext(Dispatchers.IO) {
            queries.hasAutoScrapeRetryMarker(library_id = libraryId, show_path = showPath).executeAsOne()
        }

    override suspend fun autoScrapeRetryMarkedAt(libraryId: Long, showPath: String): Long? =
        withContext(Dispatchers.IO) {
            queries.autoScrapeRetryMarkedAt(library_id = libraryId, show_path = showPath).executeAsOneOrNull()
        }

    override suspend fun isAutoScrapeSuppressed(libraryId: Long, showPath: String): Boolean =
        withContext(Dispatchers.IO) {
            queries.isAutoScrapeSuppressed(library_id = libraryId, show_path = showPath).executeAsOne()
        }

    override suspend fun suppressAutoScrape(libraryId: Long, showPath: String, suppressedAt: Long): Unit =
        withContext(Dispatchers.IO) {
            queries.suppressAutoScrape(
                library_id = libraryId,
                show_path = showPath,
                suppressed_at = suppressedAt,
            )
        }

    override suspend fun unsuppressAutoScrape(libraryId: Long, showPath: String): Unit =
        withContext(Dispatchers.IO) {
            queries.unsuppressAutoScrape(library_id = libraryId, show_path = showPath)
        }

    override suspend fun lastOnlineScrapeAt(libraryId: Long, showPath: String): Long? = withContext(Dispatchers.IO) {
        queries.lastOnlineMetaAt(library_id = libraryId, show_path = showPath).executeAsOneOrNull()
    }

    override suspend fun recordTmdbAutoMatchFailure(
        libraryId: Long,
        showPath: String,
        failedAt: Long,
    ): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            queries.insertTmdbAutoMatchFailure(library_id = libraryId, show_path = showPath, failed_at = failedAt)
            queries.updateTmdbAutoMatchFailureAt(library_id = libraryId, show_path = showPath, failed_at = failedAt)
        }
    }

    override suspend fun getTmdbAutoMatchFailure(
        libraryId: Long,
        showPath: String,
    ): TmdbAutoMatchFailureState? = withContext(Dispatchers.IO) {
        queries.getTmdbAutoMatchFailure(library_id = libraryId, show_path = showPath)
            .executeAsOneOrNull()
            ?.let { TmdbAutoMatchFailureState(it.failed_at, it.prompt_suppressed != 0L) }
    }

    override suspend fun suppressTmdbAutoMatchPrompt(libraryId: Long, showPath: String): Unit =
        withContext(Dispatchers.IO) {
            queries.suppressTmdbAutoMatchPrompt(library_id = libraryId, show_path = showPath)
        }

    override suspend fun clearTmdbAutoMatchFailure(libraryId: Long, showPath: String): Unit =
        withContext(Dispatchers.IO) {
            queries.deleteTmdbAutoMatchFailure(library_id = libraryId, show_path = showPath)
        }

    override suspend fun listScrapePending(
        libraryId: Long?,
        anchorOnly: Boolean,
        requireTmdbIdentity: Boolean,
        cooldownMs: Long,
        nowMs: Long,
    ): List<ScrapePendingShow> =
        withContext(Dispatchers.IO) {
            val rows = queries.listScrapePending(
                library_id = libraryId,
                anchor_only = if (anchorOnly) 1L else 0L,
                require_tmdb_identity = if (requireTmdbIdentity) 1L else 0L,
                cooldown_ms = cooldownMs,
                now_ms = nowMs,
            ).executeAsList()
            if (rows.isEmpty()) return@withContext emptyList()
            // 一次批量取出候选番剧全部在线 meta(避免逐候选 listOnlineMetaByShow 的 N+1),
            // 按 (library_id, show_path) 分组供文件失效复核。
            val metasByShow = queryDistinctInChunks(rows.map { it.show_path }) { showPaths ->
                queries.listOnlineMetaByShowPaths(
                    library_id = libraryId,
                    show_paths = showPaths,
                ).executeAsList()
            }.groupBy { it.library_id to it.show_path }
            val result = mutableListOf<ScrapePendingShow>()
            for (row in rows) {
                val metas = metasByShow[row.library_id to row.show_path].orEmpty()
                val hasInvalidLocalCache = row.has_database_gap == 0L &&
                    hasInvalidOnlineImageCache(metas)
                if (row.has_database_gap != 0L || hasInvalidLocalCache) {
                    result += ScrapePendingShow(row.library_id, row.show_path, row.show_id, row.title, row.tmdb_id)
                }
            }
            result
        }

    override suspend fun deleteOnlineMetaByShow(libraryId: Long, showPath: String): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            queries.deleteOnlineMetaByShow(library_id = libraryId, show_path = showPath)
            queries.deleteTmdbAutoMatchFailure(library_id = libraryId, show_path = showPath)
        }
    }

    override suspend fun reapplyOnlineMeta(libraryId: Long, showPath: String): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            val metas = queries.listOnlineMetaByShow(library_id = libraryId, show_path = showPath).executeAsList()
            if (metas.isEmpty()) return@transaction
            val show = queries.getShowByPath(library_id = libraryId, show_path = showPath).executeAsOneOrNull()
                ?: return@transaction
            for (meta in metas) {
                val manual = meta.source.isManual
                if (meta.season_number == 0L) {
                    reapplyShowMeta(show, meta, manual)
                } else {
                    reapplySeasonMeta(show.id, meta, manual)
                }
            }
        }
    }

    private fun reapplyShowMeta(show: ScrapedShow, meta: ScrapedOnlineMeta, manual: Boolean) {
        // title: 仅 overwrite_title(ANCHOR 占位/手动)或当前为空时改, 否则保留(不覆盖 nfo 真标题)
        val newTitle = when {
            meta.title.isNullOrBlank() -> show.title
            meta.overwrite_title != 0L || show.title.isNullOrBlank() -> meta.title
            else -> show.title
        }
        val newOriginal = if (manual || show.original_title.isNullOrBlank()) meta.original_title ?: show.original_title else show.original_title
        val newYear = if (manual || show.year == null) meta.year ?: show.year else show.year
        val newPlot = if (manual || show.plot.isNullOrBlank()) meta.plot ?: show.plot else show.plot
        val newRating = if (manual || show.rating == null) meta.rating ?: show.rating else show.rating
        val newRelease = if (manual || show.release_date.isNullOrBlank()) meta.release_date ?: show.release_date else show.release_date
        val newGenres = if (manual || show.genres.isNullOrBlank()) meta.genres ?: show.genres else show.genres
        val newStudios = if (manual || show.studios.isNullOrBlank()) meta.studios ?: show.studios else show.studios
        val newTmdbId = if (meta.source.isManualIdentity && meta.tmdb_id != null) {
            meta.tmdb_id
        } else {
            show.tmdb_id ?: meta.tmdb_id
        }
        // 兼容旧库: 旧实现把在线缓存绝对路径写进 fanart_path。仅清理与当前 meta 完全相同的污染值；
        // NFO/媒体源 fanart 始终留在扫描字段，在线头图由 UI 从 meta 独立回退。
        val newFanart = show.fanart_path.takeUnless {
            !meta.local_fanart_path.isNullOrBlank() && it == meta.local_fanart_path
        }
        queries.updateShowOnlineMeta(
            tmdb_id = newTmdbId, title = newTitle, original_title = newOriginal, year = newYear, plot = newPlot,
            rating = newRating, release_date = newRelease, genres = newGenres, studios = newStudios,
            fanart_path = newFanart, id = show.id,
        )
    }

    private fun reapplySeasonMeta(showId: Long, meta: ScrapedOnlineMeta, manual: Boolean) {
        val season = queries.getSeason(show_id = showId, season_number = meta.season_number).executeAsOneOrNull()
            ?: return
        // 兼容旧库: 清除旧实现写入 season_poster_path 的在线缓存路径；NFO 季照保留，在线季照由 UI 回退。
        if (!meta.local_poster_path.isNullOrBlank() && season.season_poster_path == meta.local_poster_path) {
            queries.updateSeasonPoster(season_poster_path = null, id = season.id)
        }
        // 集标题/放送日/简介: 按集号定位(UNIQUE(season_id, episode_number) 保证一一对应)
        for (ep in meta.decodedEpisodes) {
            val row = queries.getEpisodeBySeasonAndNumber(
                season_id = season.id, episode_number = ep.episodeNumber.toLong(),
            ).executeAsOneOrNull() ?: continue
            // 被忽略集(正漂移前 offset 集 = 先行篇): NFO 与在线文本都按错误坐标生成,
            // 任何回填都只会写入错位文本; UI 显示原始文件名, 这里整体跳过保持静默。
            if (isOffsetIgnoredEpisode(season.bangumi_offset, row.episode_number)) continue
            val canCorrectEpisodeText = manual || isVerifiedShiftedEpisodeText(
                scannedBangumiId = season.bangumi_id,
                bangumiOffset = season.bangumi_offset.toInt(),
                onlineBangumiId = meta.bangumi_id,
                source = meta.source,
                episode = ep,
                localEpisodeNumber = row.episode_number,
            )
            val newTitle = if (canCorrectEpisodeText || row.title.isNullOrBlank()) ep.title ?: row.title else row.title
            val newAired = if (canCorrectEpisodeText || row.aired.isNullOrBlank()) ep.aired ?: row.aired else row.aired
            val newPlot = if (canCorrectEpisodeText || row.plot.isNullOrBlank()) ep.plot ?: row.plot else row.plot
            // 兼容旧库: 在线剧照不再写入本地抽帧字段；NFO thumb_path 与本地生成 local_thumb_path 各自保留。
            val newThumb = row.local_thumb_path.takeUnless {
                !ep.thumbPath.isNullOrBlank() && it == ep.thumbPath
            }
            if (newTitle != row.title || newAired != row.aired || newPlot != row.plot || newThumb != row.local_thumb_path) {
                queries.updateEpisodeOnlineMeta(
                    title = newTitle, aired = newAired, plot = newPlot, local_thumb_path = newThumb, id = row.id,
                )
            }
        }
    }

    private fun mergeOnlineEpisodes(
        existing: List<ScrapedOnlineEpisode>,
        incoming: List<ScrapedOnlineEpisode>,
    ): List<ScrapedOnlineEpisode> {
        if (incoming.isEmpty()) return existing
        val existingByNumber = existing.associateBy { it.episodeNumber }
        return buildMap {
            existing.forEach { put(it.episodeNumber, it) }
            incoming.forEach { episode ->
                val previous = existingByNumber[episode.episodeNumber]
                val incomingCoordinates = episode.tmdbCoordinates
                val previousCoordinates = previous?.tmdbCoordinates
                val mayReusePreviousStill = incomingCoordinates == null || incomingCoordinates == previousCoordinates
                put(
                    episode.episodeNumber,
                    episode.copy(
                        // 显式确认无剧照(false)时清掉旧 thumbPath, 防 merge 残留死路径
                        thumbPath = if (episode.tmdbStillAvailable == false) {
                            null
                        } else {
                            episode.thumbPath ?: previous?.thumbPath?.takeIf { mayReusePreviousStill }
                        },
                        tmdbStillAvailable = episode.tmdbStillAvailable
                            ?: previous?.tmdbStillAvailable?.takeIf { mayReusePreviousStill },
                        catalogCoordinates = episode.catalogCoordinates ?: previous?.catalogCoordinates,
                        tmdbCoordinates = episode.tmdbCoordinates
                            ?: previous?.tmdbCoordinates?.takeIf { mayReusePreviousStill },
                    ),
                )
            }
        }.values.sortedBy { it.episodeNumber }
    }

    override suspend fun deleteShowAndBlock(showId: Long): String? = withContext(Dispatchers.IO) {
        val cacheKeys = queries.transactionWithResult {
            val show = queries.getShowById(showId).executeAsOneOrNull() ?: return@transactionWithResult null
            val keys = show.cacheKey to onlineScrapeCacheKey(show.library_id, show.show_path)
            queries.insertBlocked(
                library_id = show.library_id, show_path = show.show_path,
                title = show.title, tmdb_id = show.tmdb_id, blocked_at = platformTimeMillis(),
            )
            queries.deleteOnlineMetaByShow(library_id = show.library_id, show_path = show.show_path)  // 在线 meta 随番剧删除
            queries.deleteShow(showId)  // FK 级联删 season/episode
            keys
        }
        // 事务后清该番剧图片缓存(Impl 在 androidMain 可见 PosterCache; UI 层 commonMain 不可见, 故由此清)
        cacheKeys?.let { (cacheKey, onlineCacheKey) ->
            runSuspendCatching { PosterCache.get(context).clearShow(cacheKey) }
            runSuspendCatching { PosterCache.get(context).clearShow(onlineCacheKey) }
        }
        cacheKeys?.first
    }

    override suspend fun clearShowCache(showId: Long): Unit = withContext(Dispatchers.IO) {
        val show = queries.getShowById(showId).executeAsOneOrNull() ?: return@withContext
        queries.transaction {
            val metas = queries.listOnlineMetaByShow(
                library_id = show.library_id,
                show_path = show.show_path,
            ).executeAsList()
            queries.clearOnlineMetaImageCache(library_id = show.library_id, show_path = show.show_path)
            metas.asSequence()
                .filter { it.season_number > 0L }
                .forEach { meta ->
                    val episodes = meta.decodedEpisodes
                    val cleared = episodes.map { episode -> episode.copy(thumbPath = null) }
                    if (cleared != episodes) {
                        queries.updateOnlineMetaEpisodes(
                            library_id = show.library_id,
                            show_path = show.show_path,
                            season_number = meta.season_number,
                            episode_json = encodeOnlineEpisodes(cleared),
                        )
                    }
                }
            queries.clearShowEpisodeLocalThumbs(show_id = show.id)
        }
        runSuspendCatching { PosterCache.get(context).clearShow(show.cacheKey) }
        runSuspendCatching { PosterCache.get(context).clearShow(onlineScrapeCacheKey(show.library_id, show.show_path)) }
    }

    override suspend fun restoreNfoState(showId: Long): Unit = withContext(Dispatchers.IO) {
        val show = queries.getShowById(showId).executeAsOneOrNull() ?: return@withContext
        queries.transaction {
            queries.deleteOnlineMetaByShow(library_id = show.library_id, show_path = show.show_path)
            queries.deleteTmdbAutoMatchFailure(library_id = show.library_id, show_path = show.show_path)
            queries.clearShowEpisodeLocalThumbs(show_id = show.id)
        }
        runSuspendCatching { PosterCache.get(context).clearShow(show.cacheKey) }
        runSuspendCatching { PosterCache.get(context).clearShow(onlineScrapeCacheKey(show.library_id, show.show_path)) }
    }

    override suspend fun deleteAllScrapedData(): Unit = withContext(Dispatchers.IO) {
        // DELETE FROM ScrapedShow, FK 级联删 season/episode。保留 Library 配置。
        queries.deleteAllTmdbAutoMatchFailures()
        queries.deleteAllOnlineMeta()
        queries.deleteAllScrapedData()
    }

    // === 媒体库导出/导入 ===

    override suspend fun listOnlineMetaByLibrary(libraryId: Long): List<ScrapedOnlineMeta> =
        withContext(Dispatchers.IO) {
            queries.listOnlineMetaByLibrary(library_id = libraryId).executeAsList()
        }

    override suspend fun listBangumiSeasonLinksByLibrary(libraryId: Long): List<BangumiSeasonLink> =
        withContext(Dispatchers.IO) {
            queries.listBangumiSeasonLinksByLibrary(library_id = libraryId.toString()).executeAsList()
                .mapNotNull { entity -> loadBangumiSeasonLink(entity.identity_key) }
        }

    override suspend fun listShowOverridesByLibrary(libraryId: Long): List<ShowOverrideRow> =
        withContext(Dispatchers.IO) {
            queries.listShowOverrideByLibrary(library_id = libraryId.toString()).executeAsList().map { row ->
                ShowOverrideRow(row.identity_key, row.overrides_json, row.updated_at)
            }
        }

    override suspend fun clearLibraryData(libraryId: Long): Unit = withContext(Dispatchers.IO) {
        queries.transaction {
            queries.deleteOnlineMetaByLibrary(library_id = libraryId)
            queries.deleteShowsByLibrary(library_id = libraryId)  // FK 级联删 season/episode
            queries.deleteBlockedByLibrary(library_id = libraryId)
            queries.deleteShowOverrideByLibrary(library_id = libraryId.toString())
            queries.deleteBangumiSeasonLinkByLibrary(library_id = libraryId.toString())
            queries.deleteTmdbAutoMatchFailuresByLibrary(library_id = libraryId)
        }
    }

    override suspend fun importLibraryFull(
        libraryId: Long,
        shows: List<ShowExport>,
        blocked: List<BlockedExport>,
        links: List<BangumiLinkExport>,
        overrides: List<ShowOverrideRow>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ImportSummary = withContext(Dispatchers.IO) {
        val importedAt = platformTimeMillis()
        queries.transactionWithResult {
            val showResults = mutableMapOf<String, ImportedShowResult>()
            val total = shows.size
            shows.forEachIndexed { index, show ->
                onProgress(index, total)
                val scannedAt = 0L
                queries.insertShow(
                    library_id = libraryId, source_kind = show.sourceKind,
                    tmdb_id = show.tmdbId, folder_name = show.folderName, show_path = show.showPath,
                    title = show.title, original_title = show.originalTitle, year = show.year?.toLong(),
                    plot = show.plot, rating = show.rating, release_date = show.releaseDate,
                    genres = show.genres, studios = show.studios, poster_path = show.posterPath,
                    fanart_path = show.fanartPath, clearlogo_path = show.clearlogoPath, scanned_at = scannedAt,
                )
                val showId = queries.lastInsertRowId().executeAsOne()
                if (show.isFavorite != 0L) {
                    queries.setFavorite(is_favorite = 1L, favorited_at = show.favoritedAt, id = showId)
                }
                if (show.isHidden != 0L) {
                    queries.setHidden(is_hidden = 1L, id = showId)
                }
                val showKey = "${sanitizeFileName(show.title)}-${show.tmdbId ?: showId}"
                val seasons = mutableMapOf<Int, ImportedSeasonResult>()
                for (season in show.seasons) {
                    queries.insertSeason(
                        show_id = showId, season_number = season.seasonNumber.toLong(),
                        season_path = season.seasonPath, title = season.title,
                        year = season.year?.toLong(), release_date = season.releaseDate,
                        bangumi_id = season.bangumiId, bangumi_offset = season.bangumiOffset.toLong(),
                        season_poster_path = season.seasonPosterPath,
                        episode_count = season.episodeCount, scanned_at = scannedAt,
                    )
                    val seasonId = queries.lastInsertRowId().executeAsOne()
                    val episodes = mutableMapOf<Int, Long>()
                    for (episode in season.episodes) {
                        queries.insertEpisode(
                            season_id = seasonId, show_id = showId,
                            episode_number = episode.episodeNumber.toLong(),
                            title = episode.title, plot = episode.plot, aired = episode.aired,
                            year = episode.year?.toLong(), runtime = episode.runtime, rating = episode.rating,
                            video_path = episode.videoPath, video_name = episode.videoName,
                            thumb_path = episode.thumbPath, local_thumb_path = null,
                            media_key = episode.mediaKey, file_size = episode.fileSize, scanned_at = scannedAt,
                        )
                        episodes[episode.episodeNumber] = queries.lastInsertRowId().executeAsOne()
                    }
                    seasons[season.seasonNumber] = ImportedSeasonResult(seasonId, episodes)
                    season.onlineMeta?.let {
                        insertOnlineMetaRaw(it, libraryId, show.showPath, importedAt, season)
                    }
                }
                show.onlineMeta?.let { insertOnlineMetaRaw(it, libraryId, show.showPath, importedAt) }
                showResults[show.showPath] = ImportedShowResult(showId, showKey, seasons)
            }
            for (entry in blocked) {
                queries.insertBlocked(
                    library_id = libraryId, show_path = entry.showPath,
                    title = entry.title, tmdb_id = entry.tmdbId, blocked_at = entry.blockedAt,
                )
            }
            for (link in links) {
                val imported = link.toBangumiSeasonLinkOrNull() ?: continue
                if (shouldReplaceBangumiSeasonLink(loadBangumiSeasonLink(imported.identityKey), imported)) {
                    saveBangumiSeasonLink(imported)
                }
            }
            for (row in overrides) {
                val existing = queries.getShowOverrideRow(identity_key = row.identityKey).executeAsOneOrNull()
                if (existing == null || row.updatedAt > existing.updated_at) {
                    queries.upsertShowOverride(
                        identity_key = row.identityKey, overrides_json = row.overridesJson, updated_at = row.updatedAt,
                    )
                }
            }
            ImportSummary(showResults)
        }
    }

    /** 裸写在线 meta(无 merge 语义; local 图片路径留空, 还原后由导入流程回写)。 */
    private fun insertOnlineMetaRaw(
        meta: OnlineMetaExport,
        libraryId: Long,
        showPath: String,
        importedAt: Long,
        season: SeasonExport? = null,
    ) {
        val tmdbMapping = if (season != null) {
            season.validatedTmdbEpisodeMapping()
        } else {
            meta.validatedTmdbEpisodeMapping()
        }
        queries.insertOnlineMetaRaw(
            library_id = libraryId, show_path = showPath, season_number = meta.seasonNumber.toLong(),
            scrape_source = meta.scrapeSource, overwrite_title = if (meta.overwriteTitle) 1L else 0L,
            tmdb_id = meta.tmdbId, dandanplay_id = meta.dandanplayId, bangumi_id = meta.bangumiId,
            remote_poster_url = meta.remotePosterUrl, local_poster_path = null, poster_source = meta.posterSource,
            title = meta.title, original_title = meta.originalTitle, year = meta.year?.toLong(),
            plot = meta.plot, rating = meta.rating, release_date = meta.releaseDate,
            genres = meta.genres, studios = meta.studios,
            episode_json = encodeOnlineEpisodes(meta.episodes),
            tmdb_season_number = tmdbMapping?.seasonNumber?.toLong(),
            tmdb_episode_offset = tmdbMapping?.episodeOffset?.toLong(),
            tmdb_mapping_evidence = encodeTmdbEpisodeMappingEvidence(
                meta.validatedTmdbEpisodeMappingEvidence(tmdbMapping),
            ),
            remote_fanart_url = meta.remoteFanartUrl, local_fanart_path = null,
            scraped_at = meta.scrapedAt.takeIf { it > 0L }?.coerceAtMost(importedAt) ?: importedAt,
        )
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
        card_online_poster_path,
        card_online_fanart_path,
        card_remote_poster_url,
        card_remote_poster_season,
        card_poster_path_kind,
        card_season_number,
    )

    private fun ListShowsByLibraryRecent.toListShowsByLibrary(): ListShowsByLibrary = ListShowsByLibrary(
        id, library_id, source_kind, tmdb_id, folder_name, show_path, title, original_title,
        year, plot, rating, release_date, genres, studios, poster_path, fanart_path, clearlogo_path,
        is_favorite, favorited_at, favorite_sort_order, is_hidden, scanned_at, min_release_date, card_poster_path,
        card_online_poster_path,
        card_online_fanart_path,
        card_remote_poster_url,
        card_remote_poster_season,
        card_poster_path_kind,
        card_season_number,
    )

    private fun ListShowsSearch.toListShowsByLibrary(): ListShowsByLibrary = ListShowsByLibrary(
        id, library_id, source_kind, tmdb_id, folder_name, show_path, title, original_title,
        year, plot, rating, release_date, genres, studios, poster_path, fanart_path, clearlogo_path,
        is_favorite, favorited_at, favorite_sort_order, is_hidden, scanned_at, min_release_date, card_poster_path,
        card_online_poster_path,
        card_online_fanart_path,
        card_remote_poster_url,
        card_remote_poster_season,
        card_poster_path_kind,
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
        cardOnlinePosterPath = card_online_poster_path,
        cardOnlineFanartPath = card_online_fanart_path,
        cardRemotePosterUrl = card_remote_poster_url,
        cardRemotePosterSeason = card_remote_poster_season,
        cardPosterPathKind = ScrapedImagePathKind.fromStorage(card_poster_path_kind),
        cardSeasonNumber = card_season_number,
        lastPlayedAt = last_played_at ?: 0L,
        cacheKey = "${sanitizeFileName(title)}-${tmdb_id ?: id}",
    )

    /** 拼音排序(C-03, 与 desktopMain 实现逐条对齐)，旧收藏字段不再影响海报墙顺序。 */
    private fun List<ListShowsByLibrary>.sortedByPinyin(): List<ListShowsByLibrary> {
        return map { show ->
            PinyinShow(
                show = show,
                pinyinKey = PinyinSorter.sortKey(show.title),
                normalizedTitle = show.title.lowercase(),
            )
        }.sortedWith(
            compareBy<PinyinShow> { it.pinyinKey }
                .thenBy { it.normalizedTitle }
                .thenBy { it.show.id },
        ).map { it.show }
    }

    private data class PinyinShow(
        val show: ListShowsByLibrary,
        val pinyinKey: String,
        val normalizedTitle: String,
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
