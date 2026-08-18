package io.github.weiyongzenqi.unuplayer.playback

/** 两个平台共用同一套同步事务，避免 active/tombstone/epoch 仲裁漂移。 */
internal fun PlaybackQueries.applyPlaybackSyncMergeBatch(
    batch: PlaybackSyncMergeBatch,
    onCommitted: () -> Unit,
): PlaybackSyncMergeResult {
    var result = PlaybackSyncMergeResult()
    transaction {
        val localEpoch = getPlaybackHistoryEpoch().executeAsOne()
        if (batch.historyEpoch < localEpoch) return@transaction

        if (batch.historyEpoch > localEpoch) {
            deleteAll()
            episodeProgressDeleteAll()
            deleteAllPlaybackRecordTombstones()
            deleteAllEpisodeProgressTombstones()
            setPlaybackHistoryEpoch(batch.historyEpoch)
        }

        var mergedRecords = 0
        var mergedProgress = 0
        var mergedRecordDeletions = 0
        var mergedProgressDeletions = 0

        val recordsByKey = linkedMapOf<String, PlaybackRecord>()
        batch.records.forEach { candidate ->
            recordsByKey[candidate.media_key] = mergePlaybackRecordDimensions(
                recordsByKey[candidate.media_key],
                candidate,
            )
        }
        val recordDeletionsByKey = linkedMapOf<String, PlaybackRecordDeletion>()
        batch.recordDeletions.forEach { candidate ->
            recordDeletionsByKey[candidate.mediaKey] = newerRecordDeletion(
                recordDeletionsByKey[candidate.mediaKey],
                candidate,
            )
        }

        (recordsByKey.keys + recordDeletionsByKey.keys).forEach { mediaKey ->
            val localRecord = getByMediaKey(mediaKey).executeAsOneOrNull()
            val candidateRecord = recordsByKey[mediaKey]
            val mergedRecord = candidateRecord?.let { mergePlaybackRecordDimensions(localRecord, it) } ?: localRecord
            val localDeletion = getPlaybackRecordTombstone(mediaKey).executeAsOneOrNull()?.toDeletion()
            val candidateDeletion = recordDeletionsByKey[mediaKey]
            val deletion = when {
                candidateDeletion == null -> localDeletion
                else -> newerRecordDeletion(localDeletion, candidateDeletion)
            }

            if (deletion != null && (mergedRecord == null || deletion.syncVersion >= mergedRecord.existenceSyncVersion())) {
                if (localRecord != null) deleteByKey(mediaKey)
                if (localDeletion != deletion) {
                    writeRecordDeletion(deletion)
                    mergedRecordDeletions++
                }
            } else if (mergedRecord != null) {
                if (localDeletion != null) deletePlaybackRecordTombstone(mediaKey)
                if (localRecord != mergedRecord) {
                    writeSyncRecord(mergedRecord)
                    mergedRecords++
                }
            }
        }

        val progressByKey = linkedMapOf<String, EpisodeProgress>()
        batch.episodeProgress.forEach { candidate ->
            val key = progressDeletionKey(candidate.tmdb_id, candidate.season_number, candidate.episode_number)
            progressByKey[key] = mergeEpisodeProgress(progressByKey[key], candidate)
        }
        val progressDeletionsByKey = linkedMapOf<String, EpisodeProgressDeletion>()
        batch.progressDeletions.forEach { candidate ->
            val key = progressDeletionKey(candidate.tmdbId, candidate.seasonNumber, candidate.episodeNumber)
            progressDeletionsByKey[key] = newerProgressDeletion(progressDeletionsByKey[key], candidate)
        }

        (progressByKey.keys + progressDeletionsByKey.keys).forEach { key ->
            val candidateProgress = progressByKey[key]
            val candidateDeletion = progressDeletionsByKey[key]
            val tmdbId = candidateProgress?.tmdb_id ?: candidateDeletion!!.tmdbId
            val seasonNumber = candidateProgress?.season_number ?: candidateDeletion!!.seasonNumber
            val episodeNumber = candidateProgress?.episode_number ?: candidateDeletion!!.episodeNumber
            val localProgress = episodeProgressGetByTriple(tmdbId, seasonNumber, episodeNumber).executeAsOneOrNull()
            val merged = candidateProgress?.let { mergeEpisodeProgress(localProgress, it) } ?: localProgress
            val localDeletion = getEpisodeProgressTombstone(tmdbId, seasonNumber, episodeNumber)
                .executeAsOneOrNull()?.toDeletion()
            val deletion = when {
                candidateDeletion == null -> localDeletion
                else -> newerProgressDeletion(localDeletion, candidateDeletion)
            }

            if (deletion != null && (merged == null || deletion.syncVersion >= merged.sync_version)) {
                if (localProgress != null) episodeProgressDeleteByTriple(tmdbId, seasonNumber, episodeNumber)
                if (localDeletion != deletion) {
                    writeProgressDeletion(deletion)
                    mergedProgressDeletions++
                }
            } else if (merged != null) {
                if (localDeletion != null) deleteEpisodeProgressTombstone(tmdbId, seasonNumber, episodeNumber)
                if (localProgress != merged) {
                    writeSyncProgress(merged)
                    mergedProgress++
                }
            }
        }

        result = PlaybackSyncMergeResult(
            mergedRecords = mergedRecords,
            mergedProgress = mergedProgress,
            mergedRecordDeletions = mergedRecordDeletions,
            mergedProgressDeletions = mergedProgressDeletions,
        )
        afterCommit(onCommitted)
    }
    return result
}

