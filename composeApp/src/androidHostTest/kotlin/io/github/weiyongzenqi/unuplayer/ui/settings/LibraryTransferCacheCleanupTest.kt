package io.github.weiyongzenqi.unuplayer.ui.settings

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryTransferCacheCleanupTest {
    @Test
    fun `只删除应用缓存中的导入临时文件`() {
        val root = Files.createTempDirectory("unu-library-transfer-")
        try {
            val cacheDir = root.resolve("cache").toFile().apply { mkdirs() }
            val owned = cacheDir.resolve("library-import-owned.zip").apply { writeText("temporary") }
            val unrelated = cacheDir.resolve("other.zip").apply { writeText("keep") }
            val original = root.resolve("library-import-original.zip").toFile().apply { writeText("source") }

            assertFalse(deleteOwnedImportCacheFile(cacheDir, original))
            assertTrue(original.isFile)
            assertTrue(deleteOwnedImportCacheFile(cacheDir, owned))
            assertFalse(owned.exists())

            cacheDir.resolve("library-import-stale.zip").writeText("stale")
            cleanupStaleImportCacheFiles(cacheDir)
            assertFalse(cacheDir.resolve("library-import-stale.zip").exists())
            assertTrue(unrelated.isFile)
            assertTrue(original.isFile)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }
}
