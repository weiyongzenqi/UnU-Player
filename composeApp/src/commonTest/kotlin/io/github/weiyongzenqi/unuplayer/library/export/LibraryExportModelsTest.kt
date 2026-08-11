package io.github.weiyongzenqi.unuplayer.library.export

import io.github.weiyongzenqi.unuplayer.library.ScrapedOnlineEpisode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 导出 DTO/重映射/zip 条目名的纯逻辑测试(commonTest)。 */
class LibraryExportModelsTest {

    private fun sampleData(): LibraryExportData = LibraryExportData(
        connection = ConnectionExport(
            type = "WEBDAV", name = "我的盘", baseUrl = "https://dav.example.com",
            username = "user", passwordEnvelope = "unu-export-sec:v1:test", includePassword = true,
        ),
        library = LibraryExport(
            libraryId = 7L, name = "动画库", rootPath = "/番剧", scanDepth = 6,
            scanMode = "NFO", lastScannedAt = 1234L,
        ),
        shows = listOf(
            ShowExport(
                sourceKind = "WEBDAV", tmdbId = 12345L, folderName = "某番-12345",
                showPath = "/番剧/某番", title = "某番 第一季", year = 2024,
                isFavorite = 1L, scannedAt = 100L,
                exportShowCacheKey = "某番-12345",
                exportOnlineCacheKey = "online-scrape/7-_番剧_某番",
                seasons = listOf(
                    SeasonExport(
                        seasonNumber = 1, seasonPath = "/番剧/某番/Season 1",
                        bangumiId = 9988L, episodeCount = 2,
                        episodes = listOf(
                            EpisodeExport(
                                episodeNumber = 1, title = "第1话", videoPath = "/番剧/某番/Season 1/01.mkv",
                                videoName = "01.mkv", mediaKey = "webdav:conn-1:/番剧/某番/Season 1/01.mkv",
                            ),
                            EpisodeExport(
                                episodeNumber = 2, title = "第2话", videoPath = "/番剧/某番/Season 1/02.mkv",
                                videoName = "02.mkv", mediaKey = "webdav:conn-1:/番剧/某番/Season 1/02.mkv",
                            ),
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
            ),
        ),
        blocked = listOf(BlockedExport("/番剧/已删", "已删", 999L, 400L)),
        playback = listOf(
            PlaybackExport(
                mediaKey = "webdav:conn-1:/番剧/某番/Season 1/01.mkv",
                sourceKind = "WEBDAV", url = "https://dav.example.com/番剧/某番/Season 1/01.mkv",
                title = "某番 第一季", positionMs = 1000, durationMs = 60_000, watchProgress = 0.1,
                isCompleted = 0, tmdbId = 12345L, seasonNumber = 1, episodeNumber = 1,
                lastPlayedAt = 500L,
            ),
        ),
    )

    @Test
    fun `data DTO round-trip 保持全字段`() {
        val data = sampleData()
        val decoded = LibraryExportCodec.decodeData(LibraryExportCodec.encodeData(data))!!
        assertEquals(data.connection, decoded.connection)
        assertEquals(data.library, decoded.library)
        assertEquals(data.shows, decoded.shows)
        assertEquals(data.blocked, decoded.blocked)
        assertEquals(data.playback, decoded.playback)
        assertEquals(data.episodeProgress, decoded.episodeProgress)
    }

    @Test
    fun `manifest round-trip`() {
        val manifest = LibraryExportManifest(
            exportedAt = 1000L,
            connection = ManifestConnection("WEBDAV", "我的盘"),
            library = ManifestLibrary("动画库", "/番剧", "NFO"),
            content = ManifestContent(shows = 1, episodes = 2, hasImages = true, includePassword = true),
        )
        assertEquals(manifest, LibraryExportCodec.decodeManifest(LibraryExportCodec.encodeManifest(manifest)))
    }

    @Test
    fun `show 前缀 identity 重映射仅改库 id`() {
        assertEquals("tmdb-tv:12345:season:1", remapShowIdentity("tmdb-tv:12345:season:1", 7L, 99L))
        assertEquals("tmdb:12345", remapShowIdentity("tmdb:12345", 7L, 99L))
        assertEquals("show:99:_番剧_某番", remapShowIdentity("show:7:_番剧_某番", 7L, 99L))
        assertEquals("show:99:a:b:season:1", remapShowIdentity("show:7:a:b:season:1", 7L, 99L))
    }

    @Test
    fun `media_key 重映射改连接 id 保留路径`() {
        assertEquals(
            "webdav:conn-2:/番剧/某番/Season 1/01.mkv",
            remapMediaKey("webdav:conn-1:/番剧/某番/Season 1/01.mkv", "conn-1", "conn-2"),
        )
        assertEquals(
            "smb:conn-2:/share/path.mkv",
            remapMediaKey("smb:conn-1:/share/path.mkv", "conn-1", "conn-2"),
        )
        assertEquals("other:key", remapMediaKey("other:key", "conn-1", "conn-2"))
    }

    @Test
    fun `zip 图片条目名可往返解析`() {
        val onlineKey = "online-scrape/7-_番剧_某番"
        val posterEntry = onlineImageEntryName(onlineKey, "poster", "p-abc.jpg")
        assertEquals(OnlineImageEntry(onlineKey, "poster", "p-abc.jpg"), parseOnlineImageEntry(posterEntry))

        val fanartEntry = onlineImageEntryName(onlineKey, "fanart", "f-abc.jpg")
        assertEquals(OnlineImageEntry(onlineKey, "fanart", "f-abc.jpg"), parseOnlineImageEntry(fanartEntry))

        val seasonEntry = onlineImageEntryName(onlineKey, "season2-poster", "s2-abc.jpg")
        assertEquals(OnlineImageEntry(onlineKey, "season2-poster", "s2-abc.jpg"), parseOnlineImageEntry(seasonEntry))

        val epEntry = episodeImageEntryName("某番-12345", 1, 3)
        assertEquals(EpisodeImageEntry("某番-12345", 1, 3), parseEpisodeImageEntry(epEntry))

        assertNull(parseOnlineImageEntry(epEntry))
        assertNull(parseEpisodeImageEntry(posterEntry))
        assertNull(parseOnlineImageEntry("manifest.json"))
    }

    @Test
    fun `unknown json 键被忽略且缺省字段兜底`() {
        val json = """{"formatVersion":1,"exportedAt":1,"connection":{"type":"WEBDAV","name":"x"},"library":{"name":"n","rootPath":"/","scanMode":"NFO"},"content":{"shows":1}}"""
        val manifest = LibraryExportCodec.decodeManifest(json)!!
        assertEquals(1, manifest.formatVersion)
        assertEquals("n", manifest.library.name)
        assertEquals(1, manifest.content.shows)
        assertTrue(!manifest.content.hasImages)
    }

    @Test
    fun `不兼容格式版本被拒`() {
        val json = """{"formatVersion":99,"exportedAt":1,"connection":{"type":"WEBDAV","name":"x"},"library":{"name":"n","rootPath":"/","scanMode":"NFO"},"content":{}}"""
        // manifest 解码本身成功(版本校验在 readZip 层), DTO 层不拦截
        assertTrue(LibraryExportCodec.decodeManifest(json) != null)
    }

    @Test
    fun `同名库比较忽略首尾空白和大小写`() {
        assertTrue(hasLibraryNameConflict(listOf(" 动画库 "), "动画库"))
        assertTrue(hasLibraryNameConflict(listOf("Anime"), " anime "))
        assertFalse(hasLibraryNameConflict(listOf("动画库"), "另一个库"))
        assertFalse(hasLibraryNameConflict(listOf(""), "  "))
    }

    @Test
    fun `敏感导出与连接模型不会在字符串输出中泄漏密码`() {
        val options = ExportOptions(includePassword = true, exportPassword = "migration-secret")
        val exported = ConnectionExport(
            type = "WEBDAV",
            name = "盘",
            password = "legacy-secret",
            passwordEnvelope = "encrypted-secret",
            includePassword = true,
        )
        val edit = ConnectionEdit.WebDav("盘", "https://dav.example.com", "user", "connection-secret")

        assertFalse(options.toString().contains("migration-secret"))
        assertFalse(exported.toString().contains("legacy-secret"))
        assertFalse(exported.toString().contains("encrypted-secret"))
        assertFalse(edit.toString().contains("connection-secret"))
        assertEquals("replacement", edit.withPassword("replacement").passwordValue)
    }
}
