package io.github.weiyongzenqi.unuplayer.playback

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * P1a EpisodeProgress 测试: 三元组语义进度(跨刮削库续播锚点)。
 *
 * 覆盖场景:
 * 1. upsert 传三元组 -> EpisodeProgress 写入成功
 * 2. upsert 三元组为 null -> EpisodeProgress 不写
 * 3. updatePosition -> EpisodeProgress 级联更新
 * 4. finishPlayback -> EpisodeProgress 级联完成
 * 5. deleteByKey -> EpisodeProgress 级联删除
 * 6. deleteAll -> 两表全量级联清空(不留孤儿进度行)
 * 7. 单调守卫: 旧 last_played_at 不覆盖新值
 * 8. 跨文件同三元组: media_key 为最后写入者
 */
class EpisodeProgressTest {

    @Test
    fun `upsert 传三元组应写入 EpisodeProgress`() = runBlocking {
        withRepo { repo, mediaKey ->
            val record = buildRecord(
                mediaKey,
                positionMs = 10_000,
                lastPlayedAt = System.currentTimeMillis(),
                tmdbId = 100L,
                seasonNumber = 1L,
                episodeNumber = 1L,
            )
            repo.upsert(record)

            val progress = repo.getEpisodeProgressByTriple(100L, 1L, 1L)
            assertNotNull(progress)
            assertEquals(10_000L, progress.position_ms)
            assertEquals(0.1, progress.watch_progress, 0.001)
            assertEquals(mediaKey, progress.media_key)
        }
    }

    @Test
    fun `upsert 三元组为 null 不应写 EpisodeProgress`() = runBlocking {
        withRepo { repo, mediaKey ->
            val record = buildRecord(
                mediaKey,
                positionMs = 20_000,
                lastPlayedAt = System.currentTimeMillis(),
                tmdbId = null,
                seasonNumber = null,
                episodeNumber = null,
            )
            repo.upsert(record)

            // 三元组为 null, EpisodeProgress 不写
            val progress = repo.getEpisodeProgressByTriple(100L, 1L, 1L)
            assertNull(progress)
        }
    }

    @Test
    fun `updatePosition 应级联更新 EpisodeProgress`() = runBlocking {
        withRepo { repo, mediaKey ->
            // 先 upsert 建记录和 EpisodeProgress
            val record = buildRecord(
                mediaKey,
                positionMs = 10_000,
                lastPlayedAt = System.currentTimeMillis(),
                tmdbId = 200L,
                seasonNumber = 2L,
                episodeNumber = 5L,
            )
            repo.upsert(record)

            // updatePosition 级联
            val newTime = System.currentTimeMillis() + 1000
            repo.updatePosition(mediaKey, positionMs = 30_000, watchProgress = 0.3, lastPlayedAt = newTime)

            val progress = repo.getEpisodeProgressByTriple(200L, 2L, 5L)
            assertNotNull(progress)
            assertEquals(30_000L, progress.position_ms)
            assertEquals(0.3, progress.watch_progress, 0.001)
        }
    }

    @Test
    fun `finishPlayback 应级联完成 EpisodeProgress`() = runBlocking {
        withRepo { repo, mediaKey ->
            val record = buildRecord(
                mediaKey,
                positionMs = 95_000,
                durationMs = 100_000,
                lastPlayedAt = System.currentTimeMillis(),
                tmdbId = 300L,
                seasonNumber = 1L,
                episodeNumber = 10L,
            )
            repo.upsert(record)
            assertEquals(0L, repo.changeVersion.value, "初始化记录不应触发详情页刷新")

            val newTime = System.currentTimeMillis() + 1000
            repo.finishPlayback(
                mediaKey,
                positionMs = 100_000,
                durationMs = 100_000,
                watchProgress = 1.0,
                isCompleted = 1,
                lastPlayedAt = newTime,
            )
            assertEquals(1L, repo.changeVersion.value, "最终播放记录写入后应通知详情页刷新")

            val progress = repo.getEpisodeProgressByTriple(300L, 1L, 10L)
            assertNotNull(progress)
            assertEquals(100_000L, progress.position_ms)
            assertEquals(1L, progress.is_completed)
            assertEquals(1.0, progress.watch_progress, 0.001)
        }
    }

