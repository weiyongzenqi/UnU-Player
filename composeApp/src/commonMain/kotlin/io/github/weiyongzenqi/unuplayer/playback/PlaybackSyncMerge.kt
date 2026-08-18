package io.github.weiyongzenqi.unuplayer.playback

/** 播放记录的跨设备删除事件。没有设备确认向量前不得按时间或数量自动回收。 */
data class PlaybackRecordDeletion(
    val mediaKey: String,
    val mediaIdentity: String? = null,
    val deletedAt: Long,
    val syncVersion: Long,
)

/** 三元组语义进度的跨设备删除事件。 */
data class EpisodeProgressDeletion(
    val tmdbId: Long,
    val seasonNumber: Long,
    val episodeNumber: Long,
    val mediaKey: String? = null,
    val mediaIdentity: String? = null,
    val deletedAt: Long,
    val syncVersion: Long,
)

/**
 * Coordinator 已完成远端文件的全量验证、epoch 过滤、身份归置和同类候选归并；仓库必须在
 * 一个数据库事务内重读本地 active/tombstone 后应用整批，任意 SQL 失败整体回滚。
 */
data class PlaybackSyncMergeBatch(
    val historyEpoch: Long,
    val records: List<PlaybackRecord>,
    val episodeProgress: List<EpisodeProgress>,
    val recordDeletions: List<PlaybackRecordDeletion>,
    val progressDeletions: List<EpisodeProgressDeletion>,
)

data class PlaybackSyncMergeResult(
    val mergedRecords: Int = 0,
    val mergedProgress: Int = 0,
    val mergedRecordDeletions: Int = 0,
    val mergedProgressDeletions: Int = 0,
)

internal fun PlaybackRecord.existenceSyncVersion(): Long = maxOf(sync_version, danmaku_sync_version)

internal fun mergePlaybackRecordDimensions(
    current: PlaybackRecord?,
    candidate: PlaybackRecord,
): PlaybackRecord {
    if (current == null) return candidate
    val progressWins = candidate.sync_version > current.sync_version ||
        (candidate.sync_version == current.sync_version && candidate.last_played_at > current.last_played_at)
    val danmakuWins = candidate.danmaku_sync_version > current.danmaku_sync_version ||
        (candidate.danmaku_sync_version == current.danmaku_sync_version &&
            candidate.danmaku_updated_at > current.danmaku_updated_at)
    val progress = if (progressWins) candidate else current
    val danmaku = if (danmakuWins) candidate else current
    return progress.copy(
        danmaku_episode_id = danmaku.danmaku_episode_id,
        danmaku_anime_id = danmaku.danmaku_anime_id,
        danmaku_anime_title = danmaku.danmaku_anime_title,
        danmaku_episode_title = danmaku.danmaku_episode_title,
        danmaku_match_method = danmaku.danmaku_match_method,
        danmaku_sync_version = danmaku.danmaku_sync_version,
        danmaku_updated_at = danmaku.danmaku_updated_at,
    )
}

internal fun mergeEpisodeProgress(
    current: EpisodeProgress?,
    candidate: EpisodeProgress,
): EpisodeProgress = when {
    current == null -> candidate
    candidate.sync_version > current.sync_version -> candidate
    candidate.sync_version < current.sync_version -> current
    candidate.last_played_at > current.last_played_at -> candidate
    else -> current
}

internal fun newerRecordDeletion(
    current: PlaybackRecordDeletion?,
    candidate: PlaybackRecordDeletion,
): PlaybackRecordDeletion = when {
    current == null -> candidate
    candidate.syncVersion > current.syncVersion -> candidate
    candidate.syncVersion < current.syncVersion -> current
    candidate.deletedAt > current.deletedAt -> candidate
    candidate.deletedAt < current.deletedAt -> current
    current.mediaIdentity == null && candidate.mediaIdentity != null -> candidate
    else -> current
}

internal fun newerProgressDeletion(
    current: EpisodeProgressDeletion?,
    candidate: EpisodeProgressDeletion,
): EpisodeProgressDeletion = when {
    current == null -> candidate
    candidate.syncVersion > current.syncVersion -> candidate
    candidate.syncVersion < current.syncVersion -> current
    candidate.deletedAt > current.deletedAt -> candidate
    candidate.deletedAt < current.deletedAt -> current
    current.mediaIdentity == null && candidate.mediaIdentity != null -> candidate
    else -> current
}

internal fun progressDeletionKey(tmdbId: Long, seasonNumber: Long, episodeNumber: Long): String =
    "$tmdbId-$seasonNumber-$episodeNumber"
