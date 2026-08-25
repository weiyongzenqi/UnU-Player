package io.github.weiyongzenqi.unuplayer.playback

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import io.github.weiyongzenqi.unuplayer.playback.sync.MAX_PLAYBACK_SYNC_VERSION
import io.github.weiyongzenqi.unuplayer.playback.sync.REPAIRED_PLAYBACK_SYNC_VERSION

/**
 * 数据库单例 provider: 进程级共享 driver + UnuDatabase 实例。
 *
 * 播放记录、刮削库、WebDAV、SMB 与媒体服务器连接同库 unu_playback.db,
 * 共享 driver 以: 1) WAL/外键 PRAGMA 只配一次; 2) 跨表 join(剧集关联播放进度)同连接; 3) 省资源。
 *
 * WAL 管理(防 wal 文件无限增长, 见 .claude/plans/poster-wall.md §2.6):
 * - [UnuSqliteCallback.onOpen]: journal_mode=WAL + wal_autocheckpoint=500 + foreign_keys=ON(级联删除)
 * - [checkpointTruncate]: 扫描完成/启动/手动优化时截断 wal 文件
 *
 * 旧 PlaybackRecordRepositoryImpl 自建 driver, 改为经此 provider 取 queries, 行为等价但共享 WAL 配置。
 */
object UnuDatabaseProvider {

    @Volatile private var driver: AndroidSqliteDriver? = null
    @Volatile private var database: UnuDatabase? = null

    /** 进程级单例。首次用 [context] 建 driver+打开数据库, 后续忽略 context。
     *  数据库位置由 [DatabaseLocationStore] 决定(internal /data 或 external Android/data)。 */
    fun get(context: Context): UnuDatabase = synchronized(this) {
        database ?: run {
            val location = DatabaseLocationStore.get(context)
            val file = dbFile(context, location)
            file.parentFile?.mkdirs()  // external 时确保 databases/ 存在; internal 时默认已存在
            // name 传绝对路径: internal 时等价 /data/databases/unu_playback.db;
            // external 时 openOrCreateDatabase 尊重绝对路径(父目录需存在, 上面已 mkdirs)
            val d = AndroidSqliteDriver(
                UnuDatabase.Schema,
                context.applicationContext,
                file.absolutePath,
                callback = UnuSqliteCallback,
            )
            driver = d
            UnuDatabase(d).also { database = it }
        }
    }

    /** 关闭 driver + 清单例(迁移前用, 释放文件锁)。 */
    fun close() = synchronized(this) {
        runCatching { driver?.close() }
        driver = null
        database = null
    }

    /** 数据库文件路径。internal -> /data/databases/; external -> Android/data/files/databases/。 */
    fun dbFile(context: Context, location: String): File = when (location) {
        DatabaseLocationStore.EXTERNAL -> File(
            File(context.getExternalFilesDir(null) ?: context.filesDir, "databases"),
            "unu_playback.db",
        )
        else -> context.getDatabasePath("unu_playback.db")
    }

    /**
     * 迁移数据库到新位置: close + 消费 checkpoint + .part 验证复制 + 同步提交偏好 + 最后清旧库。
     * 调用后需重启 app(新进程 get 读新位置)。失败不改位置(保持旧位置可用)。
     * wal/shm 不复制(新位置打开重建); checkpoint 后主 db 含全部数据。
     * @return true 成功; false 失败(位置未改, 旧位置仍可用)
     */
    fun migrate(context: Context, toLocation: String): Boolean = synchronized(this) {
        val fromLocation = DatabaseLocationStore.get(context)
        if (fromLocation == toLocation) return true
        val fromFile = dbFile(context, fromLocation)
        val toFile = dbFile(context, toLocation)
        migrateDatabaseFiles(
            fromFile = fromFile,
            toFile = toFile,
            beforeCopy = {
                closeForMigration()
                if (fromFile.exists()) checkpointFileOrThrow(fromFile)
            },
            verify = ::verifyDatabaseFile,
            commitLocation = { DatabaseLocationStore.set(context, toLocation) },
        )
    }

