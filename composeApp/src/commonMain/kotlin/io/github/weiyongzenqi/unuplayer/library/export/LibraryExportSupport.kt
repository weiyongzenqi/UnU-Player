package io.github.weiyongzenqi.unuplayer.library.export

import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkSource
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkState
import io.github.weiyongzenqi.unuplayer.library.ListShowsByLibrary
import io.github.weiyongzenqi.unuplayer.library.ScrapedBlocked
import io.github.weiyongzenqi.unuplayer.library.ScrapedEpisode
import io.github.weiyongzenqi.unuplayer.library.ScrapedOnlineMeta
import io.github.weiyongzenqi.unuplayer.library.ScrapedSeason
import io.github.weiyongzenqi.unuplayer.library.TmdbEpisodeMapping
import io.github.weiyongzenqi.unuplayer.library.TmdbEpisodeMappingEvidence
import io.github.weiyongzenqi.unuplayer.library.cacheKey
import io.github.weiyongzenqi.unuplayer.library.decodedEpisodes
import io.github.weiyongzenqi.unuplayer.library.isTmdbEpisodeMappingCompatible
import io.github.weiyongzenqi.unuplayer.library.tmdbEpisodeMappingEvidence
import io.github.weiyongzenqi.unuplayer.library.onlineScrapeCacheKey
import io.github.weiyongzenqi.unuplayer.playback.EpisodeProgress
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 媒体库导出/导入支持: JSON 编解码 + 行->DTO 转换 + identity/media_key 重映射 + zip 图片条目名。
 * 纯逻辑(commonMain), 不碰平台文件。
 */

/** 导出包 JSON 配置: 未知键忽略(前向兼容), null/默认省略(文件更小)。 */
val libraryExportJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

object LibraryExportCodec {
    fun encodeManifest(manifest: LibraryExportManifest): String = libraryExportJson.encodeToString(manifest)
    fun decodeManifest(text: String): LibraryExportManifest? =
        runCatching { libraryExportJson.decodeFromString<LibraryExportManifest>(text) }.getOrNull()
    fun encodeData(data: LibraryExportData): String = libraryExportJson.encodeToString(data)
    fun decodeData(text: String): LibraryExportData? =
        runCatching { libraryExportJson.decodeFromString<LibraryExportData>(text) }.getOrNull()
}

// ============ 行 -> 导出 DTO ============

fun ListShowsByLibrary.toShowExport(
    seasons: List<SeasonExport>,
    onlineMeta: OnlineMetaExport?,
    bangumiLinks: List<BangumiLinkExport>,
    overrideJson: String?,
    overrideUpdatedAt: Long? = null,
    imageExportShowKey: String = cacheKey,
    imageExportOnlineKey: String = onlineScrapeCacheKey(library_id, show_path),
): ShowExport = ShowExport(
    sourceKind = source_kind,
    tmdbId = tmdb_id,
    folderName = folder_name,
    showPath = show_path,
    title = title,
    originalTitle = original_title,
    year = year?.toInt(),
    plot = plot,
    rating = rating,
    releaseDate = release_date,
    genres = genres,
    studios = studios,
    posterPath = poster_path,
    fanartPath = fanart_path,
    clearlogoPath = clearlogo_path,
    isFavorite = is_favorite,
    favoritedAt = favorited_at,
    favoriteSortOrder = favorite_sort_order,
    isHidden = is_hidden,
    scannedAt = scanned_at,
    exportShowCacheKey = imageExportShowKey,
    exportOnlineCacheKey = imageExportOnlineKey,
    seasons = seasons,
    onlineMeta = onlineMeta,
    bangumiLinks = bangumiLinks,
    overrideJson = overrideJson,
    overrideUpdatedAt = overrideUpdatedAt,
)

/** 不可信导出 DTO 转为受枚举约束的关联；非法状态、来源或无 subject 的 CONFIRMED 行拒绝。 */
internal fun BangumiLinkExport.toBangumiSeasonLinkOrNull(): BangumiSeasonLink? {
    val parsedState = runCatching { BangumiLinkState.valueOf(state) }.getOrNull() ?: return null
    val parsedSource = runCatching { BangumiLinkSource.valueOf(source) }.getOrNull() ?: return null
    if (parsedState == BangumiLinkState.CONFIRMED && (subjectId == null || subjectId <= 0L)) return null
    return BangumiSeasonLink(
        identityKey = identityKey,
        subjectId = subjectId,
        state = parsedState,
        source = parsedSource,
        evidence = evidence,
        updatedAt = updatedAt.coerceAtLeast(0L),
        verifiedAt = verifiedAt,
    )
}

