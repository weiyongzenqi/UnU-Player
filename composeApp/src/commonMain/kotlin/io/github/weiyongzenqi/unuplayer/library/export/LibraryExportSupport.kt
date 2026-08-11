package io.github.weiyongzenqi.unuplayer.library.export

import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.library.ListShowsByLibrary
import io.github.weiyongzenqi.unuplayer.library.ScrapedBlocked
import io.github.weiyongzenqi.unuplayer.library.ScrapedEpisode
import io.github.weiyongzenqi.unuplayer.library.ScrapedOnlineMeta
import io.github.weiyongzenqi.unuplayer.library.ScrapedSeason
import io.github.weiyongzenqi.unuplayer.library.cacheKey
import io.github.weiyongzenqi.unuplayer.library.decodedEpisodes
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
    exportShowCacheKey = cacheKey,
    exportOnlineCacheKey = onlineScrapeCacheKey(library_id, show_path),
    seasons = seasons,
    onlineMeta = onlineMeta,
    bangumiLinks = bangumiLinks,
    overrideJson = overrideJson,
)

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
    title = title,
    originalTitle = original_title,
    year = year?.toInt(),
    plot = plot,
    rating = rating,
    releaseDate = release_date,
    genres = genres,
    studios = studios,
    episodes = decodedEpisodes.map { it.copy(thumbPath = null) },
    remoteFanartUrl = remote_fanart_url,
    scrapedAt = scraped_at,
)

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

/** online 图 zip 条目名: images/online/<onlineCacheKey>/<role>-<basename>。role: poster/fanart/season<N>-poster。 */
fun onlineImageEntryName(onlineCacheKey: String, role: String, basename: String): String =
    "$ZIP_IMAGES_PREFIX$ZIP_ONLINE_DIR/$onlineCacheKey/$role-$basename"

/** 集照 zip 条目名: images/ep/<showCacheKey>/s<N>e<M>.jpg。 */
fun episodeImageEntryName(showCacheKey: String, seasonNumber: Int, episodeNumber: Int): String =
    "$ZIP_IMAGES_PREFIX$ZIP_EP_DIR/$showCacheKey/s${seasonNumber}e$episodeNumber.jpg"

/** 解析后的 online 图片条目。 */
data class OnlineImageEntry(
    val onlineCacheKey: String,
    /** "poster"(部级) / "fanart"(部级) / "season<N>-poster"(季级)。 */
    val role: String,
    val basename: String,
)

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