    private fun closeForMigration() {
        val current = driver
        driver = null
        database = null
        current?.close()
    }

    /** 用独立 framework 连接消费 checkpoint 返回行；busy 非零不能继续复制。 */
    private fun checkpointFileOrThrow(file: File) {
        val sqlite = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                check(cursor.moveToFirst()) { "数据库 checkpoint 未返回结果" }
                check(cursor.getInt(0) == 0) { "数据库 checkpoint 仍忙" }
            }
        } finally {
            sqlite.close()
        }
    }

    private fun verifyDatabaseFile(file: File) {
        check(file.isFile && file.length() > 0L) { "数据库复制结果为空" }
        val sqlite = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            sqlite.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                    "数据库完整性校验失败"
                }
            }
        } finally {
            sqlite.close()
        }
    }

    /**
     * 截断 WAL 文件(PRAGMA wal_checkpoint(TRUNCATE)): 把 wal 内容并回主库并截断 wal 文件到最小。
     * 扫描完成/App 启动/设置页"优化数据库"调用。driver 未初始化时 no-op。
     * runCatching 防 TRUNCATE 在无活动连接时偶发的异常, 不影响主流程。
     */
    fun checkpointTruncate() {
        runCatching {
            // wal_checkpoint(TRUNCATE) 返回行(busy/log/checkpointed), 用 executeQuery 消费;
            // execute/execSQL 对返回值语句会报错。
            driver?.executeQuery(
                null,
                "PRAGMA wal_checkpoint(TRUNCATE)",
                { app.cash.sqldelight.db.QueryResult.Unit },
                0,
                null,
            )
        }
    }
}

