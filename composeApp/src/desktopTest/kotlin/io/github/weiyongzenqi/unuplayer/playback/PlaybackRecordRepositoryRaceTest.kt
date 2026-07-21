package io.github.weiyongzenqi.unuplayer.playback

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * CR-064 竞态验证: 桌面 PlaybackRecordRepositoryImpl 用 Mutex 串行化写操作 + playback.sq 的
 * upsertUpdateIfNewer 对弹幕字段加 COALESCE, 防止两个独立 LaunchedEffect(建记录 upsert +
 * 弹幕匹配 updateDanmaku) 并发时, upsert 用陈旧 existing(null 弹幕) 覆盖 updateDanmaku 刚写入
 * 的匹配信息。
 *
 * 背景: DesktopPlayerScreen 有两个独立 LaunchedEffect(:564 建记录 upsert, :540 弹幕匹配
 * updateDanmaku) 无串行化; upsert 的 buildRecord 用早前 getByMediaKey 返回的 existing 填充
 * 弹幕字段, 若 existing 为旧/null, upsertUpdateIfNewer(WHERE last_played_at<:new, 单调) 会覆盖
 * updateDanmaku 新写值。Android 侧已由 CR-036 用 AndroidPlayerLifecycleTasks.runSerialized 修复,
 * 桌面侧此补齐。
 */
class PlaybackRecordRepositoryRaceTest {