internal fun PlaybackQueries.writeSyncRecord(record: PlaybackRecord) {
    upsertSyncForceUpdate(
        source_kind = record.source_kind,
        url = record.url,
        title = record.title,
        position_ms = record.position_ms,
        duration_ms = record.duration_ms,
        watch_progress = record.watch_progress,
        is_completed = record.is_completed,
        tmdb_id = record.tmdb_id,
        season_number = record.season_number,
        episode_number = record.episode_number,
        danmaku_episode_id = record.danmaku_episode_id,
        danmaku_anime_id = record.danmaku_anime_id,
        danmaku_anime_title = record.danmaku_anime_title,
        danmaku_episode_title = record.danmaku_episode_title,
        danmaku_match_method = record.danmaku_match_method,
        danmaku_sync_version = record.danmaku_sync_version,
        danmaku_updated_at = record.danmaku_updated_at,
        last_played_at = record.last_played_at,
        sync_version = record.sync_version,
        media_key = record.media_key,
    )
    upsertSyncInsertIfAbsent(
        media_key = record.media_key,
        source_kind = record.source_kind,
        url = record.url,
        title = record.title,
        position_ms = record.position_ms,
        duration_ms = record.duration_ms,
        watch_progress = record.watch_progress,
        is_completed = record.is_completed,
        tmdb_id = record.tmdb_id,
        season_number = record.season_number,
        episode_number = record.episode_number,
        danmaku_episode_id = record.danmaku_episode_id,
        danmaku_anime_id = record.danmaku_anime_id,
        danmaku_anime_title = record.danmaku_anime_title,
        danmaku_episode_title = record.danmaku_episode_title,
        danmaku_match_method = record.danmaku_match_method,
        danmaku_sync_version = record.danmaku_sync_version,
        danmaku_updated_at = record.danmaku_updated_at,
        last_played_at = record.last_played_at,
        sync_version = record.sync_version,
    )
}

internal fun PlaybackQueries.writeSyncProgress(progress: EpisodeProgress) {
    episodeProgressSyncForceUpdate(
        media_key = progress.media_key,
        position_ms = progress.position_ms,
        duration_ms = progress.duration_ms,
        watch_progress = progress.watch_progress,
        is_completed = progress.is_completed,
        last_played_at = progress.last_played_at,
        sync_version = progress.sync_version,
        tmdb_id = progress.tmdb_id,
        season_number = progress.season_number,
        episode_number = progress.episode_number,
    )
    episodeProgressSyncInsertIfAbsent(
        tmdb_id = progress.tmdb_id,
        season_number = progress.season_number,
        episode_number = progress.episode_number,
        media_key = progress.media_key,
        position_ms = progress.position_ms,
        duration_ms = progress.duration_ms,
        watch_progress = progress.watch_progress,
        is_completed = progress.is_completed,
        last_played_at = progress.last_played_at,
        sync_version = progress.sync_version,
    )
}

internal fun PlaybackQueries.writeRecordDeletion(deletion: PlaybackRecordDeletion) {
    upsertPlaybackRecordTombstone(
        media_key = deletion.mediaKey,
        media_identity = deletion.mediaIdentity,
        deleted_at = deletion.deletedAt,
        sync_version = deletion.syncVersion,
    )
}

