package io.github.weiyongzenqi.unuplayer.ui.settings

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogSettingsSlotTest {

    @Test
    fun `日志目录保留中文特殊字符并拒绝普通文件`() {
        val parent = Files.createTempDirectory("unu-log-slot-")
        try {
            val directory = parent.resolve("日志 + # [测试]").createDirectories()
            assertEquals(
                directory.toAbsolutePath().normalize().toString(),
                validateLogDirectory(directory.toFile()).getOrThrow(),
            )

            val file = parent.resolve("not-directory.txt").apply { writeText("x") }
            assertTrue(validateLogDirectory(file.toFile()).isFailure)
        } finally {
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }
}
