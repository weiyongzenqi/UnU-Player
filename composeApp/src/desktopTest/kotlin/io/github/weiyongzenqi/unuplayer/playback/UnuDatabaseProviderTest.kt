package io.github.weiyongzenqi.unuplayer.playback

import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnuDatabaseProviderTest {

    @Test
    fun `每个 JDBC 连接都启用外键和 WAL 自动 checkpoint`() {
        withTempDataSource { raw, _ ->
            val configured = configuredDesktopDataSource(raw)
            repeat(2) {
                configured.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA foreign_keys").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(1, rows.getInt(1))
                        }
                        statement.executeQuery("PRAGMA wal_autocheckpoint").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(500, rows.getInt(1))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `历史数据库可幂等补齐字段表和索引`() {
        withTempDataSource { raw, _ ->
            val configured = configuredDesktopDataSource(raw)
            configured.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE ScrapedLibrary (id INTEGER PRIMARY KEY)")
                    statement.execute("CREATE TABLE ScrapedShow (id INTEGER PRIMARY KEY)")
                }
            }

            ensureCurrentDesktopSchema(configured)
            ensureCurrentDesktopSchema(configured)

            configured.connection.use { connection ->
                fun columns(table: String): Set<String> = connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                        buildSet {
                            while (rows.next()) add(rows.getString("name"))
                        }
                    }
                }
                assertTrue(
                    setOf("is_favorite", "favorited_at", "favorite_sort_order", "is_hidden")
                        .all { it in columns("ScrapedShow") },
                )
                assertTrue(setOf("scan_mode", "anchor_filename").all { it in columns("ScrapedLibrary") })
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_blocked_library'",
                    ).use { rows -> assertTrue(rows.next()) }
                }
            }
        }
    }

    private fun withTempDataSource(block: (SQLiteDataSource, java.nio.file.Path) -> Unit) {
        val file = Files.createTempFile("unu-old-schema-", ".db")
        val dataSource = SQLiteDataSource().apply { url = "jdbc:sqlite:${file.toAbsolutePath()}" }
        try {
            block(dataSource, file)
        } finally {
            file.deleteIfExists()
            file.resolveSibling("${file.fileName}-wal").deleteIfExists()
            file.resolveSibling("${file.fileName}-shm").deleteIfExists()
        }
    }
}
