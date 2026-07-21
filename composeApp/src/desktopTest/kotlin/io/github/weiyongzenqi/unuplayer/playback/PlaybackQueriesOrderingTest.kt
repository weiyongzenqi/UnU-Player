package io.github.weiyongzenqi.unuplayer.playback

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaybackQueriesOrderingTest {

    @Test
    fun `播放记录时间戳严格递增并能越过已有值`() {
        val first = nextPlaybackWriteTimestamp()
        val second = nextPlaybackWriteTimestamp()
        val future = second + 100

        assertEquals(true, second > first)
        assertEquals(true, nextPlaybackWriteTimestamp(future) > future)
    }

    @Test
    fun `Long MAX 历史值会明确失败而不是溢出`() {
        assertFailsWith<IllegalArgumentException> {
            nextPlaybackWriteTimestamp(Long.MAX_VALUE)
        }
    }

    @Test
    fun `迟到的旧会话写入不能覆盖较新的播放记录`() {
        val directory = Files.createTempDirectory("unu-playback-ordering-")
        val databaseFile = directory.resolve("playback.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${databaseFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            val queries = UnuDatabase(driver).playbackQueries

            queries.upsertRecord(positionMs = 20_000, lastPlayedAt = 200, title = "new")
            queries.updatePosition(
                position_ms = 10_000,
                watch_progress = 0.1,
                last_played_at = 100,
                media_key = MEDIA_KEY,
            )
            queries.finishPlayback(
                position_ms = 15_000,
                duration_ms = 100_000,
                watch_progress = 0.15,
                is_completed = 0,
                last_played_at = 150,
                media_key = MEDIA_KEY,
            )
            queries.upsertRecord(positionMs = 5_000, lastPlayedAt = 50, title = "old")
            queries.finishPlayback(
                position_ms = 19_000,
                duration_ms = 100_000,
                watch_progress = 0.19,
                is_completed = 0,
                last_played_at = 200,
                media_key = MEDIA_KEY,
            )

            val protected = queries.getByMediaKey(MEDIA_KEY).executeAsOne()
            assertEquals(20_000, protected.position_ms)
            assertEquals(200, protected.last_played_at)
            assertEquals("new", protected.title)

            queries.finishPlayback(
                position_ms = 30_000,
                duration_ms = 100_000,
                watch_progress = 0.3,
                is_completed = 0,
                last_played_at = 300,
                media_key = MEDIA_KEY,
            )
            val updated = queries.getByMediaKey(MEDIA_KEY).executeAsOne()
            assertEquals(30_000, updated.position_ms)
            assertEquals(300, updated.last_played_at)
        } finally {
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun PlaybackQueries.upsertRecord(positionMs: Long, lastPlayedAt: Long, title: String) {
        // 与 PlaybackRecordRepositoryImpl.upsert 同一套全版本兼容语句(P3㉓): 先单调守卫
        // UPDATE(保持 id 稳定), 再 INSERT OR IGNORE(不存在才插), 事务内组合。
        transaction {
            upsertUpdateIfNewer(
                source_kind = "LOCAL",
                url = "file:///video.mkv",
                content_uri = null,
                title = title,
                position_ms = positionMs,
                duration_ms = 100_000,
                watch_progress = positionMs.toDouble() / 100_000,
                is_completed = 0,
                danmaku_episode_id = null,
                danmaku_anime_id = null,
                danmaku_anime_title = null,
                danmaku_episode_title = null,
                danmaku_match_method = null,
                last_played_at = lastPlayedAt,
                sync_status = 0,
                sync_version = 0,
                media_key = MEDIA_KEY,
            )
            upsertInsertIfAbsent(
                media_key = MEDIA_KEY,
                source_kind = "LOCAL",
                url = "file:///video.mkv",
                content_uri = null,
                title = title,
                position_ms = positionMs,
                duration_ms = 100_000,
                watch_progress = positionMs.toDouble() / 100_000,
                is_completed = 0,
                danmaku_episode_id = null,
                danmaku_anime_id = null,
                danmaku_anime_title = null,
                danmaku_episode_title = null,
                danmaku_match_method = null,
                last_played_at = lastPlayedAt,
                sync_status = 0,
                sync_version = 0,
            )
        }
    }

    private companion object {
        const val MEDIA_KEY = "local:test"
    }
}
