package io.github.weiyongzenqi.unuplayer.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 播放记录仓库 SQLDelight 实现(desktopMain/JDBC)。
 *
 * 单例: 经 UnuDatabaseProvider 取进程级共享 driver(同库共享 WAL/外键配置)。数据库文件 unu_playback.db。
 * 所有查询走 IO 调度器, 不阻塞 UI。
 *
 * 写串行化(CR-064): upsert/updateDanmaku/updatePosition/finishPlayback 共享同一 [writeMutex],
 * 对齐 Android AndroidPlayerLifecycleTasks.runSerialized 语义。桌面侧两个独立 LaunchedEffect
 * (建记录 upsert + 弹幕匹配 updateDanmaku) 会并发调用仓库, 不串行化时 upsert 用陈旧 existing
 * 执行 upsertUpdateIfNewer 会覆盖 updateDanmaku 刚写入的弹幕匹配字段, 下次播放重新走完整匹配链。
 * 用 Mutex.withLock suspend 语义(不 runBlocking, 避免 PF-007 同款背压)。
 */
class PlaybackRecordRepositoryImpl internal constructor(
    private val queries: PlaybackQueries,
) : PlaybackRecordRepository {

    private val writeMutex = Mutex()

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
        writeMutex.withLock {
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
    }

    override suspend fun upsert(record: PlaybackRecord) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                // P3㉓: 事务内两段 upsert(先逐字段 update 后 insert-if-absent), id 保持稳定。
                // 原 INSERT OR REPLACE 冲突时删旧插新致 id 抖动; 不用 ON CONFLICT DO UPDATE 是为与
                // androidMain 系统 SQLite(API26=3.18)保持同一套全版本兼容语句。语义见 playback.sq 注释。
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
    }

    override suspend fun updatePosition(
        mediaKey: String, positionMs: Long, watchProgress: Double, lastPlayedAt: Long,
    ) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                queries.updatePosition(
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
        writeMutex.withLock {
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

        /** 进程级单例。首次打开 desktopMain/JDBC SQLite 数据库。 */
        fun get(): PlaybackRecordRepositoryImpl =
            instance ?: synchronized(this) {
                instance ?: run {
                val database = UnuDatabaseProvider.get()
                PlaybackRecordRepositoryImpl(database.playbackQueries).also { instance = it }
            }
            }
    }
}