fun ScrapedSeason.toSeasonExport(episodes: List<EpisodeExport>, onlineMeta: OnlineMetaExport?): SeasonExport =
    SeasonExport(
        seasonNumber = season_number.toInt(),
        seasonPath = season_path,
        title = title,
        year = year?.toInt(),
        releaseDate = release_date,
        bangumiId = bangumi_id,
        bangumiOffset = bangumi_offset.toInt(),
        seasonPosterPath = season_poster_path,
        episodeCount = episode_count,
        episodes = episodes,
        onlineMeta = onlineMeta,
    )

fun ScrapedEpisode.toEpisodeExport(): EpisodeExport = EpisodeExport(
    episodeNumber = episode_number.toInt(),
    title = title,
    plot = plot,
    aired = aired,
    year = year?.toInt(),
    runtime = runtime,
    rating = rating,
    videoPath = video_path,
    videoName = video_name,
    thumbPath = thumb_path,
    mediaKey = media_key,
    fileSize = file_size,
)

/** 在线 meta -> 导出 DTO。设备特定字段丢弃: local 路径不导出, episode thumbPath 置 null(图片走 zip)。 */
fun ScrapedOnlineMeta.toOnlineMetaExport(): OnlineMetaExport = OnlineMetaExport(
    seasonNumber = season_number.toInt(),
    scrapeSource = scrape_source,
    overwriteTitle = overwrite_title != 0L,
    tmdbId = tmdb_id,
    dandanplayId = dandanplay_id,
    bangumiId = bangumi_id,
    remotePosterUrl = remote_poster_url,
    posterSource = poster_source,
    title = title,
    originalTitle = original_title,
    year = year?.toInt(),
    plot = plot,
    rating = rating,
    releaseDate = release_date,
    genres = genres,
    studios = studios,
    episodes = decodedEpisodes.map { episode ->
        episode.copy(
            thumbPath = null,
            tmdbStillAvailable = if (!episode.thumbPath.isNullOrBlank()) true else episode.tmdbStillAvailable,
        )
    },
    tmdbSeasonNumber = tmdb_season_number?.toInt(),
    tmdbEpisodeOffset = tmdb_episode_offset?.toInt(),
    tmdbMappingEvidence = tmdbEpisodeMappingEvidence,
    remoteFanartUrl = remote_fanart_url,
    scrapedAt = scraped_at,
)

/**
 * 导入包属于不可信输入。TMDB 映射必须成对、落在有限范围内，并且不能把包内任一正片映射为非正集号。
 * 不合法时只丢弃独立 TMDB 坐标，不影响本地季集和其余在线元数据导入。
 */
internal fun OnlineMetaExport.validatedTmdbEpisodeMapping(): TmdbEpisodeMapping? {
    val seasonNumber = tmdbSeasonNumber ?: return null
    val episodeOffset = tmdbEpisodeOffset ?: return null
    if (seasonNumber !in 0..999 || episodeOffset !in -100_000..100_000) return null
    if (episodes.any { it.episodeNumber.toLong() - episodeOffset.toLong() <= 0L }) return null
    return TmdbEpisodeMapping(seasonNumber, episodeOffset)
}

internal fun OnlineMetaExport.validatedTmdbEpisodeMappingEvidence(
    mapping: TmdbEpisodeMapping?,
): TmdbEpisodeMappingEvidence? {
    if (mapping == null) return null
    val evidence = tmdbMappingEvidence ?: return null
    if (evidence.version != 1 || evidence.bangumiSubjectId <= 0L ||
        evidence.bangumiOffset !in -100_000..100_000
    ) {
        return null
    }
    if (mapping.episodeOffset != 0 && mapping.episodeOffset != evidence.bangumiOffset) return null
    return evidence
}

/**
 * 迁移包里的 TMDB 映射也必须绑定当前季度的 Bangumi 分段证据。旧包没有 evidence 时，
 * 普通同号零偏移仍兼容；带 offset 的映射先丢弃，导入后由在线补全重新核验。
 */
