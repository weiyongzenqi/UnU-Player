package io.github.weiyongzenqi.unuplayer.playback

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File

/**
 * 数据库单例 provider: 进程级共享 driver + UnuDatabase 实例。
 *
 * 播放记录、刮削库与 WebDAV 连接同库 unu_playback.db,
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
     * 迁移数据库到新位置: checkpoint + close + 复制主 db + 删旧 + 更新偏好。
     * 调用后需重启 app(新进程 get 读新位置)。失败不改位置(保持旧位置可用)。
     * wal/shm 不复制(新位置打开重建); checkpoint 后主 db 含全部数据。
     * @return true 成功; false 失败(位置未改, 旧位置仍可用)
     */
    fun migrate(context: Context, toLocation: String): Boolean = synchronized(this) {
        val fromLocation = DatabaseLocationStore.get(context)
        if (fromLocation == toLocation) return true
        runCatching {
            checkpointTruncate()  // wal 并回主库, 主 db 一致
            close()  // 释放文件锁
            val fromFile = dbFile(context, fromLocation)
            val toFile = dbFile(context, toLocation)
            toFile.parentFile?.mkdirs()
            if (fromFile.exists()) fromFile.copyTo(toFile, overwrite = true)
            // 删旧位置 db+wal+shm (干净, 避免残留)
            listOf("", "-wal", "-shm").forEach { ext -> File(fromFile.path + ext).delete() }
            DatabaseLocationStore.set(context, toLocation)
            true
        }.getOrDefault(false)
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
        // 兜底确保海报墙 schema 就位: SQLDelight deriveSchemaFromMigrations=false 时 version 是 .sq
        // hash(非递增), 新版可能 < 老版触发 onDowngrade 而非 onUpgrade; 升降级若没跑成, 老库缺收藏/隐藏
        // 字段或 ScrapedBlocked 表, listShows 查询报错被 runCatching 吞成空列表。onOpen 每次幂等补齐。
        ensurePosterWallSchema(db)
    }

    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        ensurePosterWallSchema(db)
    }

    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // version 是 hash 非递增, 新版可能 < 老版触发降级; 默认 onDowngrade 抛异常, 同 onUpgrade 幂等迁移。
        ensurePosterWallSchema(db)
    }

    /** 幂等补齐海报墙收藏/屏蔽 schema(字段缺失才 ALTER, 表存在不重建)。 */
    private fun ensurePosterWallSchema(db: SupportSQLiteDatabase) {
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
    }

    private fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String, definition: String) {
        val columns = db.query("PRAGMA table_info($table)", arrayOf<Any>()).use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.toList()
        }
        if (column !in columns) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }
}
