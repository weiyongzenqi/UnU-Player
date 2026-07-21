package io.github.weiyongzenqi.unuplayer.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopWindowPreferencesTest {

    @Test
    fun `主窗口与播放器窗口状态可原子保存并重新加载`() {
        withTemporaryStore { settingsFile, store ->
            val preferences = DesktopWindowPreferences(store)
            assertEquals(1280, preferences.loadMain().width)
            assertNull(preferences.loadMain().x)

            preferences.saveMain(DesktopWindowBounds(x = -900, y = 80, width = 1100, height = 720, maximized = true))
            preferences.savePlayer(DesktopWindowBounds(x = 120, y = 160, width = 960, height = 540))

            val reloaded = DesktopWindowPreferences(DesktopSettingsFileStore(settingsFile))
            assertEquals(DesktopWindowBounds(-900, 80, 1100, 720, true), reloaded.loadMain())
            assertEquals(DesktopWindowBounds(120, 160, 960, 540, false), reloaded.loadPlayer())
        }
    }

    @Test
    fun `损坏或不完整的窗口值安全回退默认值`() {
        withTemporaryStore { _, store ->
            store.putAll(
                mapOf(
                    "desktop.mainWindow.x" to "100",
                    "desktop.mainWindow.width" to "1",
                    "desktop.mainWindow.height" to "999999",
                    "desktop.mainWindow.maximized" to "not-boolean",
                ),
            )

            val bounds = DesktopWindowPreferences(store).loadMain()
            assertNull(bounds.x)
            assertNull(bounds.y)
            assertEquals(1280, bounds.width)
            assertEquals(800, bounds.height)
            assertFalse(bounds.maximized)
        }
    }

    private fun withTemporaryStore(block: (Path, DesktopSettingsFileStore) -> Unit) {
        val temporaryDirectory = Files.createTempDirectory("unu-window-preferences-").toAbsolutePath().normalize()
        try {
            val settingsFile = temporaryDirectory.resolve("settings.properties")
            block(settingsFile, DesktopSettingsFileStore(settingsFile))
            assertTrue(Files.isRegularFile(settingsFile))
        } finally {
            deleteOwnedTempDirectory(temporaryDirectory)
        }
    }

    private fun deleteOwnedTempDirectory(directory: Path) {
        val systemTempDirectory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        require(directory.parent == systemTempDirectory) { "拒绝删除非系统临时目录的测试文件: $directory" }
        directory.toFile().deleteRecursively()
    }
}
