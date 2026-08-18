package io.github.weiyongzenqi.unuplayer.ui.player

import io.github.weiyongzenqi.unuplayer.playback.EpisodeProgress
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * B-09(桌面版) 防护契约回归锚。
 *
 * 背景(P1-2): 播放器 LaunchedEffect 曾裸调 repo, SQLite 异常(磁盘满/DB 损坏)穿透
 * Recomposer 会让 Compose Desktop 无处理器而进程退出。修复后统一经
 * safeReadPlaybackRecord/safeRecordWrite, 本测试钉住三条契约:
 * 读失败降级 null 不抛 / 写失败跳过不抛 / 取消异常必须重抛(不误吞协程取消)。
 */
class DesktopPlaybackRecordSafetyTest {

    @Test
    fun `读取抛 SQLite 异常时降级为 null 不向调用方抛`() = runBlocking {
        val repo = ThrowingRepository(SqliteFailure)
        assertNull(safeReadPlaybackRecord(repo, "key", null))
    }

    @Test
    fun `读取成功原样返回记录`() = runBlocking {
        val repo = ReturningRepository(SqliteFailure)
        assertEquals("key", safeReadPlaybackRecord(repo, "key", null)?.media_key)
    }

    @Test
    fun `写入抛 SQLite 异常时跳过且不向调用方抛`() = runBlocking {
        val repo = ThrowingRepository(SqliteFailure)
        safeRecordWrite(null, "进度更新") {
            repo.updatePosition("key", 1L, 0.5, 1L)
        }
        safeRecordWrite(null, "初始化写入") {
            repo.upsert(ThrowingRepository.record("key"))
        }
        // 未抛异常即通过
    }

    @Test
    fun `写入成功时执行写操作`() = runBlocking {
        val repo = ReturningRepository(SqliteFailure)
        safeRecordWrite(null, "进度更新") {
            repo.updatePosition("key", 1L, 0.5, 1L)
        }
        assertEquals(1, repo.positionWrites)
    }

    @Test
    fun `取消异常必须重抛不被防护吞掉`() {
        runBlocking {
            val repo = ThrowingRepository(CancellationException("cancel"))
            assertFailsWith<CancellationException> {
                safeReadPlaybackRecord(repo, "key", null)
            }
            assertFailsWith<CancellationException> {
                safeRecordWrite(null, "进度更新") {
                    repo.updatePosition("key", 1L, 0.5, 1L)
                }
            }
        }
    }

    private val SqliteFailure = IllegalStateException("db broken")

    private open class ThrowingRepository(
        private val failure: Throwable,
    ) : PlaybackRecordRepository {
        override suspend fun getByMediaKey(mediaKey: String): PlaybackRecord? {
            throw failure
        }

        override suspend fun updatePosition(mediaKey: String, positionMs: Long, watchProgress: Double, lastPlayedAt: Long) {
            throw failure
        }

        override suspend fun upsert(record: PlaybackRecord) {
            throw failure
        }

        override suspend fun upsertEntry(record: PlaybackRecord) {
            throw failure
        }

        override suspend fun updateDanmaku(mediaKey: String, episodeId: Long, animeId: Long, animeTitle: String, episodeTitle: String, matchMethod: String) {
            throw failure
        }

        override val changeVersion: StateFlow<Long> = MutableStateFlow(0L)
        override suspend fun getByMediaKeys(mediaKeys: List<String>): Map<String, PlaybackRecord> = emptyMap()
        override suspend fun finishPlayback(mediaKey: String, positionMs: Long, durationMs: Long, watchProgress: Double, isCompleted: Long, lastPlayedAt: Long) {
            throw failure
        }
        override suspend fun listPage(limit: Long, offset: Long): List<PlaybackRecord> = emptyList()
        override suspend fun getEpisodeProgressByTriple(tmdbId: Long, seasonNumber: Long, episodeNumber: Long): EpisodeProgress? = null
        override suspend fun getEpisodeProgressByTriples(tripleKeys: List<String>): Map<String, EpisodeProgress> = emptyMap()
        override suspend fun deleteByKey(mediaKey: String) {}
        override suspend fun deleteAll() {}
        override suspend fun count(): Long = 0L
        override suspend fun listAll(): List<PlaybackRecord> = emptyList()
        override suspend fun listAllEpisodeProgress(): List<EpisodeProgress> = emptyList()
        override suspend fun applyMergedRecord(record: PlaybackRecord) {}
        override suspend fun applyMergedEpisodeProgress(progress: EpisodeProgress) {}
        override suspend fun applyMergedRecordIfNewer(record: PlaybackRecord): Boolean = false
        override suspend fun applyMergedEpisodeProgressIfNewer(progress: EpisodeProgress): Boolean = false

        companion object {
            fun record(mediaKey: String): PlaybackRecord = PlaybackRecord(
                id = 0,
                media_key = mediaKey,
                source_kind = "WEBDAV",
                url = "http://example.com/video.mkv",
                content_uri = null,
                title = "video.mkv",
                position_ms = 0,
                duration_ms = 100_000,
                watch_progress = 0.0,
                is_completed = 0,
                tmdb_id = null,
                season_number = null,
                episode_number = null,
                danmaku_episode_id = null,
                danmaku_anime_id = null,
                danmaku_anime_title = null,
                danmaku_episode_title = null,
                danmaku_match_method = null,
                last_played_at = 0,
                sync_status = 0,
                sync_version = 0,
                danmaku_sync_version = 0,
                danmaku_updated_at = 0,
            )
        }
    }

    private class ReturningRepository(
        private val failure: Throwable,
    ) : ThrowingRepository(failure) {
        override suspend fun getByMediaKey(mediaKey: String): PlaybackRecord? = ThrowingRepository.record(mediaKey)
        override suspend fun updatePosition(mediaKey: String, positionMs: Long, watchProgress: Double, lastPlayedAt: Long) {
            positionWrites++
        }
        override suspend fun upsert(record: PlaybackRecord) {}
        override suspend fun upsertEntry(record: PlaybackRecord) {}
        override suspend fun updateDanmaku(mediaKey: String, episodeId: Long, animeId: Long, animeTitle: String, episodeTitle: String, matchMethod: String) {}

        var positionWrites = 0
    }
}
