package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.util.Crypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PosterCacheDesktopTest {

    @Test
    fun `同一目标single flight且最终文件不会暴露半成品`() = withTempCache { root, cache ->
        val downloaderCalls = AtomicInteger(0)
        val halfWritten = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val requests = List(40) {
            async {
                cache.get("show", "poster.jpg", "1:/poster.jpg", 1_000_000) { part ->
                    downloaderCalls.incrementAndGet()
                    part.writeBytes("half".encodeToByteArray())
                    halfWritten.complete(Unit)
                    release.await()
                    part.appendBytes("-complete".encodeToByteArray())
                    true
                }
            }
        }

        withTimeout(2_000) { halfWritten.await() }
        val duringDownload = filesUnder(root)
        assertTrue(duringDownload.isNotEmpty())
        assertTrue(duringDownload.all { it.name.endsWith(".part") })

        release.complete(Unit)
        val results = withTimeout(5_000) { requests.awaitAll() }
        assertEquals(1, downloaderCalls.get())
        assertEquals(1, results.filterNotNull().map { it.canonicalPath }.distinct().size)
        assertContentEquals("half-complete".encodeToByteArray(), results.first()!!.readBytes())
        assertFalse(filesUnder(root).any { it.name.endsWith(".part") })
    }

    @Test
    fun `不同目标可以并行下载`() = withTempCache { _, cache ->
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        suspend fun download(part: File): Boolean {
            val now = active.incrementAndGet()
            peak.updateAndGet { current -> maxOf(current, now) }
            if (now >= 2) bothStarted.complete(Unit)
            try {
                release.await()
                part.writeText("ok")
                return true
            } finally {
                active.decrementAndGet()
            }
        }

        val first = async { cache.get("show", "poster.jpg", "one", 1_000_000, downloader = ::download) }
        val second = async { cache.get("show", "poster.jpg", "two", 1_000_000, downloader = ::download) }
        withTimeout(2_000) { bothStarted.await() }
        release.complete(Unit)
        awaitAll(first, second)

        assertTrue(peak.get() >= 2)
    }

    @Test
    fun `失败异常和取消均不留下final或part`() = withTempCache { root, cache ->
        assertNull(cache.get("show", "false.jpg", "false", 1_000_000) { part ->
            part.writeText("partial")
            false
        })
        assertTrue(filesUnder(root).isEmpty())

        assertNull(cache.get("show", "throw.jpg", "throw", 1_000_000) { part ->
            part.writeText("partial")
            error("fake")
        })
        assertTrue(filesUnder(root).isEmpty())

        val started = CompletableDeferred<Unit>()
        val job = async {
            cache.get("show", "cancel.jpg", "cancel", 1_000_000) { part ->
                part.writeText("partial")
                started.complete(Unit)
                delay(5_000)
                true
            }
        }
        withTimeout(2_000) { started.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(filesUnder(root).isEmpty())
    }

    @Test
    fun `创建part失败不登记generation state`() = runBlocking {
        val root = Files.createTempDirectory("unu-poster-cache-create-fail-").toFile()
        try {
            val cache = PosterCache(root, createTempFile = { _, _, _ -> error("磁盘不可用") })
            assertNull(cache.get("failed-show", "poster.jpg", "identity", 1_000) { error("不可达") })
            assertEquals(0, cache.generationStateCountForTest())
            assertTrue(filesUnder(root).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `已有超限文件视为缓存未命中且恰好上限文件可复用`() = withTempCache { _, cache ->
        val first = cache.get(
            showKey = "show",
            imageBasename = "poster.jpg",
            sourceIdentity = "identity",
            maxSizeBytes = 1_000_000,
            maxFileBytes = 4,
            downloader = { part ->
                part.writeBytes(ByteArray(4) { 1 })
                true
            },
        )
        assertNotNull(first)

        val downloaderCalls = AtomicInteger(0)
        val reused = cache.get(
            showKey = "show",
            imageBasename = "poster.jpg",
            sourceIdentity = "identity",
            maxSizeBytes = 1_000_000,
            maxFileBytes = 4,
            downloader = {
                downloaderCalls.incrementAndGet()
                error("不应重新下载")
            },
        )
        assertNotNull(reused)
        assertEquals(0, downloaderCalls.get())

        val resized = cache.get(
            showKey = "show",
            imageBasename = "poster.jpg",
            sourceIdentity = "identity",
            maxSizeBytes = 1_000_000,
            maxFileBytes = 3,
            downloader = { part ->
                downloaderCalls.incrementAndGet()
                part.writeBytes(ByteArray(3) { 2 })
                true
            },
        )
        assertNotNull(resized)
        assertEquals(1, downloaderCalls.get())
        assertEquals(3L, resized.length())
    }

    @Test
    fun `clear期间的旧下载不能复活final且后续可重新下载`() = withTempCache { root, cache ->
        val partReady = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async {
            cache.get("show", "poster.jpg", "identity", 1_000_000) { part ->
                part.writeText("old-part")
                partReady.complete(Unit)
                release.await()
                requireNotNull(part.parentFile).mkdirs()
                part.writeText("old-complete")
                true
            }
        }

        withTimeout(2_000) { partReady.await() }
        cache.clear()
        release.complete(Unit)
        assertNull(withTimeout(2_000) { old.await() })
        assertEquals(0L, cache.sizeBytes())
        assertFalse(filesUnder(root).any { !it.name.endsWith(".part") })

        val fresh = cache.get("show", "poster.jpg", "identity", 1_000_000) { part ->
            part.writeText("fresh")
            true
        }
        assertEquals("fresh", fresh!!.readText())
    }

    @Test
    fun `首次clearShow期间的旧下载不能复活且重建目录后新下载可发布`() = withTempCache { root, cache ->
        val partReady = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async {
            cache.get("show", "old.jpg", "old", 1_000_000) { part ->
                part.writeText("old-part")
                partReady.complete(Unit)
                release.await()
                requireNotNull(part.parentFile).mkdirs()
                part.writeText("old-complete")
                true
            }
        }

        withTimeout(2_000) { partReady.await() }
        cache.clearShow("show")
        val fresh = cache.get("show", "new.jpg", "new", 1_000_000) { part ->
            part.writeText("fresh")
            true
        }
        release.complete(Unit)

        assertNull(withTimeout(2_000) { old.await() })
        assertEquals("fresh", fresh!!.readText())
        assertFalse(filesUnder(root).any { it.isFile && it.readText() == "old-complete" })
    }

    @Test
    fun `首次clearShow期间的旧导入不能复活且重建目录后新导入可发布`() = withTempCache { root, cache ->
        val publishReady = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async {
            cache.importShowImage("show", "old.jpg", "old", "old".encodeToByteArray()) {
                publishReady.complete(Unit)
                release.await()
            }
        }

        withTimeout(2_000) { publishReady.await() }
        cache.clearShow("show")
        val fresh = cache.importShowImage("show", "new.jpg", "new", "fresh".encodeToByteArray())
        release.complete(Unit)

        assertNull(withTimeout(2_000) { old.await() })
        assertNotNull(fresh)
        assertEquals("fresh", fresh.file.readText())
        assertFalse(filesUnder(root).any { it.isFile && it.readText() == "old" })
    }

    @Test
    fun `容量淘汰删除旧文件并保留最新文件`() = withTempCache(trimIntervalMillis = 0) { _, cache ->
        suspend fun put(identity: String): File = cache.get(
            "show",
            "$identity.jpg",
            identity,
            130,
        ) { part ->
            part.writeBytes(ByteArray(60) { identity.first().code.toByte() })
            true
        }!!

        put("a")
        delay(15)
        put("b")
        delay(15)
        val newest = put("c")

        assertTrue(cache.sizeBytes() <= 130L)
        assertTrue(newest.exists())
        assertEquals(60L, newest.length())
    }

    @Test
    fun `导入图片不放宽容量且收尾按当前上限整理`() = withTempCache { _, cache ->
        cache.updateMaxSizeBytes(100)

        repeat(3) { index ->
            val imported = cache.importShowImage(
                showKey = "show-$index",
                imageBasename = "poster.jpg",
                sourceIdentity = "source-$index",
                bytes = ByteArray(60) { index.toByte() },
            )
            assertNotNull(imported)
            assertTrue(imported.created)
        }

        assertEquals(180L, cache.sizeBytes(), "导入过程不应通过修改上限触发即时整理")
        cache.trimToCurrentLimit()
        assertTrue(cache.sizeBytes() <= 100L, "导入收尾必须沿用既有容量上限")
    }

    @Test
    fun `集照导入失败保留旧文件且成功时原子替换`() = withTempCache { root, cache ->
        val target = cache.episodeThumbFile("show", 7L).apply {
            writeBytes("old".encodeToByteArray())
        }

        assertNull(cache.importEpisodeThumb("show", 7L, ByteArray(0)))
        assertContentEquals("old".encodeToByteArray(), target.readBytes())
        assertFalse(filesUnder(root).any { it.name.endsWith(".part") })

        val imported = cache.importEpisodeThumb("show", 7L, "new".encodeToByteArray())
        assertNotNull(imported)
        assertFalse(imported.created, "替换既有集照不得声明为本轮新建文件")
        assertContentEquals("new".encodeToByteArray(), target.readBytes())
        assertFalse(filesUnder(root).any { it.name.endsWith(".part") })
    }

    @Test
    fun `恶意路径不越界且来源身份避免同名碰撞`() = withTempCache { root, cache ->
        val outside = File(root.parentFile, "poster-cache-sentinel-${System.nanoTime()}").apply { writeText("safe") }
        try {
            val first = cache.get("../outside\\..", "C:\\temp\\..\\poster.jpg", "lib1:/same", 1_000_000) {
                it.writeText("one")
                true
            }!!
            val second = cache.get("../outside\\..", "C:\\temp\\..\\poster.jpg", "lib2:/same", 1_000_000) {
                it.writeText("two")
                true
            }!!

            val canonicalRoot = root.canonicalFile.toPath()
            assertTrue(first.canonicalFile.toPath().startsWith(canonicalRoot))
            assertTrue(second.canonicalFile.toPath().startsWith(canonicalRoot))
            assertFalse(first.canonicalPath == second.canonicalPath)
            assertEquals("one", first.readText())
            assertEquals("two", second.readText())

            cache.clearShow("../outside\\..")
            assertTrue(outside.exists())
            assertEquals("safe", outside.readText())
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `size只统计final且clear后为零`() = withTempCache { root, cache ->
        val final = cache.get("show", "poster.jpg", "identity", 1_000_000) {
            it.writeBytes(ByteArray(37))
            true
        }!!
        File(final.parentFile, ".orphan.part").writeBytes(ByteArray(99))

        assertEquals(37L, cache.sizeBytes())
        cache.clear()
        assertEquals(0L, cache.sizeBytes())
        assertTrue(filesUnder(root).isEmpty())
    }

    @Test
    fun `首次访问清理遗留part且不影响正常缓存`() = runBlocking {
        val root = Files.createTempDirectory("unu-poster-cache-test-").toFile()
        try {
            val showDir = File(root, "show").apply { mkdirs() }
            val final = File(showDir, "poster.jpg").apply { writeText("final") }
            val orphan = File(showDir, ".poster.jpg.crashed.part").apply { writeText("partial") }
            val cache = PosterCache(root)

            assertTrue(orphan.exists())
            assertEquals(final.length(), cache.sizeBytes())
            assertFalse(orphan.exists())
            assertEquals("final", final.readText())

            val laterPart = File(showDir, ".poster.jpg.active.part").apply { writeText("active") }
            assertEquals(final.length(), cache.sizeBytes())
            assertTrue(laterPart.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `超长 showKey 用哈希后缀避免前112字符截断碰撞`() = withTempCache { root, cache ->
        val longA = "A".repeat(112) + "-X"
        val longB = "A".repeat(112) + "-Y"
        val first = cache.get(longA, "poster.jpg", "lib1:/same", 1_000_000) {
            it.writeText("one")
            true
        }!!
        val second = cache.get(longB, "poster.jpg", "lib2:/same", 1_000_000) {
            it.writeText("two")
            true
        }!!
        // 修复前: safeSegment 截断前 112 字符, 两 key 落到同一目录内容相互覆盖;
        // 修复后: 超长 key 带 12 位哈希后缀, 目录不同且内容各自保留。
        assertFalse(first.canonicalPath == second.canonicalPath, "前112字符相同的超长 key 不应碰撞到同目录")
        assertEquals("one", first.readText())
        assertEquals("two", second.readText())
        // 目录名 = 前96字符 + "-" + 12位哈希(对齐 Android safeSegment 规则)
        assertEquals(longA.take(96) + "-" + Crypto.sha256Hex(longA).take(12), first.parentFile.name)
    }

    private fun withTempCache(
        trimIntervalMillis: Long = 30_000,
        block: suspend kotlinx.coroutines.CoroutineScope.(File, PosterCache) -> Unit,
    ) = runBlocking {
        val root = Files.createTempDirectory("unu-poster-cache-test-").toFile()
        try {
            block(root, PosterCache(root, trimIntervalMillis))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun filesUnder(root: File): List<File> =
        root.walkTopDown().filter { it.isFile }.toList()
}
