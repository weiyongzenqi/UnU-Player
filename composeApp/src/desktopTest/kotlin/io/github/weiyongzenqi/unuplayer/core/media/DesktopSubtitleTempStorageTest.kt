package io.github.weiyongzenqi.unuplayer.core.media

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSubtitleTempStorageTest {

    @Test
    fun `只统计并删除超过24小时的安全会话`() = runBlocking {
        val parent = Files.createTempDirectory("unu-sub-storage-")
        try {
            val root = parent.resolve("subtitles").createDirectories()
            val stale = root.resolve("session-stale").createDirectories()
            stale.resolve("one.ass").writeBytes(ByteArray(11))
            stale.resolve("nested").createDirectories().resolve("two.srt").writeBytes(ByteArray(17))
            val fresh = root.resolve("session-fresh").createDirectories()
            fresh.resolve("active.ass").writeBytes(ByteArray(29))

            val now = 2_000_000_000_000L
            Files.setLastModifiedTime(stale, FileTime.fromMillis(now - DESKTOP_SUBTITLE_STALE_MILLIS - 1L))
            Files.setLastModifiedTime(fresh, FileTime.fromMillis(now - DESKTOP_SUBTITLE_STALE_MILLIS + 1L))
            val storage = DesktopSubtitleTempStorage(root) { now }

            assertEquals(28L, storage.staleSizeBytes())
            assertEquals(28L, storage.clearStaleSessions())
            assertFalse(stale.exists())
            assertTrue(fresh.exists())
            assertEquals(0L, storage.staleSizeBytes())
        } finally {
            deleteTree(parent)
        }
    }

    @Test
    fun `拒绝越界目录和包含链接的过期会话`() = runBlocking {
        val parent = Files.createTempDirectory("unu-sub-storage-safe-")
        try {
            val root = parent.resolve("subtitles").createDirectories()
            val outside = parent.resolve("outside").createDirectories()
            val sentinel = outside.resolve("sentinel.srt").apply { writeText("safe") }
            val outsideSession = outside.resolve("session-outside").createDirectories()
            outsideSession.resolve("outside.ass").writeText("outside")
            val now = 2_000_000_000_000L
            Files.setLastModifiedTime(outsideSession, FileTime.fromMillis(now - DESKTOP_SUBTITLE_STALE_MILLIS - 1L))
            val storage = DesktopSubtitleTempStorage(root) { now }

            assertFalse(storage.isOwnedStaleSession(outsideSession))
            assertEquals(0L, storage.clearStaleSessions())
            assertTrue(outsideSession.exists())
            assertEquals("safe", Files.readString(sentinel))

            val active = root.resolve("session-active").createDirectories()
            active.resolve("active.ass").writeBytes(ByteArray(13))
            Files.setLastModifiedTime(active, FileTime.fromMillis(now - DESKTOP_SUBTITLE_STALE_MILLIS - 1L))
            storage.markActiveSession(active)
            assertEquals(0L, storage.staleSizeBytes())
            assertEquals(0L, storage.clearStaleSessions())
            assertTrue(active.exists())
            storage.markInactiveSession(active)
            assertEquals(13L, storage.clearStaleSessions())
            assertFalse(active.exists())

            val linkedSession = root.resolve("session-linked").createDirectories()
            val linkCreated = runCatching {
                Files.createSymbolicLink(linkedSession.resolve("outside-link.srt"), sentinel)
                true
            }.getOrDefault(false)
            if (linkCreated) {
                Files.setLastModifiedTime(
                    linkedSession,
                    FileTime.fromMillis(now - DESKTOP_SUBTITLE_STALE_MILLIS - 1L),
                )
                assertEquals(0L, storage.staleSizeBytes())
                assertEquals(0L, storage.clearStaleSessions())
                assertTrue(linkedSession.exists())
                assertEquals("safe", Files.readString(sentinel))
            }
        } finally {
            deleteTree(parent)
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
