package io.github.weiyongzenqi.unuplayer.library

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteDataSource
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkSource
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkState
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonIdentity
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabase
import io.github.weiyongzenqi.unuplayer.playback.configuredDesktopDataSource
import io.github.weiyongzenqi.unuplayer.playback.ensureCurrentDesktopSchema
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 节目专属设置覆盖基础设施单测(纯基础设施, 不接播放器/UI):
 * identity 键构造、稀疏 JSON 编解码、前向兼容、overlay 合并、repository 读写删往返。
 * DB harness 复用 DesktopMediaLibraryIntegrationTest 的写法(临时文件库 + Schema.create + 幂等补齐)。
 */
class ShowOverrideSettingsTest {

    @Test
    fun `identity键构造`() {
        assertEquals("tmdb:123", ShowOverrideIdentity.tmdb(123))
        assertEquals("show:5:/a/b", ShowOverrideIdentity.anchor(5, "/a/b"))
        assertEquals(ShowOverrideIdentity.anchor(5, "/a/b"), ShowOverrideIdentity.keyFor(null, 5, "/a/b"))
        assertEquals("tmdb:123", ShowOverrideIdentity.keyFor(123, 5, "/a/b"))
    }

    @Test
    fun `稀疏编码省略null且可往返`() {
        val original = ShowOverrideSettings(danmakuFontSize = 24f)
        val encoded = ShowOverrideJson.encode(original)
        // encodeDefaults 默认 false: null 字段省略, 编码结果不应含未设置的 danmakuOpacity
        assertFalse(encoded.contains("danmakuOpacity"), "null 字段应被省略: $encoded")
        assertEquals(original, ShowOverrideJson.decode(encoded))
    }

    @Test
    fun `前向兼容忽略未知字段`() {
        val decoded = ShowOverrideJson.decode("""{"danmakuFontSize":24.0,"futureField":1}""")
        assertEquals(24f, decoded?.danmakuFontSize)
    }

    @Test
    fun `弹幕匹配优先级字段稀疏编码往返且旧JSON缺字段为跟随全局`() {
        val original = ShowOverrideSettings(
            danmakuMatchPriority = listOf("HASH", "TMDB_DATABASE"),
        )
        val encoded = ShowOverrideJson.encode(original)
        assertFalse(encoded.contains("danmakuOpacity"), "null 字段应被省略: $encoded")
        assertEquals(original, ShowOverrideJson.decode(encoded))

        // 旧版本写入的 JSON 无此字段 -> 解码为 null(跟随全局)
        assertNull(ShowOverrideJson.decode("""{"danmakuFontSize":24.0}""")?.danmakuMatchPriority)
        // 该字段非 null 时覆盖非空
        assertFalse(original.isEmpty())
    }

    @Test
    fun `字幕音轨字段稀疏编码往返`() {
        // 1. 仅设字幕缩放: 其余字幕/音轨字段(null)应被省略, 编码结果不含 subtitleBorderSize
        val original = ShowOverrideSettings(subtitleScale = 2.5f)
        val encoded = ShowOverrideJson.encode(original)
        assertFalse(encoded.contains("subtitleBorderSize"), "null 字段应被省略: $encoded")
        assertFalse(encoded.contains("defaultAudioTrackPattern"), "null 字段应被省略: $encoded")
        assertEquals(original, ShowOverrideJson.decode(encoded))

        // 2. isEmpty: 任一字幕/音轨字段非 null 即非空; 全 null 为空
        assertFalse(ShowOverrideSettings(subtitleScale = 2.5f, defaultAudioTrackPattern = ".*jpn.*").isEmpty())
        assertTrue(ShowOverrideSettings().isEmpty())
    }

    @Test
    fun `overlay合并非null覆盖null回落`() {
        val base = DanmakuConfig()
        val merged = base.withOverride(ShowOverrideSettings(danmakuFontSize = 24f, danmakuStrokeWidth = 4f))
        assertEquals(24f, merged.fontSize)
        assertEquals(4f, merged.strokeWidth)
        // 未覆盖字段回落全局默认
        assertEquals(base.opacity, merged.opacity)
        assertEquals(base.maxOnScreen, merged.maxOnScreen)
        assertEquals(base.engineType, merged.engineType)
        // null 覆盖与空覆盖均不改变原配置
        assertEquals(base, base.withOverride(null))
        assertEquals(base, base.withOverride(ShowOverrideSettings()))
    }

