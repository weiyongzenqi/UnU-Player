package io.github.weiyongzenqi.unuplayer.playback

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis

/**
 * 播放记录仓库 SQLDelight 实现(androidMain)。
 *
 * 单例: 经 UnuDatabaseProvider 取进程级共享 driver(同库共享 WAL/外键配置)。数据库文件 unu_playback.db。
 * 所有查询走 IO 调度器, 不阻塞 UI。
 */
class PlaybackRecordRepositoryImpl private constructor(
    private val queries: PlaybackQueries,
) : PlaybackRecordRepository {
    private val mutableChangeVersion = MutableStateFlow(0L)
    override val changeVersion = mutableChangeVersion.asStateFlow()

    private fun notifyChanged() {
        mutableChangeVersion.update { it + 1L }
    }

    override suspend fun getByMediaKey(mediaKey: String): PlaybackRecord? =
        withContext(Dispatchers.IO) { queries.getByMediaKey(mediaKey).executeAsOneOrNull() }

    override suspend fun getByMediaKeys(mediaKeys: List<String>): Map<String, PlaybackRecord> =
        withContext(Dispatchers.IO) {
            // 分块查: SQLite SQLITE_LIMIT_VARIABLE_NUMBER 在 API26-30 为 999, 大目录(>999 文件)
            // 的 IN :media_keys 会崩。每批 ≤500 合并, 避开限制。
            if (mediaKeys.isEmpty()) emptyMap()
            else mediaKeys.chunked(500).flatMap { batch ->
                queries.getByMediaKeys(batch).executeAsList()
            }.associateBy { it.media_key }
        }

    override suspend fun finishPlayback(
        mediaKey: String, positionMs: Long, durationMs: Long,
        watchProgress: Double, isCompleted: Long, lastPlayedAt: Long,
    ) {
        withContext(Dispatchers.IO) {
            // 双表写同事务: 与 deleteByKey/deleteAll 的 T1-m1 纪律一致, 避免进程在两条之间
            // 被杀留两表 last_played_at 不一致(续播决策读到混合状态)。
            queries.transaction {
                queries.finishPlayback(
                    position_ms = positionMs,
                    duration_ms = durationMs,
                    watch_progress = watchProgress,
                    is_completed = isCompleted,
                    last_played_at = lastPlayedAt,
                    media_key = mediaKey,
                )
                queries.episodeProgressFinish(
                    position_ms = positionMs,
                    duration_ms = durationMs,
                    watch_progress = watchProgress,
                    is_completed = isCompleted,
                    last_played_at = lastPlayedAt,
                    media_key = mediaKey,
                )
                afterCommit(::notifyChanged)
            }
        }
    }

    override suspend fun upsert(record: PlaybackRecord) {
        withContext(Dispatchers.IO) {
            // P3㉓: 事务内两段 upsert(先逐字段 update 后 insert-if-absent), id 保持稳定。
            // 原 INSERT OR REPLACE 冲突时删旧插新致 id 抖动; ON CONFLICT DO UPDATE 需 SQLite 3.24+
            // 而 API26 系统库仅 3.18, 故用全版本兼容的 INSERT OR IGNORE + UPDATE 组合。语义见 playback.sq 注释。
            queries.transaction {
                queries.upsertUpdateIfNewer(
                    source_kind = record.source_kind,
                    url = record.url,
                    content_uri = record.content_uri,
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
                    sync_status = record.sync_status,
                    sync_version = record.sync_version,
                    media_key = record.media_key,
                )
                queries.upsertInsertIfAbsent(
                    media_key = record.media_key,
                    source_kind = record.source_kind,
                    url = record.url,
                    content_uri = record.content_uri,
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
                    sync_status = record.sync_status,
                    sync_version = record.sync_version,
                )
                // EpisodeProgress 双写: 三元组非 null 且 episode>0 时才写语义进度表
                val tmdbId = record.tmdb_id
                val season = record.season_number
                val ep = record.episode_number
                if (tmdbId != null && season != null && ep != null && ep > 0L) {
                    queries.episodeProgressUpsertUpdateIfNewer(
                        tmdb_id = tmdbId, season_number = season, episode_number = ep,
                        media_key = record.media_key,
                        position_ms = record.position_ms, duration_ms = record.duration_ms,
                        watch_progress = record.watch_progress, is_completed = record.is_completed,
                        last_played_at = record.last_played_at,
                        sync_status = record.sync_status, sync_version = record.sync_version,
                    )
                    queries.episodeProgressUpsertInsertIfAbsent(
                        tmdb_id = tmdbId, season_number = season, episode_number = ep,
                        media_key = record.media_key,
                        position_ms = record.position_ms, duration_ms = record.duration_ms,
                        watch_progress = record.watch_progress, is_completed = record.is_completed,
                        last_played_at = record.last_played_at,
                        sync_status = record.sync_status, sync_version = record.sync_version,
                    )
                }
            }
        }
    }

    /** B-1: 播放入口专用 upsert, sync_version 由 SQL 原子 +1(见 playback.sq upsertEntry*)。 */
    override suspend fun upsertEntry(record: PlaybackRecord) {
        withContext(Dispatchers.IO) {
            queries.transaction {
                queries.upsertEntryUpdateIfNewer(
                    source_kind = record.source_kind,
                    url = record.url,
                    content_uri = record.content_uri,
                    title = record.title,
                    position_ms = record.position_ms,
                    duration_ms = record.duration_ms,
                    watch_progress = record.watch_progress,
                    is_completed = record.is_completed,
                    tmdb_id = record.tmdb_id,
                    season_number = record.season_number,
                    episode_number = record.episode_number,
                    last_played_at = record.last_played_at,
                    sync_status = record.sync_status,
                    media_key = record.media_key,
                )
                queries.upsertEntryInsertIfAbsent(
                    media_key = record.media_key,
                    source_kind = record.source_kind,
                    url = record.url,
                    content_uri = record.content_uri,
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
                    sync_status = record.sync_status,
                )
                queries.clearDefeatedRecordDeletion(record.media_key)
                // EpisodeProgress 双写: 三元组非 null 且 episode>0 时才写语义进度表。
                // 三元组版本由其自身行原子 +1；切换新 media_key 时不得复制从 1 起步的主行版本。
                val tmdbId = record.tmdb_id
                val season = record.season_number
                val ep = record.episode_number
                if (tmdbId != null && season != null && ep != null && ep > 0L) {
                    queries.episodeProgressUpsertEntryUpdateIfNewer(
                        tmdb_id = tmdbId, season_number = season, episode_number = ep,
                        media_key = record.media_key,
                        position_ms = record.position_ms, duration_ms = record.duration_ms,
                        watch_progress = record.watch_progress, is_completed = record.is_completed,
                        last_played_at = record.last_played_at,
                        sync_status = record.sync_status,
                    )
                    queries.episodeProgressUpsertEntryInsertIfAbsent(
                        tmdb_id = tmdbId, season_number = season, episode_number = ep,
                        media_key = record.media_key,
                        position_ms = record.position_ms, duration_ms = record.duration_ms,
                        watch_progress = record.watch_progress, is_completed = record.is_completed,
                        last_played_at = record.last_played_at,
                        sync_status = record.sync_status,
                    )
                    queries.clearDefeatedProgressDeletion(tmdbId, season, ep)
                }
            }
        }
    }

    override suspend fun updatePosition(
        mediaKey: String, positionMs: Long, watchProgress: Double, lastPlayedAt: Long,
    ) {
        withContext(Dispatchers.IO) {
            // 双表写同事务(同 finishPlayback 的 T1-m1 一致性纪律)。
            queries.transaction {
                queries.updatePosition(
                    position_ms = positionMs,
                    watch_progress = watchProgress,
                    last_played_at = lastPlayedAt,
                    media_key = mediaKey,
                )
                queries.episodeProgressUpdatePosition(
                    position_ms = positionMs,
                    watch_progress = watchProgress,
                    last_played_at = lastPlayedAt,
                    media_key = mediaKey,
                )
            }
        }
    }

    override suspend fun updateDanmaku(
        mediaKey: String, episodeId: Long, animeId: Long,
        animeTitle: String, episodeTitle: String, matchMethod: String,
    ) {
        withContext(Dispatchers.IO) {
            queries.transaction {
                queries.updateDanmaku(
                    danmaku_episode_id = episodeId,
                    danmaku_anime_id = animeId,
                    danmaku_anime_title = animeTitle,
                    danmaku_episode_title = episodeTitle,
                    danmaku_match_method = matchMethod,
                    danmaku_updated_at = platformTimeMillis(),
                    media_key = mediaKey,
                )
                queries.clearDefeatedRecordDeletion(mediaKey)
            }
        }
    }

    override suspend fun listPage(limit: Long, offset: Long): List<PlaybackRecord> =
        withContext(Dispatchers.IO) { queries.listPage(limit, offset).executeAsList() }

    override suspend fun getEpisodeProgressByTriple(tmdbId: Long, seasonNumber: Long, episodeNumber: Long): EpisodeProgress? =
        withContext(Dispatchers.IO) { queries.episodeProgressGetByTriple(tmdbId, seasonNumber, episodeNumber).executeAsOneOrNull() }

    override suspend fun getEpisodeProgressByTriples(tripleKeys: List<String>): Map<String, EpisodeProgress> =
        withContext(Dispatchers.IO) {
            if (tripleKeys.isEmpty()) emptyMap()
            else tripleKeys.chunked(500).flatMap { batch ->
                queries.episodeProgressGetByTriples(batch).executeAsList()
            }.associateBy { "${it.tmdb_id}-${it.season_number}-${it.episode_number}" }
        }

    override suspend fun deleteByKey(mediaKey: String) {
        withContext(Dispatchers.IO) {
            // 两条 DELETE 同事务: 避免进程在两条之间被杀留 EpisodeProgress 孤儿行,
            // 致清历史后重播同集仍从旧进度续播(T1-m1)。
            queries.transaction {
                queries.deletePlaybackWithTombstones(mediaKey, platformTimeMillis())
                afterCommit(::notifyChanged)
            }
        }
    }

    override suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            // 两条 DELETE 同事务: 避免进程在两条之间被杀留 EpisodeProgress 孤儿行(T1-m1)。
            queries.transaction {
                queries.clearPlaybackHistoryAndAdvanceEpoch()
                afterCommit(::notifyChanged)
            }
        }
    }

    override suspend fun count(): Long =
        withContext(Dispatchers.IO) { queries.count().executeAsOne() }

    override suspend fun listAll(): List<PlaybackRecord> =
        withContext(Dispatchers.IO) { queries.listAll().executeAsList() }

    override suspend fun listAllEpisodeProgress(): List<EpisodeProgress> =
        withContext(Dispatchers.IO) { queries.episodeProgressListAll().executeAsList() }

    override suspend fun getPlaybackHistoryEpoch(): Long =
        withContext(Dispatchers.IO) { queries.getPlaybackHistoryEpoch().executeAsOne() }

    override suspend fun listPlaybackRecordDeletions(): List<PlaybackRecordDeletion> =
        withContext(Dispatchers.IO) {
            queries.listPlaybackRecordTombstones().executeAsList().map {
                PlaybackRecordDeletion(it.media_key, it.media_identity, it.deleted_at, it.sync_version)
            }
        }

    override suspend fun listEpisodeProgressDeletions(): List<EpisodeProgressDeletion> =
        withContext(Dispatchers.IO) {
            queries.listEpisodeProgressTombstones().executeAsList().map {
                EpisodeProgressDeletion(
                    it.tmdb_id, it.season_number, it.episode_number,
                    it.media_key, it.media_identity, it.deleted_at, it.sync_version,
                )
            }
        }

    override suspend fun applySyncMergeBatch(batch: PlaybackSyncMergeBatch): PlaybackSyncMergeResult =
        withContext(Dispatchers.IO) { queries.applyPlaybackSyncMergeBatch(batch, ::notifyChanged) }

    override suspend fun applyMergedRecord(record: PlaybackRecord) {
        withContext(Dispatchers.IO) {
            queries.transaction {
                queries.writeSyncRecord(record)
                queries.deletePlaybackRecordTombstone(record.media_key)
                afterCommit(::notifyChanged)
            }
        }
    }

    override suspend fun applyMergedEpisodeProgress(progress: EpisodeProgress) {
        withContext(Dispatchers.IO) {
            queries.transaction {
                queries.writeSyncProgress(progress)
                queries.deleteEpisodeProgressTombstone(
                    progress.tmdb_id,
                    progress.season_number,
                    progress.episode_number,
                )
                afterCommit(::notifyChanged)
            }
        }
    }

    /** 版本比较后原子合并: 事务内 读-判-写, 避免快照读+内存判断的并发窗口。 */
    override suspend fun applyMergedRecordIfNewer(record: PlaybackRecord): Boolean {
        val epoch = getPlaybackHistoryEpoch()
        return applySyncMergeBatch(
            PlaybackSyncMergeBatch(epoch, listOf(record), emptyList(), emptyList(), emptyList()),
        ).mergedRecords > 0
    }

    override suspend fun applyMergedEpisodeProgressIfNewer(progress: EpisodeProgress): Boolean {
        val epoch = getPlaybackHistoryEpoch()
        return applySyncMergeBatch(
            PlaybackSyncMergeBatch(epoch, emptyList(), listOf(progress), emptyList(), emptyList()),
        ).mergedProgress > 0
    }

    companion object {
        @Volatile private var instance: PlaybackRecordRepositoryImpl? = null

        /** 进程级单例。首次用 [context] 建 driver+打开数据库, 后续忽略 context。 */
        fun get(context: Context): PlaybackRecordRepositoryImpl =
            instance ?: synchronized(this) {
                instance ?: run {
                val database = UnuDatabaseProvider.get(context)
                PlaybackRecordRepositoryImpl(database.playbackQueries).also { instance = it }
            }
            }
    }
}
