package io.github.weiyongzenqi.unuplayer.playback.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.EpisodeProgress

/**
 * P2 WebDAV 同步白名单 DTO。
 * 排除 id(自增)/url(含服务器 host+完整路径, 隐私)/content_uri(本地专有)/sync_status(本地脏位, 不该同步)。
 * 保留 sync_version(逻辑时钟, 合并比版本必需)。凭据结构性进不来(凭据不在 PlaybackRecord)。
 */
@Serializable
data class PlaybackSyncRecord(
    val media_key: String,
    val source_kind: String,
    val title: String,
    val position_ms: Long,
    val duration_ms: Long,
    val watch_progress: Double,
    val is_completed: Long,
    val tmdb_id: Long? = null,
    val season_number: Long? = null,
    val episode_number: Long? = null,
    val danmaku_episode_id: Long? = null,
    val danmaku_anime_id: Long? = null,
    val danmaku_anime_title: String? = null,
    val danmaku_episode_title: String? = null,
    val danmaku_match_method: String? = null,
    val last_played_at: Long,
    val sync_version: Long,
)

@Serializable
data class PlaybackSyncEpisodeProgress(
    val tmdb_id: Long,
    val season_number: Long,
    val episode_number: Long,
    val media_key: String? = null,
    val position_ms: Long,
    val duration_ms: Long,
    val watch_progress: Double,
    val is_completed: Long,
    val last_played_at: Long,
    val sync_version: Long,
)

/** 单设备同步载荷: /.unuplayer/playback/<deviceId>.json。 */
@Serializable
data class PlaybackSyncPayload(
    val deviceId: String,
    val deviceName: String,
    val records: List<PlaybackSyncRecord>,
    val episodeProgress: List<PlaybackSyncEpisodeProgress>,
)

/** 同步 JSON(照 ManualMatchCache 惯例)。 */
internal val playbackSyncJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** PlaybackRecord -> DTO(url 不入 DTO)。 */
fun PlaybackRecord.toSyncDto(): PlaybackSyncRecord = PlaybackSyncRecord(
    media_key = media_key, source_kind = source_kind, title = title,
    position_ms = position_ms, duration_ms = duration_ms, watch_progress = watch_progress,
    is_completed = is_completed, tmdb_id = tmdb_id, season_number = season_number, episode_number = episode_number,
    danmaku_episode_id = danmaku_episode_id, danmaku_anime_id = danmaku_anime_id,
    danmaku_anime_title = danmaku_anime_title, danmaku_episode_title = danmaku_episode_title,
    danmaku_match_method = danmaku_match_method, last_played_at = last_played_at, sync_version = sync_version,
)

/** DTO -> PlaybackRecord(合并写入用; resolvedUrl 为 WebDAV 记录重算的合法 url, 默认 media_key 满足 NOT NULL, content_uri=null, id=0 自增忽略, sync_status=0)。 */
fun PlaybackSyncRecord.toRecord(resolvedUrl: String? = null): PlaybackRecord = PlaybackRecord(
    id = 0, media_key = media_key, source_kind = source_kind, url = resolvedUrl ?: media_key, content_uri = null, title = title,
    position_ms = position_ms, duration_ms = duration_ms, watch_progress = watch_progress,
    is_completed = is_completed, tmdb_id = tmdb_id, season_number = season_number, episode_number = episode_number,
    danmaku_episode_id = danmaku_episode_id, danmaku_anime_id = danmaku_anime_id,
    danmaku_anime_title = danmaku_anime_title, danmaku_episode_title = danmaku_episode_title,
    danmaku_match_method = danmaku_match_method, last_played_at = last_played_at,
    sync_status = 0, sync_version = sync_version,
)

fun EpisodeProgress.toSyncDto(): PlaybackSyncEpisodeProgress = PlaybackSyncEpisodeProgress(
    tmdb_id = tmdb_id, season_number = season_number, episode_number = episode_number, media_key = media_key,
    position_ms = position_ms, duration_ms = duration_ms, watch_progress = watch_progress,
    is_completed = is_completed, last_played_at = last_played_at, sync_version = sync_version,
)

fun PlaybackSyncEpisodeProgress.toProgress(): EpisodeProgress = EpisodeProgress(
    tmdb_id = tmdb_id, season_number = season_number, episode_number = episode_number, media_key = media_key,
    position_ms = position_ms, duration_ms = duration_ms, watch_progress = watch_progress,
    is_completed = is_completed, last_played_at = last_played_at, sync_status = 0, sync_version = sync_version,
)