package io.github.weiyongzenqi.unuplayer.playback

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import io.github.weiyongzenqi.unuplayer.platform.DesktopAppDirectories
import java.io.File
import java.sql.Connection
import javax.sql.DataSource
import io.github.weiyongzenqi.unuplayer.playback.sync.MAX_PLAYBACK_SYNC_VERSION
import io.github.weiyongzenqi.unuplayer.playback.sync.REPAIRED_PLAYBACK_SYNC_VERSION

/**
 * 桌面数据库单例 provider: JDBC SQLite + 进程级共享 driver。
 *
 * 播放记录、刮削库、WebDAV 与媒体服务器连接共用桌面统一数据目录下的 data/unu_playback.db。
 * WAL、NORMAL synchronous、外键与 busy timeout 在首次打开时统一配置。
 */
object UnuDatabaseProvider {

    @Volatile private var driver: JdbcDriver? = null
    @Volatile private var database: UnuDatabase? = null

    /** 进程级单例。首次打开前创建用户数据目录与数据库 schema。 */
    fun get(): UnuDatabase = synchronized(this) {
        database ?: run {
            val file = dbFile()
            file.parentFile?.mkdirs()
            val createSchema = !file.exists() || file.length() == 0L

            val config = SQLiteConfig().apply {
                setJournalMode(SQLiteConfig.JournalMode.WAL)
                setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
                enforceForeignKeys(true)
                setBusyTimeout(5_000)
            }
            val dataSource = SQLiteDataSource(config).apply {
                url = "jdbc:sqlite:${file.absolutePath}"
            }
            val configuredDataSource = configuredDesktopDataSource(dataSource)
            val d = configuredDataSource.asJdbcDriver()
            if (createSchema) {
                UnuDatabase.Schema.create(d)
            }
            ensureCurrentDesktopSchema(configuredDataSource)
            driver = d
            UnuDatabase(d).also { database = it }
        }
    }

    /** 关闭 driver 并清理单例。 */
    fun close() = synchronized(this) {
        runCatching { driver?.close() }
        driver = null
        database = null
    }

    /** 正式用户数据库文件，不放入 tools 临时依赖目录。 */
    fun dbFile(): File = DesktopAppDirectories.databaseFile.toFile()

    /** 将 WAL 内容并回主库并截断 WAL；driver 未初始化时 no-op。 */
    fun checkpointTruncate() {
        runCatching {
            driver?.executeQuery(
                null,
                "PRAGMA wal_checkpoint(TRUNCATE)",
                { QueryResult.Unit },
                0,
                null,
            )
        }
    }

    /**
     * SQLDelight 未从 migration 文件推导递增 schema version；桌面旧库也必须像 Android onOpen 一样
     * 幂等补齐后来加入的海报墙字段/表，避免查询异常被上层降级成“空媒体库”。
     */
}

/**
 * SQLDelight JDBC driver 会从 DataSource 为每个查询获取连接，因此连接级 PRAGMA 必须逐连接应用。
 */
