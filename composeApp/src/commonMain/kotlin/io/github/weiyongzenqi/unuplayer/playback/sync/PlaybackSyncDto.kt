package io.github.weiyongzenqi.unuplayer.playback.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.EpisodeProgress
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordDeletion
import io.github.weiyongzenqi.unuplayer.playback.EpisodeProgressDeletion

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
    val danmaku_sync_version: Long = 0,
    val danmaku_updated_at: Long = 0,
    val last_played_at: Long,
    val sync_version: Long,
    /**
     * 跨设备稳定媒体身份(端点指纹+账号+path)。media_key 含本机 connectionId, 跨设备重装/新建连接后
     * 同一文件的 key 不同, 同步按 key 精确匹配会产生重复记录; 身份由 push 端按连接信息归一化生成,
     * pull 端优先按身份匹配合并。旧版本 payload 无此字段(null), 回落 media_key 匹配。
     */
    val media_identity: String? = null,
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
    /**
     * media_key 对应的跨设备稳定媒体身份(同 PlaybackSyncRecord.media_identity)。B-2:
     * pull 合并时把远端 connId 的 ghost media_key 归置到本地连接, 否则本地级联
     * (updatePosition/deleteByKey)落空且"同步后导出"按 media_key 过滤丢行。
     * 旧版本 payload 无此字段(null), 回落 media_key 原值。
     */
    val media_identity: String? = null,
)

@Serializable
data class PlaybackSyncRecordDeletion(
    val media_key: String,
    val deleted_at: Long,
    val sync_version: Long,
    val media_identity: String? = null,
)

@Serializable
data class PlaybackSyncEpisodeProgressDeletion(
    val tmdb_id: Long,
    val season_number: Long,
    val episode_number: Long,
    val deleted_at: Long,
    val sync_version: Long,
    val media_key: String? = null,
    val media_identity: String? = null,
)

/** 当前播放同步协议版本；协议升级必须同时隔离远端目录，不能只依赖此字段。 */
internal const val CURRENT_PLAYBACK_SYNC_SCHEMA_VERSION = 2

/** 单设备同步载荷: /.unuplayer/playback/v2/<deviceId>.json.gz。 */
@Serializable
data class PlaybackSyncPayload(
    val deviceId: String,
    val deviceName: String,
    val records: List<PlaybackSyncRecord>,
    val episodeProgress: List<PlaybackSyncEpisodeProgress>,
    val schemaVersion: Int = CURRENT_PLAYBACK_SYNC_SCHEMA_VERSION,
    val historyEpoch: Long = 0,
    val recordDeletions: List<PlaybackSyncRecordDeletion> = emptyList(),
    val progressDeletions: List<PlaybackSyncEpisodeProgressDeletion> = emptyList(),
)

/** 同步 JSON(照 ManualMatchCache 惯例)。 */
internal val playbackSyncJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * 远端/迁移包可控的逻辑时钟上限。1e12 已远高于正常使用寿命，同时为 SQLite 的后续 +1
 * 留出充足空间，避免 Long.MAX_VALUE 使本地版本溢出或永久失去写入能力。
 */
internal const val MAX_PLAYBACK_SYNC_VERSION = 1_000_000_000_000L

/** 存量非法版本归一基线：保留对正常版本的优势，同时为后续本地 +1 留出等量空间。 */
internal const val REPAIRED_PLAYBACK_SYNC_VERSION = MAX_PLAYBACK_SYNC_VERSION / 2L

internal fun isPlaybackSyncVersionSafe(value: Long): Boolean =
    value in 0L until MAX_PLAYBACK_SYNC_VERSION

/** 任一逻辑时钟越界都拒绝整份远端快照，保持 pull 的零部分写入契约。 */
internal fun PlaybackSyncPayload.hasSafeLogicalVersions(): Boolean =
    isPlaybackSyncVersionSafe(historyEpoch) &&
        records.all {
            isPlaybackSyncVersionSafe(it.sync_version) &&
                isPlaybackSyncVersionSafe(it.danmaku_sync_version)
        } &&
        episodeProgress.all { isPlaybackSyncVersionSafe(it.sync_version) } &&
        recordDeletions.all { isPlaybackSyncVersionSafe(it.sync_version) } &&
        progressDeletions.all { isPlaybackSyncVersionSafe(it.sync_version) }

internal fun PlaybackSyncPayload.hasSupportedSchemaVersion(): Boolean =
    schemaVersion == CURRENT_PLAYBACK_SYNC_SCHEMA_VERSION

/** 非空媒体身份必须与同条 media_key 指向完全相同的 WebDAV path，否则拒绝整份快照。 */
internal fun PlaybackSyncPayload.hasConsistentMediaIdentityPaths(): Boolean =
    records.all { mediaIdentityMatchesKey(it.media_identity, it.media_key) } &&
        recordDeletions.all { mediaIdentityMatchesKey(it.media_identity, it.media_key) } &&
        episodeProgress.all { mediaIdentityMatchesOptionalKey(it.media_identity, it.media_key) } &&
        progressDeletions.all { mediaIdentityMatchesOptionalKey(it.media_identity, it.media_key) }