internal fun SeasonExport.validatedTmdbEpisodeMapping(): TmdbEpisodeMapping? {
    val meta = onlineMeta ?: return null
    val mapping = meta.validatedTmdbEpisodeMapping() ?: return null
    return mapping.takeIf {
        isTmdbEpisodeMappingCompatible(
            mapping = it,
            localSeasonNumber = seasonNumber,
            localEpisodeNumbers = episodes.map { episode -> episode.episodeNumber },
            bangumiId = bangumiId,
            bangumiOffset = bangumiOffset,
            evidence = meta.validatedTmdbEpisodeMappingEvidence(mapping),
        )
    }
}

fun ScrapedBlocked.toBlockedExport(): BlockedExport = BlockedExport(
    showPath = show_path,
    title = title,
    tmdbId = tmdb_id,
    blockedAt = blocked_at,
)

fun PlaybackRecord.toPlaybackExport(): PlaybackExport = PlaybackExport(
    mediaKey = media_key,
    sourceKind = source_kind,
    url = url,
    title = title,
    positionMs = position_ms,
    durationMs = duration_ms,
    watchProgress = watch_progress,
    isCompleted = is_completed,
    tmdbId = tmdb_id,
    seasonNumber = season_number,
    episodeNumber = episode_number,
    danmakuEpisodeId = danmaku_episode_id,
    danmakuAnimeId = danmaku_anime_id,
    danmakuAnimeTitle = danmaku_anime_title,
    danmakuEpisodeTitle = danmaku_episode_title,
    danmakuMatchMethod = danmaku_match_method,
    danmakuSyncVersion = danmaku_sync_version,
    danmakuUpdatedAt = danmaku_updated_at,
    lastPlayedAt = last_played_at,
    syncStatus = sync_status,
    syncVersion = sync_version,
)

fun EpisodeProgress.toEpisodeProgressExport(): EpisodeProgressExport = EpisodeProgressExport(
    tmdbId = tmdb_id,
    seasonNumber = season_number,
    episodeNumber = episode_number,
    mediaKey = media_key,
    positionMs = position_ms,
    durationMs = duration_ms,
    watchProgress = watch_progress,
    isCompleted = is_completed,
    lastPlayedAt = last_played_at,
    syncStatus = sync_status,
    syncVersion = sync_version,
)

// ============ Bangumi 关联导出 ============

fun BangumiSeasonLink.toLinkExport(): BangumiLinkExport = BangumiLinkExport(
    identityKey = identityKey,
    subjectId = subjectId,
    state = state.name,
    source = source.name,
    evidence = evidence,
    updatedAt = updatedAt,
    verifiedAt = verifiedAt,
)

// ============ identity / media_key 重映射(导入) ============

/**
 * 重映射 show 前缀 identity(show:<旧库id>:<path>[-:season:<n>]) 到新库 id。
 * tmdb-tv:/tmdb: 前缀跨设备有效, 原样返回。
 */
fun remapShowIdentity(identityKey: String, oldLibraryId: Long, newLibraryId: Long): String {
    val prefix = "show:$oldLibraryId:"
    return if (identityKey.startsWith(prefix)) {
        "show:$newLibraryId:" + identityKey.removePrefix(prefix)
    } else {
        identityKey
    }
}

/**
 * 重映射 media_key 内嵌连接 id(webdav:<旧id>:<path> / smb:<旧id>:<path>)到新连接 id。
 * 非本连接前缀原样返回。
 */
fun remapMediaKey(mediaKey: String, oldConnectionId: String, newConnectionId: String): String {
    val webDavPrefix = "webdav:$oldConnectionId:"
    if (mediaKey.startsWith(webDavPrefix)) return "webdav:$newConnectionId:" + mediaKey.removePrefix(webDavPrefix)
    val smbPrefix = "smb:$oldConnectionId:"
    if (mediaKey.startsWith(smbPrefix)) return "smb:$newConnectionId:" + mediaKey.removePrefix(smbPrefix)
    return mediaKey
}

// ============ zip 图片条目名 ============

const val ZIP_IMAGES_PREFIX = "images/"
const val ZIP_ONLINE_DIR = "online"
const val ZIP_EP_DIR = "ep"

/** 包内图片定位键追加来源 Show 行 id，避免同标题+同 TMDB ID 的不同路径共享缓存键后条目冲突。 */
internal fun imageExportKey(cacheKey: String, showRowId: Long): String = "$cacheKey-row$showRowId"

