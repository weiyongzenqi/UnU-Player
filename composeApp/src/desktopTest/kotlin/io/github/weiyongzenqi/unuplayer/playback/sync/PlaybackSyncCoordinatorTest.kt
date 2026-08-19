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
import io.github.weiyongzenqi.unuplayer.core.security.CredentialCipher
import io.github.weiyongzenqi.unuplayer.domain.SettingsState
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import io.github.weiyongzenqi.unuplayer.webdav.WebDavClient
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.sqlite.SQLiteDataSource
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            // clear-all 推进 epoch；模拟另一设备已确认该新 epoch 后再恢复快照。
            rebaseRemotePayloadEpoch(repo, server, "device-test")

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
            server.dataStore["/.unuplayer/playback/v2/other-device.json.gz"] = gzipCompress(
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
            server.dataStore["/.unuplayer/playback/v2/other-device.json.gz"] = gzipCompress(
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
            val storedBytes = server.dataStore["/.unuplayer/playback/v2/device-test.json.gz"]
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
    fun `push 在网络请求前拒绝运行期非法本地逻辑版本`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            repo.applyMergedRecord(
                buildRecord(
                    mediaKey = "runtime-corrupt-version",
                    positionMs = 10_000,
                    syncVersion = MAX_PLAYBACK_SYNC_VERSION,
                ),
            )
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )

            val result = coordinator.push()

            assertFalse(result.success)
            assertTrue(result.error?.contains("本地同步状态逻辑版本超出安全范围") == true)
            assertEquals(0, server.propfindCount)
            assertEquals(0, server.putCount)
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
            rebaseRemotePayloadEpoch(repo, server, "device-test")

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
            server.dataStore["/.unuplayer/playback/v2/device-1.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(
                    PlaybackSyncPayload.serializer(),
                    payloadV5,
                )
            )
            server.dataStore["/.unuplayer/playback/v2/device-2.json.gz"] = gzipCompress(
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
            server.dataStore["/.unuplayer/playback/v2/other-device.json.gz"] = gzipCompress(
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
            val stored = server.dataStore["/.unuplayer/playback/v2/device-gz.json.gz"]
            assertNotNull(stored, "服务器应有 .json.gz 文件")
            assertTrue(stored.isNotEmpty(), "压缩字节应非空")

            // 清库 + pull 恢复
            repo.deleteAll()
            rebaseRemotePayloadEpoch(repo, server, "device-gz")
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
    fun `pull 任一快照损坏时整轮失败且零合并`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 非法 gzip 文件与合法文件并存。严格预检必须在第一次写库前失败。
            server.dataStore["/.unuplayer/playback/v2/corrupt-device.json.gz"] =
                "this is definitely not gzip bytes".encodeToByteArray()

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
            server.dataStore["/.unuplayer/playback/v2/good-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), goodPayload),
            )

            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pullResult = coordinator.pull()

            assertFalse(pullResult.success, "任一损坏快照必须使整轮失败")
            assertEquals(0, pullResult.pulled)
            assertEquals(0, pullResult.mergedRecords)
            assertNull(repo.getByMediaKey("media-key-good"), "预检失败前不得写入合法文件中的记录")
        }
    }

    @Test
    fun `pull 任一快照 JSON 损坏时整轮失败且零合并`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            server.dataStore["/.unuplayer/playback/v2/badjson-device.json.gz"] = gzipCompress("{not json")

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
            server.dataStore["/.unuplayer/playback/v2/good-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), goodPayload),
            )

            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pullResult = coordinator.pull()

            assertFalse(pullResult.success, "任一坏 JSON 快照必须使整轮失败")
            assertEquals(0, pullResult.pulled)
            assertEquals(0, pullResult.mergedRecords)
            assertNull(repo.getByMediaKey("media-key-good2"), "预检失败前不得写入合法文件中的记录")
        }
    }

    @Test
    fun `pull 逻辑版本越界时整轮失败且零合并`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            val payload = PlaybackSyncPayload(
                deviceId = "poisoned-device",
                deviceName = "Poisoned",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "media-key-overflow",
                        source_kind = "WEBDAV",
                        title = "video.mkv",
                        position_ms = 60_000,
                        duration_ms = 100_000,
                        watch_progress = 0.6,
                        is_completed = 0,
                        last_played_at = 1000,
                        sync_version = Long.MAX_VALUE,
                    ),
                ),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/v2/poisoned-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), payload),
            )

            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pullResult = coordinator.pull()

            assertFalse(pullResult.success)
            assertTrue(pullResult.error.orEmpty().contains("逻辑版本"))
            assertEquals(0, pullResult.pulled)
            assertEquals(0, pullResult.mergedRecords)
            assertNull(repo.getByMediaKey("media-key-overflow"))
        }
    }

    @Test
    fun `sync 拉取失败时不得覆盖当前设备远端快照`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            val currentPath = "/.unuplayer/playback/v2/device-test.json.gz"
            val originalCurrentSnapshot = "broken but recoverable elsewhere".encodeToByteArray()
            server.dataStore[currentPath] = originalCurrentSnapshot
            repo.upsert(buildRecord("local-newer", positionMs = 90_000, syncVersion = 9))

            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val result = coordinator.sync()

            assertFalse(result.success)
            assertTrue(originalCurrentSnapshot.contentEquals(server.dataStore[currentPath]))
        }
    }

    @Test
    fun `sync 远端文件数超限时零合并且零 PUT`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            repeat(2) { index ->
                val payload = PlaybackSyncPayload(
                    deviceId = "remote-$index",
                    deviceName = "Remote $index",
                    records = listOf(
                        PlaybackSyncRecord(
                            media_key = "remote-file-$index",
                            source_kind = "WEBDAV",
                            title = "video-$index.mkv",
                            position_ms = 1_000,
                            duration_ms = 10_000,
                            watch_progress = 0.1,
                            is_completed = 0,
                            last_played_at = 1_000,
                            sync_version = 1,
                        ),
                    ),
                    episodeProgress = emptyList(),
                )
                server.dataStore["/.unuplayer/playback/v2/remote-$index.json.gz"] = gzipCompress(
                    playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), payload),
                )
            }
            repo.upsert(buildRecord("local-preserved", positionMs = 9_000, syncVersion = 9))
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
                maxRemoteFiles = 1,
            )

            val result = coordinator.sync()

            assertFalse(result.success)
            assertEquals(0, server.putCount)
            assertNotNull(repo.getByMediaKey("local-preserved"))
            assertNull(repo.getByMediaKey("remote-file-0"))
            assertNull(repo.getByMediaKey("remote-file-1"))
        }
    }

    @Test
    fun `sync 累计解压预算超限时零合并且零 PUT`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            val payload = PlaybackSyncPayload(
                deviceId = "remote-large",
                deviceName = "Remote Large",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "remote-large-record",
                        source_kind = "WEBDAV",
                        title = "x".repeat(2_048),
                        position_ms = 1_000,
                        duration_ms = 10_000,
                        watch_progress = 0.1,
                        is_completed = 0,
                        last_played_at = 1_000,
                        sync_version = 1,
                    ),
                ),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/v2/remote-large.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), payload),
            )
            repo.upsert(buildRecord("local-preserved", positionMs = 9_000, syncVersion = 9))
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
                maxTotalPayloadBytes = 128,
            )

            val result = coordinator.sync()

            assertFalse(result.success)
            assertEquals(0, server.putCount)
            assertNotNull(repo.getByMediaKey("local-preserved"))
            assertNull(repo.getByMediaKey("remote-large-record"))
        }
    }

    @Test
    fun `防抖和退出补同步遇远端损坏时均不得 PUT`() = runBlocking {
        withSyncTestEnv { _, repo, server ->
            server.dataStore["/.unuplayer/playback/v2/corrupt-device.json.gz"] =
                "not-a-gzip-snapshot".encodeToByteArray()
            repo.upsert(buildRecord("local-newer", positionMs = 90_000, syncVersion = 9))

            var storedConnections = listOf(
                WebDavConnection(
                    id = "sync-connection",
                    name = "测试同步",
                    baseUrl = "http://127.0.0.1:${server.port}",
                    username = "",
                    password = "",
                ),
            )
            val connectionRepository = WebDavConnectionRepository(
                object : WebDavConnectionStore {
                    override suspend fun loadAll(): List<WebDavConnection> = storedConnections
                    override suspend fun replaceAll(connections: List<WebDavConnection>) {
                        storedConnections = connections
                    }
                },
                object : CredentialCipher {
                    override fun protect(purpose: String, plaintext: String): String = plaintext
                    override fun unprotect(purpose: String, protectedValue: String): String = protectedValue
                },
            )
            val triggerHttpClients = mutableListOf<HttpClient>()
            val debouncedFinished = CompletableDeferred<Unit>()
            val trigger = PlaybackSyncTrigger(
                webDavRepository = connectionRepository,
                playbackRepository = repo,
                deviceIdentityProvider = PlaybackSyncDeviceIdentityProvider { "device-test" },
                sharedHttpClientProvider = {
                    HttpClient(OkHttp).also(triggerHttpClients::add)
                },
                deviceName = "Test Device",
                logger = object : AppLogger {
                    override fun setDirectory(path: String?) = Unit
                    override fun setAppLogLevel(level: LogLevel) = Unit
                    override fun log(level: String, prefix: String, text: String) = Unit
                    override fun appEvent(tag: String, message: String, level: LogLevel) {
                        if (tag == "playback-sync" && message.contains("退出推送失败")) {
                            debouncedFinished.complete(Unit)
                        }
                    }
                    override suspend fun clearLogs(): Long = 0
                    override suspend fun logsSize(): Long = 0
                },
            )
            val settings = SettingsState(
                playbackSyncEnabled = true,
                playbackSyncConnectionId = "sync-connection",
                playbackAutoSync = true,
            )

            try {
                trigger.scheduleDebouncedPush(settings, delayMs = 0)
                withTimeout(5_000) { debouncedFinished.await() }
                assertTrue(server.propfindCount >= 1)
                assertEquals(0, server.putCount, "防抖同步预检失败后不得上传")

                trigger.flushAndClose(settings)
                assertTrue(server.propfindCount >= 2, "退出补同步应重新执行严格预检")
                assertEquals(0, server.putCount, "退出补同步预检失败后不得上传")
            } finally {
                trigger.close()
                triggerHttpClients.forEach { it.close() }
            }
        }
    }

    @Test
    fun `关闭自动同步会撤销旧防抖任务和退出补推`() = runBlocking {
        withSyncTestEnv { _, repo, server ->
            var storedConnections = listOf(
                WebDavConnection(
                    id = "sync-connection",
                    name = "测试同步",
                    baseUrl = "http://127.0.0.1:${server.port}",
                    username = "",
                    password = "",
                ),
            )
            val connectionRepository = WebDavConnectionRepository(
                object : WebDavConnectionStore {
                    override suspend fun loadAll(): List<WebDavConnection> = storedConnections
                    override suspend fun replaceAll(connections: List<WebDavConnection>) {
                        storedConnections = connections
                    }
                },
                object : CredentialCipher {
                    override fun protect(purpose: String, plaintext: String): String = plaintext
                    override fun unprotect(purpose: String, protectedValue: String): String = protectedValue
                },
            )
            val triggerHttpClients = mutableListOf<HttpClient>()
            val trigger = PlaybackSyncTrigger(
                webDavRepository = connectionRepository,
                playbackRepository = repo,
                deviceIdentityProvider = PlaybackSyncDeviceIdentityProvider { "device-test" },
                sharedHttpClientProvider = {
                    HttpClient(OkHttp).also(triggerHttpClients::add)
                },
                deviceName = "Test Device",
            )
            val enabled = SettingsState(
                playbackSyncEnabled = true,
                playbackSyncConnectionId = "sync-connection",
                playbackAutoSync = true,
            )
            val disabled = enabled.copy(playbackAutoSync = false)

            try {
                trigger.scheduleDebouncedPush(enabled, delayMs = 60_000)
                trigger.reconcileAutoSyncSettings(disabled)
                trigger.flushAndClose(disabled)

                assertEquals(0, server.propfindCount)
                assertEquals(0, server.putCount)
                assertTrue(triggerHttpClients.isEmpty())
            } finally {
                trigger.close()
                triggerHttpClients.forEach { it.close() }
            }
        }
    }

    @Test
    fun `退出时没有 pending 防抖任务仍按当前设置同步`() = runBlocking {
        withSyncTestEnv { _, repo, server ->
            repo.upsert(buildRecord("exit-final-record", positionMs = 42_000, syncVersion = 1))
            var storedConnections = listOf(
                WebDavConnection(
                    id = "sync-connection",
                    name = "测试同步",
                    baseUrl = "http://127.0.0.1:${server.port}",
                    username = "",
                    password = "",
                ),
            )
            val connectionRepository = WebDavConnectionRepository(
                object : WebDavConnectionStore {
                    override suspend fun loadAll(): List<WebDavConnection> = storedConnections
                    override suspend fun replaceAll(connections: List<WebDavConnection>) {
                        storedConnections = connections
                    }
                },
                object : CredentialCipher {
                    override fun protect(purpose: String, plaintext: String): String = plaintext
                    override fun unprotect(purpose: String, protectedValue: String): String = protectedValue
                },
            )
            val triggerHttpClients = mutableListOf<HttpClient>()
            val trigger = PlaybackSyncTrigger(
                webDavRepository = connectionRepository,
                playbackRepository = repo,
                deviceIdentityProvider = PlaybackSyncDeviceIdentityProvider { "device-test" },
                sharedHttpClientProvider = {
                    HttpClient(OkHttp).also(triggerHttpClients::add)
                },
                deviceName = "Test Device",
            )
            val settings = SettingsState(
                playbackSyncEnabled = true,
                playbackSyncConnectionId = "sync-connection",
                playbackAutoSync = true,
            )

            try {
                trigger.flushAndClose(settings)

                assertEquals(1, server.putCount)
                val storedBytes = assertNotNull(server.dataStore["/.unuplayer/playback/v2/device-test.json.gz"])
                val payload = playbackSyncJson.decodeFromString(
                    PlaybackSyncPayload.serializer(),
                    gzipDecompress(storedBytes),
                )
                assertEquals(42_000L, payload.records.single { it.media_key == "exit-final-record" }.position_ms)
            } finally {
                trigger.close()
                triggerHttpClients.forEach { it.close() }
            }
        }
    }

    @Test
    fun `启动同步后共享客户端仍可用于 WebDAV 浏览`() = runBlocking {
        withSyncTestEnv { _, repo, server ->
            val baseUrl = "http://127.0.0.1:${server.port}"
            var storedConnections = listOf(
                WebDavConnection(
                    id = "sync-connection",
                    name = "测试同步",
                    baseUrl = baseUrl,
                    username = "",
                    password = "",
                ),
            )
            val connectionRepository = WebDavConnectionRepository(
                object : WebDavConnectionStore {
                    override suspend fun loadAll(): List<WebDavConnection> = storedConnections
                    override suspend fun replaceAll(connections: List<WebDavConnection>) {
                        storedConnections = connections
                    }
                },
                object : CredentialCipher {
                    override fun protect(purpose: String, plaintext: String): String = plaintext
                    override fun unprotect(purpose: String, protectedValue: String): String = protectedValue
                },
            )
            val sharedHttpClient = HttpClient(OkHttp)
            val trigger = PlaybackSyncTrigger(
                webDavRepository = connectionRepository,
                playbackRepository = repo,
                deviceIdentityProvider = PlaybackSyncDeviceIdentityProvider { "device-test" },
                sharedHttpClientProvider = { sharedHttpClient },
                deviceName = "Test Device",
            )
            val settings = SettingsState(
                playbackSyncEnabled = true,
                playbackSyncConnectionId = "sync-connection",
                playbackAutoSync = true,
            )

            try {
                val syncResult = assertNotNull(trigger.sync(settings))
                assertTrue(syncResult.success, "启动同步应成功: ${syncResult.error}")

                val propfindCountBeforeBrowse = server.propfindCount
                WebDavClient(
                    sharedHttpClient,
                    baseUrl,
                    "",
                    "",
                    fallbackRequestIntervalMs = 0L,
                ).listDirectory("/")
                assertTrue(
                    server.propfindCount > propfindCountBeforeBrowse,
                    "同步结束后共享客户端必须仍能发起 WebDAV 浏览请求",
                )
            } finally {
                trigger.close()
                sharedHttpClient.close()
            }
        }
    }

    @Test
    fun `v2 同步目录隔离旧目录并保留旧数据`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            val legacyPayload = PlaybackSyncPayload(
                deviceId = "legacy-device",
                deviceName = "Legacy",
                records = listOf(buildRecord("legacy-record", positionMs = 12_000, syncVersion = 1).toSyncDto()),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/legacy-device.json.gz"] = gzipCompress(encodeLegacyPayload(legacyPayload))
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
                syncDirPath = PlaybackSyncCoordinator.CURRENT_SYNC_DIR,
            )

            val result = coordinator.sync()

            assertTrue(result.success, "v2 首次同步应只读导入旧目录: ${result.error}")
            assertNotNull(repo.getByMediaKey("legacy-record"))
            val v2Bytes = assertNotNull(server.dataStore["/.unuplayer/playback/v2/device-test.json.gz"])
            val v2Payload = playbackSyncJson.decodeFromString(
                PlaybackSyncPayload.serializer(),
                gzipDecompress(v2Bytes),
            )
            assertEquals(CURRENT_PLAYBACK_SYNC_SCHEMA_VERSION, v2Payload.schemaVersion)
            assertNotNull(server.dataStore["/.unuplayer/playback/legacy-device.json.gz"])
            val parentEntries = client.listDirectoryAll(PlaybackSyncCoordinator.LEGACY_SYNC_DIR)
            assertTrue(parentEntries.any { it.isDirectory && it.name == "v2" })
            assertFalse(
                parentEntries.any { !it.isDirectory && it.name == "device-test.json.gz" },
                "Depth:1 枚举父目录时不得泄漏 v2 子目录内的快照文件",
            )

            val lateLegacyPayload = legacyPayload.copy(
                records = legacyPayload.records +
                    buildRecord("late-legacy-record", positionMs = 24_000, syncVersion = 2).toSyncDto(),
            )
            server.dataStore["/.unuplayer/playback/legacy-device.json.gz"] = gzipCompress(
                encodeLegacyPayload(lateLegacyPayload),
            )
            val secondPull = coordinator.pull()
            assertTrue(secondPull.success, "v2 已建立后应继续只读 v2: ${secondPull.error}")
            assertNull(repo.getByMediaKey("late-legacy-record"), "v2 已有快照后不得再次导入旧目录")
        }
    }

    @Test
    fun `v2 同步拒绝未知协议版本`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            val unsupported = PlaybackSyncPayload(
                deviceId = "future-device",
                deviceName = "Future",
                records = emptyList(),
                episodeProgress = emptyList(),
                schemaVersion = CURRENT_PLAYBACK_SYNC_SCHEMA_VERSION + 1,
            )
            server.dataStore["/.unuplayer/playback/v2/future-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), unsupported),
            )
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
                syncDirPath = PlaybackSyncCoordinator.CURRENT_SYNC_DIR,
            )

            val result = coordinator.pull()

            assertFalse(result.success)
            assertTrue(result.error.orEmpty().contains("协议版本"))
            assertEquals(0L, repo.count())
        }
    }

    @Test
    fun `v2 同步拒绝缺失协议版本`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            val missingVersion = PlaybackSyncPayload(
                deviceId = "missing-version-device",
                deviceName = "Missing Version",
                records = emptyList(),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/v2/missing-version-device.json.gz"] = gzipCompress(
                encodeLegacyPayload(missingVersion),
            )
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
                syncDirPath = PlaybackSyncCoordinator.CURRENT_SYNC_DIR,
            )

            val result = coordinator.pull()

            assertFalse(result.success)
            assertTrue(result.error.orEmpty().contains("协议版本"))
            assertEquals(0L, repo.count())
        }
    }

    @Test
    fun `旧目录拒绝带协议声明的快照`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            val declaredPayload = PlaybackSyncPayload(
                deviceId = "declared-legacy-device",
                deviceName = "Declared Legacy",
                records = emptyList(),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/declared-legacy-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), declaredPayload),
            )
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )

            val result = coordinator.pull()

            assertFalse(result.success)
            assertTrue(result.error.orEmpty().contains("旧同步快照"))
            assertEquals(0L, repo.count())
        }
    }

    @Test
    fun `启动同步在同一进程只调度一次`() = runBlocking {
        withSyncTestEnv { _, repo, server ->
            val sharedHttpClient = HttpClient(OkHttp)
            val finished = CompletableDeferred<Unit>()
            val connectionRepository = WebDavConnectionRepository(
                object : WebDavConnectionStore {
                    override suspend fun loadAll(): List<WebDavConnection> = listOf(
                        WebDavConnection(
                            id = "sync-connection",
                            name = "测试同步",
                            baseUrl = "http://127.0.0.1:${server.port}",
                            username = "",
                            password = "",
                        ),
                    )

                    override suspend fun replaceAll(connections: List<WebDavConnection>) = Unit
                },
                object : CredentialCipher {
                    override fun protect(purpose: String, plaintext: String): String = plaintext
                    override fun unprotect(purpose: String, protectedValue: String): String = protectedValue
                },
            )
            val trigger = PlaybackSyncTrigger(
                webDavRepository = connectionRepository,
                playbackRepository = repo,
                deviceIdentityProvider = PlaybackSyncDeviceIdentityProvider { "device-test" },
                sharedHttpClientProvider = { sharedHttpClient },
                deviceName = "Test Device",
                logger = object : AppLogger {
                    override fun setDirectory(path: String?) = Unit
                    override fun setAppLogLevel(level: LogLevel) = Unit
                    override fun log(level: String, prefix: String, text: String) = Unit
                    override fun appEvent(tag: String, message: String, level: LogLevel) {
                        if (tag == "playback-sync" && message.startsWith("同步完成")) finished.complete(Unit)
                    }
                    override suspend fun clearLogs(): Long = 0
                    override suspend fun logsSize(): Long = 0
                },
            )
            val settings = SettingsState(
                playbackSyncEnabled = true,
                playbackSyncConnectionId = "sync-connection",
                playbackAutoSync = true,
            )

            try {
                trigger.scheduleStartupSync(settings)
                trigger.scheduleStartupSync(settings)
                withTimeout(5_000) { finished.await() }
                assertEquals(1, server.putCount)
            } finally {
                trigger.close()
                sharedHttpClient.close()
            }
        }
    }

    @Test
    fun `启动时关闭自动同步会消费本进程启动门控`() = runBlocking {
        withSyncTestEnv { _, repo, server ->
            val connectionRepository = WebDavConnectionRepository(
                object : WebDavConnectionStore {
                    override suspend fun loadAll(): List<WebDavConnection> = emptyList()
                    override suspend fun replaceAll(connections: List<WebDavConnection>) = Unit
                },
                object : CredentialCipher {
                    override fun protect(purpose: String, plaintext: String): String = plaintext
                    override fun unprotect(purpose: String, protectedValue: String): String = protectedValue
                },
            )
            val trigger = PlaybackSyncTrigger(
                webDavRepository = connectionRepository,
                playbackRepository = repo,
                deviceIdentityProvider = PlaybackSyncDeviceIdentityProvider { "device-test" },
                sharedHttpClientProvider = { error("关闭自动同步时不得创建客户端") },
                deviceName = "Test Device",
            )
            val disabledAtStartup = SettingsState(playbackAutoSync = false)
            val enabledAfterRecreate = SettingsState(
                playbackSyncEnabled = true,
                playbackSyncConnectionId = "sync-connection",
                playbackAutoSync = true,
            )

            try {
                trigger.scheduleStartupSync(disabledAtStartup)
                trigger.scheduleStartupSync(enabledAfterRecreate)
                assertEquals(0, server.propfindCount)
                assertEquals(0, server.putCount)
            } finally {
                trigger.close()
            }
        }
    }

    @Test
    fun `push 最终仍超限时失败且不创建远端文件`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            repo.upsert(buildRecord("oversize", positionMs = 10_000, syncVersion = 1))
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
                maxPayloadBytes = 1,
            )

            val result = coordinator.push()

            assertFalse(result.success)
            assertEquals(0, result.pushed)
            assertEquals(0, result.pushedProgress)
            assertEquals(
                PlaybackSyncCoordinator.PlaybackSyncErrorCode.BASE_PAYLOAD_EXCEEDS_LIMIT,
                result.errorCode,
            )
            assertFalse(server.dataStore.containsKey("/.unuplayer/playback/v2/device-test.json.gz"))
        }
    }

    @Test
    fun `push 唯一记录无法容纳时不得上传空快照覆盖旧文件`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            val path = "/.unuplayer/playback/v2/device-test.json.gz"
            val oldRemote = "old remote snapshot".encodeToByteArray()
            server.dataStore[path] = oldRemote
            repo.upsert(buildRecord("only-record", positionMs = 10_000, syncVersion = 1))
            val emptyPayloadBytes = playbackSyncJson.encodeToString(
                PlaybackSyncPayload.serializer(),
                PlaybackSyncPayload(
                    deviceId = "device-test",
                    deviceName = "UnU Player",
                    records = emptyList(),
                    episodeProgress = emptyList(),
                ),
            ).encodeToByteArray().size
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
                maxPayloadBytes = emptyPayloadBytes,
            )

            val result = coordinator.push()

            assertFalse(result.success)
            assertEquals(
                PlaybackSyncCoordinator.PlaybackSyncErrorCode.ACTIVE_ENTRY_EXCEEDS_LIMIT,
                result.errorCode,
            )
            assertTrue(oldRemote.contentEquals(server.dataStore[path]))
        }
    }

    @Test
    fun `push 仅删除事件超预算时拒绝上传并提示安全恢复路径`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            val oversizedMediaKey = "webdav:connection:/" + "deleted/".repeat(1_000) + "video.mkv"
            repo.upsert(buildRecord(oversizedMediaKey, positionMs = 10_000, syncVersion = 1))
            repo.deleteByKey(oversizedMediaKey)
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
                maxPayloadBytes = 1_024,
            )

            val rejected = coordinator.push()

            assertFalse(rejected.success)
            assertEquals(
                PlaybackSyncCoordinator.PlaybackSyncErrorCode.DELETION_METADATA_EXCEEDS_LIMIT,
                rejected.errorCode,
            )
            assertTrue(rejected.error.orEmpty().contains("记录删除=1"))
            assertTrue(rejected.error.orEmpty().contains("进度删除=0"))
            assertTrue(rejected.error.orEmpty().contains("上限=1024 字节"))
            assertTrue(rejected.error.orEmpty().contains("清空全部播放记录"))
            assertEquals(0, server.putCount)

            repo.deleteAll()
            val recovered = coordinator.push()

            assertTrue(recovered.success)
            assertEquals(1, server.putCount)
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
            server.dataStore["/.unuplayer/playback/v2/other-device.json.gz"] = gzipCompress(
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
            server.dataStore["/.unuplayer/playback/v2/other-device.json.gz"] = gzipCompress(
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

    @Test
    fun `跨设备同一文件不同连接ID按稳定身份合并为一条记录`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 本地记录: 本机连接 id=local-conn
            repo.upsert(buildRecord("webdav:local-conn:/anime/S01E01.mkv", positionMs = 1_000, syncVersion = 1, lastPlayedAt = 500))

            // 远端 payload: 同文件但 connectionId 不同(remote-conn), 携带稳定身份(prefix:path)
            val payload = PlaybackSyncPayload(
                deviceId = "other-device",
                deviceName = "Other",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "webdav:remote-conn:/anime/S01E01.mkv",
                        media_identity = "webdav:test:/anime/S01E01.mkv",
                        source_kind = "WEBDAV",
                        title = "S01E01.mkv",
                        position_ms = 50_000,
                        duration_ms = 100_000,
                        watch_progress = 0.5,
                        is_completed = 0,
                        last_played_at = 2_000,
                        sync_version = 5,
                    ),
                ),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/v2/other-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), payload),
            )

            // 身份 resolver: 任意 connId 的 webdav key 都归一化到 prefix:path(模拟同端点不同 connId)
            val resolver: (suspend (String) -> String?) = { mediaKey ->
                parseWebDavMediaKeyPath(mediaKey)?.let { "webdav:test:$it" }
            }
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
                mediaIdentityResolver = resolver,
            )
            val pullResult = coordinator.pull()
            assertTrue(pullResult.success)
            assertEquals(1, pullResult.mergedRecords, "同一文件应合并 1 条记录")

            // 保留本地 media_key(local-conn), 版本/进度取远端更高版本
            val merged = repo.getByMediaKey("webdav:local-conn:/anime/S01E01.mkv")
            assertNotNull(merged, "应合并到本地 key(而非远端 key)")
            assertEquals(5L, merged.sync_version)
            assertEquals(50_000L, merged.position_ms)
            // 不产生重复记录(远端 key 不应存在)
            assertNull(repo.getByMediaKey("webdav:remote-conn:/anime/S01E01.mkv"))
        }
    }

    @Test
    fun `跨设备同文件身份相同但本地版本更高时不覆盖`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            repo.upsert(buildRecord("webdav:local-conn:/anime/S01E01.mkv", positionMs = 50_000, syncVersion = 5, lastPlayedAt = 2_000))

            val payload = PlaybackSyncPayload(
                deviceId = "other-device",
                deviceName = "Other",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "webdav:remote-conn:/anime/S01E01.mkv",
                        media_identity = "webdav:test:/anime/S01E01.mkv",
                        source_kind = "WEBDAV",
                        title = "S01E01.mkv",
                        position_ms = 1_000,
                        duration_ms = 100_000,
                        watch_progress = 0.1,
                        is_completed = 0,
                        last_played_at = 500,
                        sync_version = 3,
                    ),
                ),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/v2/other-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), payload),
            )

            val resolver: (suspend (String) -> String?) = { mediaKey ->
                parseWebDavMediaKeyPath(mediaKey)?.let { "webdav:test:$it" }
            }
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
                mediaIdentityResolver = resolver,
            )
            val pullResult = coordinator.pull()
            assertTrue(pullResult.success)

            val merged = repo.getByMediaKey("webdav:local-conn:/anime/S01E01.mkv")
            assertNotNull(merged)
            assertEquals(5L, merged.sync_version, "本地更高版本应保留")
            assertEquals(50_000L, merged.position_ms)
            assertNull(repo.getByMediaKey("webdav:remote-conn:/anime/S01E01.mkv"))
        }
    }

    @Test
    fun `pull 远端胜出时刷新已存在记录的 url`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 本地已有同 key 记录, url 是过期的旧服务器 URL
            repo.upsert(
                buildRecord("webdav:local-conn:/anime/S01E01.mkv", positionMs = 1_000, syncVersion = 1, lastPlayedAt = 500)
                    .copy(url = "https://stale.example.com/old.mkv"),
            )
            val payload = PlaybackSyncPayload(
                deviceId = "other-device",
                deviceName = "Other",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "webdav:local-conn:/anime/S01E01.mkv",
                        source_kind = "WEBDAV",
                        title = "S01E01.mkv",
                        position_ms = 50_000,
                        duration_ms = 100_000,
                        watch_progress = 0.5,
                        is_completed = 0,
                        last_played_at = 2_000,
                        sync_version = 5,
                    ),
                ),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/v2/other-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), payload),
            )

            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
            )
            val pullResult = coordinator.pull()
            assertTrue(pullResult.success)

            val merged = repo.getByMediaKey("webdav:local-conn:/anime/S01E01.mkv")
            assertNotNull(merged)
            assertEquals(50_000L, merged.position_ms)
            assertTrue(merged.url.startsWith("http://127.0.0.1:"), "已存在记录被远端胜出时 url 应按当前连接重算, 实际: ${merged.url}")
            assertFalse(merged.url.contains("stale.example.com"))
        }
    }

    @Test
    fun `pull 无本地记录的身份落到本地连接避免ghost`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // 空本地库; 远端记录带身份, 且该身份归属本地连接 local-conn(端点+账号指纹)
            val payload = PlaybackSyncPayload(
                deviceId = "other-device",
                deviceName = "Other",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "webdav:remote-conn:/anime/S01E01.mkv",
                        media_identity = "webdav:test:/anime/S01E01.mkv",
                        source_kind = "WEBDAV",
                        title = "S01E01.mkv",
                        position_ms = 30_000,
                        duration_ms = 100_000,
                        watch_progress = 0.3,
                        is_completed = 0,
                        last_played_at = 1_000,
                        sync_version = 1,
                    ),
                ),
                episodeProgress = emptyList(),
            )
            server.dataStore["/.unuplayer/playback/v2/other-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), payload),
            )

            val resolver: (suspend (String) -> String?) = { mediaKey ->
                parseWebDavMediaKeyPath(mediaKey)?.let { "webdav:test:$it" }
            }
            // 身份 "webdav:test:/anime/..." 归属本地连接 local-conn(baseUrl 本地测试服务器)
            val localTarget: (suspend (String) -> PlaybackSyncCoordinator.LocalSyncTarget?) = { identity ->
                if (identity.startsWith("webdav:test:/")) {
                    PlaybackSyncCoordinator.LocalSyncTarget("local-conn", client.baseUrl)
                } else {
                    null
                }
            }
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
                mediaIdentityResolver = resolver,
                localTargetByIdentity = localTarget,
            )
            val pullResult = coordinator.pull()
            assertTrue(pullResult.success)

            // 落库到本地连接的 media_key, 而不是远端 connId 的 ghost key
            val merged = repo.getByMediaKey("webdav:local-conn:/anime/S01E01.mkv")
            assertNotNull(merged, "应落到本地连接 key, 而非远端 connId")
            assertNull(repo.getByMediaKey("webdav:remote-conn:/anime/S01E01.mkv"), "不应留下远端 connId ghost 记录")
            assertTrue(merged.url.startsWith("http://127.0.0.1:"), "url 应基于本地连接 baseUrl 重算")
        }
    }

    @Test
    fun `pull 合并 EpisodeProgress 时 media_key 按身份归置到本地连接`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // B-2: 远端三元组进度的 media_key 是远端 connId 的 ghost key; 无本地记录匹配。
            // 修复前 media_key 原样落库: 本地级联(updatePosition/deleteByKey)落空,
            // 且"同步后导出"按 media_key 过滤丢行。
            val payload = PlaybackSyncPayload(
                deviceId = "other-device",
                deviceName = "Other",
                records = emptyList(),
                episodeProgress = listOf(
                    PlaybackSyncEpisodeProgress(
                        tmdb_id = 42L,
                        season_number = 1L,
                        episode_number = 3L,
                        media_key = "webdav:remote-conn:/anime/S01E03.mkv",
                        media_identity = "webdav:test:/anime/S01E03.mkv",
                        position_ms = 30_000,
                        duration_ms = 100_000,
                        watch_progress = 0.3,
                        is_completed = 0,
                        last_played_at = 1_000,
                        sync_version = 1,
                    ),
                ),
            )
            server.dataStore["/.unuplayer/playback/v2/other-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), payload),
            )

            val resolver: (suspend (String) -> String?) = { mediaKey ->
                parseWebDavMediaKeyPath(mediaKey)?.let { "webdav:test:$it" }
            }
            val localTarget: (suspend (String) -> PlaybackSyncCoordinator.LocalSyncTarget?) = { identity ->
                if (identity.startsWith("webdav:test:/")) {
                    PlaybackSyncCoordinator.LocalSyncTarget("local-conn", client.baseUrl)
                } else {
                    null
                }
            }
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
                mediaIdentityResolver = resolver,
                localTargetByIdentity = localTarget,
            )
            val pullResult = coordinator.pull()
            assertTrue(pullResult.success)

            val progress = repo.getEpisodeProgressByTriple(42L, 1L, 3L)
            assertNotNull(progress, "远端胜出的三元组进度应落库")
            assertEquals(
                "webdav:local-conn:/anime/S01E03.mkv",
                progress.media_key,
                "media_key 应归置到本地连接, 而非远端 connId ghost key",
            )
        }
    }

    @Test
    fun `pull 合并 EpisodeProgress 时 media_key 跟随同身份本地记录归置`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            // B-2: 同一 payload 内 records 先合并并建立身份->本地 key 映射,
            // EpisodeProgress 用同身份跟随归置到同一本地 key(本机后续播放级联命中)。
            val payload = PlaybackSyncPayload(
                deviceId = "other-device",
                deviceName = "Other",
                records = listOf(
                    PlaybackSyncRecord(
                        media_key = "webdav:remote-conn:/anime/S01E03.mkv",
                        media_identity = "webdav:test:/anime/S01E03.mkv",
                        source_kind = "WEBDAV",
                        title = "S01E03.mkv",
                        position_ms = 30_000,
                        duration_ms = 100_000,
                        watch_progress = 0.3,
                        is_completed = 0,
                        last_played_at = 1_000,
                        sync_version = 1,
                    ),
                ),
                episodeProgress = listOf(
                    PlaybackSyncEpisodeProgress(
                        tmdb_id = 42L,
                        season_number = 1L,
                        episode_number = 3L,
                        media_key = "webdav:remote-conn:/anime/S01E03.mkv",
                        media_identity = "webdav:test:/anime/S01E03.mkv",
                        position_ms = 30_000,
                        duration_ms = 100_000,
                        watch_progress = 0.3,
                        is_completed = 0,
                        last_played_at = 1_000,
                        sync_version = 1,
                    ),
                ),
            )
            server.dataStore["/.unuplayer/playback/v2/other-device.json.gz"] = gzipCompress(
                playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), payload),
            )

            val resolver: (suspend (String) -> String?) = { mediaKey ->
                parseWebDavMediaKeyPath(mediaKey)?.let { "webdav:test:$it" }
            }
            val localTarget: (suspend (String) -> PlaybackSyncCoordinator.LocalSyncTarget?) = { identity ->
                if (identity.startsWith("webdav:test:/")) {
                    PlaybackSyncCoordinator.LocalSyncTarget("local-conn", client.baseUrl)
                } else {
                    null
                }
            }
            val coordinator = PlaybackSyncCoordinator(
                repository = repo,
                client = client,
                deviceIdProvider = { "device-test" },
                mediaIdentityResolver = resolver,
                localTargetByIdentity = localTarget,
            )
            val pullResult = coordinator.pull()
            assertTrue(pullResult.success)

            val record = repo.getByMediaKey("webdav:local-conn:/anime/S01E03.mkv")
            val progress = repo.getEpisodeProgressByTriple(42L, 1L, 3L)
            assertNotNull(record, "记录应归置到本地连接")
            assertNotNull(progress, "三元组进度应落库")
            assertEquals(
                record.media_key,
                progress.media_key,
                "三元组行 media_key 应跟随同身份记录的本地归置结果",
            )
        }
    }

    @Test
    fun `媒体身份与媒体键路径不一致时四类实体均拒绝且零部分写入`() = runBlocking {
        withSyncTestEnv { client, repo, server ->
            val mediaKey = "webdav:remote-conn:/anime/wrong.mkv"
            val identity = "webdav:test:/anime/right.mkv"
            val record = PlaybackSyncRecord(
                media_key = mediaKey,
                media_identity = identity,
                source_kind = "WEBDAV",
                title = "wrong.mkv",
                position_ms = 10_000L,
                duration_ms = 100_000L,
                watch_progress = 0.1,
                is_completed = 0L,
                last_played_at = 1_000L,
                sync_version = 1L,
            )
            val progress = PlaybackSyncEpisodeProgress(
                tmdb_id = 42L,
                season_number = 1L,
                episode_number = 1L,
                media_key = mediaKey,
                media_identity = identity,
                position_ms = 10_000L,
                duration_ms = 100_000L,
                watch_progress = 0.1,
                is_completed = 0L,
                last_played_at = 1_000L,
                sync_version = 1L,
            )
            val empty = PlaybackSyncPayload(
                deviceId = "other-device",
                deviceName = "Other",
                records = emptyList(),
                episodeProgress = emptyList(),
            )
            val variants = listOf(
                "record" to empty.copy(records = listOf(record)),
                "record deletion" to empty.copy(
                    recordDeletions = listOf(
                        PlaybackSyncRecordDeletion(mediaKey, 1_000L, 1L, identity),
                    ),
                ),
                "progress" to empty.copy(episodeProgress = listOf(progress)),
                "progress deletion" to empty.copy(
                    progressDeletions = listOf(
                        PlaybackSyncEpisodeProgressDeletion(42L, 1L, 1L, 1_000L, 1L, mediaKey, identity),
                    ),
                ),
            )

            variants.forEachIndexed { index, (label, payload) ->
                server.dataStore.clear()
                server.dataStore["/.unuplayer/playback/v2/invalid-$index.json.gz"] = gzipCompress(
                    playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), payload),
                )

                val result = PlaybackSyncCoordinator(
                    repository = repo,
                    client = client,
                    deviceIdProvider = { "device-test" },
                ).pull()

                assertFalse(result.success, label)
                assertTrue(result.error?.contains("媒体身份与媒体键不一致") == true, label)
                assertEquals(0L, repo.count(), label)
                assertTrue(repo.listAllEpisodeProgress().isEmpty(), label)
                assertTrue(repo.listPlaybackRecordDeletions().isEmpty(), label)
                assertTrue(repo.listEpisodeProgressDeletions().isEmpty(), label)
            }
        }
    }

    @Test
    fun `buildSyncMediaIdentity 使用版本化SHA假名并保留账号大小写`() = runBlocking {
        val connA = io.github.weiyongzenqi.unuplayer.domain.WebDavConnection("a", "A", "https://Host:8443/Dav/", " Alice ", "p")
        val connB = io.github.weiyongzenqi.unuplayer.domain.WebDavConnection("b", "B", "https://host:8443/Dav/", "Alice", "p")
        // 同端点不同 connId + 账号首尾空白差异 -> 同一身份；scheme/host 小写，路径保持大小写。
        assertEquals(
            buildSyncMediaIdentity(listOf(connA), "webdav:a:/anime/01.mkv"),
            buildSyncMediaIdentity(listOf(connB), "webdav:b:/anime/01.mkv"),
        )
        // 不同账号 -> 不同身份
        val connC = io.github.weiyongzenqi.unuplayer.domain.WebDavConnection("c", "C", "https://host:8443/Dav/", "bob", "p")
        assertNotEquals(
            buildSyncMediaIdentity(listOf(connA), "webdav:a:/anime/01.mkv"),
            buildSyncMediaIdentity(listOf(connC), "webdav:c:/anime/01.mkv"),
        )
        // 密码轮换不改变媒体身份，也不能让同步 payload 成为离线密码校验器。
        val connD = io.github.weiyongzenqi.unuplayer.domain.WebDavConnection("d", "D", "https://host:8443/Dav/", "alice", "other")
        val connE = io.github.weiyongzenqi.unuplayer.domain.WebDavConnection("e", "E", "https://host:8443/Dav/", "Alice", "other")
        assertEquals(
            buildSyncMediaIdentity(listOf(connA), "webdav:a:/anime/01.mkv"),
            buildSyncMediaIdentity(listOf(connE), "webdav:e:/anime/01.mkv"),
        )
        val connF = connE.copy(id = "f", password = "")
        assertEquals(
            buildSyncMediaIdentity(listOf(connA), "webdav:a:/anime/01.mkv"),
            buildSyncMediaIdentity(listOf(connF), "webdav:f:/anime/01.mkv"),
            "密码内容不得参与可公开读取的同步身份",
        )
        assertNotEquals(
            buildSyncMediaIdentity(listOf(connA), "webdav:a:/anime/01.mkv"),
            buildSyncMediaIdentity(listOf(connD), "webdav:d:/anime/01.mkv"),
            "通用 WebDAV 账号可能区分大小写，Alice 与 alice 不得合并",
        )
        // 隐私: 身份不落账号名/主机明文
        val identity = buildSyncMediaIdentity(listOf(connA), "webdav:a:/anime/01.mkv")!!
        assertTrue(identity.startsWith("webdav:h2-"))
        assertFalse(identity.contains("Alice") || identity.contains("alice"))
        assertFalse(identity.contains("host") || identity.contains(":8443"))
        assertTrue(identity.endsWith(":/anime/01.mkv"), "path 应保留(媒体键本已含 path)")
        assertNotEquals(
            legacyMediaIdentityPrefix(connA.baseUrl, connA.username),
            identitySyncPrefix(identity),
            "新 payload 不得继续写入无版本标记的旧 SHA 前缀",
        )

        val anonymous = connA.copy(id = "anonymous", username = "")
        assertNull(
            buildSyncMediaIdentity(listOf(anonymous), "webdav:anonymous:/anime/01.mkv"),
            "匿名账号没有稳定账号身份，应回落 media_key 匹配",
        )
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
        danmaku_sync_version = 0,
        danmaku_updated_at = 0,
    )

    private suspend fun rebaseRemotePayloadEpoch(
        repo: PlaybackRecordRepositoryImpl,
        server: TestWebDavServer,
        deviceId: String,
    ) {
        val path = "/.unuplayer/playback/v2/$deviceId.json.gz"
        val stored = requireNotNull(server.dataStore[path]) { "缺少待重定基的同步快照: $path" }
        val payload = playbackSyncJson.decodeFromString(
            PlaybackSyncPayload.serializer(),
            gzipDecompress(stored),
        )
        server.dataStore[path] = gzipCompress(
            playbackSyncJson.encodeToString(
                PlaybackSyncPayload.serializer(),
                payload.copy(historyEpoch = repo.getPlaybackHistoryEpoch()),
            ),
        )
    }

    private class TestWebDavServer {
        val dataStore = ConcurrentHashMap<String, ByteArray>()
        var rejectNextUpload = false
        var putCount = 0
        var propfindCount = 0

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
                        putCount++
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
                        propfindCount++
                        // 非 207 时直接返回该状态码(模拟目录不存在 404 / 认证失败 401 等)
                        if (propfindStatus != 207) {
                            exchange.sendResponseHeaders(propfindStatus, -1)
                            exchange.close()
                            return
                        }
                        // 按请求目录返回文件列表，覆盖生产 v2 目录与旧目录只读导入。
                        val syncDir = if (path.endsWith('/')) path else "$path/"
                        val relativePaths = dataStore.keys
                            .filter { it.startsWith(syncDir) }
                            .map { it.removePrefix(syncDir) }
                        val files = relativePaths.filterNot { it.contains('/') }
                        val directories = relativePaths
                            .filter { it.contains('/') }
                            .map { it.substringBefore('/') }
                            .distinct()

                        val xml = buildPropfindXml(syncDir, files, directories)
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

        private fun buildPropfindXml(
            basePath: String,
            files: List<String>,
            directories: List<String>,
        ): String {
            if (files.isEmpty() && directories.isEmpty()) {
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
      <D:getcontentlength>${dataStore["$basePath$name"]?.size ?: 0}</D:getcontentlength>
      <D:resourcetype/>
    </D:prop></D:propstat>
  </D:response>"""
            }
            val directoryResponses = directories.joinToString("\n") { name ->
                """  <D:response>
    <D:href>$basePath$name/</D:href>
    <D:propstat><D:prop>
      <D:displayname>$name</D:displayname>
      <D:resourcetype><D:collection/></D:resourcetype>
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
$directoryResponses
</D:multistatus>"""
        }
    }

    private fun encodeLegacyPayload(payload: PlaybackSyncPayload): String {
        val encoded = playbackSyncJson.encodeToJsonElement(PlaybackSyncPayload.serializer(), payload).jsonObject
        return JsonObject(encoded - "schemaVersion").toString()
    }
}