/** 文件事务保持旧库到位置 commit 成功之后；任何失败只清理本轮 .part。 */
internal fun migrateDatabaseFiles(
    fromFile: File,
    toFile: File,
    beforeCopy: () -> Unit,
    verify: (File) -> Unit,
    commitLocation: () -> Boolean,
): Boolean {
    val partFile = File(toFile.path + ".part")
    return try {
        beforeCopy()
        if (fromFile.exists()) {
            val parent = toFile.parentFile
            check(parent == null || parent.isDirectory || parent.mkdirs()) { "无法创建数据库目标目录" }
            check(!partFile.exists() || partFile.delete()) { "无法清理旧数据库临时文件" }
            fromFile.inputStream().use { input ->
                FileOutputStream(partFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            verify(partFile)
            listOf("-wal", "-shm").forEach { suffix ->
                check(!File(toFile.path + suffix).exists() || File(toFile.path + suffix).delete()) {
                    "无法清理目标数据库旧 sidecar: $suffix"
                }
            }
            Files.move(
                partFile.toPath(),
                toFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            verify(toFile)
        }
        check(commitLocation()) { "数据库位置提交失败" }
        listOf("", "-wal", "-shm").forEach { suffix ->
            runCatching { File(fromFile.path + suffix).delete() }
        }
        true
    } catch (_: Throwable) {
        runCatching { partFile.delete() }
        false
    }
}

/**
 * 带 PRAGMA 的 SQLite Callback。
 * 继承 [AndroidSqliteDriver.Callback] 复用其 onCreate(建表)/onUpgrade(迁移), 仅 override onOpen 注入 PRAGMA。
 */
private object UnuSqliteCallback : AndroidSqliteDriver.Callback(UnuDatabase.Schema) {
    override fun onOpen(db: SupportSQLiteDatabase) {
        // WAL: 用 enableWriteAheadLogging API。不能用 execSQL("PRAGMA journal_mode=WAL")——
        // journal_mode 是返回值的 PRAGMA(返回 journal_mode 行), execSQL 禁止执行有返回结果的
        // 语句, 会抛 "Queries can be performed using SQLiteDatabase query or rawQuery methods only"。
        db.enableWriteAheadLogging()
        // 自动 checkpoint 每 500 页防 wal 无限增长。wal_autocheckpoint=N 设置后返回当前值(一行),
        // execSQL 对返回值语句报错(同 journal_mode), 用 query 消费。
        db.query("PRAGMA wal_autocheckpoint=500", arrayOf<Any>()).use { it.moveToFirst() }
        // 外键级联删除生效(用 API, 等价 PRAGMA foreign_keys=ON)。
        db.setForeignKeyConstraintsEnabled(true)
        // 兜底确保当前 schema 就位: SQLDelight deriveSchemaFromMigrations=false 时 version 是 .sq
        // hash(非递增), 新版可能 < 老版触发 onDowngrade 而非 onUpgrade；onOpen 每次幂等补齐后来加入的
        // 字段和表，避免老库查询失败。
        ensureCurrentSchema(db)
    }

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        ensureCurrentSchema(db)
    }

    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // version 是 hash 非递增, 新版可能 < 老版触发降级; 默认 onDowngrade 抛异常, 同 onUpgrade 幂等迁移。
        ensureCurrentSchema(db)
    }

    /** 幂等补齐当前 schema；字段缺失才 ALTER，表存在则不重建。 */
    private fun ensureCurrentSchema(db: SupportSQLiteDatabase) {
        addColumnIfMissing(db, "ScrapedShow", "is_favorite", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "ScrapedShow", "favorited_at", "INTEGER")
        addColumnIfMissing(db, "ScrapedShow", "favorite_sort_order", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "ScrapedShow", "is_hidden", "INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS WebDavConnectionEntity (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                base_url TEXT NOT NULL,
                username TEXT NOT NULL,
                password TEXT NOT NULL,
                sort_order INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
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
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS SmbConnectionEntity (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                share_name TEXT NOT NULL,
                username TEXT NOT NULL,
                password TEXT NOT NULL,
                domain TEXT NOT NULL,
                require_encryption INTEGER NOT NULL,
                sort_order INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS ScrapedBlocked (
            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            library_id INTEGER NOT NULL,
            show_path TEXT NOT NULL,
            title TEXT,
            tmdb_id INTEGER,
            blocked_at INTEGER NOT NULL,
            UNIQUE(library_id, show_path),
            FOREIGN KEY(library_id) REFERENCES ScrapedLibrary(id) ON DELETE CASCADE
        )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_blocked_library ON ScrapedBlocked(library_id)")
        // ANCHOR 模式字段(老库幂等补列; DEFAULT 'NFO' 老库自动 NFO 行为不变)
        addColumnIfMissing(db, "ScrapedLibrary", "scan_mode", "TEXT NOT NULL DEFAULT 'NFO'")
        addColumnIfMissing(db, "ScrapedLibrary", "anchor_filename", "TEXT")
        // 集照本地生成: 老库幂等补 local_thumb_path 列(新库 CREATE TABLE 已含)
        addColumnIfMissing(db, "ScrapedEpisode", "local_thumb_path", "TEXT")
        // P1a: 播放记录三元组标注列(老库幂等补) + EpisodeProgress 语义进度表
        addColumnIfMissing(db, "PlaybackRecord", "tmdb_id", "INTEGER")
        addColumnIfMissing(db, "PlaybackRecord", "season_number", "INTEGER")
        addColumnIfMissing(db, "PlaybackRecord", "episode_number", "INTEGER")
        addColumnIfMissing(db, "PlaybackRecord", "danmaku_sync_version", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "PlaybackRecord", "danmaku_updated_at", "INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
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
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_episode_progress_media_key ON EpisodeProgress(media_key)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS PlaybackRecordTombstone (
                media_key TEXT NOT NULL PRIMARY KEY,
                media_identity TEXT,
                deleted_at INTEGER NOT NULL,
                sync_version INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS EpisodeProgressTombstone (
                tmdb_id INTEGER NOT NULL,
                season_number INTEGER NOT NULL,
                episode_number INTEGER NOT NULL,
                media_key TEXT,
                media_identity TEXT,
                deleted_at INTEGER NOT NULL,
                sync_version INTEGER NOT NULL,
                PRIMARY KEY (tmdb_id, season_number, episode_number)
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS PlaybackSyncState (
                singleton_id INTEGER NOT NULL PRIMARY KEY CHECK (singleton_id = 1),
                history_epoch INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL("INSERT OR IGNORE INTO PlaybackSyncState(singleton_id, history_epoch) VALUES (1, 0)")
        sanitizePlaybackState(db, System.currentTimeMillis().coerceAtLeast(0L))
        // 本部专属设置覆盖表(老库幂等补; 新库经 scraped.sq Schema.create 已建)
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS ShowSettingsOverride (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                identity_key TEXT NOT NULL UNIQUE,
                overrides_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS BangumiSeasonLinkEntity (
                identity_key TEXT NOT NULL PRIMARY KEY,
                bangumi_subject_id INTEGER,
                state TEXT NOT NULL,
                source TEXT NOT NULL,
                evidence TEXT,
                updated_at INTEGER NOT NULL,
                verified_at INTEGER
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_bangumi_season_subject ON BangumiSeasonLinkEntity(bangumi_subject_id)"
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS ScheduleWatch (
                subject_id INTEGER NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                air_weekday INTEGER NOT NULL,
                anime_id INTEGER,
                tmdb_id INTEGER,
                watched_at INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'WANT',
                sync_version INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        addColumnIfMissing(db, "ScheduleWatch", "status", "TEXT NOT NULL DEFAULT 'WANT'")
        addColumnIfMissing(db, "ScheduleWatch", "sync_version", "INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS ScheduleWatchTombstone (
                subject_id INTEGER NOT NULL PRIMARY KEY,
                deleted_at INTEGER NOT NULL,
                sync_version INTEGER NOT NULL
            )""".trimIndent()
        )
        // 在线刮削 meta(老库幂等补表; 新库经 scraped.sq Schema.create 已建)。独立表不被打扫,
        // 扫描器 upsertShow 删季重插后经 reapplyOnlineMeta 把数据放回显示表(见 .claude/plans/online-scraping-2026-08-06.md §5.2)。
        db.execSQL(
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
                tmdb_season_number INTEGER,
                tmdb_episode_offset INTEGER,
                tmdb_mapping_evidence TEXT,
                remote_fanart_url TEXT,
                local_fanart_path TEXT,
                poster_source TEXT,
                scraped_at INTEGER NOT NULL,
                UNIQUE(library_id, show_path, season_number),
                FOREIGN KEY(library_id) REFERENCES ScrapedLibrary(id) ON DELETE CASCADE
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_online_meta_show ON ScrapedOnlineMeta(library_id, show_path)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_online_meta_bangumi ON ScrapedOnlineMeta(bangumi_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_online_meta_dandanplay ON ScrapedOnlineMeta(dandanplay_id)")
        addColumnIfMissing(db, "ScrapedOnlineMeta", "remote_fanart_url", "TEXT")
        addColumnIfMissing(db, "ScrapedOnlineMeta", "local_fanart_path", "TEXT")
        addColumnIfMissing(db, "ScrapedOnlineMeta", "tmdb_id", "INTEGER")
        addColumnIfMissing(db, "ScrapedOnlineMeta", "poster_source", "TEXT")
        addColumnIfMissing(db, "ScrapedOnlineMeta", "tmdb_season_number", "INTEGER")
        addColumnIfMissing(db, "ScrapedOnlineMeta", "tmdb_episode_offset", "INTEGER")
        addColumnIfMissing(db, "ScrapedOnlineMeta", "tmdb_mapping_evidence", "TEXT")
        // 存量海报对回填归属来源(幂等; 之后由 upsert 显式维护)
        db.execSQL(
            """UPDATE ScrapedOnlineMeta SET poster_source = scrape_source
               WHERE poster_source IS NULL
                 AND ((remote_poster_url IS NOT NULL AND TRIM(remote_poster_url) != '')
                   OR (local_poster_path IS NOT NULL AND TRIM(local_poster_path) != ''))"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS TmdbAutoMatchFailure (
                library_id INTEGER NOT NULL,
                show_path TEXT NOT NULL,
                failed_at INTEGER NOT NULL,
                prompt_suppressed INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(library_id, show_path),
                FOREIGN KEY(library_id, show_path)
                    REFERENCES ScrapedShow(library_id, show_path) ON DELETE CASCADE
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS AutoScrapeSuppression (
                library_id INTEGER NOT NULL,
                show_path TEXT NOT NULL,
                suppressed_at INTEGER NOT NULL,
                PRIMARY KEY(library_id, show_path),
                FOREIGN KEY(library_id, show_path)
                    REFERENCES ScrapedShow(library_id, show_path) ON DELETE CASCADE
            )""".trimIndent()
        )
    }

    /** 打开历史库时事务化修复包/远端曾写入的非法时间与逻辑版本。 */
    private fun sanitizePlaybackState(db: SupportSQLiteDatabase, nowMillis: Long) {
        val startedTransaction = !db.inTransaction()
        if (startedTransaction) db.beginTransaction()
        try {
            if (androidTableExists(db, "PlaybackRecord")) db.execSQL(
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
            if (androidTableExists(db, "EpisodeProgress")) db.execSQL(
                """UPDATE EpisodeProgress SET
                    last_played_at = MIN(MAX(last_played_at, 0), $nowMillis),
                    sync_version = CASE WHEN sync_version < 0 OR sync_version >= $MAX_PLAYBACK_SYNC_VERSION
                        THEN $REPAIRED_PLAYBACK_SYNC_VERSION ELSE sync_version END
                   WHERE last_played_at < 0 OR last_played_at > $nowMillis
                      OR sync_version < 0 OR sync_version >= $MAX_PLAYBACK_SYNC_VERSION""".trimIndent(),
            )
            if (androidTableExists(db, "PlaybackRecordTombstone")) db.execSQL(
                """UPDATE PlaybackRecordTombstone SET
                    deleted_at = MIN(MAX(deleted_at, 0), $nowMillis),
                    sync_version = CASE WHEN sync_version < 0 OR sync_version >= $MAX_PLAYBACK_SYNC_VERSION
                        THEN $REPAIRED_PLAYBACK_SYNC_VERSION ELSE sync_version END
                   WHERE deleted_at < 0 OR deleted_at > $nowMillis
                      OR sync_version < 0 OR sync_version >= $MAX_PLAYBACK_SYNC_VERSION""".trimIndent(),
            )
            if (androidTableExists(db, "EpisodeProgressTombstone")) db.execSQL(
                """UPDATE EpisodeProgressTombstone SET
                    deleted_at = MIN(MAX(deleted_at, 0), $nowMillis),
                    sync_version = CASE WHEN sync_version < 0 OR sync_version >= $MAX_PLAYBACK_SYNC_VERSION
                        THEN $REPAIRED_PLAYBACK_SYNC_VERSION ELSE sync_version END
                   WHERE deleted_at < 0 OR deleted_at > $nowMillis
                      OR sync_version < 0 OR sync_version >= $MAX_PLAYBACK_SYNC_VERSION""".trimIndent(),
            )
            if (androidTableExists(db, "PlaybackSyncState")) db.execSQL(
                """UPDATE PlaybackSyncState SET history_epoch = 0
                   WHERE history_epoch < 0 OR history_epoch >= $MAX_PLAYBACK_SYNC_VERSION""".trimIndent(),
            )
            if (startedTransaction) db.setTransactionSuccessful()
        } finally {
            if (startedTransaction) db.endTransaction()
        }
    }

    private fun androidTableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table),
        ).use { cursor -> cursor.moveToFirst() }

    private fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String, definition: String) {
        val tableExists = db.query(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table),
        ).use { cursor -> cursor.moveToFirst() }
        if (!tableExists) return
        val columns = db.query("PRAGMA table_info($table)", arrayOf<Any>()).use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.toList()
        }
        if (column !in columns) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }
}
