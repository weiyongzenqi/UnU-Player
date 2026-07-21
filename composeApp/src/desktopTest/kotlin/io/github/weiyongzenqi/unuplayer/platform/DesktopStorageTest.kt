package io.github.weiyongzenqi.unuplayer.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopStorageTest {

    @Test
    fun `批量编辑会持久化所有类型并删除指定值`() = runBlocking {
        val temporaryDirectory = Files.createTempDirectory("unu-desktop-storage-batch-")
            .toAbsolutePath()
            .normalize()
        try {
            val settingsFile = temporaryDirectory.resolve("settings.properties")
            val initialStore = DesktopSettingsFileStore(settingsFile)
            initialStore.putString("obsolete", "旧值")
            val storage = DesktopStorage(initialStore)

            storage.edit {
                putString("startupHome", "ANIME")
                putBoolean("darkTheme", true)
                putInt("posterColumns", 6)
                remove("obsolete")
            }

            val reloaded = DesktopStorage(DesktopSettingsFileStore(settingsFile))
            assertEquals("ANIME", reloaded.getString("startupHome"))
            assertTrue(reloaded.getBoolean("darkTheme"))
            assertEquals(6, reloaded.getInt("posterColumns"))
            assertEquals("missing", reloaded.getString("obsolete", "missing"))
        } finally {
            deleteOwnedTempDirectory(temporaryDirectory)
        }
    }

    @Test
    fun `设置会持久化到文件且删除后恢复默认值`() {
        val temporaryDirectory = Files.createTempDirectory("unu-desktop-storage-")
            .toAbsolutePath()
            .normalize()
        try {
            val settingsFile = temporaryDirectory.resolve("settings.properties")
            val store = DesktopSettingsFileStore(settingsFile)

            store.putString("startupHome", "ANIME")
            store.putBoolean("darkTheme", true)
            store.putInt("posterColumns", 6)

            assertTrue(Files.isRegularFile(settingsFile))

            val reloaded = DesktopSettingsFileStore(settingsFile)
            assertEquals("ANIME", reloaded.getString("startupHome"))
            assertTrue(reloaded.getBoolean("darkTheme"))
            assertEquals(6, reloaded.getInt("posterColumns"))

            reloaded.remove("startupHome")

            val afterRemoval = DesktopSettingsFileStore(settingsFile)
            assertEquals("WEBDAV", afterRemoval.getString("startupHome", "WEBDAV"))
            assertFalse(afterRemoval.getBoolean("missingBoolean", false))
            assertEquals(3, afterRemoval.getInt("missingInt", 3))
        } finally {
            deleteOwnedTempDirectory(temporaryDirectory)
        }
    }

    private fun deleteOwnedTempDirectory(directory: Path) {
        val normalizedDirectory = directory.toAbsolutePath().normalize()
        val systemTempDirectory = Path.of(System.getProperty("java.io.tmpdir"))
            .toAbsolutePath()
            .normalize()
        require(normalizedDirectory.parent == systemTempDirectory) {
            "拒绝删除非系统临时目录的测试文件: $normalizedDirectory"
        }
        normalizedDirectory.toFile().deleteRecursively()
    }
}
