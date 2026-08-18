package io.github.weiyongzenqi.unuplayer.playback

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CR-162/164/167：同步合并、删除事件和 epoch 的数据库边界回归。 */
class PlaybackSyncMergeTest {

    @Test
    fun `进度和弹幕使用独立版本且互不覆盖`() = runBlocking {
        runWithRepository { repo, _ ->
        val mediaKey = "sync:dimensions"
        repo.applyMergedRecord(
            record(mediaKey, positionMs = 50_000, syncVersion = 5, danmakuId = 10, danmakuVersion = 2, danmakuUpdatedAt = 100),
        )

        repo.applySyncMergeBatch(
            PlaybackSyncMergeBatch(
                historyEpoch = repo.getPlaybackHistoryEpoch(),
                records = listOf(
                    record(mediaKey, positionMs = 60_000, syncVersion = 6, danmakuId = 20, danmakuVersion = 1, danmakuUpdatedAt = 200),
                ),
                episodeProgress = emptyList(),
                recordDeletions = emptyList(),
                progressDeletions = emptyList(),
            ),
        )
        val progressWins = assertNotNull(repo.getByMediaKey(mediaKey))
        assertEquals(60_000L, progressWins.position_ms)
        assertEquals(6L, progressWins.sync_version)
        assertEquals(10L, progressWins.danmaku_episode_id)
        assertEquals(2L, progressWins.danmaku_sync_version)

        repo.applySyncMergeBatch(
            PlaybackSyncMergeBatch(
                historyEpoch = repo.getPlaybackHistoryEpoch(),
                records = listOf(
                    record(mediaKey, positionMs = 40_000, syncVersion = 4, danmakuId = 30, danmakuVersion = 3, danmakuUpdatedAt = 300),
                ),
                episodeProgress = emptyList(),
                recordDeletions = emptyList(),
                progressDeletions = emptyList(),
            ),
        )
        val danmakuWins = assertNotNull(repo.getByMediaKey(mediaKey))
        assertEquals(60_000L, danmakuWins.position_ms)
        assertEquals(6L, danmakuWins.sync_version)
        assertEquals(30L, danmakuWins.danmaku_episode_id)
        assertEquals(3L, danmakuWins.danmaku_sync_version)
        }
    }

    @Test
    fun `记录删除跨设备不复活且新播放版本严格越过 tombstone`() = runBlocking {
        runWithRepository { repo, _ ->
        val mediaKey = "sync:delete"
        repo.applyMergedRecord(record(mediaKey, positionMs = 10_000, syncVersion = 3))
        repo.deleteByKey(mediaKey)
        assertNull(repo.getByMediaKey(mediaKey))
        assertEquals(1, repo.listPlaybackRecordDeletions().size)
        assertEquals(4L, repo.listPlaybackRecordDeletions().single().syncVersion)

        suspend fun merge(candidateVersion: Long) {
            repo.applySyncMergeBatch(
                PlaybackSyncMergeBatch(
                    historyEpoch = repo.getPlaybackHistoryEpoch(),
                    records = listOf(record(mediaKey, positionMs = 20_000, syncVersion = candidateVersion)),
                    episodeProgress = emptyList(),
                    recordDeletions = emptyList(),
                    progressDeletions = emptyList(),
                ),
            )
        }

        merge(4)
        assertNull(repo.getByMediaKey(mediaKey), "版本平手时 tombstone 必须胜出")
        assertEquals(4L, repo.listPlaybackRecordDeletions().single().syncVersion)

        merge(5)
        assertNotNull(repo.getByMediaKey(mediaKey))
        assertEquals(5L, repo.getByMediaKey(mediaKey)?.sync_version)
        assertTrue(repo.listPlaybackRecordDeletions().isEmpty())
        }
    }

    @Test
    fun `EpisodeProgress 删除跨设备不复活且新版本清除 tombstone`() = runBlocking {
        runWithRepository { repo, _ ->
        val triple = Triple(42L, 1L, 3L)
        repo.applyMergedEpisodeProgress(progress(triple, mediaKey = "sync:episode", syncVersion = 7))
        repo.deleteByKey("sync:episode")
        assertNull(repo.getEpisodeProgressByTriple(triple.first, triple.second, triple.third))
        assertEquals(8L, repo.listEpisodeProgressDeletions().single().syncVersion)

        repo.applySyncMergeBatch(
            PlaybackSyncMergeBatch(
                historyEpoch = repo.getPlaybackHistoryEpoch(),
                records = emptyList(),
                episodeProgress = listOf(progress(triple, mediaKey = "sync:episode", syncVersion = 8)),
                recordDeletions = emptyList(),
                progressDeletions = emptyList(),
            ),
        )
        assertNull(repo.getEpisodeProgressByTriple(triple.first, triple.second, triple.third))

        repo.applySyncMergeBatch(
            PlaybackSyncMergeBatch(
                historyEpoch = repo.getPlaybackHistoryEpoch(),
                records = emptyList(),
                episodeProgress = listOf(progress(triple, mediaKey = "sync:episode", syncVersion = 9)),
                recordDeletions = emptyList(),
                progressDeletions = emptyList(),
            ),
        )
        assertEquals(9L, repo.getEpisodeProgressByTriple(triple.first, triple.second, triple.third)?.sync_version)
        assertTrue(repo.listEpisodeProgressDeletions().isEmpty())
        }
    }