    @Test
    fun `deleteByKey 应级联删除 EpisodeProgress`() = runBlocking {
        withRepo { repo, mediaKey ->
            val record = buildRecord(
                mediaKey,
                positionMs = 10_000,
                lastPlayedAt = System.currentTimeMillis(),
                tmdbId = 400L,
                seasonNumber = 1L,
                episodeNumber = 1L,
            )
            repo.upsert(record)

            // 删除前存在
            assertNotNull(repo.getEpisodeProgressByTriple(400L, 1L, 1L))

            repo.deleteByKey(mediaKey)

            // 删除后不存在
            assertNull(repo.getEpisodeProgressByTriple(400L, 1L, 1L))
        }
    }

    @Test
    fun `deleteAll 应级联清空两表不留孤儿进度行`() = runBlocking {
        withRepo { repo, _ ->
            val time = System.currentTimeMillis()
            // 写入若干记录及对应 EpisodeProgress(含同剧不同集与不同剧)
            repo.upsert(buildRecord("file:1", 10_000, lastPlayedAt = time, tmdbId = 700L, seasonNumber = 1L, episodeNumber = 1L))
            repo.upsert(buildRecord("file:2", 20_000, lastPlayedAt = time, tmdbId = 700L, seasonNumber = 1L, episodeNumber = 2L))
            repo.upsert(buildRecord("file:3", 30_000, lastPlayedAt = time, tmdbId = 701L, seasonNumber = 2L, episodeNumber = 1L))

            // 清空前两表均有数据
            assertEquals(3, repo.listAll().size)
            assertEquals(3, repo.listAllEpisodeProgress().size)

            repo.deleteAll()

            // 清空后两表皆空: 级联清, 无 EpisodeProgress 孤儿行(T1-m1)
            assertEquals(0, repo.listAll().size)
            assertEquals(0, repo.listAllEpisodeProgress().size)
            assertNull(repo.getEpisodeProgressByTriple(700L, 1L, 1L))
            assertNull(repo.getEpisodeProgressByTriple(701L, 2L, 1L))
        }
    }

    @Test
    fun `单调守卫应阻止旧值覆盖新值`() = runBlocking {
        withRepo { repo, mediaKey ->
            val oldTime = System.currentTimeMillis() - 10_000
            val newTime = System.currentTimeMillis()

            // 先写新值
            val newRecord = buildRecord(
                mediaKey,
                positionMs = 50_000,
                lastPlayedAt = newTime,
                tmdbId = 500L,
                seasonNumber = 1L,
                episodeNumber = 1L,
            )
            repo.upsert(newRecord)

            // 用旧时间戳 upsert
            val oldRecord = buildRecord(
                mediaKey,
                positionMs = 10_000,
                lastPlayedAt = oldTime,
                tmdbId = 500L,
                seasonNumber = 1L,
                episodeNumber = 1L,
            )
            repo.upsert(oldRecord)

            // 单调守卫: 旧值不覆盖
            val progress = repo.getEpisodeProgressByTriple(500L, 1L, 1L)
            assertNotNull(progress)
            assertEquals(50_000L, progress.position_ms, "单调守卫应阻止旧值覆盖")
        }
    }

