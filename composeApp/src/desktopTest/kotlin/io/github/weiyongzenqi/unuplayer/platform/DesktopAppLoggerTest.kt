package io.github.weiyongzenqi.unuplayer.platform

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopAppLoggerTest {

    @Test
    fun `程序与mpv日志落盘前统一脱敏`() {
        val directory = Files.createTempDirectory("unu-logger-redaction-")
        val logger = DesktopAppLogger()
        try {
            logger.setDirectory(directory.toString())
            logger.appEvent(
                "security",
                "Authorization: Basic basic-canary password=app-canary https://user:pass@example.invalid/dav",
            )
            logger.log("info", "mpv", "http-header-fields=Authorization: Bearer bearer-canary")
            logger.shutdown()

            val text = findLogFiles(directory, "unu-").joinToString("\n") { it.readText() }
            listOf("basic-canary", "app-canary", "user:pass", "bearer-canary").forEach {
                assertFalse(text.contains(it), "日志仍包含敏感 canary：$it")
            }
            assertTrue(text.contains("<redacted>"))
        } finally {
            logger.shutdown()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clear屏障会先处理旧日志并保留无关文件`() {
        val directory = Files.createTempDirectory("unu-logger-clear-")
        val logger = DesktopAppLogger()
        try {
            val unrelated = directory.resolve("other.txt").apply { writeText("keep") }
            logger.setDirectory(directory.toString())
            logger.appEvent("test", "清理前日志")

            val size = runBlocking { logger.logsSize() }
            assertTrue(size > 0L)
            assertEquals(size, runBlocking { logger.clearLogs() })
            assertTrue(findLogFiles(directory, "unu-app-").isEmpty())
            assertTrue(unrelated.exists())

            logger.appEvent("test", "清理后日志")
            logger.shutdown()
            val lines = findLogFiles(directory, "unu-app-").single().readLines()
            assertEquals(1, lines.size)
            assertTrue(lines.single().contains("清理后日志"))
        } finally {
            logger.shutdown()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `并发生产后立即shutdown会drain且时间格式完整`() {
        val directory = Files.createTempDirectory("unu-logger-drain-")
        val logger = DesktopAppLogger(queueCapacity = 4096, batchSize = 128, flushIntervalMs = 50)
        val executor = Executors.newFixedThreadPool(8)
        try {
            logger.setDirectory(directory.toString())
            val start = CountDownLatch(1)
            val done = CountDownLatch(8)
            repeat(8) { worker ->
                executor.execute {
                    start.await()
                    repeat(200) { index ->
                        if (index % 2 == 0) {
                            logger.appEvent("worker-$worker", "app-$worker-$index")
                        } else {
                            logger.log("info", "mpv-$worker", "mpv-$worker-$index")
                        }
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))

            logger.shutdown()
            logger.appEvent("after", "shutdown 后不得写入")

            val appLines = findLogFiles(directory, "unu-app-").single().readLines()
            val mpvLines = findLogFiles(directory, "unu-mpv-").single().readLines()
            assertEquals(800, appLines.size)
            assertEquals(800, mpvLines.size)
            assertTrue((appLines + mpvLines).all(DATE_LINE_REGEX::matches))
            assertFalse((appLines + mpvLines).any { it.contains("shutdown 后不得写入") })
        } finally {
            executor.shutdownNow()
            logger.shutdown()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `队列满时生产端不阻塞并记录丢弃摘要`() {
        val directory = Files.createTempDirectory("unu-logger-overflow-")
        val firstWrite = AtomicBoolean(true)
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val logger = DesktopAppLogger(
            queueCapacity = 4,
            batchSize = 128,
            flushIntervalMs = 10_000,
            beforeWrite = {
                if (firstWrite.compareAndSet(true, false)) {
                    writeStarted.countDown()
                    check(releaseWrite.await(10, TimeUnit.SECONDS))
                }
            },
        )
        try {
            logger.setDirectory(directory.toString())
            logger.appEvent("test", "first")
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS))

            val startedAt = System.nanoTime()
            repeat(200) { logger.appEvent("overflow", "entry-$it") }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue(elapsedMs < 1_000, "有界队列满时生产端不应阻塞，实际 ${elapsedMs}ms")

            releaseWrite.countDown()
            logger.shutdown()

            val text = findLogFiles(directory, "unu-app-").single().readText()
            assertTrue(text.contains("日志队列已满，丢弃"))
            assertTrue(text.contains("app=196 mpv=0"))
            assertTrue(text.lineSequence().count { it.isNotBlank() } < 201)
        } finally {
            releaseWrite.countDown()
            logger.shutdown()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `单条写入失败会重置slot且下一条可恢复`() {
        val directory = Files.createTempDirectory("unu-logger-recover-")
        val firstWrite = AtomicBoolean(true)
        val logger = DesktopAppLogger(
            queueCapacity = 16,
            beforeWrite = {
                if (firstWrite.compareAndSet(true, false)) error("fault")
            },
        )
        try {
            logger.setDirectory(directory.toString())
            logger.appEvent("test", "will-fail")
            logger.appEvent("test", "will-recover")
            logger.shutdown()

            val text = findLogFiles(directory, "unu-app-").single().readText()
            assertFalse(text.contains("will-fail"))
            assertTrue(text.contains("will-recover"))
        } finally {
            logger.shutdown()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `目录切换与阻塞写入串行且旧日志不会写入新目录`() {
        val oldDirectory = Files.createTempDirectory("unu-logger-old-")
        val newDirectory = Files.createTempDirectory("unu-logger-new-")
        val firstWrite = AtomicBoolean(true)
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val logger = DesktopAppLogger(
            queueCapacity = 32,
            beforeWrite = {
                if (firstWrite.compareAndSet(true, false)) {
                    writeStarted.countDown()
                    check(releaseWrite.await(10, TimeUnit.SECONDS))
                }
            },
        )
        try {
            logger.setDirectory(oldDirectory.toString())
            logger.appEvent("test", "old-entry")
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS))

            logger.setDirectory(newDirectory.toString())
            logger.setDirectory(newDirectory.toString()) // 同目录必须幂等
            logger.appEvent("test", "new-entry")
            releaseWrite.countDown()
            logger.shutdown()

            val oldText = findLogFiles(oldDirectory, "unu-app-").single().readText()
            val newText = findLogFiles(newDirectory, "unu-app-").single().readText()
            assertTrue(oldText.contains("old-entry"))
            assertFalse(oldText.contains("new-entry"))
            assertTrue(newText.contains("new-entry"))
            assertFalse(newText.contains("old-entry"))
        } finally {
            releaseWrite.countDown()
            logger.shutdown()
            oldDirectory.toFile().deleteRecursively()
            newDirectory.toFile().deleteRecursively()
        }
    }

    private fun findLogFiles(directory: java.nio.file.Path, prefix: String): List<java.nio.file.Path> =
        Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().startsWith(prefix) }.toList()
        }

    private companion object {
        val DATE_LINE_REGEX = Regex(
            "^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}] \\[.+] \\[.+] .*$",
        )
    }
}
