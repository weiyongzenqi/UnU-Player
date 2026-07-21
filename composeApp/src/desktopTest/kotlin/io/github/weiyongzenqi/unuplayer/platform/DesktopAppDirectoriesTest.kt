package io.github.weiyongzenqi.unuplayer.platform

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAppDirectoriesTest {

    @Test
    fun `LOCALAPPDATA提供Windows默认根目录`() {
        val localAppData = Files.createTempDirectory("unu-local-app-data-")
        try {
            val root = resolveDesktopAppRoot(
                dataDirectoryOverride = null,
                localAppData = localAppData.toString(),
                xdgDataHome = null,
                userHome = localAppData.resolve("home").toString(),
            )

            assertEquals(
                localAppData.resolve("UnU-Player").toAbsolutePath().normalize(),
                root,
            )
            assertTrue(root.isAbsolute)
        } finally {
            localAppData.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unu data dir覆盖系统默认目录`() {
        val parent = Files.createTempDirectory("unu-data-override-")
        try {
            val override = parent.resolve("custom").resolve("..").resolve("runtime")
            val root = resolveDesktopAppRoot(
                dataDirectoryOverride = override.toString(),
                localAppData = parent.resolve("local").toString(),
                xdgDataHome = parent.resolve("xdg").toString(),
                userHome = parent.resolve("home").toString(),
            )

            assertEquals(override.toAbsolutePath().normalize(), root)
            assertTrue(root.isAbsolute)
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `旧目录成功迁移并保留文件`() {
        val parent = Files.createTempDirectory("unu-directory-migration-")
        try {
            val source = parent.resolve("legacy").resolve("data").createDirectories()
            source.resolve("unu_playback.db").writeText("database")
            val target = parent.resolve("new-root").resolve("data")

            val result = migrateLegacyDesktopDirectory(source, target)

            assertEquals(target.toAbsolutePath().normalize(), result)
            assertFalse(source.exists())
            assertEquals("database", target.resolve("unu_playback.db").readText())
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `目标已存在时不覆盖并返回目标`() {
        val parent = Files.createTempDirectory("unu-directory-existing-")
        try {
            val source = parent.resolve("legacy").resolve("fonts").createDirectories()
            source.resolve("legacy.ttf").writeText("legacy")
            val target = parent.resolve("new-root").resolve("fonts").createDirectories()
            target.resolve("current.ttf").writeText("current")

            val result = migrateLegacyDesktopDirectory(source, target)

            assertEquals(target.toAbsolutePath().normalize(), result)
            assertTrue(source.resolve("legacy.ttf").exists())
            assertEquals("legacy", source.resolve("legacy.ttf").readText())
            assertEquals("current", target.resolve("current.ttf").readText())
            assertFalse(target.resolve("legacy.ttf").exists())
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native临时目录只清理直接普通文件`() {
        val parent = Files.createTempDirectory("unu-native-temp-cleanup-")
        try {
            val directory = parent.resolve("jna").createDirectories()
            val directFile = directory.resolve("jna-old.dll").apply { writeText("old") }
            val nestedDirectory = directory.resolve("nested").createDirectories()
            val nestedFile = nestedDirectory.resolve("keep.dll").apply { writeText("keep") }

            clearOwnedNativeTempFiles(directory)

            assertFalse(directFile.exists())
            assertTrue(nestedDirectory.exists())
            assertTrue(nestedFile.exists())
            assertEquals("keep", nestedFile.readText())
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}
