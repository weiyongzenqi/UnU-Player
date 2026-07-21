package io.github.weiyongzenqi.unuplayer.ui.player

import java.nio.file.Files
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackTempFileSessionTest {

    @Test
    fun `detach只返回本会话文件且不可再次创建`() {
        val root = Files.createTempDirectory("unu-playback-temp-")
        try {
            val unrelated = root.resolve("sub_auto_other_0.srt").createFile().toFile()
            val session = PlaybackTempFileSession(root.toFile(), sessionId = 42)
            val importedLease = session.newFile("sub_import", "SRT")
            val imported = importedLease.file.apply { createNewFile() }
            importedLease.close()
            val automaticLease = session.newFile("sub_auto", "../ASS")
            val automatic = automaticLease.file.apply { createNewFile() }
            automaticLease.close()

            val cleanup = requireNotNull(session.detachCleanupTask())
            val owned = cleanup()

            assertEquals(listOf(imported, automatic), owned)
            assertTrue(unrelated !in owned)
            assertEquals(null, session.detachCleanupTask())
            assertFailsWith<IllegalStateException> { session.newFile("sub_auto", "srt") }
            assertEquals("sub_import_16_0.srt", imported.name)
            assertEquals("sub_auto_16_1.ass", automatic.name)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `最终写关闭后拒绝迟到的周期写`() {
        val gate = PlaybackRecordWriteGate()
        val events = mutableListOf<String>()

        assertTrue(gate.submitIfOpen { events += "periodic" })
        assertTrue(gate.closeAndSubmit { events += "final" })
        assertFalse(gate.submitIfOpen { events += "late" })
        assertFalse(gate.closeAndSubmit { events += "duplicate-final" })

        assertEquals(listOf("periodic", "final"), events)
    }

    @Test
    fun `首次清理超时后最后一个 lease 只触发一次迟到清理`() {
        val root = Files.createTempDirectory("unu-playback-late-cleanup-")
        try {
            val session = PlaybackTempFileSession(
                root.toFile(),
                sessionId = 43,
                cleanupWaitMillis = 10,
            )
            val lease = session.newFile("sub_auto", "srt")
            val file = lease.file.apply { createNewFile() }
            val cleanup = requireNotNull(session.detachCleanupTask())
            val scheduled = AtomicInteger(0)
            var retriedFiles = emptyList<java.io.File>()
            session.setLateCleanupScheduler {
                scheduled.incrementAndGet()
                retriedFiles = cleanup()
            }

            assertTrue(cleanup().isEmpty())
            lease.close()
            lease.close()

            assertEquals(1, scheduled.get())
            assertEquals(listOf(file), retriedFiles)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
