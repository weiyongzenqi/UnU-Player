package io.github.weiyongzenqi.unuplayer.library

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidEpisodeThumbFilePublisherTest {
    @Test
    fun `原子发布替换目标且 part 消失`() {
        val directory = Files.createTempDirectory("unu-thumb-atomic-")
        try {
            val part = directory.resolve(".ep1.part").toFile()
            val destination = directory.resolve("ep1.jpg").toFile()
            destination.writeText("old")
            part.writeText("new-complete")

            assertTrue(publishAtomicEpisodeThumbPart(part, destination))
            assertFalse(part.exists())
            assertEquals("new-complete", destination.readText())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `空 part 不会删除旧目标`() {
        val directory = Files.createTempDirectory("unu-thumb-atomic-empty-")
        try {
            val part = directory.resolve(".ep1.part").toFile()
            val destination = directory.resolve("ep1.jpg").toFile()
            destination.writeText("old")
            part.createNewFile()

            assertFalse(publishAtomicEpisodeThumbPart(part, destination))
            assertTrue(part.exists())
            assertEquals("old", destination.readText())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
