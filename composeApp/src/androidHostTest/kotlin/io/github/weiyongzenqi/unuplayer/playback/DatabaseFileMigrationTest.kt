package io.github.weiyongzenqi.unuplayer.playback

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseFileMigrationTest {

    @Test
    fun `成功提交位置之后才删除旧库`() = withTempDirectory { root ->
        val source = root.resolve("old/unu.db").toFile().apply {
            checkNotNull(parentFile).mkdirs()
            writeText("valid-db")
        }
        val target = root.resolve("new/unu.db").toFile()
        var committedWhileSourcePresent = false

        val migrated = migrateDatabaseFiles(
            fromFile = source,
            toFile = target,
            beforeCopy = {},
            verify = { check(it.readText() == "valid-db") },
            commitLocation = {
                committedWhileSourcePresent = source.exists()
                true
            },
        )

        assertTrue(migrated)
        assertTrue(committedWhileSourcePresent)
        assertFalse(source.exists())
        assertEquals("valid-db", target.readText())
    }

    @Test
    fun `临时副本校验失败保留旧库和既有目标`() = withTempDirectory { root ->
        val source = root.resolve("old/unu.db").toFile().apply {
            checkNotNull(parentFile).mkdirs()
            writeText("broken-copy")
        }
        val target = root.resolve("new/unu.db").toFile().apply {
            checkNotNull(parentFile).mkdirs()
            writeText("previous-target")
        }
        var committed = false

        val migrated = migrateDatabaseFiles(
            fromFile = source,
            toFile = target,
            beforeCopy = {},
            verify = { error("模拟完整性失败") },
            commitLocation = { committed = true; true },
        )

        assertFalse(migrated)
        assertFalse(committed)
        assertEquals("broken-copy", source.readText())
        assertEquals("previous-target", target.readText())
        assertFalse(root.resolve("new/unu.db.part").toFile().exists())
    }

    @Test
    fun `位置提交失败时旧库仍可用且目标只作为可回收副本`() = withTempDirectory { root ->
        val source = root.resolve("old/unu.db").toFile().apply {
            checkNotNull(parentFile).mkdirs()
            writeText("valid-db")
        }
        val target = root.resolve("new/unu.db").toFile()

        val migrated = migrateDatabaseFiles(
            fromFile = source,
            toFile = target,
            beforeCopy = {},
            verify = { check(it.readText() == "valid-db") },
            commitLocation = { false },
        )

        assertFalse(migrated)
        assertEquals("valid-db", source.readText())
        assertEquals("valid-db", target.readText())
        assertFalse(root.resolve("new/unu.db.part").toFile().exists())
    }

    @Test
    fun `替换目标主库前清理目标旧 wal 和 shm`() = withTempDirectory { root ->
        val source = root.resolve("old/unu.db").toFile().apply {
            checkNotNull(parentFile).mkdirs()
            writeText("valid-db")
        }
        val target = root.resolve("new/unu.db").toFile().apply {
            checkNotNull(parentFile).mkdirs()
            writeText("previous-target")
        }
        val targetWal = root.resolve("new/unu.db-wal").toFile().apply { writeText("stale-wal") }
        val targetShm = root.resolve("new/unu.db-shm").toFile().apply { writeText("stale-shm") }

        val migrated = migrateDatabaseFiles(
            fromFile = source,
            toFile = target,
            beforeCopy = {},
            verify = { file ->
                check(file.readText() == "valid-db")
                if (file.absoluteFile == target.absoluteFile) {
                    check(!targetWal.exists())
                    check(!targetShm.exists())
                }
            },
            commitLocation = { true },
        )

        assertTrue(migrated)
        assertEquals("valid-db", target.readText())
        assertFalse(targetWal.exists())
        assertFalse(targetShm.exists())
    }

    private fun withTempDirectory(block: (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("unu-db-migrate-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
