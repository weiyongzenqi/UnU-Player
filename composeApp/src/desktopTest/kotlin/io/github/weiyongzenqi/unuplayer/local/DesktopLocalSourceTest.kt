package io.github.weiyongzenqi.unuplayer.local

import kotlinx.coroutines.runBlocking
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopLocalSourceTest {

    @Test
    fun `拒绝访问和删除媒体库根目录之外的路径`() = runBlocking {
        withTestTree { root, outside ->
            val showDir = root.resolve("中文 番剧 [01]").createDirectories()
            val video = showDir.resolve("S01E01.mkv").createFile()
            val nfo = showDir.resolve("tvshow.nfo").apply { writeText("<title>测试</title>") }
            val secret = outside.resolve("secret.nfo").apply { writeText("不能读取") }
            val source = DesktopLocalSource(root.toString())

            assertEquals(listOf("中文 番剧 [01]"), source.listFolder("").map { it.name })
            assertEquals("<title>测试</title>", source.readTextFile(nfo.toString()))
            assertTrue(source.testConnection())

            assertTrue(source.listFolder(outside.toString()).isEmpty())
            assertNull(source.readTextFile(secret.toString()))
            assertFalse(source.downloadToFile(secret.toString(), PlatformFile(root.resolve("copied.nfo").toString())))
            assertFalse(source.deleteFile(outside.toString()))
            assertFalse(source.deleteFile(root.toString()))
            assertTrue(secret.exists())
            assertTrue(root.exists())

            val externalEntry = MediaEntry(
                name = secret.fileName.toString(),
                path = secret.toString(),
                isDirectory = false,
            )
            assertFailsWith<IllegalArgumentException> { source.resolvePlayMedia(externalEntry) }

            val playable = source.resolvePlayMedia(
                MediaEntry(video.fileName.toString(), video.toString(), isDirectory = false),
            )
            assertEquals(video.toAbsolutePath().normalize().toString(), playable.url)
        }
    }

    @Test
    fun `扫描不跟随指向库外的符号链接或目录联接`() = runBlocking {
        withTestTree { root, outside ->
            outside.resolve("secret.nfo").writeText("不能读取")
            val link = root.resolve("outside-link")
            val linkCreated = runCatching {
                link.createSymbolicLinkPointingTo(outside)
                true
            }.getOrDefault(false)
            if (!linkCreated) return@withTestTree

            val source = DesktopLocalSource(root.toString())
            assertTrue(source.listFolderAll("").none { it.name == link.fileName.toString() })
            assertNull(source.readTextFile(link.resolve("secret.nfo").toString()))
            assertFalse(source.deleteFile(link.toString()))
            assertTrue(outside.resolve("secret.nfo").exists())
        }
    }

    @Test
    fun `数据库路径也不能穿过指向库内的链接目录`() = runBlocking {
        withTestTree { root, _ ->
            val realDir = root.resolve("real").createDirectories()
            val nfo = realDir.resolve("tvshow.nfo").apply { writeText("不能经链接读取") }
            val link = root.resolve("inside-link")
            val linkCreated = runCatching {
                link.createSymbolicLinkPointingTo(realDir)
                true
            }.getOrDefault(false)
            if (!linkCreated) return@withTestTree

            val source = DesktopLocalSource(root.toString())
            assertNull(source.readTextFile(link.resolve(nfo.fileName).toString()))
            assertFalse(source.deleteFile(link.resolve(nfo.fileName).toString()))
            assertTrue(nfo.exists())
        }
    }

    @Test
    fun `允许删除库内番剧目录但不跟随其中的链接`() = runBlocking {
        withTestTree { root, outside ->
            val showDir = root.resolve("Show").createDirectories()
            showDir.resolve("S01E01.mkv").createFile()
            val outsideFile = outside.resolve("keep.txt").apply { writeText("保留") }
            runCatching { showDir.resolve("outside-link").createSymbolicLinkPointingTo(outside) }

            val source = DesktopLocalSource(root.toString())
            assertTrue(source.deleteFile(showDir.toString()))
            assertFalse(showDir.exists())
            assertTrue(outsideFile.exists())
        }
    }

    private suspend fun withTestTree(block: suspend (root: Path, outside: Path) -> Unit) {
        val parent = Files.createTempDirectory("unu-local-source-")
        val root = parent.resolve("library").createDirectories()
        val outside = parent.resolve("outside").createDirectories()
        try {
            block(root, outside)
        } finally {
            Files.walk(parent).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    runCatching { path.deleteIfExists() }
                }
            }
        }
    }
}