internal fun PlaybackQueries.writeProgressDeletion(deletion: EpisodeProgressDeletion) {
    upsertEpisodeProgressTombstone(
        tmdb_id = deletion.tmdbId,
        season_number = deletion.seasonNumber,
        episode_number = deletion.episodeNumber,
        media_key = deletion.mediaKey,
        media_identity = deletion.mediaIdentity,
        deleted_at = deletion.deletedAt,
        sync_version = deletion.syncVersion,
    )
}

/** 单条删除在同一事务内先建立 tombstone，再移除 active 行。 */
internal fun PlaybackQueries.deletePlaybackWithTombstones(mediaKey: String, deletedAt: Long) {
    val record = getByMediaKey(mediaKey).executeAsOneOrNull()
    val existingRecordDeletion = getPlaybackRecordTombstone(mediaKey).executeAsOneOrNull()?.toDeletion()
    if (record != null || existingRecordDeletion != null) {
        writeRecordDeletion(
            PlaybackRecordDeletion(
                mediaKey = mediaKey,
                mediaIdentity = existingRecordDeletion?.mediaIdentity,
                deletedAt = deletedAt,
                syncVersion = maxOf(
                    record?.existenceSyncVersion() ?: -1L,
                    existingRecordDeletion?.syncVersion ?: -1L,
                ) + 1L,
            ),
        )
    }

    episodeProgressListByMediaKey(mediaKey).executeAsList().forEach { progress ->
        val existing = getEpisodeProgressTombstone(
            progress.tmdb_id,
            progress.season_number,
            progress.episode_number,
        ).executeAsOneOrNull()?.toDeletion()
        writeProgressDeletion(
            EpisodeProgressDeletion(
                tmdbId = progress.tmdb_id,
                seasonNumber = progress.season_number,
                episodeNumber = progress.episode_number,
                mediaKey = mediaKey,
                mediaIdentity = existing?.mediaIdentity,
                deletedAt = deletedAt,
                syncVersion = maxOf(progress.sync_version, existing?.syncVersion ?: -1L) + 1L,
            ),
        )
    }
    deleteByKey(mediaKey)
    episodeProgressDeleteByMediaKey(mediaKey)
}

/** clear-all 以 epoch 表达，旧设备的全部低 epoch 快照都会被忽略。 */
internal fun PlaybackQueries.clearPlaybackHistoryAndAdvanceEpoch() {
    incrementPlaybackHistoryEpoch()
    deleteAll()
    episodeProgressDeleteAll()
    deleteAllPlaybackRecordTombstones()
    deleteAllEpisodeProgressTombstones()
}

/** 新播放/新匹配只有在逻辑版本严格超过 tombstone 后才允许清除删除事件。 */
internal fun PlaybackQueries.clearDefeatedRecordDeletion(mediaKey: String) {
    val record = getByMediaKey(mediaKey).executeAsOneOrNull() ?: return
    val deletion = getPlaybackRecordTombstone(mediaKey).executeAsOneOrNull() ?: return
    if (record.existenceSyncVersion() > deletion.sync_version) deletePlaybackRecordTombstone(mediaKey)
}

internal fun PlaybackQueries.clearDefeatedProgressDeletion(tmdbId: Long, seasonNumber: Long, episodeNumber: Long) {
    val progress = episodeProgressGetByTriple(tmdbId, seasonNumber, episodeNumber).executeAsOneOrNull() ?: return
    val deletion = getEpisodeProgressTombstone(tmdbId, seasonNumber, episodeNumber).executeAsOneOrNull() ?: return
    if (progress.sync_version > deletion.sync_version) {
        deleteEpisodeProgressTombstone(tmdbId, seasonNumber, episodeNumber)
    }
}

private fun PlaybackRecordTombstone.toDeletion(): PlaybackRecordDeletion = PlaybackRecordDeletion(
    mediaKey = media_key,
    mediaIdentity = media_identity,
    deletedAt = deleted_at,
    syncVersion = sync_version,
)

private fun EpisodeProgressTombstone.toDeletion(): EpisodeProgressDeletion = EpisodeProgressDeletion(
    tmdbId = tmdb_id,
    seasonNumber = season_number,
    episodeNumber = episode_number,
    mediaKey = media_key,
    mediaIdentity = media_identity,
    deletedAt = deleted_at,
    syncVersion = sync_version,
)