    @Test
    fun `clear all 推进 epoch 后忽略旧快照`() = runBlocking {
        runWithRepository { repo, _ ->
        val mediaKey = "sync:epoch"
        val oldEpoch = repo.getPlaybackHistoryEpoch()
        repo.applyMergedRecord(record(mediaKey, positionMs = 1_000, syncVersion = 1))
        repo.deleteAll()
        val newEpoch = repo.getPlaybackHistoryEpoch()
        assertEquals(oldEpoch + 1L, newEpoch)

        repo.applySyncMergeBatch(
            PlaybackSyncMergeBatch(
                historyEpoch = oldEpoch,
                records = listOf(record(mediaKey, positionMs = 99_000, syncVersion = 99)),
                episodeProgress = emptyList(),
                recordDeletions = emptyList(),
                progressDeletions = emptyList(),
            ),
        )
        assertNull(repo.getByMediaKey(mediaKey))

        repo.applySyncMergeBatch(
            PlaybackSyncMergeBatch(
                historyEpoch = newEpoch,
                records = listOf(record(mediaKey, positionMs = 2_000, syncVersion = 2)),
                episodeProgress = emptyList(),
                recordDeletions = emptyList(),
                progressDeletions = emptyList(),
            ),
        )
        assertEquals(2_000L, repo.getByMediaKey(mediaKey)?.position_ms)
        }
    }

    @Test
    fun `整批 SQL 失败时 active 和 epoch 整体回滚`() = runBlocking {
        runWithRepository { repo, dataSource ->
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TRIGGER fail_sync_progress
                    BEFORE INSERT ON EpisodeProgress
                    WHEN NEW.tmdb_id = 999
                    BEGIN
                        SELECT RAISE(ABORT, 'injected sync failure');
                    END
                    """.trimIndent(),
                )
            }
        }
        val epoch = repo.getPlaybackHistoryEpoch()
        val failed = try {
            repo.applySyncMergeBatch(
                PlaybackSyncMergeBatch(
                    historyEpoch = epoch + 1,
                    records = listOf(record("sync:rollback", positionMs = 12_000, syncVersion = 1)),
                    episodeProgress = listOf(progress(Triple(999L, 1L, 1L), mediaKey = "sync:rollback", syncVersion = 1)),
                    recordDeletions = emptyList(),
                    progressDeletions = emptyList(),
                ),
            )
            false
        } catch (_: Throwable) {
            true
        }
        assertTrue(failed, "故障注入必须让整批事务失败")
        assertEquals(epoch, repo.getPlaybackHistoryEpoch())
        assertNull(repo.getByMediaKey("sync:rollback"))
        assertFalse(repo.listPlaybackRecordDeletions().any { it.mediaKey == "sync:rollback" })
        }
    }

    private suspend fun runWithRepository(
        block: suspend (PlaybackRecordRepositoryImpl, SQLiteDataSource) -> Unit,
    ) {
        val directory = Files.createTempDirectory("unu-sync-merge-")
        val dataSource = SQLiteDataSource().apply {
            url = "jdbc:sqlite:${directory.resolve("playback.db").toAbsolutePath()}"
        }
        val driver = configuredDesktopDataSource(dataSource).asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            block(PlaybackRecordRepositoryImpl(UnuDatabase(driver).playbackQueries), dataSource)
        } finally {
            runCatching { driver.close() }
            directory.toFile().deleteRecursively()
        }
    }

    private fun record(
        mediaKey: String,
        positionMs: Long,
        syncVersion: Long,
        danmakuId: Long? = null,
        danmakuVersion: Long = 0,
        danmakuUpdatedAt: Long = 0,
    ): PlaybackRecord = PlaybackRecord(
        id = 0,
        media_key = mediaKey,
        source_kind = "WEBDAV",
        url = "https://example.test/$mediaKey",
        content_uri = null,
        title = mediaKey,
        position_ms = positionMs,
        duration_ms = 100_000,
        watch_progress = positionMs.toDouble() / 100_000.0,
        is_completed = 0,
        tmdb_id = null,
        season_number = null,
        episode_number = null,
        danmaku_episode_id = danmakuId,
        danmaku_anime_id = danmakuId,
        danmaku_anime_title = danmakuId?.toString(),
        danmaku_episode_title = danmakuId?.toString(),
        danmaku_match_method = danmakuId?.let { "TEST" },
        danmaku_sync_version = danmakuVersion,
        danmaku_updated_at = danmakuUpdatedAt,
        last_played_at = positionMs,
        sync_status = 0,
        sync_version = syncVersion,
    )

    private fun progress(
        triple: Triple<Long, Long, Long>,
        mediaKey: String,
        syncVersion: Long,
    ): EpisodeProgress = EpisodeProgress(
        tmdb_id = triple.first,
        season_number = triple.second,
        episode_number = triple.third,
        media_key = mediaKey,
        position_ms = 10_000,
        duration_ms = 100_000,
        watch_progress = 0.1,
        is_completed = 0,
        last_played_at = syncVersion,
        sync_status = 0,
        sync_version = syncVersion,
    )
}
