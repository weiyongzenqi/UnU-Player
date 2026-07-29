package io.github.weiyongzenqi.unuplayer.library

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import kotlinx.coroutines.runBlocking
import org.sqlite.SQLiteDataSource
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabase
import io.github.weiyongzenqi.unuplayer.playback.configuredDesktopDataSource
import io.github.weiyongzenqi.unuplayer.playback.ensureCurrentDesktopSchema
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