    @Test
    fun `upsert 与 updateDanmaku 并发时弹幕匹配信息不丢失`() = runBlocking {
        withRepo { repo, mediaKey ->
            // 预置: 已有记录但弹幕字段为 null(模拟首次 upsert 后、弹幕匹配未完成的中间态)。
            val seed = buildRecord(mediaKey, positionMs = 5_000, lastPlayedAt = nextPlaybackWriteTimestamp())
            repo.upsert(seed)

            // 竞态构造: 两个 LaunchedEffect 并发调用仓库
            // A(建记录): upsert(buildRecord(..., existing=null)) -> 弹幕字段全 null
            // B(弹幕匹配): updateDanmaku(新弹幕信息)
            // 修复前: 若 B 先于 A 的 upsertUpdateIfNewer 执行, A 的 null 弹幕覆盖 B 写入的非 null
            // 修复后: Mutex 串行化两写; COALESCE(:null, 旧值) 保留旧值, 不论顺序均保留 B 写入值。
            val staleUpsert = buildRecord(
                mediaKey,
                positionMs = 10_000,
                lastPlayedAt = nextPlaybackWriteTimestamp(seed.last_played_at),
                // 弹幕字段全 null(模拟 existing=null 时 buildRecord 的结果)
                danmakuEpisodeId = null,
                danmakuAnimeId = null,
                danmakuAnimeTitle = null,
                danmakuEpisodeTitle = null,
                danmakuMatchMethod = null,
            )

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                withTimeout(5_000) {
                    val upsertJob = async { repo.upsert(staleUpsert) }
                    val updateDanmakuJob = async {
                        repo.updateDanmaku(
                            mediaKey,
                            episodeId = 12345L,
                            animeId = 67890L,
                            animeTitle = "测试番剧",
                            episodeTitle = "第1集",
                            matchMethod = "HASH",
                        )
                    }
                    awaitAll(upsertJob, updateDanmakuJob)
                }

                // 断言: 弹幕匹配信息保留(updateDanmaku 写入的值未被 upsert 的 null 覆盖)
                val final = repo.getByMediaKey(mediaKey)
                assertNotNull(final)
                assertEquals(12345L, final.danmaku_episode_id, "danmaku_episode_id 应保留 updateDanmaku 写入值")
                assertEquals(67890L, final.danmaku_anime_id, "danmaku_anime_id 应保留 updateDanmaku 写入值")
                assertEquals("测试番剧", final.danmaku_anime_title)
                assertEquals("第1集", final.danmaku_episode_title)
                assertEquals("HASH", final.danmaku_match_method)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `多次交替并发 upsert 与 updateDanmaku 不丢失弹幕信息`() = runBlocking {
        withRepo { repo, mediaKey ->
            val seed = buildRecord(mediaKey, positionMs = 5_000, lastPlayedAt = nextPlaybackWriteTimestamp())
            repo.upsert(seed)

            // 多轮并发: 20 个写操作(10 个 upsert + 10 个 updateDanmaku)交替提交到同一 Mutex。
            // COALESCE 保证每个 upsert 的 null 弹幕不覆盖 DB 已有的非 null; updateDanmaku 写入新值。
            // 不论调度顺序, 最终 DB 弹幕字段应为最后一次 updateDanmaku 的值。
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                withTimeout(10_000) {
                    val jobs = (0 until 20).map { i ->
                        async {
                            if (i % 2 == 0) {
                                // upsert with null danmaku (simulating stale existing=null)
                                repo.upsert(
                                    buildRecord(
                                        mediaKey,
                                        positionMs = 10_000L + i,
                                        lastPlayedAt = nextPlaybackWriteTimestamp(),
                                        danmakuEpisodeId = null,
                                        danmakuAnimeId = null,
                                        danmakuAnimeTitle = null,
                                        danmakuEpisodeTitle = null,
                                        danmakuMatchMethod = null,
                                    ),
                                )
                            } else {
                                repo.updateDanmaku(
                                    mediaKey,
                                    episodeId = 12345L,
                                    animeId = 67890L,
                                    animeTitle = "测试番剧",
                                    episodeTitle = "第1集",
                                    matchMethod = "HASH",
                                )
                            }
                        }
                    }
                    jobs.awaitAll()
                }

                val final = repo.getByMediaKey(mediaKey)
                assertNotNull(final)
                assertEquals(12345L, final.danmaku_episode_id, "多轮并发后 danmaku_episode_id 仍应保留")
                assertEquals(67890L, final.danmaku_anime_id)
                assertEquals("测试番剧", final.danmaku_anime_title)
                assertEquals("第1集", final.danmaku_episode_title)
                assertEquals("HASH", final.danmaku_match_method)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `updateDanmaku 在记录不存在时为 no_op 不影响后续 upsert 建记录`() = runBlocking {
        withRepo { repo, mediaKey ->
            // 反向场景: updateDanmaku 先于 upsert(记录不存在) -> updateDanmaku no-op(WHERE 命中 0 行)
            // 随后 upsert 建新记录(弹幕字段 null) -> 弹幕信息丢失(updateDanmaku 已执行完, 无法补)
            // 这是 updateDanmaku 本身的设计局限(不建记录, 只更新); 真实流程中 danmaku LaunchedEffect
            // 有 1s 轮询等记录出现再匹配, 不会在 upsert 前调 updateDanmaku。此测试仅验证 no-op 语义。
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                withTimeout(5_000) {
                    val updateDanmakuJob = async {
                        repo.updateDanmaku(
                            mediaKey,
                            episodeId = 99999L,
                            animeId = 88888L,
                            animeTitle = "不应存在",
                            episodeTitle = "不应存在",
                            matchMethod = "MANUAL",
                        )
                    }
                    val upsertJob = async {
                        repo.upsert(
                            buildRecord(mediaKey, positionMs = 1_000, lastPlayedAt = nextPlaybackWriteTimestamp()),
                        )
                    }
                    awaitAll(updateDanmakuJob, upsertJob)
                }

                val final = repo.getByMediaKey(mediaKey)
                assertNotNull(final)
                // updateDanmaku 在无记录时 no-op, upsert 建记录时弹幕字段为 null
                assertEquals(null, final.danmaku_episode_id, "记录不存在时 updateDanmaku 应 no-op")
                assertEquals(null, final.danmaku_anime_id)
            } finally {
                scope.cancel()
            }
        }
    }

    /** 构造临时 DB + 仓库, 执行 [block] 后自动关闭 driver 和清理临时目录。 */
    private suspend fun withRepo(
        block: suspend (repo: PlaybackRecordRepositoryImpl, mediaKey: String) -> Unit,
    ) {
        val directory = Files.createTempDirectory("unu-playback-race-")
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
        lastPlayedAt: Long,
        danmakuEpisodeId: Long? = null,
        danmakuAnimeId: Long? = null,
        danmakuAnimeTitle: String? = null,
        danmakuEpisodeTitle: String? = null,
        danmakuMatchMethod: String? = null,
    ): PlaybackRecord = PlaybackRecord(
        id = 0,
        media_key = mediaKey,
        source_kind = "WEBDAV",
        url = "http://example.com/video.mkv",
        content_uri = null,
        title = "video.mkv",
        position_ms = positionMs,
        duration_ms = 100_000,
        watch_progress = positionMs.toDouble() / 100_000,
        is_completed = 0,
        danmaku_episode_id = danmakuEpisodeId,
        danmaku_anime_id = danmakuAnimeId,
        danmaku_anime_title = danmakuAnimeTitle,
        danmaku_episode_title = danmakuEpisodeTitle,
        danmaku_match_method = danmakuMatchMethod,
        last_played_at = lastPlayedAt,
        sync_status = 0,
        sync_version = 0,
    )
}
