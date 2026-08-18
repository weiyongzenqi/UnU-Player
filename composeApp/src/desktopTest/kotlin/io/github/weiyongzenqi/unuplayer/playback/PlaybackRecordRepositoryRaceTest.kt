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

    // === B-1: 播放入口 upsertEntry 的 Lamport 时钟原子性 ===
    // 背景: 旧实现由 UI 层"快照读 -> 内存 v+1 -> upsert", 读与写之间 pull 合并高版本时,
    // 旧快照 v+1 会把高版本回退, 远端旧进度在下次同步胜出覆盖本地新进度。
    // 修复: upsertEntry 的 sync_version 由 SQL 在事务内原子 +1(基于写入时行内版本)。

    @Test
    fun `upsertEntry 空库首次写入 sync_version 从 0 起步为 1`() = runBlocking {
        withRepo { repo, mediaKey ->
            repo.upsertEntry(
                buildRecord(mediaKey, positionMs = 1_000, lastPlayedAt = 1_000),
            )
            val final = repo.getByMediaKey(mediaKey)
            assertNotNull(final)
            assertEquals(1L, final.sync_version, "空库首次入口写版本应为 1")
            assertEquals(1_000L, final.position_ms)
        }
    }

    @Test
    fun `快照读之后 pull 合并高版本 入口写不回退版本`() = runBlocking {
        withRepo { repo, mediaKey ->
            // 预置: 本地已有 v0 记录; UI 层读到快照(旧实现据此算 v+1=1)
            repo.upsert(buildRecord(mediaKey, positionMs = 5_000, lastPlayedAt = 1_000))
            val snapshot = repo.getByMediaKey(mediaKey)
            assertNotNull(snapshot)
            assertEquals(0L, snapshot.sync_version)

            // 快照读之后: 后台 pull 把远端 v9 合并进来(applyMergedRecord 无守卫 force 写)
            repo.applyMergedRecord(
                buildRecord(mediaKey, positionMs = 8_000, lastPlayedAt = 2_000, syncVersion = 9),
            )

            // 入口写: 修复前 UI 用旧快照算 v=1 写回 -> 版本从 9 回退到 1;
            // 修复后 SQL 侧 9+1=10, 本地新进度(12_000)在下次同步合并时仍胜出。
            repo.upsertEntry(
                buildRecord(mediaKey, positionMs = 12_000, lastPlayedAt = 3_000),
            )

            val final = repo.getByMediaKey(mediaKey)
            assertNotNull(final)
            assertEquals(10L, final.sync_version, "入口写必须在合并版本基础上 +1, 不得回退")
            assertEquals(12_000L, final.position_ms)
        }
    }

    @Test
    fun `upsertEntry 三元组行版本与主行镜像一致`() = runBlocking {
        withRepo { repo, mediaKey ->
            repo.upsertEntry(
                buildRecord(
                    mediaKey,
                    positionMs = 1_000,
                    lastPlayedAt = 1_000,
                    tmdbId = 42L,
                    seasonNumber = 1L,
                    episodeNumber = 3L,
                ),
            )
            val record = repo.getByMediaKey(mediaKey)
            val progress = repo.getEpisodeProgressByTriple(42L, 1L, 3L)
            assertNotNull(record)
            assertNotNull(progress)
            assertEquals(1L, record.sync_version)
            assertEquals(record.sync_version, progress.sync_version, "三元组行版本须与主行镜像一致")
            assertEquals(mediaKey, progress.media_key)

            // 第二次入口写: 两行版本同步 +1
            repo.upsertEntry(
                buildRecord(
                    mediaKey,
                    positionMs = 2_000,
                    lastPlayedAt = 2_000,
                    tmdbId = 42L,
                    seasonNumber = 1L,
                    episodeNumber = 3L,
                ),
            )
            val record2 = repo.getByMediaKey(mediaKey)
            val progress2 = repo.getEpisodeProgressByTriple(42L, 1L, 3L)
            assertNotNull(record2)
            assertNotNull(progress2)
            assertEquals(2L, record2.sync_version)
            assertEquals(record2.sync_version, progress2.sync_version)
        }
    }

    @Test
    fun `upsertEntry 按三元组行版本递增且不复制新主行版本`() = runBlocking {
        withRepo { repo, mediaKeyA ->
            val mediaKeyB = "$mediaKeyA:replacement"
            repo.applyMergedEpisodeProgress(
                EpisodeProgress(
                    tmdb_id = 42L,
                    season_number = 1L,
                    episode_number = 3L,
                    media_key = mediaKeyA,
                    position_ms = 8_000,
                    duration_ms = 100_000,
                    watch_progress = 0.08,
                    is_completed = 0L,
                    last_played_at = 2_000L,
                    sync_status = 0L,
                    sync_version = 9L,
                ),
            )

            repo.upsertEntry(
                buildRecord(
                    mediaKey = mediaKeyB,
                    positionMs = 12_000,
                    lastPlayedAt = 3_000,
                    tmdbId = 42L,
                    seasonNumber = 1L,
                    episodeNumber = 3L,
                ),
            )

            val record = repo.getByMediaKey(mediaKeyB)
            val progress = repo.getEpisodeProgressByTriple(42L, 1L, 3L)
            assertNotNull(record)
            assertNotNull(progress)
            assertEquals(1L, record.sync_version, "新媒体键主行应从版本 1 起步")
            assertEquals(10L, progress.sync_version, "三元组行必须在自身版本 9 基础上递增")
            assertEquals(mediaKeyB, progress.media_key)
            assertEquals(12_000L, progress.position_ms)
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
        tmdbId: Long? = null,
        seasonNumber: Long? = null,
        episodeNumber: Long? = null,
        syncVersion: Long = 0,
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
        tmdb_id = tmdbId,
        season_number = seasonNumber,
        episode_number = episodeNumber,
        danmaku_episode_id = danmakuEpisodeId,
        danmaku_anime_id = danmakuAnimeId,
        danmaku_anime_title = danmakuAnimeTitle,
        danmaku_episode_title = danmakuEpisodeTitle,
        danmaku_match_method = danmakuMatchMethod,
        last_played_at = lastPlayedAt,
        sync_status = 0,
        sync_version = syncVersion,
        danmaku_sync_version = 0,
        danmaku_updated_at = 0,
    )
}
