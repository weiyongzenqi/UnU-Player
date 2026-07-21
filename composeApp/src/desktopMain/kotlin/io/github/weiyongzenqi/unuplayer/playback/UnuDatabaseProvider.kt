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

/**
 * 桌面数据库单例 provider: JDBC SQLite + 进程级共享 driver。
 *
 * 播放记录、刮削库与 WebDAV 连接共用桌面统一数据目录下的 data/unu_playback.db。
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
        }
    }
}
