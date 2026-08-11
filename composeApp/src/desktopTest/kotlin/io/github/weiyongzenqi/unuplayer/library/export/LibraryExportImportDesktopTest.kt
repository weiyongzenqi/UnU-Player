package io.github.weiyongzenqi.unuplayer.library.export

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.security.CredentialCipher
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepositoryImpl
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.ScrapedOnlineEpisode
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
        override suspend fun listShowFiles(showKey: String): List<ImageFileEntry> = emptyList()
        override suspend fun writeShowImage(showKey: String, basename: String, bytes: ByteArray): String? {
            writtenShowImages += Triple(showKey, basename, bytes.size)
            return "/fake/root/$showKey/$basename"
        }
        override suspend fun writeEpisodeThumb(showKey: String, episodeId: Long, bytes: ByteArray): String? {
            writtenEpisodeThumbs += showKey to episodeId
            return "/fake/root/$showKey/ep$episodeId.jpg"
        }
    }

    private class FakePlaybackRepository : PlaybackRecordRepository {
        val applied = mutableListOf<PlaybackRecord>()
        override val changeVersion: StateFlow<Long> = MutableStateFlow(0L)
        override suspend fun getByMediaKey(mediaKey: String): PlaybackRecord? = applied.firstOrNull { it.media_key == mediaKey }
        override suspend fun getByMediaKeys(mediaKeys: List<String>): Map<String, PlaybackRecord> =
            applied.filter { it.media_key in mediaKeys }.associateBy { it.media_key }
        override suspend fun upsert(record: PlaybackRecord) { applied += record }
        override suspend fun finishPlayback(mediaKey: String, positionMs: Long, durationMs: Long, watchProgress: Double, isCompleted: Long, lastPlayedAt: Long) {}
        override suspend fun updatePosition(mediaKey: String, positionMs: Long, watchProgress: Double, lastPlayedAt: Long) {}
        override suspend fun updateDanmaku(mediaKey: String, episodeId: Long, animeId: Long, animeTitle: String, episodeTitle: String, matchMethod: String) {}
        override suspend fun listPage(limit: Long, offset: Long): List<PlaybackRecord> = applied
        override suspend fun getEpisodeProgressByTriple(tmdbId: Long, seasonNumber: Long, episodeNumber: Long): EpisodeProgress? = null
        override suspend fun getEpisodeProgressByTriples(tripleKeys: List<String>): Map<String, EpisodeProgress> = emptyMap()
        override suspend fun deleteByKey(mediaKey: String) { applied.removeAll { it.media_key == mediaKey } }
        override suspend fun deleteAll() { applied.clear() }
        override suspend fun count(): Long = applied.size.toLong()
        override suspend fun listAll(): List<PlaybackRecord> = applied
        override suspend fun listAllEpisodeProgress(): List<EpisodeProgress> = emptyList()
        override suspend fun applyMergedRecord(record: PlaybackRecord) { applied += record }
        override suspend fun applyMergedEpisodeProgress(progress: EpisodeProgress) {}
    }

    private fun emptyWebDavRepository(): WebDavConnectionRepository = WebDavConnectionRepository(
        object : WebDavConnectionStore {
            override suspend fun loadAll(): List<WebDavConnection> = emptyList()
            override suspend fun replaceAll(connections: List<WebDavConnection>) {}
        },
        fakeCipher,
    )

    private fun unusedImporter(
        webDavRepository: WebDavConnectionRepository = emptyWebDavRepository(),
    ): LibraryImporter = LibraryImporter(
        scrapedRepo = unusedProxy(ScrapedLibraryRepository::class.java),
        webDavRepository = webDavRepository,
        smbRepository = null,
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
            playbackRepo.applied += PlaybackRecord(
                id = 0, media_key = "webdav:conn-old:/番剧/某番/Season 1/01.mkv",
                source_kind = "WEBDAV", url = "https://dav.example.com/x.mkv", content_uri = null,
                title = "某番", position_ms = 1000, duration_ms = 60_000, watch_progress = 0.1,
                is_completed = 0, tmdb_id = 12345L, season_number = 1, episode_number = 1,
                danmaku_episode_id = null, danmaku_anime_id = null, danmaku_anime_title = null,
                danmaku_episode_title = null, danmaku_match_method = null,
                last_played_at = 500L, sync_status = 0, sync_version = 0,
            )

            // 2. 导出
            val exporter = LibraryExporter(repository, webDavRepo, null, playbackRepo, imageService)
            assertFailsWith<IllegalArgumentException> {
                exporter.exportLibrary(
                    initialLibId,
                    ExportOptions(includePassword = true, exportPassword = "short"),
                )
            }
            val output = exporter.exportLibrary(initialLibId, ExportOptions(includePlayback = true))
            val exportedData = LibraryExportCodec.decodeData(output.dataJson)!!
            assertEquals(1, exportedData.shows.size)
            assertEquals("我的盘", exportedData.connection.name)
            assertEquals(1, exportedData.playback.size)
            assertEquals(1, exportedData.shows[0].seasons.size)
            assertEquals(2, exportedData.shows[0].seasons[0].episodes.size)
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
            val season = repository.listSeasons(show.id).first()
            assertEquals(1L, season.season_number)
            assertEquals(9988L, season.bangumi_id)
            val episodes = repository.listEpisodes(season.id)
            assertEquals(2, episodes.size)
            assertEquals("webdav:conn-new:/番剧/某番/Season 1/01.mkv", episodes.first().media_key)

            val seasonMeta = repository.getOnlineMeta(newLibId, "/番剧/某番", 1)!!
            assertEquals(5566L, seasonMeta.dandanplay_id)
            assertEquals("第1话", seasonMeta.decodedEpisodes.first().title)
            val showMeta = repository.getOnlineMeta(newLibId, "/番剧/某番", 0)!!
            assertEquals(12345L, showMeta.tmdb_id)

            // 关联: tmdb-tv 前缀跨设备有效, 导入后不变
            val remappedLink = repository.getBangumiSeasonLink("tmdb-tv:12345:season:1")
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

            // 构造带 images/ 条目的 zip(用 putText 直接写图片字节)
            val zipPath = parent.resolve("with-images.zip").toAbsolutePath().toString()
            LibraryZipOutput(zipPath).apply {
                putText("images/ep/某番-12345/s1e1.jpg", "ep-bytes")
                putText("images/online/online-scrape/1-_番剧_某番/poster-x.jpg", "poster-bytes")
                finish()
            }

            val restored = importer.restoreImages(zipPath, newLibId, data, result.summary)
            assertEquals(2, restored.restored)
            assertEquals(0, restored.skipped)

            // 集照: 写入 showKey 子目录 + DB local_thumb_path 回写
            val importedShow = repository.getShowByPath(newLibId, "/番剧/某番")!!
            val season = repository.listSeasons(importedShow.id).first()
            val episode = repository.listEpisodes(season.id).first()
            val newShowKey = "某番 第一季-12345"
            assertEquals(listOf(newShowKey to episode.id), imageService.writtenEpisodeThumbs)
            assertEquals("/fake/root/$newShowKey/ep${episode.id}.jpg", episode.local_thumb_path)

            // 季照: 写入新 onlineKey + 部级 meta local_poster_path 回写
            val newOnlineKey = "online-scrape/$newLibId-_番剧_某番"
            assertEquals(listOf(Triple(newOnlineKey, "x.jpg", "poster-bytes".encodeToByteArray().size)), imageService.writtenShowImages)
            val showMeta = repository.getOnlineMeta(newLibId, "/番剧/某番", 0)!!
            assertEquals("/fake/root/$newOnlineKey/x.jpg", showMeta.local_poster_path)
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
}