private fun mediaIdentityMatchesOptionalKey(identity: String?, mediaKey: String?): Boolean = when {
    identity == null -> true
    mediaKey == null -> false
    else -> mediaIdentityMatchesKey(identity, mediaKey)
}

private fun mediaIdentityMatchesKey(identity: String?, mediaKey: String): Boolean {
    if (identity == null) return true
    val identityPath = parseSyncMediaIdentityPath(identity) ?: return false
    val mediaKeyPath = parseWebDavMediaKeyPath(mediaKey) ?: return false
    return identityPath == mediaKeyPath
}

/** PlaybackRecord -> DTO(url 不入 DTO; mediaIdentity 为可选跨设备稳定身份)。 */
fun PlaybackRecord.toSyncDto(mediaIdentity: String? = null): PlaybackSyncRecord = PlaybackSyncRecord(
    media_key = media_key, source_kind = source_kind, title = title,
    position_ms = position_ms, duration_ms = duration_ms, watch_progress = watch_progress,
    is_completed = is_completed, tmdb_id = tmdb_id, season_number = season_number, episode_number = episode_number,
    danmaku_episode_id = danmaku_episode_id, danmaku_anime_id = danmaku_anime_id,
    danmaku_anime_title = danmaku_anime_title, danmaku_episode_title = danmaku_episode_title,
    danmaku_match_method = danmaku_match_method,
    danmaku_sync_version = danmaku_sync_version, danmaku_updated_at = danmaku_updated_at,
    last_played_at = last_played_at, sync_version = sync_version,
    media_identity = mediaIdentity,
)

/** DTO -> PlaybackRecord(合并写入用; resolvedUrl 为 WebDAV 记录重算的合法 url, 默认 media_key 满足 NOT NULL, content_uri=null, id=0 自增忽略, sync_status=0)。 */
fun PlaybackSyncRecord.toRecord(resolvedUrl: String? = null): PlaybackRecord = PlaybackRecord(
    id = 0, media_key = media_key, source_kind = source_kind, url = resolvedUrl ?: media_key, content_uri = null, title = title,
    position_ms = position_ms, duration_ms = duration_ms, watch_progress = watch_progress,
    is_completed = is_completed, tmdb_id = tmdb_id, season_number = season_number, episode_number = episode_number,
    danmaku_episode_id = danmaku_episode_id, danmaku_anime_id = danmaku_anime_id,
    danmaku_anime_title = danmaku_anime_title, danmaku_episode_title = danmaku_episode_title,
    danmaku_match_method = danmaku_match_method,
    danmaku_sync_version = danmaku_sync_version, danmaku_updated_at = danmaku_updated_at,
    last_played_at = last_played_at,
    sync_status = 0, sync_version = sync_version,
)

fun EpisodeProgress.toSyncDto(mediaIdentity: String? = null): PlaybackSyncEpisodeProgress = PlaybackSyncEpisodeProgress(
    tmdb_id = tmdb_id, season_number = season_number, episode_number = episode_number, media_key = media_key,
    position_ms = position_ms, duration_ms = duration_ms, watch_progress = watch_progress,
    is_completed = is_completed, last_played_at = last_played_at, sync_version = sync_version,
    media_identity = mediaIdentity,
)

fun PlaybackSyncEpisodeProgress.toProgress(): EpisodeProgress = EpisodeProgress(
    tmdb_id = tmdb_id, season_number = season_number, episode_number = episode_number, media_key = media_key,
    position_ms = position_ms, duration_ms = duration_ms, watch_progress = watch_progress,
    is_completed = is_completed, last_played_at = last_played_at, sync_status = 0, sync_version = sync_version,
)

fun PlaybackRecordDeletion.toSyncDto(mediaIdentity: String? = null): PlaybackSyncRecordDeletion =
    PlaybackSyncRecordDeletion(
        media_key = mediaKey,
        deleted_at = deletedAt,
        sync_version = syncVersion,
        media_identity = mediaIdentity ?: this.mediaIdentity,
    )

fun PlaybackSyncRecordDeletion.toDeletion(): PlaybackRecordDeletion = PlaybackRecordDeletion(
    mediaKey = media_key,
    mediaIdentity = media_identity,
    deletedAt = deleted_at,
    syncVersion = sync_version,
)

fun EpisodeProgressDeletion.toSyncDto(mediaIdentity: String? = null): PlaybackSyncEpisodeProgressDeletion =
    PlaybackSyncEpisodeProgressDeletion(
        tmdb_id = tmdbId,
        season_number = seasonNumber,
        episode_number = episodeNumber,
        media_key = mediaKey,
        media_identity = mediaIdentity ?: this.mediaIdentity,
        deleted_at = deletedAt,
        sync_version = syncVersion,
    )

fun PlaybackSyncEpisodeProgressDeletion.toDeletion(): EpisodeProgressDeletion = EpisodeProgressDeletion(
    tmdbId = tmdb_id,
    seasonNumber = season_number,
    episodeNumber = episode_number,
    mediaKey = media_key,
    mediaIdentity = media_identity,
    deletedAt = deleted_at,
    syncVersion = sync_version,
)