/** online 图 zip 条目名。role: poster/fanart/season<N>-poster/season<N>-episode<M>。 */
fun onlineImageEntryName(onlineCacheKey: String, role: String, basename: String): String =
    "$ZIP_IMAGES_PREFIX$ZIP_ONLINE_DIR/$onlineCacheKey/$role-$basename"

/** 集照 zip 条目名: images/ep/<showCacheKey>/s<N>e<M>.jpg。 */
fun episodeImageEntryName(showCacheKey: String, seasonNumber: Int, episodeNumber: Int): String =
    "$ZIP_IMAGES_PREFIX$ZIP_EP_DIR/$showCacheKey/s${seasonNumber}e$episodeNumber.jpg"

/** 解析后的 online 图片条目。 */
data class OnlineImageEntry(
    val onlineCacheKey: String,
    /** "poster"(部级) / "fanart"(部级) / "season<N>-poster"(季级) / "season<N>-episode<M>"(TMDB 集照)。 */
    val role: String,
    val basename: String,
)

/** 恢复到 show 缓存目录时保留已验证的 role，避免不同语义图片同 basename 相互覆盖。 */
internal fun onlineImageRestoreBasename(entry: OnlineImageEntry): String = "${entry.role}-${entry.basename}"

data class OnlineEpisodeImageRole(val seasonNumber: Int, val episodeNumber: Int)

fun onlineEpisodeImageRole(seasonNumber: Int, episodeNumber: Int): String =
    "season$seasonNumber-episode$episodeNumber"

fun parseOnlineEpisodeImageRole(role: String): OnlineEpisodeImageRole? {
    val match = Regex("^season(\\d+)-episode(\\d+)$").matchEntire(role) ?: return null
    val seasonNumber = match.groupValues[1].toIntOrNull() ?: return null
    val episodeNumber = match.groupValues[2].toIntOrNull() ?: return null
    return OnlineEpisodeImageRole(seasonNumber, episodeNumber)
}

/** 解析后的集照条目。 */
data class EpisodeImageEntry(val showCacheKey: String, val seasonNumber: Int, val episodeNumber: Int)

/** 解析 zip 条目名 -> online 图片条目; 非 online 图返回 null。 */
fun parseOnlineImageEntry(entryName: String): OnlineImageEntry? {
    if (!entryName.startsWith("$ZIP_IMAGES_PREFIX$ZIP_ONLINE_DIR/")) return null
    val rest = entryName.removePrefix("$ZIP_IMAGES_PREFIX$ZIP_ONLINE_DIR/")
    val slash = rest.lastIndexOf('/')
    if (slash <= 0) return null
    val onlineCacheKey = rest.substring(0, slash)
    val filePart = rest.substring(slash + 1)
    if (filePart.startsWith("fanart-")) return OnlineImageEntry(onlineCacheKey, "fanart", filePart.removePrefix("fanart-"))
    if (filePart.startsWith("poster-")) return OnlineImageEntry(onlineCacheKey, "poster", filePart.removePrefix("poster-"))
    val season = Regex("^season(\\d+)-poster-(.+)$").find(filePart)
    if (season != null) {
        return OnlineImageEntry(onlineCacheKey, "season${season.groupValues[1]}-poster", season.groupValues[2])
    }
    val episode = Regex("^(season\\d+-episode\\d+)-(.+)$").find(filePart)
    if (episode != null) {
        return OnlineImageEntry(onlineCacheKey, episode.groupValues[1], episode.groupValues[2])
    }
    return null
}

/** 解析 zip 条目名 -> 集照条目; 非集照返回 null。 */
fun parseEpisodeImageEntry(entryName: String): EpisodeImageEntry? {
    if (!entryName.startsWith("$ZIP_IMAGES_PREFIX$ZIP_EP_DIR/")) return null
    val rest = entryName.removePrefix("$ZIP_IMAGES_PREFIX$ZIP_EP_DIR/")
    val slash = rest.lastIndexOf('/')
    if (slash <= 0) return null
    val showCacheKey = rest.substring(0, slash)
    val file = rest.substring(slash + 1)
    val match = Regex("^s(\\d+)e(\\d+)\\.jpg$").find(file) ?: return null
    return EpisodeImageEntry(showCacheKey, match.groupValues[1].toInt(), match.groupValues[2].toInt())
}
