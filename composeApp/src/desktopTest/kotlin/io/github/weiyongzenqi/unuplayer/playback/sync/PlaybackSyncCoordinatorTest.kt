package io.github.weiyongzenqi.unuplayer.playback.sync

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.github.weiyongzenqi.unuplayer.playback.EpisodeProgress
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepositoryImpl
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabase
import io.github.weiyongzenqi.unuplayer.playback.configuredDesktopDataSource
import io.github.weiyongzenqi.unuplayer.webdav.WebDavClient
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteDataSource
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PlaybackSyncCoordinator 测试: 进程内 HttpServer + 临时 DB。
 */
class PlaybackSyncCoordinatorTest {

    @Test
    fun `push 后清库再 pull 能恢复记录`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 插入记录A(position=50000, is_completed=0, sync_version=0)
            val recordA = buildRecord("media-key-A", positionMs = 50_000, syncVersion = 0)
            repo.upsert(recordA)

            // push
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
                deviceNameProvider = { "Test Device" },
            )
            val pushResult = coordinator.push()
            assertTrue(pushResult.success, "push 应成功: ${pushResult.error}")

            // 清库
            repo.deleteAll()
            assertEquals(0L, repo.count())

            // pull
            val pullResult = coordinator.pull()
            assertTrue(pullResult.success, "pull 应成功: ${pullResult.error}")
            assertEquals(1, pullResult.pulled, "应拉取 1 个文件")
            assertEquals(1, pullResult.mergedRecords, "应合并 1 条记录")

            // 断言记录恢复
            val restored = repo.getByMediaKey("media-key-A")
            assertNotNull(restored)
            assertEquals(50_000L, restored.position_ms)
            assertEquals(0L, restored.sync_version)
        }
    }

    @Test
    fun `LWW 本地高版本不被远端低版本覆盖`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // repo 有记录B(sync_version=5, last_played_at=1000)
            val recordB = buildRecord("media-key-B", positionMs = 30_000, syncVersion = 5, lastPlayedAt = 1000)
            repo.upsert(recordB)

            // 服务器放旧 payload(记录B sync_version=3, last_played_at=2000)
            val oldPayload = PlaybackSyncPayload(
                deviceId = "other-device",
                deviceName = "Other",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "media-key-B",
                        source_kind = "WEBDAV",
                        title = "video.mkv",
                        position_ms = 20_000,
                        duration_ms = 100_000,
                        watch_progress = 0.2,
                        is_completed = 0,
                        last_played_at = 2000,
                        sync_version = 3,
                    ),
                ),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/other-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(
                    PlaybackSyncPayload.serializer(),
                    oldPayload,
                )
            )

            // pull
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pullResult = coordinator.pull()
            assertTrue(pullResult.success)

            // 断言本地记录仍是 sync_version=5(本地胜)
            val local = repo.getByMediaKey("media-key-B")
            assertNotNull(local)
            assertEquals(5L, local.sync_version, "本地高版本不应被远端低版本覆盖")
            assertEquals(30_000L, local.position_ms, "本地数据应保留")
        }
    }

    @Test
    fun `LWW 远端高版本覆盖本地`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // repo 有记录C(sync_version=1, last_played_at=500)
            val recordC = buildRecord("media-key-C", positionMs = 10_000, syncVersion = 1, lastPlayedAt = 500)
            repo.upsert(recordC)

            // 服务器 payload 记录C(sync_version=5, last_played_at 更大)
            val newPayload = PlaybackSyncPayload(
                deviceId = "other-device",
                deviceName = "Other",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "media-key-C",
                        source_kind = "WEBDAV",
                        title = "video.mkv",
                        position_ms = 80_000,
                        duration_ms = 100_000,
                        watch_progress = 0.8,
                        is_completed = 0,
                        last_played_at = 2000,
                        sync_version = 5,
                    ),
                ),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/other-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(
                    PlaybackSyncPayload.serializer(),
                    newPayload,
                )
            )

            // pull
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pullResult = coordinator.pull()
            assertTrue(pullResult.success)

            // 断言记录C变 sync_version=5(远端胜)
            val local = repo.getByMediaKey("media-key-C")
            assertNotNull(local)
            assertEquals(5L, local.sync_version, "远端高版本应覆盖本地")
            assertEquals(80_000L, local.position_ms)
        }
    }

    @Test
    fun `position=0 未完成记录不被 push`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 插入记录D(position=0, is_completed=0)
            val recordD = buildRecord("media-key-D", positionMs = 0, syncVersion = 0, isCompleted = 0)
            repo.upsert(recordD)

            // push
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pushResult = coordinator.push()
            assertTrue(pushResult.success)

            // GET 服务器文件, gunzip 后解析 payload
            val storedBytes = server.dataStore["/.unuplayer/playback/device-test.json.gz"]
            assertNotNull(storedBytes)
            val payload = playbackSyncJson.decodeFromString(
                PlaybackSyncPayload.serializer(),
                gzipDecompress(storedBytes),
            )

            // 断言 records 不含 D
            assertTrue(payload.records.none { it.media_key == "media-key-D" }, "position=0 未完成记录不应被 push")
        }
    }

    @Test
    fun `EpisodeProgress 跨设备恢复`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 插入 EpisodeProgress(三元组 1-1-1)
            val progress = EpisodeProgress(
                tmdb_id = 1L,
                season_number = 1L,
                episode_number = 1L,
                media_key = "media-key-E",
                position_ms = 45_000,
                duration_ms = 100_000,
                watch_progress = 0.45,
                is_completed = 0,
                last_played_at = 1000,
                sync_status = 0,
                sync_version = 2,
            )
            repo.applyMergedEpisodeProgress(progress)

            // push
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pushResult = coordinator.push()
            assertTrue(pushResult.success)

            // 清库
            repo.deleteAll()
            assertEquals(0L, repo.count())

            // pull
            val pullResult = coordinator.pull()
            assertTrue(pullResult.success)

            // 断言 EpisodeProgress 恢复
            val restored = repo.getEpisodeProgressByTriple(1L, 1L, 1L)
            assertNotNull(restored)
            assertEquals(45_000L, restored.position_ms)
            assertEquals(2L, restored.sync_version)
        }
    }

    @Test
    fun `push 失败返回错误信息`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 配置服务器返回 401
            server.rejectNextUpload = true

            val record = buildRecord("media-key-F", positionMs = 10_000, syncVersion = 0)
            repo.upsert(record)

            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pushResult = coordinator.push()

            assertEquals(false, pushResult.success, "push 应失败")
            assertTrue(pushResult.error?.contains("推送失败") == true, "应返回错误信息")
        }
    }

    @Test
    fun `多设备同记录 pull 后保留最高版本`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 本地空。服务器放两个设备文件，同 media_key，版本不同
            val payloadV5 = PlaybackSyncPayload(
                deviceId = "device-1",
                deviceName = "D1",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "media-key-G",
                        source_kind = "WEBDAV",
                        title = "v.mkv",
                        position_ms = 50_000,
                        duration_ms = 100_000,
                        watch_progress = 0.5,
                        is_completed = 0,
                        last_played_at = 1000,
                        sync_version = 5,
                    ),
                ),
                episodeProgress = emptyList(),
            )
            val payloadV3 = PlaybackSyncPayload(
                deviceId = "device-2",
                deviceName = "D2",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "media-key-G",
                        source_kind = "WEBDAV",
                        title = "v.mkv",
                        position_ms = 30_000,
                        duration_ms = 100_000,
                        watch_progress = 0.3,
                        is_completed = 0,
                        last_played_at = 2000,
                        sync_version = 3,
                    ),
                ),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/device-1.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(
                    PlaybackSyncPayload.serializer(),
                    payloadV5,
                )
            )
            server.dataStore["/.unuplayer/playback/device-2.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(
                    PlaybackSyncPayload.serializer(),
                    payloadV3,
                )
            )

            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pullResult = coordinator.pull()
            assertTrue(pullResult.success)

            val local = repo.getByMediaKey("media-key-G")
            assertNotNull(local)
            assertEquals(5L, local.sync_version, "多设备同记录应保留最高版本 v5，不被 v3 覆盖")
            assertEquals(50_000L, local.position_ms, "应保留 v5 的 position")
        }
    }

    @Test
    fun `pull 合并的 WebDAV 记录 url 重算为当前连接合法 URL`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 验证 resolvePlayUrl 可用
            val testPath = "/anime/S01E01.mkv"
            val testUrl = client.resolvePlayUrl(testPath)
            assertTrue(testUrl.startsWith("http://127.0.0.1:"), "resolvePlayUrl 应返回合法 URL: $testUrl")
            assertTrue(testUrl.endsWith(testPath), "resolvePlayUrl 应含 path: $testUrl")

            // 验证 parseWebDavMediaKeyPath 可用
            val testMediaKey = "webdav:old-conn-id:/anime/S01E01.mkv"
            val parsedPath = parseWebDavMediaKeyPath(testMediaKey)
            assertEquals(testPath, parsedPath, "parseWebDavMediaKeyPath 应解析出 path")

            // 服务器放一个其他设备的 WebDAV 记录, media_key 含原 connId + path, DTO 无 url
            val payload = PlaybackSyncPayload(
                deviceId = "other-device",
                deviceName = "Other",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "webdav:old-conn-id:/anime/S01E01.mkv",  // 原设备 connId
                        source_kind = "WEBDAV",
                        title = "S01E01.mkv",
                        position_ms = 30_000,
                        duration_ms = 100_000,
                        watch_progress = 0.3,
                        is_completed = 0,
                        last_played_at = 1000,
                        sync_version = 1,
                    ),
                ),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/other-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(
                    PlaybackSyncPayload.serializer(),
                    payload,
                )
            )

            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pullResult = coordinator.pull()
            assertTrue(pullResult.success)
            assertEquals(1, pullResult.mergedRecords, "应合并 1 条记录")

            val local = repo.getByMediaKey("webdav:old-conn-id:/anime/S01E01.mkv")
            assertNotNull(local, "记录应被合并到本地")
            // url 应被重算为当前连接(baseUrl) + path, 而非 media_key
            assertTrue(local.url.startsWith("http://127.0.0.1:"), "url 应重算为当前连接合法 URL, 实际: ${local.url}")
            assertTrue(local.url.endsWith("/anime/S01E01.mkv"), "url 应含原 path")
            assertNotEquals(local.media_key, local.url, "url 不应等于 media_key")
        }
    }

    @Test
    fun `gzip 压缩往返 push pull 数据完整`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 插入多条记录
            repeat(3) { i ->
                repo.upsert(buildRecord("media-key-gz-$i", positionMs = (i + 1) * 10_000L, syncVersion = i.toLong()))
            }
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-gz" },
            )
            val pushResult = coordinator.push()
            assertTrue(pushResult.success, "push 应成功: ${pushResult.error}")

            // 服务器存的是 gzip 字节(非明文 JSON)
            val stored = server.dataStore["/.unuplayer/playback/device-gz.json.gz"]
            assertNotNull(stored, "服务器应有 .json.gz 文件")
            assertTrue(stored.isNotEmpty(), "压缩字节应非空")

            // 清库 + pull 恢复
            repo.deleteAll()
            val pullResult = coordinator.pull()
            assertTrue(pullResult.success, "pull 应成功: ${pullResult.error}")
            assertEquals(3, pullResult.mergedRecords, "应合并 3 条")
            repeat(3) { i ->
                val r = repo.getByMediaKey("media-key-gz-$i")
                assertNotNull(r, "记录 $i 应恢复")
                assertEquals((i + 1) * 10_000L, r.position_ms)
            }
        }
    }

    @Test
    fun `pull 遇损坏非 gzip 字节跳过不崩且其他文件正常合并`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 非法 gzip 文件(纯文本字节): 锁定 gzipDecompress 的 runCatching 容错(跳过而非整体抛)
            server.dataStore["/.unuplayer/playback/corrupt-device.json.gz"] =
                "this is definitely not gzip bytes".encodeToByteArray()

            // 合法文件: 验证损坏文件被跳过时其他文件仍正常合并
            val goodPayload = PlaybackSyncPayload(
                deviceId = "good-device",
                deviceName = "Good",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "media-key-good",
                        source_kind = "WEBDAV",
                        title = "video.mkv",
                        position_ms = 40_000,
                        duration_ms = 100_000,
                        watch_progress = 0.4,
                        is_completed = 0,
                        last_played_at = 1000,
                        sync_version = 1,
                    ),
                ),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/good-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), goodPayload),
            )

            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pullResult = coordinator.pull()

            assertTrue(pullResult.success, "损坏文件应被跳过而非整体失败: ${pullResult.error}")
            assertEquals(1, pullResult.pulled, "仅合法文件计入 pulled, 损坏文件跳过")
            assertEquals(1, pullResult.mergedRecords, "合法文件记录应合并")
            val good = repo.getByMediaKey("media-key-good")
            assertNotNull(good, "合法文件记录应恢复")
            assertEquals(40_000L, good.position_ms)
        }
    }

    @Test
    fun `pull 遇合法 gzip 但坏 JSON 跳过不崩且其他文件正常合并`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 合法 gzip 但解压后是坏 JSON: 锁定 decodeFromString 的 runCatching 容错(跳过而非整体抛)
            server.dataStore["/.unuplayer/playback/badjson-device.json.gz"] = gzipCompress("{not json")

            val goodPayload = PlaybackSyncPayload(
                deviceId = "good-device",
                deviceName = "Good",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "media-key-good2",
                        source_kind = "WEBDAV",
                        title = "video.mkv",
                        position_ms = 60_000,
                        duration_ms = 100_000,
                        watch_progress = 0.6,
                        is_completed = 0,
                        last_played_at = 1000,
                        sync_version = 1,
                    ),
                ),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/good-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), goodPayload),
            )

            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pullResult = coordinator.pull()

            assertTrue(pullResult.success, "坏 JSON 文件应被跳过而非整体失败: ${pullResult.error}")
            assertEquals(1, pullResult.pulled, "仅合法文件计入 pulled, 坏 JSON 文件跳过")
            assertEquals(1, pullResult.mergedRecords, "合法文件记录应合并")
            val good = repo.getByMediaKey("media-key-good2")
            assertNotNull(good, "合法文件记录应恢复")
            assertEquals(60_000L, good.position_ms)
        }
    }

    @Test
    fun `PROPFIND 404 时 pull 当空目录成功而非失败`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 模拟服务器首次同步尚无同步目录: PROPFIND 返回 404
            server.propfindStatus = 404

            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pullResult = coordinator.pull()

            // 锁定 f65628d"首次拉取目录不存在当空" + T2-m1 结构化状态码(404 in {404,405,409} 才当空)
            assertTrue(pullResult.success, "404 应当空成功而非失败: ${pullResult.error}")
            assertEquals(0, pullResult.pulled, "目录不存在应拉取 0 个文件")
        }
    }

    @Test
    fun `PROPFIND 401 时 pull 失败而非当空成功`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 模拟认证失败: PROPFIND 返回 401
            server.propfindStatus = 401

            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pullResult = coordinator.pull()

            // 锁定 401 不被误吞: 结构化 statusCode=401 不在 {404,405,409}, 应按真失败返回
            assertEquals(false, pullResult.success, "401 认证失败不能当空成功")
            assertNotNull(pullResult.error, "应携带错误信息")
        }
    }

    @Test
    fun `版本平手时远端 last_played_at 更晚覆盖本地`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 本地记录: sync_version=5, last_played_at=1000
            repo.upsert(buildRecord("media-key-tie-later", positionMs = 30_000, syncVersion = 5, lastPlayedAt = 1000))

            // 远端同记录: sync_version=5(平手), last_played_at=2000(更晚) -> 远端应覆盖
            val payload = PlaybackSyncPayload(
                deviceId = "other-device",
                deviceName = "Other",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "media-key-tie-later",
                        source_kind = "WEBDAV",
                        title = "video.mkv",
                        position_ms = 70_000,
                        duration_ms = 100_000,
                        watch_progress = 0.7,
                        is_completed = 0,
                        last_played_at = 2000,
                        sync_version = 5,
                    ),
                ),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/other-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), payload),
            )

            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pullResult = coordinator.pull()
            assertTrue(pullResult.success)

            val local = repo.getByMediaKey("media-key-tie-later")
            assertNotNull(local)
            assertEquals(70_000L, local.position_ms, "版本平手且远端 last_played_at 更晚, 远端应覆盖本地")
            assertEquals(2000L, local.last_played_at, "应写入远端的 last_played_at")
        }
    }

    @Test
    fun `版本平手时远端 last_played_at 更早或相等本地保留`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 场景一: 远端更早(1000 < 本地 2000) -> 本地保留
            repo.upsert(buildRecord("media-key-tie-earlier", positionMs = 30_000, syncVersion = 5, lastPlayedAt = 2000))
            // 场景二: 远端相等(1500 == 本地 1500) -> 平手 <= 不写, 本地保留
            repo.upsert(buildRecord("media-key-tie-equal", positionMs = 40_000, syncVersion = 5, lastPlayedAt = 1500))

            val payload = PlaybackSyncPayload(
                deviceId = "other-device",
                deviceName = "Other",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "media-key-tie-earlier",
                        source_kind = "WEBDAV",
                        title = "video.mkv",
                        position_ms = 90_000,
                        duration_ms = 100_000,
                        watch_progress = 0.9,
                        is_completed = 0,
                        last_played_at = 1000,  // 早于本地 2000
                        sync_version = 5,
                    ),
                    PlaybackSyncRecord(
                        media_key = "media-key-tie-equal",
                        source_kind = "WEBDAV",
                        title = "video.mkv",
                        position_ms = 95_000,
                        duration_ms = 100_000,
                        watch_progress = 0.95,
                        is_completed = 0,
                        last_played_at = 1500,  // 等于本地 1500, 平手不写
                        sync_version = 5,
                    ),
                ),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/other-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), payload),
            )

            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pullResult = coordinator.pull()
            assertTrue(pullResult.success)
            assertEquals(0, pullResult.mergedRecords, "版本平手且远端不更新, 两条均不应写入")

            val earlier = repo.getByMediaKey("media-key-tie-earlier")
            assertNotNull(earlier)
            assertEquals(30_000L, earlier.position_ms, "远端 last_played_at 更早, 本地应保留")
            assertEquals(2000L, earlier.last_played_at)

            val equal = repo.getByMediaKey("media-key-tie-equal")
            assertNotNull(equal)
            assertEquals(40_000L, equal.position_ms, "last_played_at 相等(平手 <=), 本地应保留")
            assertEquals(1500L, equal.last_played_at)
        }
    }

    // === 测试台辅助 ===

    private suspend fun withSyncTestEnv(
        block: suspend (client: WebDavClient, repo: PlaybackRecordRepositoryImpl, server: TestWebDavServer) -> Unit,
    ) {
        val directory = Files.createTempDirectory("unu-sync-test-")
        val databaseFile = directory.resolve("playback.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${databaseFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        val server = TestWebDavServer()
        val httpClient = HttpClient(OkHttp)

        try {
            UnuDatabase.Schema.create(driver)
            val queries = UnuDatabase(driver).playbackQueries
            val repo = PlaybackRecordRepositoryImpl(queries)

            server.start()
            val baseUrl = "http://127.0.0.1:${server.port}"
            val client = WebDavClient(httpClient, baseUrl, "", "", fallbackRequestIntervalMs = 0L)

            block(client, repo, server)
        } finally {
            runCatching { httpClient.close() }
            server.stop()
            runCatching { driver.close() }
            directory.toFile().deleteRecursively()
        }
    }

    private fun buildRecord(
        mediaKey: String,
        positionMs: Long,
        syncVersion: Long,
        lastPlayedAt: Long = System.currentTimeMillis(),
        isCompleted: Long = 0,
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
        is_completed = isCompleted,
        tmdb_id = null,
        season_number = null,
        episode_number = null,
        danmaku_episode_id = null,
        danmaku_anime_id = null,
        danmaku_anime_title = null,
        danmaku_episode_title = null,
        danmaku_match_method = null,
        last_played_at = lastPlayedAt,
        sync_status = 0,
        sync_version = syncVersion,
    )

    private class TestWebDavServer {
        val dataStore = ConcurrentHashMap<String, ByteArray>()
        var rejectNextUpload = false

        /**
         * PROPFIND 响应状态码: 默认 207(正常多状态, 既有行为不变)。
         * 设为 404/401 等可模拟"目录不存在/认证失败", 锁定 Coordinator 的结构化状态码判定。
         */
        var propfindStatus = 207
        private lateinit var server: HttpServer
        val port: Int get() = server.address.port

        fun start() {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = Executors.newSingleThreadExecutor()
                createContext("/") { exchange ->
                    handleRequest(exchange)
                }
                start()
            }
        }

        fun stop() {
            runCatching { server.stop(0) }
            runCatching { (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow() }
        }

        private fun handleRequest(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            val method = exchange.requestMethod

            try {
                when {
                    method == "MKCOL" -> {
                        exchange.sendResponseHeaders(201, -1)
                        exchange.close()
                    }
                    method == "PUT" -> {
                        if (rejectNextUpload) {
                            rejectNextUpload = false
                            exchange.sendResponseHeaders(401, -1)
                            exchange.close()
                            return
                        }
                        val body = exchange.requestBody.use { it.readBytes() }
                        dataStore[path] = body
                        exchange.sendResponseHeaders(204, -1)
                        exchange.close()
                    }
                    method == "GET" -> {
                        val content = dataStore[path]
                        if (content != null) {
                            exchange.sendResponseHeaders(200, content.size.toLong())
                            exchange.responseBody.use { it.write(content) }
                        } else {
                            exchange.sendResponseHeaders(404, -1)
                        }
                        exchange.close()
                    }
                    method == "PROPFIND" -> {
                        // 非 207 时直接返回该状态码(模拟目录不存在 404 / 认证失败 401 等)
                        if (propfindStatus != 207) {
                            exchange.sendResponseHeaders(propfindStatus, -1)
                            exchange.close()
                            return
                        }
                        // 返回同步目录下的文件列表
                        val syncDir = "/.unuplayer/playback/"
                        val files = dataStore.keys
                            .filter { it.startsWith(syncDir) }
                            .map { it.removePrefix(syncDir) }

                        val xml = buildPropfindXml(syncDir, files)
                        val bytes = xml.encodeToByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/xml; charset=utf-8")
                        exchange.sendResponseHeaders(207, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                        exchange.close()
                    }
                    else -> {
                        exchange.sendResponseHeaders(405, -1)
                        exchange.close()
                    }
                }
            } catch (e: Exception) {
                runCatching { exchange.close() }
            }
        }

        private fun buildPropfindXml(basePath: String, files: List<String>): String {
            if (files.isEmpty()) {
                return """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>$basePath</D:href>
    <D:propstat><D:prop>
      <D:displayname>playback</D:displayname>
      <D:resourcetype><D:collection/></D:resourcetype>
    </D:prop></D:propstat>
  </D:response>
</D:multistatus>"""
            }

            val fileResponses = files.joinToString("\n") { name ->
                """  <D:response>
    <D:href>$basePath$name</D:href>
    <D:propstat><D:prop>
      <D:displayname>$name</D:displayname>
      <D:getcontentlength>${dataStore["/.unuplayer/playback/$name"]?.size ?: 0}</D:getcontentlength>
      <D:resourcetype/>
    </D:prop></D:propstat>
  </D:response>"""
            }

            return """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>$basePath</D:href>
    <D:propstat><D:prop>
      <D:displayname>playback</D:displayname>
      <D:resourcetype><D:collection/></D:resourcetype>
    </D:prop></D:propstat>
  </D:response>
$fileResponses
</D:multistatus>"""
        }
    }
}