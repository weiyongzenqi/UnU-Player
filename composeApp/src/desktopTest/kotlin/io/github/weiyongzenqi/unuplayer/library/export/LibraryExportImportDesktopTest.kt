package io.github.weiyongzenqi.unuplayer.library.export

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkSource
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkState
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.security.CredentialCipher
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepositoryImpl
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.ScrapedOnlineEpisode
import io.github.weiyongzenqi.unuplayer.library.ScrapeSource
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideRow
import io.github.weiyongzenqi.unuplayer.library.decodedEpisodes
import io.github.weiyongzenqi.unuplayer.playback.EpisodeProgress
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabase
import io.github.weiyongzenqi.unuplayer.playback.configuredDesktopDataSource
import io.github.weiyongzenqi.unuplayer.playback.ensureCurrentDesktopSchema
import io.github.weiyongzenqi.unuplayer.smb.SmbConnectionRepository
import io.github.weiyongzenqi.unuplayer.smb.SmbConnectionStore
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteDataSource
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 媒体库导出/导入全流程集成测试(desktopTest, 真实 SQLite)。 */
class LibraryExportImportDesktopTest {

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> unusedProxy(type: Class<T>): T = Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type),
    ) { _, _, _ -> error("unused") } as T

    private val fakeCipher = object : CredentialCipher {
        override fun isProtected(value: String): Boolean = value.startsWith("unu-sec:")
        override fun protect(purpose: String, plaintext: String): String = "unu-sec:$purpose:$plaintext"
        override fun unprotect(purpose: String, protectedValue: String): String =
            protectedValue.removePrefix("unu-sec:$purpose:")
    }

    private class FakeImageService : LibraryImageService {
        val writtenShowImages = mutableListOf<Triple<String, String, Int>>()   // showKey, basename, size
        val writtenEpisodeThumbs = mutableListOf<Pair<String, Long>>()         // showKey, episodeId
        val deletedShowImages = mutableListOf<Pair<String, String>>()           // showKey, absolutePath
        var afterShowImageWrite: suspend (String, String, String) -> Unit = { _, _, _ -> }
        var finishRestoreCalls = 0
        var showImageCreated = true
        var episodeThumbCreated = true
        override suspend fun listShowFiles(showKey: String): List<ImageFileEntry> = emptyList()
        override suspend fun writeShowImage(showKey: String, basename: String, bytes: ByteArray): ImageWriteResult? {
            writtenShowImages += Triple(showKey, basename, bytes.size)
            val path = "/fake/root/$showKey/$basename"
            afterShowImageWrite(showKey, basename, path)
            return ImageWriteResult(path, created = showImageCreated)
        }
        override suspend fun deleteShowImage(showKey: String, absolutePath: String): Boolean {
            deletedShowImages += showKey to absolutePath
            return true
        }
        override suspend fun writeEpisodeThumb(
            showKey: String,
            episodeId: Long,
            bytes: ByteArray,
        ): ImageWriteResult? {
            writtenEpisodeThumbs += showKey to episodeId
            return ImageWriteResult("/fake/root/$showKey/ep$episodeId.jpg", created = episodeThumbCreated)
        }
        override suspend fun finishRestore() {
            finishRestoreCalls++
        }
    }

    private class FakePlaybackRepository : PlaybackRecordRepository {
        val applied = mutableListOf<PlaybackRecord>()
        val progress = mutableListOf<EpisodeProgress>()
        override val changeVersion: StateFlow<Long> = MutableStateFlow(0L)
        override suspend fun getByMediaKey(mediaKey: String): PlaybackRecord? = applied.firstOrNull { it.media_key == mediaKey }
        override suspend fun getByMediaKeys(mediaKeys: List<String>): Map<String, PlaybackRecord> =
            applied.filter { it.media_key in mediaKeys }.associateBy { it.media_key }
        override suspend fun upsert(record: PlaybackRecord) { applied += record }
        override suspend fun upsertEntry(record: PlaybackRecord) { applied += record }
        override suspend fun finishPlayback(mediaKey: String, positionMs: Long, durationMs: Long, watchProgress: Double, isCompleted: Long, lastPlayedAt: Long) {}
        override suspend fun updatePosition(mediaKey: String, positionMs: Long, watchProgress: Double, lastPlayedAt: Long) {}
        override suspend fun updateDanmaku(mediaKey: String, episodeId: Long, animeId: Long, animeTitle: String, episodeTitle: String, matchMethod: String) {}
        override suspend fun listPage(limit: Long, offset: Long): List<PlaybackRecord> = applied
        override suspend fun getEpisodeProgressByTriple(tmdbId: Long, seasonNumber: Long, episodeNumber: Long): EpisodeProgress? =
            progress.firstOrNull { it.tmdb_id == tmdbId && it.season_number == seasonNumber && it.episode_number == episodeNumber }
        override suspend fun getEpisodeProgressByTriples(tripleKeys: List<String>): Map<String, EpisodeProgress> = emptyMap()
        override suspend fun deleteByKey(mediaKey: String) { applied.removeAll { it.media_key == mediaKey } }
        override suspend fun deleteAll() { applied.clear(); progress.clear() }
        override suspend fun count(): Long = applied.size.toLong()
        override suspend fun listAll(): List<PlaybackRecord> = applied
        override suspend fun listAllEpisodeProgress(): List<EpisodeProgress> = progress
        override suspend fun applyMergedRecord(record: PlaybackRecord) {
            // SQL 侧按 media_key UNIQUE force upsert(覆盖), 与真实仓库语义一致
            applied.removeAll { it.media_key == record.media_key }
            applied += record
        }
        override suspend fun applyMergedEpisodeProgress(progress: EpisodeProgress) { this.progress += progress }
        override suspend fun applyMergedRecordIfNewer(record: PlaybackRecord): Boolean {
            val existing = applied.firstOrNull { it.media_key == record.media_key }
            val wins = existing == null || record.sync_version > existing.sync_version ||
                (record.sync_version == existing.sync_version && record.last_played_at > existing.last_played_at)
            if (wins) {
                applied.removeAll { it.media_key == record.media_key }
                applied += record
            }
            return wins
        }
        override suspend fun applyMergedEpisodeProgressIfNewer(progress: EpisodeProgress): Boolean {
            val existing = this.progress.firstOrNull { it.tmdb_id == progress.tmdb_id && it.season_number == progress.season_number && it.episode_number == progress.episode_number }
            val wins = existing == null || progress.sync_version > existing.sync_version ||
                (progress.sync_version == existing.sync_version && progress.last_played_at > existing.last_played_at)
            if (wins) {
                this.progress.removeAll { it.tmdb_id == progress.tmdb_id && it.season_number == progress.season_number && it.episode_number == progress.episode_number }
                this.progress += progress
            }
            return wins
        }
    }

    private fun webDavRepository(vararg initial: WebDavConnection): WebDavConnectionRepository {
        var stored = initial.toList()
        return WebDavConnectionRepository(
            object : WebDavConnectionStore {
                override suspend fun loadAll(): List<WebDavConnection> = stored
                override suspend fun replaceAll(connections: List<WebDavConnection>) {
                    stored = connections
                }
            },
            fakeCipher,
        )
    }

    private fun emptyWebDavRepository(): WebDavConnectionRepository = webDavRepository()

    private fun unusedImporter(
        webDavRepository: WebDavConnectionRepository = emptyWebDavRepository(),
        smbRepository: SmbConnectionRepository? = null,
    ): LibraryImporter = LibraryImporter(
        scrapedRepo = unusedProxy(ScrapedLibraryRepository::class.java),
        webDavRepository = webDavRepository,
        smbRepository = smbRepository,
        playbackRepository = unusedProxy(PlaybackRecordRepository::class.java),
        imageService = unusedProxy(LibraryImageService::class.java),
        newConnectionId = { "new-connection" },
    )

    private fun sampleShow(): ShowExport = ShowExport(
        sourceKind = "WEBDAV", tmdbId = 12345L, folderName = "某番-12345",
        showPath = "/番剧/某番", title = "某番 第一季", year = 2024,
        isFavorite = 1L, scannedAt = 100L,
        exportShowCacheKey = "某番-12345",
        exportOnlineCacheKey = "online-scrape/1-_番剧_某番",
        seasons = listOf(
            SeasonExport(
                seasonNumber = 1, seasonPath = "/番剧/某番/Season 1",
                bangumiId = 9988L, episodeCount = 2,
                episodes = listOf(
                    EpisodeExport(1, title = "第1话", videoPath = "/番剧/某番/Season 1/01.mkv", videoName = "01.mkv", mediaKey = "webdav:conn-old:/番剧/某番/Season 1/01.mkv"),
                    EpisodeExport(2, title = "第2话", videoPath = "/番剧/某番/Season 1/02.mkv", videoName = "02.mkv", mediaKey = "webdav:conn-old:/番剧/某番/Season 1/02.mkv"),
                ),
                onlineMeta = OnlineMetaExport(
                    seasonNumber = 1, scrapeSource = "DANDANPLAY", scrapedAt = 200L,
                    dandanplayId = 5566L, remotePosterUrl = "https://img.example/p.jpg",
                    episodes = listOf(ScrapedOnlineEpisode(1, "第1话", aired = "2024-01-05")),
                ),
            ),
        ),
        onlineMeta = OnlineMetaExport(seasonNumber = 0, scrapeSource = "TMDB", scrapedAt = 300L, tmdbId = 12345L),
        bangumiLinks = listOf(
            BangumiLinkExport("tmdb-tv:12345:season:1", 9988L, "CONFIRMED", "MANUAL", null, 100L, 100L),
        ),
        overrideJson = """{"danmakuOpacity":0.8}""",
    )

    private fun playbackShow(
        mediaKey: String = "webdav:conn-old:/番剧/某番/Season 1/01.mkv",
        sourceKind: String = "WEBDAV",
        tmdbId: Long = 12345L,
    ): ShowExport = ShowExport(
        sourceKind = sourceKind,
        tmdbId = tmdbId,
        folderName = "某番",
        showPath = "/番剧/某番",
        title = "某番",
        seasons = listOf(
            SeasonExport(
                seasonNumber = 1,
                seasonPath = "/番剧/某番/Season 1",
                episodes = listOf(
                    EpisodeExport(
                        episodeNumber = 1,
                        videoPath = "/番剧/某番/Season 1/01.mkv",
                        videoName = "01.mkv",
                        mediaKey = mediaKey,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `导出-导入全流程保持数据并重映射连接与库 id`() = runBlocking {
        val parent = Files.createTempDirectory("unu-export-")
        val dbFile = parent.resolve("export.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val repository = ScrapedLibraryRepositoryImpl(UnuDatabase(driver).scrapedQueries)

            val webDavRepo = WebDavConnectionRepository(
                object : WebDavConnectionStore {
                    var conns = listOf(WebDavConnection("conn-old", "我的盘", "https://dav.example.com", "u", "p"))
                    override suspend fun loadAll(): List<WebDavConnection> = conns
                    override suspend fun replaceAll(connections: List<WebDavConnection>) { conns = connections }
                },
                fakeCipher,
            )
            val smbRepo = SmbConnectionRepository(
                object : SmbConnectionStore {
                    override suspend fun loadAll(): List<SmbConnection> = emptyList()
                    override suspend fun replaceAll(connections: List<SmbConnection>) {}
                },
                fakeCipher,
            )
            val playbackRepo = FakePlaybackRepository()
            val imageService = FakeImageService()

            // 1. 构造初始库数据(库 id=1)
            val initialLibId = repository.addLibrary(
                name = "动画库", sourceKind = MediaSourceKind.WEBDAV,
                connectionId = "conn-old", localUri = null, rootPath = "/番剧", scanDepth = 6,
            )
            repository.importLibraryFull(
                initialLibId,
                listOf(sampleShow()),
                emptyList(),
                sampleShow().bangumiLinks,
                listOf(ShowOverrideRow("tmdb:12345", """{"danmakuOpacity":0.8}""", 50L)),
            )
            val initialShow = repository.getShowByPath(initialLibId, "/番剧/某番")!!
            val initialSeason = repository.listSeasons(initialShow.id).single()
            assertEquals(0L, initialShow.scanned_at)
            assertEquals(0L, initialSeason.scanned_at)
            assertTrue(repository.listEpisodes(initialSeason.id).all { it.scanned_at == 0L })
            assertEquals(200L, repository.getOnlineMeta(initialLibId, "/番剧/某番", 1)?.scraped_at)

            val tmdbStill = Files.write(parent.resolve("tmdb-s1e1.jpg"), "tmdb-still".encodeToByteArray())
            val sourceSeasonMeta = repository.getOnlineMeta(initialLibId, "/番剧/某番", 1)!!
            repository.updateOnlineMetaEpisodes(
                initialLibId,
                "/番剧/某番",
                1,
                sourceSeasonMeta.decodedEpisodes.map { episode ->
                    if (episode.episodeNumber == 1) {
                        episode.copy(
                            thumbPath = tmdbStill.toAbsolutePath().toString(),
                            tmdbStillAvailable = true,
                        )
                    } else {
                        episode
                    }
                },
            )
            playbackRepo.applied += PlaybackRecord(
                id = 0, media_key = "webdav:conn-old:/番剧/某番/Season 1/01.mkv",
                source_kind = "WEBDAV", url = "https://dav.example.com/x.mkv", content_uri = null,
                title = "某番", position_ms = 1000, duration_ms = 60_000, watch_progress = 0.1,
                is_completed = 0, tmdb_id = 12345L, season_number = 1, episode_number = 1,
                danmaku_episode_id = null, danmaku_anime_id = null, danmaku_anime_title = null,
                danmaku_episode_title = null, danmaku_match_method = null,
                last_played_at = 500L, sync_status = 0, sync_version = 0,
                danmaku_sync_version = 0, danmaku_updated_at = 0,
            )

            // 2. 导出
            val exporter = LibraryExporter(repository, webDavRepo, null, playbackRepo, imageService)
            assertFailsWith<IllegalArgumentException> {
                exporter.exportLibrary(
                    initialLibId,
                    ExportOptions(includePassword = true, exportPassword = "short"),
                )
            }
            val outputWithImages = exporter.exportLibrary(
                initialLibId,
                ExportOptions(includeImages = true, includePlayback = false),
            )
            val exportedWithImages = LibraryExportCodec.decodeData(outputWithImages.dataJson)!!
            val exportedOnlineKey = exportedWithImages.shows.single().exportOnlineCacheKey!!
            assertEquals(
                onlineImageEntryName(exportedOnlineKey, "season1-episode1", "tmdb-s1e1.jpg"),
                outputWithImages.imageFiles.single().zipEntryName,
            )
            val exportedOnlineEpisode = exportedWithImages.shows.single().seasons.single()
                .onlineMeta!!.episodes.first { it.episodeNumber == 1 }
            assertNull(exportedOnlineEpisode.thumbPath)
            assertEquals(true, exportedOnlineEpisode.tmdbStillAvailable)

            val output = exporter.exportLibrary(initialLibId, ExportOptions(includePlayback = true))
            val exportedData = LibraryExportCodec.decodeData(output.dataJson)!!
            assertEquals(1, exportedData.shows.size)
            assertEquals("我的盘", exportedData.connection.name)
            assertEquals(1, exportedData.playback.size)
            assertEquals(1, exportedData.shows[0].seasons.size)
            assertEquals(2, exportedData.shows[0].seasons[0].episodes.size)
            assertEquals(50L, exportedData.shows[0].overrideUpdatedAt)
            // 密码开关：新格式绝不写明文，仅写迁移口令密文
            val protectedData = LibraryExportCodec.decodeData(
                exporter.exportLibrary(
                    initialLibId,
                    ExportOptions(includePassword = true, exportPassword = "migration-pass"),
                ).dataJson,
            )!!
            assertEquals(null, protectedData.connection.password)
            assertTrue(protectedData.connection.passwordEnvelope?.startsWith(LIBRARY_EXPORT_PASSWORD_PREFIX) == true)
            assertNotEquals("p", protectedData.connection.passwordEnvelope)
            val plainData = LibraryExportCodec.decodeData(
                exporter.exportLibrary(initialLibId, ExportOptions(includePassword = false)).dataJson,
            )!!
            assertEquals(null, plainData.connection.password)

            // 3. zip 写读
            val zipPath = parent.resolve("export.zip").toAbsolutePath().toString()
            LibraryZipOutput(zipPath).apply {
                putText("manifest.json", output.manifestJson)
                putText("data/library.json", output.dataJson)
                finish()
            }
            val importer = LibraryImporter(repository, webDavRepo, null, playbackRepo, imageService) { "conn-new" }
            val payload = importer.readZip(zipPath)!!
            assertEquals("动画库", payload.manifest.library.name)
            assertEquals(1, payload.manifest.content.shows)
            assertTrue(importer.resolveConnectionCandidate(payload.data) is ConnectionCandidate.Reuse)

            // 实际导入流程会先落下用户确认的新连接；播放 URL 只能从该目标连接重算。
            webDavRepo.add(WebDavConnection("conn-new", "新盘", "https://new.example.com/webdav", "u", "p"))

            // 4. 清库后导入(新连接 conn-new, 新库 id)
            repository.clearLibraryData(initialLibId)
            val newLibId = repository.addLibrary(
                name = "动画库-导入", sourceKind = MediaSourceKind.WEBDAV,
                connectionId = "conn-new", localUri = null, rootPath = "/番剧", scanDepth = 6,
            )
            val result = importer.importLibrary(payload.data, newLibId, "conn-new", ImportOptions())
            importer.importPlayback(payload.data, "conn-new")

            // 5. 断言数据一致 + 重映射
            val importedShows = repository.listShows(newLibId)
            assertEquals(1, importedShows.size)
            val show = repository.getShowByPath(newLibId, "/番剧/某番")!!
            assertEquals(12345L, show.tmdb_id)
            assertEquals(1L, show.is_favorite)
            assertEquals(0L, show.scanned_at)
            val season = repository.listSeasons(show.id).first()
            assertEquals(1L, season.season_number)
            assertEquals(9988L, season.bangumi_id)
            assertEquals(0L, season.scanned_at)
            val episodes = repository.listEpisodes(season.id)
            assertEquals(2, episodes.size)
            assertEquals("webdav:conn-new:/番剧/某番/Season 1/01.mkv", episodes.first().media_key)
            assertTrue(episodes.all { it.scanned_at == 0L })

            val seasonMeta = repository.getOnlineMeta(newLibId, "/番剧/某番", 1)!!
            assertEquals(5566L, seasonMeta.dandanplay_id)
            assertEquals("第1话", seasonMeta.decodedEpisodes.first().title)
            assertEquals(200L, seasonMeta.scraped_at)
            assertNull(seasonMeta.decodedEpisodes.first().thumbPath)
            assertEquals(true, seasonMeta.decodedEpisodes.first().tmdbStillAvailable)
            val showMeta = repository.getOnlineMeta(newLibId, "/番剧/某番", 0)!!
            assertEquals(12345L, showMeta.tmdb_id)

            // 关联: tmdb-tv 前缀跨设备有效, 导入后不变
            val remappedLink = repository.getBangumiSeasonLink("tmdb-tv:12345:season:1:offset:0")
            assertNotNull(remappedLink)
            assertEquals(9988L, remappedLink.subjectId)

            // 本部覆盖: tmdb 前缀跨设备有效
            assertEquals("""{"danmakuOpacity":0.8}""", repository.getShowOverrideJson("tmdb:12345"))

            // 播放进度: media_key 重映射到新连接(初始 1 条 + 导入写入 1 条)
            assertEquals(2, playbackRepo.applied.size)
            assertEquals("webdav:conn-old:/番剧/某番/Season 1/01.mkv", playbackRepo.applied.first().media_key)
            assertEquals("webdav:conn-new:/番剧/某番/Season 1/01.mkv", playbackRepo.applied.last().media_key)

            // 6. 图片还原(无 images/ 条目)
            val report = importer.restoreImages(zipPath, newLibId, payload.data, result.summary)
            assertEquals(0, report.restored)
            assertTrue(imageService.writtenShowImages.isEmpty())
        } finally {
            driver.close()
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }

    @Test
    fun `旧格式明文密码导入被拒绝`() {
        runBlocking {
            val parent = Files.createTempDirectory("unu-export-plain-")
            val zipPath = parent.resolve("plain.zip").toAbsolutePath().toString()
            try {
                val manifest = LibraryExportManifest(
                    formatVersion = 1,
                    exportedAt = 1L,
                    connection = ManifestConnection("WEBDAV", "盘"),
                    library = ManifestLibrary("库", "/", "NFO"),
                    content = ManifestContent(includePassword = true),
                )
                val data = LibraryExportData(
                    connection = ConnectionExport(
                        "WEBDAV",
                        "盘",
                        baseUrl = "https://dav.example.com",
                        password = "明文",
                        includePassword = true,
                    ),
                    library = LibraryExport(name = "库", rootPath = "/", scanDepth = 1, scanMode = "NFO"),
                    shows = emptyList(),
                )
                LibraryZipOutput(zipPath).apply {
                    putText("manifest.json", LibraryExportCodec.encodeManifest(manifest))
                    putText("data/library.json", LibraryExportCodec.encodeData(data))
                    finish()
                }
                assertFailsWith<IllegalArgumentException> { unusedImporter().readZip(zipPath) }
            } finally {
                Files.walk(parent).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
                }
            }
        }
    }

    @Test
    fun `超大 manifest 在解码前被拒绝`() = runBlocking {
        val parent = Files.createTempDirectory("unu-export-limit-")
        val zipPath = parent.resolve("oversized.zip").toAbsolutePath().toString()
        try {
            LibraryZipOutput(zipPath).apply {
                putText("manifest.json", " ".repeat(64 * 1024 + 1))
                finish()
            }
            assertFailsWith<IllegalArgumentException> { unusedImporter().readZip(zipPath) }
        } finally {
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
        Unit
    }

    @Test
    fun `HTTP WebDAV 新建连接必须显式授权`() = runBlocking {
        var stored = emptyList<WebDavConnection>()
        val repository = WebDavConnectionRepository(
            object : WebDavConnectionStore {
                override suspend fun loadAll(): List<WebDavConnection> = stored
                override suspend fun replaceAll(connections: List<WebDavConnection>) {
                    stored = connections
                }
            },
            fakeCipher,
        )
        val importer = unusedImporter(repository)
        val edit = ConnectionEdit.WebDav(
            name = "HTTP 盘",
            baseUrl = "http://dav.example.com/root",
            username = "user",
            password = "password",
        )

        assertFailsWith<IllegalArgumentException> { importer.createConnection(edit) }
        assertTrue(stored.isEmpty())

        assertEquals("new-connection", importer.createConnection(edit.copy(allowCleartext = true)))
        assertEquals("password", repository.loadAll().single().password)
        assertTrue(stored.single().password.startsWith("unu-sec:"))
    }

    @Test
    fun `WebDAV 同端点不同账号要求用户选择复用或新建`() = runBlocking {
        val repository = WebDavConnectionRepository(
            object : WebDavConnectionStore {
                override suspend fun loadAll(): List<WebDavConnection> = listOf(
                    WebDavConnection("existing", "现有账号", "https://dav.example.com/root", "alice", "secret"),
                )
                override suspend fun replaceAll(connections: List<WebDavConnection>) = Unit
            },
            fakeCipher,
        )
        val data = LibraryExportData(
            connection = ConnectionExport(
                type = "WEBDAV",
                name = "导入账号",
                baseUrl = "https://dav.example.com/root/",
                username = "bob",
            ),
            library = LibraryExport(name = "库", rootPath = "/", scanDepth = 1, scanMode = "NFO"),
            shows = emptyList(),
        )

        val candidate = assertIs<ConnectionCandidate.Choose>(
            unusedImporter(repository).resolveConnectionCandidate(data),
        )
        assertEquals("existing", candidate.reuse.connectionId)
        assertEquals("bob", assertIs<ConnectionEdit.WebDav>(candidate.create.edit).username)
    }

    @Test
    fun `WebDAV 同端点多账号优先复用后续可用精确账号`() = runBlocking {
        val repository = WebDavConnectionRepository(
            object : WebDavConnectionStore {
                override suspend fun loadAll(): List<WebDavConnection> = listOf(
                    WebDavConnection("alice", "账号 A", "https://dav.example.com/root", "alice", "secret-a"),
                    WebDavConnection("bob", "账号 B", "https://dav.example.com/root/", "bob", "secret-b"),
                )
                override suspend fun replaceAll(connections: List<WebDavConnection>) = Unit
            },
            fakeCipher,
        )
        val data = LibraryExportData(
            connection = ConnectionExport(
                type = "WEBDAV",
                name = "导入账号",
                baseUrl = "https://dav.example.com/root",
                username = "bob",
            ),
            library = LibraryExport(name = "库", rootPath = "/", scanDepth = 1, scanMode = "NFO"),
            shows = emptyList(),
        )

        val candidate = assertIs<ConnectionCandidate.Reuse>(
            unusedImporter(repository).resolveConnectionCandidate(data),
        )
        assertEquals("bob", candidate.connectionId)
    }

    @Test
    fun `WebDAV 精确账号凭据失效且无其它可用连接时强制新建`() = runBlocking {
        val brokenCipher = object : CredentialCipher {
            override fun isProtected(value: String): Boolean = value.startsWith("unu-sec:")
            override fun protect(purpose: String, plaintext: String): String = error("unused")
            override fun unprotect(purpose: String, protectedValue: String): String = error("凭据已失效")
        }
        val repository = WebDavConnectionRepository(
            object : WebDavConnectionStore {
                override suspend fun loadAll(): List<WebDavConnection> = listOf(
                    WebDavConnection(
                        "broken",
                        "失效账号",
                        "https://dav.example.com/root",
                        "bob",
                        "unu-sec:broken",
                    ),
                )
                override suspend fun replaceAll(connections: List<WebDavConnection>) = Unit
            },
            brokenCipher,
        )
        val data = LibraryExportData(
            connection = ConnectionExport(
                type = "WEBDAV",
                name = "导入账号",
                baseUrl = "https://dav.example.com/root",
                username = "bob",
            ),
            library = LibraryExport(name = "库", rootPath = "/", scanDepth = 1, scanMode = "NFO"),
            shows = emptyList(),
        )

        assertIs<ConnectionCandidate.Create>(unusedImporter(repository).resolveConnectionCandidate(data))
        Unit
    }

    @Test
    fun `SMB 同端点不同账号或安全配置要求用户选择`() = runBlocking {
        val repository = SmbConnectionRepository(
            object : SmbConnectionStore {
                override suspend fun loadAll(): List<SmbConnection> = listOf(
                    SmbConnection(
                        id = "existing",
                        name = "现有 SMB",
                        host = "nas.local",
                        share = "Anime",
                        username = "alice",
                        password = "secret",
                        domain = "HOME",
                        requireEncryption = false,
                    ),
                )
                override suspend fun replaceAll(connections: List<SmbConnection>) = Unit
            },
            fakeCipher,
        )
        val data = LibraryExportData(
            connection = ConnectionExport(
                type = "SMB",
                name = "导入 SMB",
                host = "NAS.LOCAL",
                port = 445,
                share = "Anime",
                username = "alice",
                domain = "HOME",
                requireEncryption = true,
            ),
            library = LibraryExport(name = "库", rootPath = "/", scanDepth = 1, scanMode = "NFO"),
            shows = emptyList(),
        )

        val candidate = assertIs<ConnectionCandidate.Choose>(
            unusedImporter(smbRepository = repository).resolveConnectionCandidate(data),
        )
        assertEquals("existing", candidate.reuse.connectionId)
        assertEquals(true, assertIs<ConnectionEdit.Smb>(candidate.create.edit).requireEncryption)
    }

    @Test
    fun `SMB 同端点多账号优先复用后续可用精确账号`() = runBlocking {
        val repository = SmbConnectionRepository(
            object : SmbConnectionStore {
                override suspend fun loadAll(): List<SmbConnection> = listOf(
                    SmbConnection(
                        id = "alice", name = "账号 A", host = "nas.local", share = "Anime",
                        username = "alice", password = "secret-a", domain = "HOME",
                    ),
                    SmbConnection(
                        id = "bob", name = "账号 B", host = "NAS.LOCAL", share = "Anime",
                        username = "bob", password = "secret-b", domain = "HOME", requireEncryption = true,
                    ),
                )
                override suspend fun replaceAll(connections: List<SmbConnection>) = Unit
            },
            fakeCipher,
        )
        val data = LibraryExportData(
            connection = ConnectionExport(
                type = "SMB", name = "导入 SMB", host = "nas.local", port = 445, share = "Anime",
                username = "bob", domain = "HOME", requireEncryption = true,
            ),
            library = LibraryExport(name = "库", rootPath = "/", scanDepth = 1, scanMode = "NFO"),
            shows = listOf(
                playbackShow(
                    mediaKey = "smb:conn-old:/番剧/某番/Season 1/01.mkv",
                    sourceKind = "SMB",
                ),
            ),
        )

        val candidate = assertIs<ConnectionCandidate.Reuse>(
            unusedImporter(smbRepository = repository).resolveConnectionCandidate(data),
        )
        assertEquals("bob", candidate.connectionId)
    }

    @Test
    fun `图片还原写缓存并回写 DB 局部路径`() = runBlocking {
        val parent = Files.createTempDirectory("unu-export-img-")
        val dbFile = parent.resolve("img.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val repository = ScrapedLibraryRepositoryImpl(UnuDatabase(driver).scrapedQueries)
            val webDavRepo = WebDavConnectionRepository(
                object : WebDavConnectionStore {
                    override suspend fun loadAll(): List<WebDavConnection> = emptyList()
                    override suspend fun replaceAll(connections: List<WebDavConnection>) {}
                },
                fakeCipher,
            )
            val smbRepo = SmbConnectionRepository(
                object : SmbConnectionStore {
                    override suspend fun loadAll(): List<SmbConnection> = emptyList()
                    override suspend fun replaceAll(connections: List<SmbConnection>) {}
                },
                fakeCipher,
            )
            val playbackRepo = FakePlaybackRepository()
            val imageService = FakeImageService()

            val data = LibraryExportData(
                connection = ConnectionExport(type = "WEBDAV", name = "盘", baseUrl = "https://dav.example.com", username = "u"),
                library = LibraryExport(libraryId = 1L, name = "动画库", rootPath = "/番剧", scanDepth = 6, scanMode = "NFO"),
                shows = listOf(sampleShow()),
            )
            val newLibId = repository.addLibrary("动画库", MediaSourceKind.WEBDAV, "conn-new", null, "/番剧", 6)
            val importer = LibraryImporter(repository, webDavRepo, smbRepo, playbackRepo, imageService) { "conn-new" }
            val result = importer.importLibrary(data, newLibId, "conn-new", ImportOptions())
            val sourceShow = data.shows.single()
            val sourceSeason = sourceShow.seasons.single()
            val sourceMeta = requireNotNull(sourceSeason.onlineMeta)
            // 模拟来源快照比目标库多一集、并多一个目标不存在的季度；写缓存前必须以目标 DB 为准。
            val restoreData = data.copy(
                shows = listOf(
                    sourceShow.copy(
                        seasons = listOf(
                            sourceSeason.copy(
                                onlineMeta = sourceMeta.copy(
                                    episodes = sourceMeta.episodes + ScrapedOnlineEpisode(2, "第2话"),
                                ),
                            ),
                            sourceSeason.copy(
                                seasonNumber = 2,
                                episodes = emptyList(),
                                onlineMeta = sourceMeta.copy(
                                    seasonNumber = 2,
                                    episodes = listOf(ScrapedOnlineEpisode(1, "第二季第1话")),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            // 构造带 images/ 条目的 zip(用 putText 直接写图片字节)
            val zipPath = parent.resolve("with-images.zip").toAbsolutePath().toString()
            LibraryZipOutput(zipPath).apply {
                putText("images/ep/某番-12345/s1e1.jpg", "ep-bytes")
                putText("images/online/online-scrape/1-_番剧_某番/poster-image.jpg", "poster-bytes")
                putText("images/online/online-scrape/1-_番剧_某番/fanart-image.jpg", "fanart-bytes")
                putText("images/online/online-scrape/1-_番剧_某番/season1-poster-image.jpg", "season-poster-bytes")
                putText(
                    "images/online/online-scrape/1-_番剧_某番/season1-episode1-image.jpg",
                    "still-bytes",
                )
                putText(
                    "images/online/online-scrape/1-_番剧_某番/season1-episode2-stale.jpg",
                    "stale-episode-bytes",
                )
                putText(
                    "images/online/online-scrape/1-_番剧_某番/season2-episode1-missing-season.jpg",
                    "missing-season-bytes",
                )
                finish()
            }

            val restored = importer.restoreImages(zipPath, newLibId, restoreData, result.summary)
            assertEquals(5, restored.restored)
            assertEquals(2, restored.skipped)
            assertEquals(1, imageService.finishRestoreCalls)

            // 集照: 写入 showKey 子目录 + DB local_thumb_path 回写
            val importedShow = repository.getShowByPath(newLibId, "/番剧/某番")!!
            val season = repository.listSeasons(importedShow.id).first()
            val episode = repository.listEpisodes(season.id).first()
            val newShowKey = "某番 第一季-12345"
            assertEquals(listOf(newShowKey to episode.id), imageService.writtenEpisodeThumbs)
            assertEquals("/fake/root/$newShowKey/ep${episode.id}.jpg", episode.local_thumb_path)

            // 季照: 写入新 onlineKey + 部级 meta local_poster_path 回写
            val newOnlineKey = "online-scrape/$newLibId-_番剧_某番"
            assertEquals(
                listOf(
                    Triple(newOnlineKey, "poster-image.jpg", "poster-bytes".encodeToByteArray().size),
                    Triple(newOnlineKey, "fanart-image.jpg", "fanart-bytes".encodeToByteArray().size),
                    Triple(newOnlineKey, "season1-poster-image.jpg", "season-poster-bytes".encodeToByteArray().size),
                    Triple(newOnlineKey, "season1-episode1-image.jpg", "still-bytes".encodeToByteArray().size),
                ),
                imageService.writtenShowImages,
            )
            val showMeta = repository.getOnlineMeta(newLibId, "/番剧/某番", 0)!!
            assertEquals("/fake/root/$newOnlineKey/poster-image.jpg", showMeta.local_poster_path)
            assertEquals("/fake/root/$newOnlineKey/fanart-image.jpg", showMeta.local_fanart_path)
            val seasonMeta = repository.getOnlineMeta(newLibId, "/番剧/某番", 1)!!
            assertEquals("/fake/root/$newOnlineKey/season1-poster-image.jpg", seasonMeta.local_poster_path)
            val onlineEpisode = seasonMeta.decodedEpisodes.first { it.episodeNumber == 1 }
            assertEquals("/fake/root/$newOnlineKey/season1-episode1-image.jpg", onlineEpisode.thumbPath)
            assertEquals(true, onlineEpisode.tmdbStillAvailable)

            // 普通在线图 DB 回写失败：只删除本轮新建文件，并且异常出口仍整理缓存。
            val posterFailureRepo = object : ScrapedLibraryRepository by repository {
                override suspend fun updateOnlineMetaLocalPoster(
                    libraryId: Long,
                    showPath: String,
                    seasonNumber: Int,
                    localPosterPath: String?,
                ): Unit = error("测试海报 DB 失败")
            }
            val posterFailureImages = FakeImageService()
            val posterFailureImporter = LibraryImporter(
                posterFailureRepo, webDavRepo, smbRepo, playbackRepo, posterFailureImages,
            ) { "conn-new" }
            val posterFailureZip = parent.resolve("poster-db-failure.zip").toAbsolutePath().toString()
            LibraryZipOutput(posterFailureZip).apply {
                putText(onlineImageEntryName(requireNotNull(sourceShow.exportOnlineCacheKey), "poster", "db-fail.jpg"), "poster")
                finish()
            }
            assertFailsWith<IllegalStateException> {
                posterFailureImporter.restoreImages(posterFailureZip, newLibId, data, result.summary)
            }
            assertEquals(
                listOf(newOnlineKey to "/fake/root/$newOnlineKey/poster-db-fail.jpg"),
                posterFailureImages.deletedShowImages,
            )
            assertEquals(1, posterFailureImages.finishRestoreCalls)

            val reusedPosterImages = FakeImageService().apply { showImageCreated = false }
            val reusedPosterImporter = LibraryImporter(
                posterFailureRepo, webDavRepo, smbRepo, playbackRepo, reusedPosterImages,
            ) { "conn-new" }
            assertFailsWith<IllegalStateException> {
                reusedPosterImporter.restoreImages(posterFailureZip, newLibId, data, result.summary)
            }
            assertTrue(reusedPosterImages.deletedShowImages.isEmpty(), "复用既有图片时 DB 失败不得删除目标")
            assertEquals(1, reusedPosterImages.finishRestoreCalls)

            // 本地集照 DB 回写失败：本轮新建文件同样补偿删除。
            val episodeFailureRepo = object : ScrapedLibraryRepository by repository {
                override suspend fun updateEpisodeLocalThumb(episodeId: Long, path: String?): Unit =
                    error("测试集照 DB 失败")
            }
            val episodeFailureImages = FakeImageService()
            val episodeFailureImporter = LibraryImporter(
                episodeFailureRepo, webDavRepo, smbRepo, playbackRepo, episodeFailureImages,
            ) { "conn-new" }
            val episodeFailureZip = parent.resolve("episode-db-failure.zip").toAbsolutePath().toString()
            LibraryZipOutput(episodeFailureZip).apply {
                putText(episodeImageEntryName(requireNotNull(sourceShow.exportShowCacheKey), 1, 1), "episode")
                finish()
            }
            assertFailsWith<IllegalStateException> {
                episodeFailureImporter.restoreImages(episodeFailureZip, newLibId, data, result.summary)
            }
            assertEquals(
                listOf(newShowKey to "/fake/root/$newShowKey/ep${episode.id}.jpg"),
                episodeFailureImages.deletedShowImages,
            )
            assertEquals(1, episodeFailureImages.finishRestoreCalls)

            // 两季在线集照都已落盘，第一季 merge 失败时，当前及后续未提交季的新文件必须全部清理。
            repository.upsertOnlineMeta(
                libraryId = newLibId,
                showPath = sourceShow.showPath,
                seasonNumber = 2,
                source = ScrapeSource.TMDB,
                overwriteTitle = false,
                dandanplayId = null,
                bangumiId = null,
                remotePosterUrl = null,
                localPosterPath = null,
                title = null,
                originalTitle = null,
                year = null,
                plot = null,
                rating = null,
                releaseDate = null,
                genres = emptyList(),
                studios = emptyList(),
                episodes = listOf(ScrapedOnlineEpisode(1, "第二季第1话")),
                scrapedAt = 1L,
            )
            val mergeFailureRepo = object : ScrapedLibraryRepository by repository {
                override suspend fun mergeOnlineMetaEpisodeThumbs(
                    libraryId: Long,
                    showPath: String,
                    seasonNumber: Int,
                    thumbPaths: Map<Int, String>,
                ): Set<Int> = error("测试分季 merge 失败")
            }
            val mergeFailureImages = FakeImageService()
            val mergeFailureImporter = LibraryImporter(
                mergeFailureRepo, webDavRepo, smbRepo, playbackRepo, mergeFailureImages,
            ) { "conn-new" }
            val mergeFailureZip = parent.resolve("season-merge-failure.zip").toAbsolutePath().toString()
            LibraryZipOutput(mergeFailureZip).apply {
                val onlineKey = requireNotNull(sourceShow.exportOnlineCacheKey)
                putText(onlineImageEntryName(onlineKey, onlineEpisodeImageRole(1, 1), "s1.jpg"), "s1")
                putText(onlineImageEntryName(onlineKey, onlineEpisodeImageRole(2, 1), "s2.jpg"), "s2")
                finish()
            }
            assertFailsWith<IllegalStateException> {
                mergeFailureImporter.restoreImages(mergeFailureZip, newLibId, restoreData, result.summary)
            }
            assertEquals(
                setOf(
                    newOnlineKey to "/fake/root/$newOnlineKey/season1-episode1-s1.jpg",
                    newOnlineKey to "/fake/root/$newOnlineKey/season2-episode1-s2.jpg",
                ),
                mergeFailureImages.deletedShowImages.toSet(),
            )
            assertEquals(1, mergeFailureImages.finishRestoreCalls)

            // 预检后、最终 DB 合并前目标集被并发移除：事务化合并应拒绝路径并撤销缓存。
            imageService.afterShowImageWrite = { _, basename, _ ->
                if (basename == "season1-episode1-race.jpg") {
                    repository.updateOnlineMetaEpisodes(newLibId, "/番剧/某番", 1, emptyList())
                }
            }
            val raceZipPath = parent.resolve("race-images.zip").toAbsolutePath().toString()
            LibraryZipOutput(raceZipPath).apply {
                putText(
                    "images/online/online-scrape/1-_番剧_某番/season1-episode1-race.jpg",
                    "race-bytes",
                )
                finish()
            }
            val raceReport = importer.restoreImages(raceZipPath, newLibId, data, result.summary)
            assertEquals(0, raceReport.restored)
            assertEquals(1, raceReport.skipped)
            assertEquals(
                listOf(newOnlineKey to "/fake/root/$newOnlineKey/season1-episode1-race.jpg"),
                imageService.deletedShowImages,
            )
            assertEquals(2, imageService.finishRestoreCalls)
        } finally {
            driver.close()
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }

    @Test
    fun `图片还原取消时仍回写已经落盘的在线集照`() = runBlocking {
        val parent = Files.createTempDirectory("unu-export-cancel-img-")
        val dbFile = parent.resolve("cancel-img.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val repository = ScrapedLibraryRepositoryImpl(UnuDatabase(driver).scrapedQueries)
            val webDavRepo = emptyWebDavRepository()
            val smbRepo = SmbConnectionRepository(
                object : SmbConnectionStore {
                    override suspend fun loadAll(): List<SmbConnection> = emptyList()
                    override suspend fun replaceAll(connections: List<SmbConnection>) {}
                },
                fakeCipher,
            )
            val sourceShow = sampleShow()
            val sourceSeason = sourceShow.seasons.single()
            val onlineEpisodes = listOf(1, 2).map { episodeNumber ->
                ScrapedOnlineEpisode(episodeNumber, "第${episodeNumber}话", aired = "2024-01-05")
            }
            val show = sourceShow.copy(
                seasons = listOf(
                    sourceSeason.copy(
                        onlineMeta = requireNotNull(sourceSeason.onlineMeta).copy(episodes = onlineEpisodes),
                    ),
                ),
            )
            val data = LibraryExportData(
                connection = ConnectionExport(
                    type = "WEBDAV",
                    name = "盘",
                    baseUrl = "https://dav.example.com",
                    username = "u",
                ),
                library = LibraryExport(
                    libraryId = 1L,
                    name = "动画库",
                    rootPath = "/番剧",
                    scanDepth = 6,
                    scanMode = "NFO",
                ),
                shows = listOf(show),
            )
            var writeCount = 0
            var finishCalled = false
            val cancellingImageService = object : LibraryImageService {
                override suspend fun listShowFiles(showKey: String): List<ImageFileEntry> = emptyList()
                override suspend fun writeShowImage(
                    showKey: String,
                    basename: String,
                    bytes: ByteArray,
                ): ImageWriteResult? {
                    writeCount++
                    if (writeCount == 2) throw CancellationException("测试取消")
                    return ImageWriteResult("/fake/root/$showKey/$basename", created = true)
                }
                override suspend fun writeEpisodeThumb(
                    showKey: String,
                    episodeId: Long,
                    bytes: ByteArray,
                ): ImageWriteResult? = error("unused")
                override suspend fun finishRestore() {
                    finishCalled = true
                }
            }
            val newLibraryId = repository.addLibrary(
                "动画库",
                MediaSourceKind.WEBDAV,
                "conn-new",
                null,
                "/番剧",
                6,
            )
            val importer = LibraryImporter(
                repository,
                webDavRepo,
                smbRepo,
                FakePlaybackRepository(),
                cancellingImageService,
            ) { "conn-new" }
            val result = importer.importLibrary(data, newLibraryId, "conn-new", ImportOptions())
            val exportOnlineKey = requireNotNull(show.exportOnlineCacheKey)
            val zipPath = parent.resolve("cancel-images.zip").toAbsolutePath().toString()
            LibraryZipOutput(zipPath).apply {
                putText(
                    onlineImageEntryName(exportOnlineKey, onlineEpisodeImageRole(1, 1), "first.jpg"),
                    "first-bytes",
                )
                putText(
                    onlineImageEntryName(exportOnlineKey, onlineEpisodeImageRole(1, 2), "second.jpg"),
                    "second-bytes",
                )
                finish()
            }

            assertFailsWith<CancellationException> {
                importer.restoreImages(zipPath, newLibraryId, data, result.summary)
            }

            val refreshed = assertNotNull(repository.getOnlineMeta(newLibraryId, show.showPath, 1))
            assertEquals(
                "/fake/root/online-scrape/$newLibraryId-_番剧_某番/season1-episode1-first.jpg",
                refreshed.decodedEpisodes.first { it.episodeNumber == 1 }.thumbPath,
            )
            assertNull(refreshed.decodedEpisodes.first { it.episodeNumber == 2 }.thumbPath)
            assertTrue(finishCalled, "取消出口也必须按当前配置整理缓存")
        } finally {
            driver.close()
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }

    @Test
    fun `ANCHOR 番剧的 show 前缀关联与覆盖按新库 id 重映射`() = runBlocking {
        val parent = Files.createTempDirectory("unu-export-anchor-")
        val dbFile = parent.resolve("anchor.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val repository = ScrapedLibraryRepositoryImpl(UnuDatabase(driver).scrapedQueries)
            val webDavRepo = WebDavConnectionRepository(
                object : WebDavConnectionStore {
                    override suspend fun loadAll(): List<WebDavConnection> =
                        listOf(WebDavConnection("conn-old", "盘", "https://dav.example.com", "u", "p"))
                    override suspend fun replaceAll(connections: List<WebDavConnection>) {}
                },
                fakeCipher,
            )
            val smbRepo = SmbConnectionRepository(
                object : SmbConnectionStore {
                    override suspend fun loadAll(): List<SmbConnection> = emptyList()
                    override suspend fun replaceAll(connections: List<SmbConnection>) {}
                },
                fakeCipher,
            )
            val playbackRepo = FakePlaybackRepository()
            val imageService = FakeImageService()

            // ANCHOR 番剧: 无 tmdbId -> link/override 用 show:<库id>:<path> 前缀
            val anchorShow = ShowExport(
                sourceKind = "WEBDAV", tmdbId = null, folderName = "锚点番",
                showPath = "/番剧/锚点番", title = "锚点番",
                exportShowCacheKey = "锚点番",
                exportOnlineCacheKey = "online-scrape/1-_番剧_锚点番",
                seasons = listOf(
                    SeasonExport(
                        seasonNumber = 1, seasonPath = "/番剧/锚点番/Season 1", episodeCount = 1,
                        episodes = listOf(
                            EpisodeExport(1, title = "第1话", videoPath = "/番剧/锚点番/Season 1/01.mkv", videoName = "01.mkv", mediaKey = "webdav:conn-old:/番剧/锚点番/Season 1/01.mkv"),
                        ),
                    ),
                ),
                bangumiLinks = listOf(
                    BangumiLinkExport("show:1:/番剧/锚点番:season:1", 7777L, "CONFIRMED", "AUTO", null, 100L, null),
                ),
                overrideJson = """{"danmakuFontSize":1.5}""",
            )
            val initialLibId = repository.addLibrary("锚点库", MediaSourceKind.WEBDAV, "conn-old", null, "/番剧", 6)
            repository.importLibraryFull(
                initialLibId, listOf(anchorShow), emptyList(),
                anchorShow.bangumiLinks,
                listOf(ShowOverrideRow("show:1:/番剧/锚点番", """{"danmakuFontSize":1.5}""", 50L)),
            )

            val exporter = LibraryExporter(repository, webDavRepo, smbRepo, playbackRepo, imageService)
            val output = exporter.exportLibrary(initialLibId, ExportOptions())
            val exported = LibraryExportCodec.decodeData(output.dataJson)!!
            assertEquals(1, exported.shows[0].bangumiLinks.size)
            assertEquals("show:1:/番剧/锚点番:season:1", exported.shows[0].bangumiLinks[0].identityKey)
            assertEquals("""{"danmakuFontSize":1.5}""", exported.shows[0].overrideJson)
            assertEquals(50L, exported.shows[0].overrideUpdatedAt)

            val zipPath = parent.resolve("anchor.zip").toAbsolutePath().toString()
            LibraryZipOutput(zipPath).apply {
                putText("manifest.json", output.manifestJson)
                putText("data/library.json", output.dataJson)
                finish()
            }
            val importer = LibraryImporter(repository, webDavRepo, smbRepo, playbackRepo, imageService) { "conn-new" }
            val payload = importer.readZip(zipPath)!!
            val newLibId = repository.addLibrary("锚点库-导入", MediaSourceKind.WEBDAV, "conn-new", null, "/番剧", 6)
            importer.importLibrary(payload.data, newLibId, "conn-new", ImportOptions())

            // show 前缀按新库 id 重映射
            val link = repository.getBangumiSeasonLink("show:$newLibId:/番剧/锚点番:season:1")
            assertNotNull(link)
            assertEquals(7777L, link.subjectId)
            assertEquals("""{"danmakuFontSize":1.5}""", repository.getShowOverrideJson("show:$newLibId:/番剧/锚点番"))
        } finally {
            driver.close()
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }

    @Test
    fun `导入播放记录时按目标连接重算WebDAV url`() = runBlocking {
        val webDavRepo = WebDavConnectionRepository(
            object : WebDavConnectionStore {
                override suspend fun loadAll(): List<WebDavConnection> =
                    listOf(WebDavConnection("conn-new", "新盘", "https://new.example.com/webdav", "u", "p"))
                override suspend fun replaceAll(connections: List<WebDavConnection>) {}
            },
            fakeCipher,
        )
        val playbackRepo = FakePlaybackRepository()
        val importer = LibraryImporter(
            scrapedRepo = unusedProxy(ScrapedLibraryRepository::class.java),
            webDavRepository = webDavRepo,
            smbRepository = null,
            playbackRepository = playbackRepo,
            imageService = unusedProxy(LibraryImageService::class.java),
            newConnectionId = { "conn-new" },
        )
        val data = LibraryExportData(
            connection = ConnectionExport("WEBDAV", "旧盘", baseUrl = "https://old.example.com", username = "u"),
            library = LibraryExport(name = "库", rootPath = "/番剧", scanDepth = 6, scanMode = "NFO"),
            shows = listOf(playbackShow()),
            playback = listOf(
                PlaybackExport(
                    mediaKey = "webdav:conn-old:/番剧/某番/Season 1/01.mkv",
                    sourceKind = "WEBDAV", url = "https://old.example.com/番剧/某番/Season 1/01.mkv",
                    title = "某番", positionMs = 1000, durationMs = 60_000, watchProgress = 0.1,
                    isCompleted = 0, lastPlayedAt = 500L, syncStatus = 0, syncVersion = 0,
                ),
            ),
        )

        importer.importPlayback(data, "conn-new")

        val merged = playbackRepo.applied.single()
        assertEquals("webdav:conn-new:/番剧/某番/Season 1/01.mkv", merged.media_key)
        assertTrue(
            merged.url.startsWith("https://new.example.com"),
            "url 应按目标连接 baseUrl 重算, 实际: ${merged.url}",
        )
        assertFalse(merged.url.contains("old.example.com"))
    }

    @Test
    fun `导入到同一连接id但端点已变更时仍重算WebDAV url`() = runBlocking {
        val webDavRepo = WebDavConnectionRepository(
            object : WebDavConnectionStore {
                override suspend fun loadAll(): List<WebDavConnection> =
                    listOf(WebDavConnection("conn-same", "新盘", "https://new.example.com/webdav", "u", "p"))
                override suspend fun replaceAll(connections: List<WebDavConnection>) {}
            },
            fakeCipher,
        )
        val playbackRepo = FakePlaybackRepository()
        val importer = LibraryImporter(
            scrapedRepo = unusedProxy(ScrapedLibraryRepository::class.java),
            webDavRepository = webDavRepo,
            smbRepository = null,
            playbackRepository = playbackRepo,
            imageService = unusedProxy(LibraryImageService::class.java),
            newConnectionId = { "conn-same" },
        )
        val data = LibraryExportData(
            connection = ConnectionExport("WEBDAV", "旧盘", baseUrl = "https://old.example.com", username = "u"),
            library = LibraryExport(name = "库", rootPath = "/番剧", scanDepth = 6, scanMode = "NFO"),
            shows = listOf(
                playbackShow(mediaKey = "webdav:conn-same:/番剧/某番/01.mkv"),
            ),
            playback = listOf(
                PlaybackExport(
                    mediaKey = "webdav:conn-same:/番剧/某番/01.mkv",
                    sourceKind = "WEBDAV", url = "https://old.example.com/旧路径.mkv",
                    title = "某番", positionMs = 1000, durationMs = 60_000, watchProgress = 0.1,
                    isCompleted = 0, lastPlayedAt = 500L, syncStatus = 0, syncVersion = 0,
                ),
            ),
        )

        importer.importPlayback(data, "conn-same")

        val merged = playbackRepo.applied.single()
        assertTrue(merged.url.startsWith("https://new.example.com"))
        assertFalse(merged.url.contains("old.example.com"))
        assertFalse(merged.url.contains("旧路径"))
    }

    @Test
    fun `目标WebDAV连接不存在时不回落导出包旧url`() = runBlocking {
        val playbackRepo = FakePlaybackRepository()
        val importer = LibraryImporter(
            scrapedRepo = unusedProxy(ScrapedLibraryRepository::class.java),
            webDavRepository = emptyWebDavRepository(),
            smbRepository = null,
            playbackRepository = playbackRepo,
            imageService = unusedProxy(LibraryImageService::class.java),
            newConnectionId = { "conn-new" },
        )
        val data = LibraryExportData(
            connection = ConnectionExport("WEBDAV", "旧盘", baseUrl = "https://old.example.com", username = "u"),
            library = LibraryExport(name = "库", rootPath = "/番剧", scanDepth = 6, scanMode = "NFO"),
            shows = listOf(playbackShow()),
            playback = listOf(
                PlaybackExport(
                    mediaKey = "webdav:conn-old:/番剧/某番/01.mkv",
                    sourceKind = "WEBDAV", url = "https://old.example.com/01.mkv",
                    title = "某番", positionMs = 1000, durationMs = 60_000, watchProgress = 0.1,
                    isCompleted = 0, lastPlayedAt = 500L, syncStatus = 0, syncVersion = 0,
                ),
            ),
        )

        importer.importPlayback(data, "conn-new")

        assertTrue(playbackRepo.applied.isEmpty())
    }

    @Test
    fun `播放导入拒绝会溢出本地时钟的逻辑版本`() = runBlocking {
        val playbackRepo = FakePlaybackRepository()
        val importer = LibraryImporter(
            scrapedRepo = unusedProxy(ScrapedLibraryRepository::class.java),
            webDavRepository = webDavRepository(
                WebDavConnection("conn-new", "新盘", "https://new.example.com", "u", "p"),
            ),
            smbRepository = null,
            playbackRepository = playbackRepo,
            imageService = unusedProxy(LibraryImageService::class.java),
            newConnectionId = { "conn-new" },
        )
        val data = LibraryExportData(
            connection = ConnectionExport("WEBDAV", "旧盘", baseUrl = "https://old.example.com", username = "u"),
            library = LibraryExport(name = "库", rootPath = "/番剧", scanDepth = 6, scanMode = "NFO"),
            shows = listOf(playbackShow()),
            playback = listOf(
                PlaybackExport(
                    mediaKey = "webdav:conn-old:/番剧/某番/Season 1/01.mkv",
                    sourceKind = "WEBDAV", url = "https://old.example.com/01.mkv",
                    title = "某番", positionMs = 1000, durationMs = 60_000, watchProgress = 0.1,
                    isCompleted = 0, lastPlayedAt = 500L, syncStatus = 0,
                    syncVersion = Long.MAX_VALUE,
                    danmakuSyncVersion = Long.MAX_VALUE,
                ),
            ),
            episodeProgress = listOf(
                EpisodeProgressExport(
                    tmdbId = 12345L,
                    seasonNumber = 1L,
                    episodeNumber = 1L,
                    mediaKey = "webdav:conn-old:/番剧/某番/Season 1/01.mkv",
                    positionMs = 1000,
                    durationMs = 60_000,
                    watchProgress = 0.1,
                    isCompleted = 0,
                    lastPlayedAt = 500L,
                    syncVersion = Long.MAX_VALUE,
                ),
            ),
        )

        importer.importPlayback(data, "conn-new")

        assertTrue(playbackRepo.applied.isEmpty())
        assertTrue(playbackRepo.progress.isEmpty())
    }

    @Test
    fun `播放导入应钳定时间戳并保护不超过当前时间`() = runBlocking {
        val playbackRepo = FakePlaybackRepository()
        var clockReads = 0
        val importNow = 123_456_789L
        val importer = LibraryImporter(
            scrapedRepo = unusedProxy(ScrapedLibraryRepository::class.java),
            webDavRepository = webDavRepository(
                WebDavConnection("conn-new", "新盘", "https://new.example.com", "u", "p"),
            ),
            smbRepository = null,
            playbackRepository = playbackRepo,
            imageService = unusedProxy(LibraryImageService::class.java),
            newConnectionId = { "conn-new" },
            nowMillis = { clockReads++; importNow },
        )
        val mediaKey = "webdav:conn-old:/番剧/某番/Season 1/01.mkv"
        val data = LibraryExportData(
            connection = ConnectionExport("WEBDAV", "旧盘", baseUrl = "https://old.example.com", username = "u"),
            library = LibraryExport(name = "库", rootPath = "/番剧", scanDepth = 6, scanMode = "NFO"),
            shows = listOf(playbackShow(mediaKey = mediaKey)),
            playback = listOf(
                PlaybackExport(
                    mediaKey = mediaKey,
                    sourceKind = "WEBDAV",
                    url = "https://old.example.com/01.mkv",
                    title = "某番",
                    positionMs = 1000,
                    durationMs = 60_000,
                    watchProgress = 0.1,
                    isCompleted = 0,
                    tmdbId = 12345L,
                    seasonNumber = 1L,
                    episodeNumber = 1L,
                    danmakuUpdatedAt = -1L,
                    lastPlayedAt = Long.MAX_VALUE,
                ),
            ),
            episodeProgress = listOf(
                EpisodeProgressExport(
                    tmdbId = 12345L,
                    seasonNumber = 1L,
                    episodeNumber = 1L,
                    mediaKey = mediaKey,
                    positionMs = 1000,
                    durationMs = 60_000,
                    watchProgress = 0.1,
                    isCompleted = 0,
                    lastPlayedAt = Long.MAX_VALUE,
                ),
            ),
        )

        importer.importPlayback(data, "conn-new")

        assertEquals(1, clockReads)
        val record = playbackRepo.applied.single()
        assertEquals(0L, record.danmaku_updated_at)
        assertEquals(importNow, record.last_played_at)
        assertEquals(importNow, playbackRepo.progress.single().last_played_at)
    }

    @Test
    fun `导入全局identity拒绝跨番剧注入且旧快照不覆盖目标端手动值`() = runBlocking {
        val parent = Files.createTempDirectory("unu-import-identity-")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${parent.resolve("identity.db").toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val repository = ScrapedLibraryRepositoryImpl(UnuDatabase(driver).scrapedQueries)
            val legacyIdentity = "tmdb-tv:12345:season:1"
            val existingIdentity = "tmdb-tv:12345:season:1:offset:0"
            repository.upsertBangumiSeasonLink(
                BangumiSeasonLink(
                    identityKey = legacyIdentity,
                    subjectId = 111L,
                    state = BangumiLinkState.CONFIRMED,
                    source = BangumiLinkSource.MANUAL,
                    evidence = "目标端手动选择",
                    updatedAt = 1_000L,
                    verifiedAt = 1_000L,
                ),
            )
            repository.upsertShowOverride("tmdb:12345", """{"danmakuOpacity":0.9}""", 1_000L)

            val importedShow = sampleShow().copy(
                bangumiLinks = listOf(
                    BangumiLinkExport(legacyIdentity, 222L, "CONFIRMED", "AUTO", null, 5_000L, 5_000L),
                    BangumiLinkExport("tmdb-tv:99999:season:1", 333L, "CONFIRMED", "MANUAL", null, 6_000L, 6_000L),
                ),
                overrideJson = """{"danmakuOpacity":0.2}""",
                overrideUpdatedAt = 500L,
            )
            val data = LibraryExportData(
                connection = ConnectionExport("WEBDAV", "旧盘", baseUrl = "https://old.example.com", username = "u"),
                library = LibraryExport(libraryId = 1L, name = "库", rootPath = "/番剧", scanDepth = 6, scanMode = "NFO"),
                shows = listOf(importedShow),
            )
            val newLibraryId = repository.addLibrary(
                "导入库", MediaSourceKind.WEBDAV, "conn-new", null, "/番剧", 6,
            )
            val importer = LibraryImporter(
                repository,
                emptyWebDavRepository(),
                null,
                FakePlaybackRepository(),
                FakeImageService(),
            ) { "conn-new" }

            importer.importLibrary(data, newLibraryId, "conn-new", ImportOptions())

            assertEquals(111L, repository.getBangumiSeasonLink(existingIdentity)?.subjectId)
            assertNull(repository.getBangumiSeasonLink("tmdb-tv:99999:season:1"))
            assertEquals("""{"danmakuOpacity":0.9}""", repository.getShowOverrideJson("tmdb:12345"))
        } finally {
            driver.close()
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }

    @Test
    fun `旧导出包不覆盖目标端更新的播放进度`() = runBlocking {
        val webDavRepo = WebDavConnectionRepository(
            object : WebDavConnectionStore {
                override suspend fun loadAll(): List<WebDavConnection> =
                    listOf(WebDavConnection("conn-new", "新盘", "https://new.example.com", "u", "p"))
                override suspend fun replaceAll(connections: List<WebDavConnection>) {}
            },
            fakeCipher,
        )
        val playbackRepo = FakePlaybackRepository().apply {
            applied += PlaybackRecord(
                id = 0, media_key = "webdav:conn-new:/番剧/某番/Season 1/01.mkv",
                source_kind = "WEBDAV", url = "https://new.example.com/x.mkv", content_uri = null,
                title = "某番", position_ms = 50_000, duration_ms = 60_000, watch_progress = 0.8,
                is_completed = 0, tmdb_id = null, season_number = null, episode_number = null,
                danmaku_episode_id = null, danmaku_anime_id = null, danmaku_anime_title = null,
                danmaku_episode_title = null, danmaku_match_method = null,
                last_played_at = 900L, sync_status = 0, sync_version = 10L,
                danmaku_sync_version = 0, danmaku_updated_at = 0,
            )
        }
        val importer = LibraryImporter(
            scrapedRepo = unusedProxy(ScrapedLibraryRepository::class.java),
            webDavRepository = webDavRepo,
            smbRepository = null,
            playbackRepository = playbackRepo,
            imageService = unusedProxy(LibraryImageService::class.java),
            newConnectionId = { "conn-new" },
        )
        val data = LibraryExportData(
            connection = ConnectionExport("WEBDAV", "旧盘", baseUrl = "https://old.example.com", username = "u"),
            library = LibraryExport(name = "库", rootPath = "/番剧", scanDepth = 6, scanMode = "NFO"),
            shows = listOf(playbackShow()),
            playback = listOf(
                PlaybackExport(
                    mediaKey = "webdav:conn-old:/番剧/某番/Season 1/01.mkv",
                    sourceKind = "WEBDAV", url = "https://old.example.com/x.mkv",
                    title = "某番", positionMs = 1000, durationMs = 60_000, watchProgress = 0.1,
                    isCompleted = 0, lastPlayedAt = 800L, syncStatus = 0, syncVersion = 5L,
                ),
            ),
        )

        importer.importPlayback(data, "conn-new")

        // 本地 sync_version(10) > 导出包(5), last_played_at(900 > 800): 不被覆盖, 不新增重复行
        assertEquals(1, playbackRepo.applied.size)
        assertEquals(50_000L, playbackRepo.applied.single().position_ms)
    }

    @Test
    fun `更新的导出包正常覆盖目标端播放进度`() = runBlocking {
        val webDavRepo = WebDavConnectionRepository(
            object : WebDavConnectionStore {
                override suspend fun loadAll(): List<WebDavConnection> =
                    listOf(WebDavConnection("conn-new", "新盘", "https://new.example.com", "u", "p"))
                override suspend fun replaceAll(connections: List<WebDavConnection>) {}
            },
            fakeCipher,
        )
        val playbackRepo = FakePlaybackRepository().apply {
            applied += PlaybackRecord(
                id = 0, media_key = "webdav:conn-new:/番剧/某番/Season 1/01.mkv",
                source_kind = "WEBDAV", url = "https://new.example.com/x.mkv", content_uri = null,
                title = "某番", position_ms = 50_000, duration_ms = 60_000, watch_progress = 0.8,
                is_completed = 0, tmdb_id = null, season_number = null, episode_number = null,
                danmaku_episode_id = null, danmaku_anime_id = null, danmaku_anime_title = null,
                danmaku_episode_title = null, danmaku_match_method = null,
                last_played_at = 900L, sync_status = 0, sync_version = 10L,
                danmaku_sync_version = 0, danmaku_updated_at = 0,
            )
        }
        val importer = LibraryImporter(
            scrapedRepo = unusedProxy(ScrapedLibraryRepository::class.java),
            webDavRepository = webDavRepo,
            smbRepository = null,
            playbackRepository = playbackRepo,
            imageService = unusedProxy(LibraryImageService::class.java),
            newConnectionId = { "conn-new" },
        )
        val data = LibraryExportData(
            connection = ConnectionExport("WEBDAV", "旧盘", baseUrl = "https://old.example.com", username = "u"),
            library = LibraryExport(name = "库", rootPath = "/番剧", scanDepth = 6, scanMode = "NFO"),
            shows = listOf(playbackShow()),
            playback = listOf(
                PlaybackExport(
                    mediaKey = "webdav:conn-old:/番剧/某番/Season 1/01.mkv",
                    sourceKind = "WEBDAV", url = "https://old.example.com/x.mkv",
                    title = "某番", positionMs = 55_000, durationMs = 60_000, watchProgress = 0.9,
                    isCompleted = 0, lastPlayedAt = 1000L, syncStatus = 0, syncVersion = 15L,
                ),
            ),
        )

        importer.importPlayback(data, "conn-new")

        // 导出包 sync_version(15) > 本地(10): 覆盖
        assertEquals(1, playbackRepo.applied.size)
        assertEquals(55_000L, playbackRepo.applied.single().position_ms)
    }

    @Test
    fun `EpisodeProgress导入按版本比较不覆盖更新进度`() = runBlocking {
        val webDavRepo = WebDavConnectionRepository(
            object : WebDavConnectionStore {
                override suspend fun loadAll(): List<WebDavConnection> = emptyList()
                override suspend fun replaceAll(connections: List<WebDavConnection>) {}
            },
            fakeCipher,
        )
        val playbackRepo = FakePlaybackRepository().apply {
            progress += EpisodeProgress(
                tmdb_id = 12345L, season_number = 1L, episode_number = 1L,
                media_key = "webdav:conn-new:/番剧/某番/Season 1/01.mkv",
                position_ms = 50_000, duration_ms = 60_000, watch_progress = 0.8,
                is_completed = 0, last_played_at = 900L, sync_status = 0, sync_version = 10L,
            )
        }
        val importer = LibraryImporter(
            scrapedRepo = unusedProxy(ScrapedLibraryRepository::class.java),
            webDavRepository = webDavRepo,
            smbRepository = null,
            playbackRepository = playbackRepo,
            imageService = unusedProxy(LibraryImageService::class.java),
            newConnectionId = { "conn-new" },
        )
        val data = LibraryExportData(
            connection = ConnectionExport("WEBDAV", "旧盘", baseUrl = "https://old.example.com", username = "u"),
            library = LibraryExport(name = "库", rootPath = "/番剧", scanDepth = 6, scanMode = "NFO"),
            shows = listOf(playbackShow()),
            episodeProgress = listOf(
                EpisodeProgressExport(
                    tmdbId = 12345L, seasonNumber = 1L, episodeNumber = 1L,
                    mediaKey = "webdav:conn-old:/番剧/某番/Season 1/01.mkv",
                    positionMs = 1000, durationMs = 60_000, watchProgress = 0.1,
                    isCompleted = 0, lastPlayedAt = 800L, syncStatus = 0, syncVersion = 5L,
                ),
            ),
        )

        importer.importPlayback(data, "conn-new")

        assertEquals(1, playbackRepo.progress.size)
        assertEquals(50_000L, playbackRepo.progress.single().position_ms)
    }

    @Test
    fun `remapSmbPlaybackUrl 替换smbfd url的连接id并保留path`() {
        val oldUrl = smbLocatorUrl("conn-old", "动画/第 01 集 [1080p].mkv")
        val remapped = remapSmbPlaybackUrl(oldUrl, "conn-new")
        assertNotNull(remapped)
        assertEquals(smbLocatorUrl("conn-new", "动画/第 01 集 [1080p].mkv"), remapped)
        // 非 SMB url 返回 null(调用方回落原 URL)
        assertNull(remapSmbPlaybackUrl("https://dav.example.com/x.mkv", "conn-new"))
    }

    @Test
    fun `导入播放记录时按新连接重算SMB url`() = runBlocking {
        val webDavRepo = WebDavConnectionRepository(
            object : WebDavConnectionStore {
                override suspend fun loadAll(): List<WebDavConnection> = emptyList()
                override suspend fun replaceAll(connections: List<WebDavConnection>) {}
            },
            fakeCipher,
        )
        val playbackRepo = FakePlaybackRepository()
        val importer = LibraryImporter(
            scrapedRepo = unusedProxy(ScrapedLibraryRepository::class.java),
            webDavRepository = webDavRepo,
            smbRepository = null,
            playbackRepository = playbackRepo,
            imageService = unusedProxy(LibraryImageService::class.java),
            newConnectionId = { "conn-new" },
        )
        val data = LibraryExportData(
            connection = ConnectionExport("SMB", "旧盘", host = "old-host", port = 445, share = "share", username = "u"),
            library = LibraryExport(name = "库", rootPath = "/番剧", scanDepth = 6, scanMode = "NFO"),
            shows = listOf(
                playbackShow(
                    mediaKey = "smb:conn-old:/番剧/某番/Season 1/01.mkv",
                    sourceKind = "SMB",
                ),
            ),
            playback = listOf(
                PlaybackExport(
                    mediaKey = "smb:conn-old:/番剧/某番/Season 1/01.mkv",
                    sourceKind = "SMB",
                    // URL 被损坏时也必须从已验证 media_key 重建，不能回落旧值。
                    url = "smbfd://损坏的旧定位",
                    title = "某番", positionMs = 1000, durationMs = 60_000, watchProgress = 0.1,
                    isCompleted = 0, lastPlayedAt = 500L, syncStatus = 0, syncVersion = 0,
                ),
            ),
        )

        importer.importPlayback(data, "conn-new")

        val merged = playbackRepo.applied.single()
        assertEquals("smb:conn-new:/番剧/某番/Season 1/01.mkv", merged.media_key)
        assertEquals(smbLocatorUrl("conn-new", "/番剧/某番/Season 1/01.mkv"), merged.url, "SMB url 应按目标 media_key 重算")
        assertFalse(merged.url.contains("conn-old"))
    }

    @Test
    fun `不属于导出连接的播放记录被跳过不产生ghost`() = runBlocking {
        val webDavRepo = WebDavConnectionRepository(
            object : WebDavConnectionStore {
                override suspend fun loadAll(): List<WebDavConnection> =
                    listOf(WebDavConnection("conn-new", "新盘", "https://new.example.com", "u", "p"))
                override suspend fun replaceAll(connections: List<WebDavConnection>) {}
            },
            fakeCipher,
        )
        val playbackRepo = FakePlaybackRepository()
        val importer = LibraryImporter(
            scrapedRepo = unusedProxy(ScrapedLibraryRepository::class.java),
            webDavRepository = webDavRepo,
            smbRepository = null,
            playbackRepository = playbackRepo,
            imageService = unusedProxy(LibraryImageService::class.java),
            newConnectionId = { "conn-new" },
        )
        val data = LibraryExportData(
            connection = ConnectionExport("WEBDAV", "旧盘", baseUrl = "https://old.example.com", username = "u"),
            library = LibraryExport(name = "库", rootPath = "/番剧", scanDepth = 6, scanMode = "NFO"),
            shows = listOf(playbackShow()),
            playback = listOf(
                // 属于导出主连接(conn-old): media_key 重映射 + url 重算
                PlaybackExport(
                    mediaKey = "webdav:conn-old:/番剧/某番/Season 1/01.mkv",
                    sourceKind = "WEBDAV", url = "https://old.example.com/01.mkv",
                    title = "某番", positionMs = 1000, durationMs = 60_000, watchProgress = 0.1,
                    isCompleted = 0, lastPlayedAt = 500L, syncStatus = 0, syncVersion = 0,
                ),
                // 另一连接(conn-other)的记录(篡改/损坏包夹带): 整条跳过, 不写引用未知连接 id 的 ghost 行
                PlaybackExport(
                    mediaKey = "webdav:conn-other:/别的库/02.mkv",
                    sourceKind = "WEBDAV", url = "https://other.example.com/02.mkv",
                    title = "别部", positionMs = 2000, durationMs = 60_000, watchProgress = 0.2,
                    isCompleted = 0, lastPlayedAt = 600L, syncStatus = 0, syncVersion = 0,
                ),
            ),
        )

        importer.importPlayback(data, "conn-new")

        val byKey = playbackRepo.applied.associateBy { it.media_key }
        val remapped = byKey.getValue("webdav:conn-new:/番剧/某番/Season 1/01.mkv")
        assertTrue(remapped.url.startsWith("https://new.example.com"), "主连接记录 url 应按新连接重算")
        assertFalse(
            playbackRepo.applied.any { it.media_key == "webdav:conn-other:/别的库/02.mkv" },
            "非主连接记录不应写入(ghost 记录防护)",
        )
    }

    @Test
    fun `播放导入只接受媒体键与三元组同时属于导入图谱的行`() = runBlocking {
        val playbackRepo = FakePlaybackRepository()
        val importer = LibraryImporter(
            scrapedRepo = unusedProxy(ScrapedLibraryRepository::class.java),
            webDavRepository = webDavRepository(
                WebDavConnection("conn-new", "新盘", "https://new.example.com", "u", "p"),
            ),
            smbRepository = null,
            playbackRepository = playbackRepo,
            imageService = unusedProxy(LibraryImageService::class.java),
            newConnectionId = { "conn-new" },
        )
        val validOldKey = "webdav:conn-old:/番剧/某番/Season 1/01.mkv"
        val foreignOldKey = "webdav:conn-old:/别的库/另一番/01.mkv"
        val data = LibraryExportData(
            connection = ConnectionExport("WEBDAV", "旧盘", baseUrl = "https://old.example.com", username = "u"),
            library = LibraryExport(name = "库", rootPath = "/番剧", scanDepth = 6, scanMode = "NFO"),
            shows = listOf(playbackShow(mediaKey = validOldKey)),
            playback = listOf(
                PlaybackExport(
                    mediaKey = validOldKey, sourceKind = "WEBDAV", url = "https://old.example.com/01.mkv",
                    title = "合法", positionMs = 1_000, durationMs = 60_000, watchProgress = 0.1,
                    isCompleted = 0, tmdbId = 12345L, seasonNumber = 1L, episodeNumber = 1L,
                    lastPlayedAt = 10L,
                ),
                PlaybackExport(
                    mediaKey = validOldKey, sourceKind = "WEBDAV", url = "https://old.example.com/01.mkv",
                    title = "错三元组", positionMs = 2_000, durationMs = 60_000, watchProgress = 0.2,
                    isCompleted = 0, tmdbId = 99999L, seasonNumber = 1L, episodeNumber = 1L,
                    lastPlayedAt = 20L,
                ),
                PlaybackExport(
                    mediaKey = foreignOldKey, sourceKind = "WEBDAV", url = "https://old.example.com/foreign.mkv",
                    title = "错媒体键", positionMs = 3_000, durationMs = 60_000, watchProgress = 0.3,
                    isCompleted = 0, tmdbId = 12345L, seasonNumber = 1L, episodeNumber = 1L,
                    lastPlayedAt = 30L,
                ),
            ),
            episodeProgress = listOf(
                EpisodeProgressExport(
                    tmdbId = 12345L, seasonNumber = 1L, episodeNumber = 1L, mediaKey = validOldKey,
                    positionMs = 1_000, durationMs = 60_000, watchProgress = 0.1,
                    isCompleted = 0, lastPlayedAt = 10L,
                ),
                EpisodeProgressExport(
                    tmdbId = 99999L, seasonNumber = 1L, episodeNumber = 1L, mediaKey = validOldKey,
                    positionMs = 2_000, durationMs = 60_000, watchProgress = 0.2,
                    isCompleted = 0, lastPlayedAt = 20L,
                ),
                EpisodeProgressExport(
                    tmdbId = 12345L, seasonNumber = 1L, episodeNumber = 1L, mediaKey = foreignOldKey,
                    positionMs = 3_000, durationMs = 60_000, watchProgress = 0.3,
                    isCompleted = 0, lastPlayedAt = 30L,
                ),
            ),
        )

        importer.importPlayback(data, "conn-new")

        assertEquals(1, playbackRepo.applied.size)
        assertEquals("合法", playbackRepo.applied.single().title)
        assertEquals(1, playbackRepo.progress.size)
        assertEquals(12345L, playbackRepo.progress.single().tmdb_id)
    }

    /** 构造 smbfd:// 播放 URL(java Base64 url-safe 无 padding, 与 SmbPlaybackLocator 同编码)。 */
    private fun smbLocatorUrl(connId: String, path: String): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        return "smbfd://" + enc.encodeToString(connId.toByteArray(Charsets.UTF_8)) + "/" +
            enc.encodeToString(path.toByteArray(Charsets.UTF_8))
    }
}
