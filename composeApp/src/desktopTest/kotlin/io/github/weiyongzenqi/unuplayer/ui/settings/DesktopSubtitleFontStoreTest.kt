package io.github.weiyongzenqi.unuplayer.ui.settings

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSubtitleFontStoreTest {

    @Test
    fun `拒绝非法扩展超限文件和符号链接`() {
        val parent = Files.createTempDirectory("unu-font-validation-")
        try {
            val root = parent.resolve("fonts")
            val store = testStore(root, maxBytes = 4L)
            val invalidExtension = parent.resolve("font.txt").apply { writeBytes(byteArrayOf(1)) }
            val oversized = parent.resolve("large.ttf").apply { writeBytes(ByteArray(5)) }

            assertFailsWith<IllegalArgumentException> { store.importFont(invalidExtension) }
            assertFailsWith<IllegalArgumentException> { store.importFont(oversized) }

            val regular = parent.resolve("regular.ttf").apply { writeBytes(ByteArray(4)) }
            val link = parent.resolve("linked.ttf")
            val linkCreated = runCatching {
                Files.createSymbolicLink(link, regular)
                true
            }.getOrDefault(false)
            if (linkCreated) {
                assertFailsWith<IllegalArgumentException> { store.importFont(link) }
            }
        } finally {
            deleteTree(parent)
        }
    }

    @Test
    fun `目标文件名拒绝路径越界`() {
        val root = Files.createTempDirectory("unu-font-containment-")
        try {
            assertFailsWith<IllegalArgumentException> {
                checkContainedTarget(root, "../outside.ttf")
            }
            assertFailsWith<IllegalArgumentException> {
                checkContainedTarget(root, "nested/font.ttf")
            }
            assertEquals(root.resolve("safe.ttf"), checkContainedTarget(root, "safe.ttf"))
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `先校验part再原子发布字体`() {
        val parent = Files.createTempDirectory("unu-font-atomic-")
        try {
            val root = parent.resolve("fonts")
            val sourceBytes = "valid-font-payload".encodeToByteArray()
            val source = parent.resolve("中文 字体.ttf").apply { writeBytes(sourceBytes) }
            var parsedPart: Path? = null
            val store = DesktopSubtitleFontStore(root, 1024L) { path ->
                parsedPart = path
                assertTrue(path.name.endsWith(".part"))
                assertTrue(path.exists())
                listOf(DesktopFontFace("Test Family", "Test Family Regular", path.name))
            }

            val imported = store.importFont(source)

            assertTrue(imported.path.exists())
            assertEquals(root.toAbsolutePath().normalize(), imported.path.parent)
            assertContentEquals(sourceBytes, imported.path.readBytes())
            assertEquals("Test Family", imported.faces.single().family)
            assertEquals(imported.path.fileName.toString(), imported.faces.single().fileName)
            assertFalse(parsedPart!!.exists())
            Files.newDirectoryStream(root, "*.part").use { assertFalse(it.iterator().hasNext()) }
        } finally {
            deleteTree(parent)
        }
    }

    @Test
    fun `清除只删除私有目录中的安全字体文件`() {
        val parent = Files.createTempDirectory("unu-font-clear-")
        try {
            val root = parent.resolve("fonts").createDirectories()
            val ttf = root.resolve("one.ttf").apply { writeBytes(byteArrayOf(1)) }
            val otf = root.resolve("two.otf").apply { writeBytes(byteArrayOf(2)) }
            val ttc = root.resolve("three.ttc").apply { writeBytes(byteArrayOf(3)) }
            val part = root.resolve(".font-import-old.part").apply { writeBytes(byteArrayOf(4)) }
            val unrelated = root.resolve("keep.txt").apply { writeText("keep") }
            val nested = root.resolve("nested").createDirectories().resolve("nested.ttf").apply {
                writeBytes(byteArrayOf(5))
            }
            val outside = parent.resolve("outside.ttf").apply { writeText("outside") }
            val link = root.resolve("linked.ttf")
            val linkCreated = runCatching {
                Files.createSymbolicLink(link, outside)
                true
            }.getOrDefault(false)

            testStore(root).clearImportedFonts()

            assertFalse(ttf.exists())
            assertFalse(otf.exists())
            assertFalse(ttc.exists())
            assertFalse(part.exists())
            assertTrue(unrelated.exists())
            assertTrue(nested.exists())
            assertTrue(outside.exists())
            if (linkCreated) assertTrue(Files.isSymbolicLink(link))
        } finally {
            deleteTree(parent)
        }
    }

    private fun testStore(root: Path, maxBytes: Long = 1024L): DesktopSubtitleFontStore {
        return DesktopSubtitleFontStore(root, maxBytes) { path ->
            listOf(DesktopFontFace("Test Family", "Test Family Regular", path.fileName.toString()))
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
