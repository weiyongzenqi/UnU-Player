package io.github.weiyongzenqi.unuplayer.playback

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import io.github.weiyongzenqi.unuplayer.playback.sync.REPAIRED_PLAYBACK_SYNC_VERSION
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackDatabaseSanitizationTest {

    @Test
    fun `重开历史库会幂等修复非法时间和逻辑版本且仍可继续写入`() = runBlocking {
        val directory = Files.createTempDirectory("unu-playback-sanitize-")
        val dataSource = SQLiteDataSource().apply {
            url = "jdbc:sqlite:${directory.resolve("playback.db").toAbsolutePath()}"
        }
        val configured = configuredDesktopDataSource(dataSource)
        val schemaDriver = configured.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(schemaDriver)
        } finally {
            schemaDriver.close()
        }

        try {
            configured.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """INSERT INTO PlaybackRecord(
                            media_key, source_kind, url, title, position_ms, duration_ms,
                            watch_progress, is_completed, danmaku_sync_version, danmaku_updated_at,
                            last_played_at, sync_status, sync_version
                        ) VALUES ('bad-record', 'WEBDAV', 'https://example.test/video', '坏记录',
                            1000, 10000, 0.1, 0, ${Long.MIN_VALUE}, -1,
                            ${Long.MAX_VALUE}, 0, ${Long.MAX_VALUE})""".trimIndent(),
                    )
                    statement.executeUpdate(
                        """INSERT INTO EpisodeProgress(
                            tmdb_id, season_number, episode_number, media_key, position_ms,
                            duration_ms, watch_progress, is_completed, last_played_at, sync_status, sync_version
                        ) VALUES (1, 1, 1, 'bad-record', 1000, 10000, 0.1, 0,
                            ${Long.MAX_VALUE}, 0, ${Long.MIN_VALUE})""".trimIndent(),
                    )
                    statement.executeUpdate(
                        "INSERT INTO PlaybackRecordTombstone(media_key, deleted_at, sync_version) " +
                            "VALUES ('deleted-record', -1, ${Long.MAX_VALUE})",
                    )
                    statement.executeUpdate(
                        """INSERT INTO EpisodeProgressTombstone(
                            tmdb_id, season_number, episode_number, deleted_at, sync_version
                        ) VALUES (2, 1, 1, ${Long.MAX_VALUE}, ${Long.MIN_VALUE})""".trimIndent(),
                    )
                    statement.executeUpdate(
                        "UPDATE PlaybackSyncState SET history_epoch = ${Long.MAX_VALUE} WHERE singleton_id = 1",
                    )
                }
            }

            val before = System.currentTimeMillis()
            ensureCurrentDesktopSchema(configured)
            ensureCurrentDesktopSchema(configured)
            val after = System.currentTimeMillis()

            configured.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT last_played_at, danmaku_updated_at, sync_version, danmaku_sync_version " +
                            "FROM PlaybackRecord WHERE media_key='bad-record'",
                    ).use { row ->
                        assertTrue(row.next())
                        assertTrue(row.getLong(1) in before..after)
                        assertEquals(0L, row.getLong(2))
                        assertEquals(REPAIRED_PLAYBACK_SYNC_VERSION, row.getLong(3))
                        assertEquals(REPAIRED_PLAYBACK_SYNC_VERSION, row.getLong(4))
                    }
                    assertSanitizedPair(
                        statement.executeQuery("SELECT last_played_at, sync_version FROM EpisodeProgress"),
                        before,
                        after,
                    )
                    assertSanitizedPair(
                        statement.executeQuery("SELECT deleted_at, sync_version FROM PlaybackRecordTombstone"),
                        0L,
                        0L,
                    )
                    assertSanitizedPair(
                        statement.executeQuery("SELECT deleted_at, sync_version FROM EpisodeProgressTombstone"),
                        before,
                        after,
                    )
                    statement.executeQuery("SELECT history_epoch FROM PlaybackSyncState WHERE singleton_id=1").use { row ->
                        assertTrue(row.next())
                        assertEquals(0L, row.getLong(1))
                    }
                }
            }

            val driver = configured.asJdbcDriver()
            try {
                val repository = PlaybackRecordRepositoryImpl(UnuDatabase(driver).playbackQueries)
                val repaired = requireNotNull(repository.getByMediaKey("bad-record"))
                repository.upsertEntry(
                    repaired.copy(
                        position_ms = 2_000L,
                        last_played_at = after + 1L,
                    ),
                )
                val updated = requireNotNull(repository.getByMediaKey("bad-record"))
                assertEquals(2_000L, updated.position_ms)
                assertEquals(REPAIRED_PLAYBACK_SYNC_VERSION + 1L, updated.sync_version)
            } finally {
                driver.close()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun assertSanitizedPair(
        rows: java.sql.ResultSet,
        minimumTime: Long,
        maximumTime: Long,
    ) = rows.use { row ->
        assertTrue(row.next())
        assertTrue(row.getLong(1) in minimumTime..maximumTime)
        assertEquals(REPAIRED_PLAYBACK_SYNC_VERSION, row.getLong(2))
    }
}
