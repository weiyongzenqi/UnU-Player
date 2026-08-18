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

            assertFalse(deleteOwnedTransferCacheFile(cacheDir, original))
            assertTrue(original.isFile)
            assertTrue(deleteOwnedTransferCacheFile(cacheDir, owned))
            assertFalse(owned.exists())

            cacheDir.resolve("library-import-stale.zip").writeText("stale")
            cleanupStaleTransferCacheFiles(cacheDir)
            assertFalse(cacheDir.resolve("library-import-stale.zip").exists())
            assertTrue(unrelated.isFile)
            assertTrue(original.isFile)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }

    @Test
    fun `导出残留zip也会被清理而用户文件不受影响`() {
        val root = Files.createTempDirectory("unu-library-export-cleanup-")
        try {
            val cacheDir = root.resolve("cache").toFile().apply { mkdirs() }
            val staleExport = cacheDir.resolve("library-export-1234567.zip").apply { writeText("temporary export") }
            val unrelated = cacheDir.resolve("export.zip").apply { writeText("keep") }
            val otherDir = root.resolve("library-export-keep.zip").toFile().apply { writeText("source") }

            cleanupStaleTransferCacheFiles(cacheDir)
            assertFalse(staleExport.exists(), "进程被杀残留的导出 zip 应被清理")
            assertTrue(unrelated.isFile)
            assertTrue(otherDir.isFile, "缓存目录之外的同名前缀文件不受影响")
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { path.deleteIfExists() } }
            }
        }
    }
}