    @Test
    fun `差分写入仅记变动字段且保持稀疏`() {
        // 1. 仅变动字段写入, 其余保持 null(稀疏)
        assertEquals(
            ShowOverrideSettings(danmakuFontSize = 24f),
            ShowOverrideSettings().diffUpdate(DanmakuConfig(), DanmakuConfig().copy(fontSize = 24f)),
        )
        // 2. 链式: 保留已有覆盖 + 新增变动字段
        assertEquals(
            ShowOverrideSettings(danmakuFontSize = 24f, danmakuStrokeWidth = 4f),
            ShowOverrideSettings(danmakuStrokeWidth = 4f)
                .diffUpdate(DanmakuConfig(), DanmakuConfig().copy(fontSize = 24f)),
        )
        // 3. 无变动: 有效配置旧==新, 覆盖不被写入(保持空)
        val cfg = DanmakuConfig(fontSize = 30f, strokeWidth = 5f)
        assertEquals(ShowOverrideSettings(), ShowOverrideSettings().diffUpdate(cfg, cfg))
    }

    @Test
    fun `repository覆盖读写删往返`() = runBlocking {
        val parent = Files.createTempDirectory("unu-show-override-")
        val dbFile = parent.resolve("override.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val repository = ScrapedLibraryRepositoryImpl(UnuDatabase(driver).scrapedQueries)

            // 初始无记录
            assertNull(repository.getShowOverrideJson("tmdb:1"))

            // 写入后可读回
            val json1 = ShowOverrideJson.encode(ShowOverrideSettings(danmakuFontSize = 24f))
            repository.upsertShowOverride("tmdb:1", json1, 111)
            assertEquals(json1, repository.getShowOverrideJson("tmdb:1"))

            // 再 upsert 整行替换(INSERT OR REPLACE 幂等)
            val json2 = ShowOverrideJson.encode(ShowOverrideSettings(danmakuOpacity = 0.5f))
            repository.upsertShowOverride("tmdb:1", json2, 222)
            assertEquals(json2, repository.getShowOverrideJson("tmdb:1"))

            // 清除后归 null
            repository.clearShowOverride("tmdb:1")
            assertNull(repository.getShowOverrideJson("tmdb:1"))
        } finally {
            driver.close()
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }

    @Test
    fun `deleteLibrary 清理键控的覆盖与季关联孤儿行`() {
        runBlocking {
        val parent = Files.createTempDirectory("unu-del-library-")
        val dbFile = parent.resolve("del.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val repository = ScrapedLibraryRepositoryImpl(UnuDatabase(driver).scrapedQueries)

            // 建库(无 show, 不触发 PosterCache 清理路径)
            val libraryId = repository.addLibrary(
                name = "测试库",
                sourceKind = MediaSourceKind.WEBDAV,
                connectionId = "conn-1",
                localUri = null,
                rootPath = "/dav/anime",
                scanDepth = 3,
            )
            // 该库的覆盖设置与 Bangumi 季关联(identity_key 以 "show:<libId>:" 前缀键控, 无 FK 级联)
            val overrideKey = ShowOverrideIdentity.anchor(libraryId, "/dav/anime/Show")
            val json = ShowOverrideJson.encode(ShowOverrideSettings(danmakuFontSize = 24f))
            repository.upsertShowOverride(overrideKey, json, 111)
            repository.upsertBangumiSeasonLink(
                BangumiSeasonLink(
                    identityKey = overrideKey,
                    subjectId = 400602,
                    state = BangumiLinkState.CONFIRMED,
                    source = BangumiLinkSource.AUTO,
                    evidence = "high-confidence",
                    updatedAt = 100,
                    verifiedAt = 100,
                ),
            )
            // 另一库的覆盖(不应被误删)
            val otherLibraryId = repository.addLibrary(
                name = "另一库",
                sourceKind = MediaSourceKind.WEBDAV,
                connectionId = "conn-2",
                localUri = null,
                rootPath = "/dav/other",
                scanDepth = 3,
            )
            val otherKey = ShowOverrideIdentity.anchor(otherLibraryId, "/dav/other/Show")
            repository.upsertShowOverride(otherKey, json, 222)

            // B-4 修复前: deleteLibrary 不清理两表 -> 覆盖/关联孤儿行残留
            repository.deleteLibrary(libraryId)

            assertNull(repository.getLibrary(libraryId), "库行应删除")
            assertNull(repository.getShowOverrideJson(overrideKey), "删除库后覆盖设置不应残留")
            assertNull(repository.getBangumiSeasonLink(overrideKey), "删除库后 Bangumi 季关联不应残留")
            // 另一库数据不受影响
            assertNotNull(repository.getLibrary(otherLibraryId), "另一库不应被误删")
            assertNotNull(repository.getShowOverrideJson(otherKey), "另一库的覆盖不应被误删")
        } finally {
            driver.close()
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
        }
    }

    @Test
    fun `updateSeasonBangumiOffset 更新漂移并迁移关联到新键`() {
        runBlocking {
        val parent = Files.createTempDirectory("unu-offset-")
        val dbFile = parent.resolve("offset.db")
        val dataSource = configuredDesktopDataSource(
            SQLiteDataSource().apply { url = "jdbc:sqlite:${dbFile.toAbsolutePath()}" },
        )
        val driver = dataSource.asJdbcDriver()
        try {
            UnuDatabase.Schema.create(driver)
            ensureCurrentDesktopSchema(dataSource)
            val repository = ScrapedLibraryRepositoryImpl(UnuDatabase(driver).scrapedQueries)

            val libraryId = repository.addLibrary(
                name = "测试库", sourceKind = MediaSourceKind.WEBDAV, connectionId = "conn-1",
                localUri = null, rootPath = "/dav/anime", scanDepth = 3,
            )
            val showPath = "/dav/anime/【我推的孩子】 第二季"
            val showId = repository.upsertShow(
                libraryId = libraryId, sourceKind = MediaSourceKind.WEBDAV, tmdbId = 203737L,
                folderName = "【我推的孩子】 第二季", showPath = showPath,
                title = "【我推的孩子】", originalTitle = null, year = null, plot = null, rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                posterPath = null, fanartPath = null, clearlogoPath = null, scannedAt = 1L,
                seasons = listOf(
                    SeasonScanData(
                        nfo = SeasonNfo(seasonNumber = 2, title = null, year = null, releaseDate = null),
                        bangumi = BangumiIni(id = 443428L, offset = -11),
                        seasonPath = "$showPath/Season 2",
                        seasonPosterPath = null,
                        episodes = listOf(
                            EpisodeNfo(null, null, null, null, null, 1, 2, null) to EpisodeFile(
                                videoPath = "$showPath/Season 2/e1.mkv", videoName = "e1.mkv",
                                thumbPath = null, mediaKey = null, fileSize = 1L,
                            ),
                        ),
                    ),
                ),
            )
            val season = repository.listSeasons(showId).single()
            assertEquals(-11L, season.bangumi_offset)

            val oldKey = BangumiSeasonIdentity.keyFor(203737L, libraryId, showPath, 2, -11)
            repository.upsertBangumiSeasonLink(
                BangumiSeasonLink(
                    identityKey = oldKey,
                    subjectId = 443428L,
                    state = BangumiLinkState.CONFIRMED,
                    source = BangumiLinkSource.MANUAL,
                    evidence = "user-confirmed",
                    updatedAt = 1L,
                    verifiedAt = 1L,
                ),
            )

            repository.updateSeasonBangumiOffset(
                libraryId = libraryId, showPath = showPath, tmdbId = 203737L,
                seasonId = season.id, seasonNumber = 2, newOffset = 0L,
            )

            assertEquals(0L, repository.listSeasons(showId).single().bangumi_offset)
            // 旧键行保留(复制语义): 重新扫描把漂移改回 ini 值时旧键重新生效, 手动选择不丢。
            assertNotNull(
                repository.getBangumiSeasonLink(oldKey),
                "旧 offset 键的关联应保留供漂移改回后复用",
            )
            val migrated = repository.getBangumiSeasonLink(
                BangumiSeasonIdentity.keyFor(203737L, libraryId, showPath, 2, 0),
            )
            assertEquals(443428L, migrated?.subjectId, "手动关联应复制到新键下")
            assertEquals(BangumiLinkSource.MANUAL, migrated?.source)
        } finally {
            driver.close()
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
        }
    }
}