internal fun configuredDesktopDataSource(delegate: DataSource): DataSource = object : DataSource by delegate {
    override fun getConnection(): Connection = configureDesktopConnection(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        configureDesktopConnection(delegate.getConnection(username, password))
}

private fun configureDesktopConnection(connection: Connection): Connection = try {
    connection.createStatement().use { statement ->
        statement.execute("PRAGMA foreign_keys=ON")
        statement.execute("PRAGMA wal_autocheckpoint=500")
    }
    connection
} catch (error: Throwable) {
    runCatching { connection.close() }
    throw error
}

/** 幂等补齐 Windows 历史数据库缺失的海报墙字段、表和索引。 */
internal fun ensureCurrentDesktopSchema(dataSource: DataSource) {
    dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            fun tableExists(table: String): Boolean = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            ).use { query ->
                query.setString(1, table)
                query.executeQuery().use { it.next() }
            }

            fun addColumnIfMissing(table: String, column: String, definition: String) {
                if (!tableExists(table)) return
                val columns = mutableSetOf<String>()
                statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                    while (rows.next()) columns += rows.getString("name")
                }
                if (column !in columns) {
                    statement.execute("ALTER TABLE $table ADD COLUMN $column $definition")
                }
            }

            addColumnIfMissing("ScrapedShow", "is_favorite", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("ScrapedShow", "favorited_at", "INTEGER")
            addColumnIfMissing("ScrapedShow", "favorite_sort_order", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("ScrapedShow", "is_hidden", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("ScrapedLibrary", "scan_mode", "TEXT NOT NULL DEFAULT 'NFO'")
            addColumnIfMissing("ScrapedLibrary", "anchor_filename", "TEXT")
            // 集照本地生成: 老库幂等补 local_thumb_path 列(新库 CREATE TABLE 已含)
            addColumnIfMissing("ScrapedEpisode", "local_thumb_path", "TEXT")
            // P1a: 播放记录三元组标注列(老库幂等补) + EpisodeProgress 语义进度表
            addColumnIfMissing("PlaybackRecord", "tmdb_id", "INTEGER")
            addColumnIfMissing("PlaybackRecord", "season_number", "INTEGER")
            addColumnIfMissing("PlaybackRecord", "episode_number", "INTEGER")
            addColumnIfMissing("PlaybackRecord", "danmaku_sync_version", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("PlaybackRecord", "danmaku_updated_at", "INTEGER NOT NULL DEFAULT 0")
            statement.execute(
                """CREATE TABLE IF NOT EXISTS EpisodeProgress (
                    tmdb_id INTEGER NOT NULL,
                    season_number INTEGER NOT NULL,
                    episode_number INTEGER NOT NULL,
                    media_key TEXT,
                    position_ms INTEGER NOT NULL DEFAULT 0,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    watch_progress REAL NOT NULL DEFAULT 0.0,
                    is_completed INTEGER NOT NULL DEFAULT 0,
                    last_played_at INTEGER NOT NULL,
                    sync_status INTEGER NOT NULL DEFAULT 0,
                    sync_version INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (tmdb_id, season_number, episode_number)
                )""".trimIndent(),
            )
            statement.execute("CREATE INDEX IF NOT EXISTS idx_episode_progress_media_key ON EpisodeProgress(media_key)")
            statement.execute(
                """CREATE TABLE IF NOT EXISTS PlaybackRecordTombstone (
                    media_key TEXT NOT NULL PRIMARY KEY,
                    media_identity TEXT,
                    deleted_at INTEGER NOT NULL,
                    sync_version INTEGER NOT NULL
                )""".trimIndent(),
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS EpisodeProgressTombstone (
                    tmdb_id INTEGER NOT NULL,
                    season_number INTEGER NOT NULL,
                    episode_number INTEGER NOT NULL,
                    media_key TEXT,
                    media_identity TEXT,
                    deleted_at INTEGER NOT NULL,
                    sync_version INTEGER NOT NULL,
                    PRIMARY KEY (tmdb_id, season_number, episode_number)
                )""".trimIndent(),
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS PlaybackSyncState (
                    singleton_id INTEGER NOT NULL PRIMARY KEY CHECK (singleton_id = 1),
                    history_epoch INTEGER NOT NULL DEFAULT 0
                )""".trimIndent(),
            )
            statement.execute("INSERT OR IGNORE INTO PlaybackSyncState(singleton_id, history_epoch) VALUES (1, 0)")
            sanitizePlaybackState(connection, System.currentTimeMillis().coerceAtLeast(0L))

            statement.execute(
                """CREATE TABLE IF NOT EXISTS WebDavConnectionEntity (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    base_url TEXT NOT NULL,
                    username TEXT NOT NULL,
                    password TEXT NOT NULL,
                    sort_order INTEGER NOT NULL
                )""".trimIndent(),
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS MediaServerConnectionEntity (
                    id TEXT NOT NULL PRIMARY KEY,
                    vendor TEXT NOT NULL,
                    name TEXT NOT NULL,
                    base_url TEXT NOT NULL,
                    server_id TEXT NOT NULL,
                    server_version TEXT,
                    user_id TEXT NOT NULL,
                    username TEXT NOT NULL,
                    access_token TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    sort_order INTEGER NOT NULL
                )""".trimIndent(),
            )

            if (tableExists("ScrapedLibrary")) {
                statement.execute(
                    """CREATE TABLE IF NOT EXISTS ScrapedBlocked (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        library_id INTEGER NOT NULL,
                        show_path TEXT NOT NULL,
                        title TEXT,
                        tmdb_id INTEGER,
                        blocked_at INTEGER NOT NULL,
                        UNIQUE(library_id, show_path),
                        FOREIGN KEY(library_id) REFERENCES ScrapedLibrary(id) ON DELETE CASCADE
                    )""".trimIndent(),
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_blocked_library ON ScrapedBlocked(library_id)",
                )
            }

            // 本部专属设置覆盖表(老库幂等补; 新库经 scraped.sq Schema.create 已建)
            statement.execute(
                """CREATE TABLE IF NOT EXISTS ShowSettingsOverride (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    identity_key TEXT NOT NULL UNIQUE,
                    overrides_json TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )""".trimIndent(),
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS BangumiSeasonLinkEntity (
                    identity_key TEXT NOT NULL PRIMARY KEY,
                    bangumi_subject_id INTEGER,
                    state TEXT NOT NULL,
                    source TEXT NOT NULL,
                    evidence TEXT,
                    updated_at INTEGER NOT NULL,
                    verified_at INTEGER
                )""".trimIndent(),
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_bangumi_season_subject ON BangumiSeasonLinkEntity(bangumi_subject_id)",
            )
            // 在线刮削 meta(老库幂等补表; 新库经 scraped.sq Schema.create 已建)。独立表不被打扫,
            // 扫描器 upsertShow 删季重插后经 reapplyOnlineMeta 把数据放回显示表(见 .claude/plans/online-scraping-2026-08-06.md §5.2)。
            statement.execute(
                """CREATE TABLE IF NOT EXISTS ScrapedOnlineMeta (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    library_id INTEGER NOT NULL,
                    show_path TEXT NOT NULL,
                    season_number INTEGER NOT NULL DEFAULT 0,
                    scrape_source TEXT NOT NULL,
                    overwrite_title INTEGER NOT NULL DEFAULT 0,
                    tmdb_id INTEGER,
                    dandanplay_id INTEGER,
                    bangumi_id INTEGER,
                    remote_poster_url TEXT,
                    local_poster_path TEXT,
                    title TEXT,
                    original_title TEXT,
                    year INTEGER,
                    plot TEXT,
                    rating REAL,
                    release_date TEXT,
                    genres TEXT,
                    studios TEXT,
                    episode_json TEXT,
                    remote_fanart_url TEXT,
                    local_fanart_path TEXT,
                    poster_source TEXT,
                    scraped_at INTEGER NOT NULL,
                    UNIQUE(library_id, show_path, season_number),
                    FOREIGN KEY(library_id) REFERENCES ScrapedLibrary(id) ON DELETE CASCADE
                )""".trimIndent(),
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_online_meta_show ON ScrapedOnlineMeta(library_id, show_path)",
            )
            addColumnIfMissing("ScrapedOnlineMeta", "remote_fanart_url", "TEXT")
            addColumnIfMissing("ScrapedOnlineMeta", "local_fanart_path", "TEXT")
            addColumnIfMissing("ScrapedOnlineMeta", "tmdb_id", "INTEGER")
            addColumnIfMissing("ScrapedOnlineMeta", "poster_source", "TEXT")
            // 存量海报对回填归属来源(幂等; 之后由 upsert 显式维护)
            statement.execute(
                """UPDATE ScrapedOnlineMeta SET poster_source = scrape_source
                   WHERE poster_source IS NULL
                     AND ((remote_poster_url IS NOT NULL AND TRIM(remote_poster_url) != '')
                       OR (local_poster_path IS NOT NULL AND TRIM(local_poster_path) != ''))"""
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS TmdbAutoMatchFailure (
                    library_id INTEGER NOT NULL,
                    show_path TEXT NOT NULL,
                    failed_at INTEGER NOT NULL,
                    prompt_suppressed INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(library_id, show_path),
                    FOREIGN KEY(library_id, show_path)
                        REFERENCES ScrapedShow(library_id, show_path) ON DELETE CASCADE
                )""".trimIndent(),
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS AutoScrapeSuppression (
                    library_id INTEGER NOT NULL,
                    show_path TEXT NOT NULL,
                    suppressed_at INTEGER NOT NULL,
                    PRIMARY KEY(library_id, show_path),
                    FOREIGN KEY(library_id, show_path)
                        REFERENCES ScrapedShow(library_id, show_path) ON DELETE CASCADE
                )""".trimIndent(),
            )
        }
    }
}

private fun sanitizePlaybackState(connection: Connection, nowMillis: Long) {
    val managedTransaction = connection.autoCommit
    if (managedTransaction) connection.autoCommit = false
    try {
        connection.createStatement().use { statement ->
            if (desktopTableExists(connection, "PlaybackRecord")) statement.executeUpdate(
                """UPDATE PlaybackRecord SET
                    last_played_at = MIN(MAX(last_played_at, 0), $nowMillis),
                    danmaku_updated_at = MIN(MAX(danmaku_updated_at, 0), $nowMillis),
                    sync_version = CASE WHEN sync_version < 0 OR sync_version >= $MAX_PLAYBACK_SYNC_VERSION
                        THEN $REPAIRED_PLAYBACK_SYNC_VERSION ELSE sync_version END,
                    danmaku_sync_version = CASE
                        WHEN danmaku_sync_version < 0 OR danmaku_sync_version >= $MAX_PLAYBACK_SYNC_VERSION
                        THEN $REPAIRED_PLAYBACK_SYNC_VERSION ELSE danmaku_sync_version END
                   WHERE last_played_at < 0 OR last_played_at > $nowMillis
                      OR danmaku_updated_at < 0 OR danmaku_updated_at > $nowMillis
                      OR sync_version < 0 OR sync_version >= $MAX_PLAYBACK_SYNC_VERSION
                      OR danmaku_sync_version < 0 OR danmaku_sync_version >= $MAX_PLAYBACK_SYNC_VERSION""".trimIndent(),
            )
            if (desktopTableExists(connection, "EpisodeProgress")) statement.executeUpdate(
                """UPDATE EpisodeProgress SET
                    last_played_at = MIN(MAX(last_played_at, 0), $nowMillis),
                    sync_version = CASE WHEN sync_version < 0 OR sync_version >= $MAX_PLAYBACK_SYNC_VERSION
                        THEN $REPAIRED_PLAYBACK_SYNC_VERSION ELSE sync_version END
                   WHERE last_played_at < 0 OR last_played_at > $nowMillis
                      OR sync_version < 0 OR sync_version >= $MAX_PLAYBACK_SYNC_VERSION""".trimIndent(),
            )
            if (desktopTableExists(connection, "PlaybackRecordTombstone")) statement.executeUpdate(
                """UPDATE PlaybackRecordTombstone SET
                    deleted_at = MIN(MAX(deleted_at, 0), $nowMillis),
                    sync_version = CASE WHEN sync_version < 0 OR sync_version >= $MAX_PLAYBACK_SYNC_VERSION
                        THEN $REPAIRED_PLAYBACK_SYNC_VERSION ELSE sync_version END
                   WHERE deleted_at < 0 OR deleted_at > $nowMillis
                      OR sync_version < 0 OR sync_version >= $MAX_PLAYBACK_SYNC_VERSION""".trimIndent(),
            )
            if (desktopTableExists(connection, "EpisodeProgressTombstone")) statement.executeUpdate(
                """UPDATE EpisodeProgressTombstone SET
                    deleted_at = MIN(MAX(deleted_at, 0), $nowMillis),
                    sync_version = CASE WHEN sync_version < 0 OR sync_version >= $MAX_PLAYBACK_SYNC_VERSION
                        THEN $REPAIRED_PLAYBACK_SYNC_VERSION ELSE sync_version END
                   WHERE deleted_at < 0 OR deleted_at > $nowMillis
                      OR sync_version < 0 OR sync_version >= $MAX_PLAYBACK_SYNC_VERSION""".trimIndent(),
            )
            if (desktopTableExists(connection, "PlaybackSyncState")) statement.executeUpdate(
                """UPDATE PlaybackSyncState SET history_epoch = 0
                   WHERE history_epoch < 0 OR history_epoch >= $MAX_PLAYBACK_SYNC_VERSION""".trimIndent(),
            )
        }
        if (managedTransaction) connection.commit()
    } catch (error: Throwable) {
        if (managedTransaction) runCatching { connection.rollback() }
        throw error
    } finally {
        if (managedTransaction) connection.autoCommit = true
    }
}

private fun desktopTableExists(connection: Connection, table: String): Boolean =
    connection.prepareStatement(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
    ).use { query ->
        query.setString(1, table)
        query.executeQuery().use { rows -> rows.next() }
    }