    @Test
    fun `跨文件同三元组应只有一行且 media_key 为最后写入者`() = runBlocking {
        withRepo { repo, _ ->
            val time1 = System.currentTimeMillis()
            val time2 = time1 + 1000

            // 文件 A 先写
            val recordA = buildRecord(
                "file:A",
                positionMs = 10_000,
                lastPlayedAt = time1,
                tmdbId = 600L,
                seasonNumber = 1L,
                episodeNumber = 1L,
            )
            repo.upsert(recordA)

            // 文件 B 后写(同三元组)
            val recordB = buildRecord(
                "file:B",
                positionMs = 20_000,
                lastPlayedAt = time2,
                tmdbId = 600L,
                seasonNumber = 1L,
                episodeNumber = 1L,
            )
            repo.upsert(recordB)

            // EpisodeProgress 只有一行(三元组主键)
            val progress = repo.getEpisodeProgressByTriple(600L, 1L, 1L)
            assertNotNull(progress)
            assertEquals("file:B", progress.media_key, "media_key 应为最后写入者")
            assertEquals(20_000L, progress.position_ms, "position 应为最后写入者值")
        }
    }

    @Test
    fun `批量三元组查询应返回正确映射`() = runBlocking {
        withRepo { repo, _ ->
            val time = System.currentTimeMillis()
            // 写入三个三元组
            repo.upsert(buildRecord("file:1", 10_000, lastPlayedAt = time, tmdbId = 100L, seasonNumber = 1L, episodeNumber = 1L))
            repo.upsert(buildRecord("file:2", 20_000, lastPlayedAt = time, tmdbId = 100L, seasonNumber = 1L, episodeNumber = 2L))
            repo.upsert(buildRecord("file:3", 30_000, lastPlayedAt = time, tmdbId = 100L, seasonNumber = 1L, episodeNumber = 3L))

            // 批量查
            val keys = listOf(
                episodeProgressKey(100L, 1L, 1L),
                episodeProgressKey(100L, 1L, 2L),
                episodeProgressKey(100L, 1L, 3L),
                episodeProgressKey(999L, 9L, 9L),  // 不存在
            )
            val result = repo.getEpisodeProgressByTriples(keys)

            assertEquals(3, result.size)
            assertEquals(10_000L, result[episodeProgressKey(100L, 1L, 1L)]?.position_ms)
            assertEquals(20_000L, result[episodeProgressKey(100L, 1L, 2L)]?.position_ms)
            assertEquals(30_000L, result[episodeProgressKey(100L, 1L, 3L)]?.position_ms)
        }
    }

    @Test
    fun `空批量查询应返回空映射`() = runBlocking {
        withRepo { repo, _ ->
            val result = repo.getEpisodeProgressByTriples(emptyList())
            assertEquals(0, result.size)
        }
    }

    /** 构造临时 DB + 仓库, 执行 [block] 后自动关闭 driver 和清理临时目录。 */
    private suspend fun withRepo(
        block: suspend (repo: PlaybackRecordRepositoryImpl, mediaKey: String) -> Unit,
    ) {
        val directory = Files.createTempDirectory("unu-episode-progress-")
        val databaseFile = directory.resolve("playback.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${databaseFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            val queries = UnuDatabase(driver).playbackQueries
            val repo = PlaybackRecordRepositoryImpl(queries)
            block(repo, "test:media:${System.nanoTime()}")
        } finally {
            runCatching { driver.close() }
            directory.toFile().deleteRecursively()
        }
    }

    private fun buildRecord(
        mediaKey: String,
        positionMs: Long,
        durationMs: Long = 100_000,
        lastPlayedAt: Long,
        tmdbId: Long? = null,
        seasonNumber: Long? = null,
        episodeNumber: Long? = null,
    ): PlaybackRecord = PlaybackRecord(
        id = 0,
        media_key = mediaKey,
        source_kind = "WEBDAV",
        url = "http://example.com/video.mkv",
        content_uri = null,
        title = "video.mkv",
        position_ms = positionMs,
        duration_ms = durationMs,
        watch_progress = positionMs.toDouble() / durationMs,
        is_completed = 0,
        tmdb_id = tmdbId,
        season_number = seasonNumber,
        episode_number = episodeNumber,
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
