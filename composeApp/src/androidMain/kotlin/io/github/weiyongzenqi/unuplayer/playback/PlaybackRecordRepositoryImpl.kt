package io.github.weiyongzenqi.unuplayer.playback

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 播放记录仓库 SQLDelight 实现(androidMain)。
 *
 * 单例: 经 UnuDatabaseProvider 取进程级共享 driver(同库共享 WAL/外键配置)。数据库文件 unu_playback.db。
 * 所有查询走 IO 调度器, 不阻塞 UI。
 */
class PlaybackRecordRepositoryImpl private constructor(
    private val queries: PlaybackQueries,
) : PlaybackRecordRepository {

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
            queries.finishPlayback(
                position_ms = positionMs,
                duration_ms = durationMs,
                watch_progress = watchProgress,
                is_completed = isCompleted,
                last_played_at = lastPlayedAt,
                media_key = mediaKey,
            )
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
                    danmaku_episode_id = record.danmaku_episode_id,
                    danmaku_anime_id = record.danmaku_anime_id,
                    danmaku_anime_title = record.danmaku_anime_title,
                    danmaku_episode_title = record.danmaku_episode_title,
                    danmaku_match_method = record.danmaku_match_method,
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
                    danmaku_episode_id = record.danmaku_episode_id,
                    danmaku_anime_id = record.danmaku_anime_id,
                    danmaku_anime_title = record.danmaku_anime_title,
                    danmaku_episode_title = record.danmaku_episode_title,
                    danmaku_match_method = record.danmaku_match_method,
                    last_played_at = record.last_played_at,
                    sync_status = record.sync_status,
                    sync_version = record.sync_version,
                )
            }
        }
    }

    override suspend fun updatePosition(
        mediaKey: String, positionMs: Long, watchProgress: Double, lastPlayedAt: Long,
    ) {
        withContext(Dispatchers.IO) {
            queries.updatePosition(
                position_ms = positionMs,
                watch_progress = watchProgress,
                last_played_at = lastPlayedAt,
                media_key = mediaKey,
            )
        }
    }

    override suspend fun updateDanmaku(
        mediaKey: String, episodeId: Long, animeId: Long,
        animeTitle: String, episodeTitle: String, matchMethod: String,
    ) {
        withContext(Dispatchers.IO) {
            queries.updateDanmaku(
                danmaku_episode_id = episodeId,
                danmaku_anime_id = animeId,
                danmaku_anime_title = animeTitle,
                danmaku_episode_title = episodeTitle,
                danmaku_match_method = matchMethod,
                media_key = mediaKey,
            )
        }
    }

    override suspend fun listPage(limit: Long, offset: Long): List<PlaybackRecord> =
        withContext(Dispatchers.IO) { queries.listPage(limit, offset).executeAsList() }

    override suspend fun deleteByKey(mediaKey: String) {
        withContext(Dispatchers.IO) { queries.deleteByKey(mediaKey) }
    }

    override suspend fun deleteAll() {
        withContext(Dispatchers.IO) { queries.deleteAll() }
    }

    override suspend fun count(): Long =
        withContext(Dispatchers.IO) { queries.count().executeAsOne() }

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
