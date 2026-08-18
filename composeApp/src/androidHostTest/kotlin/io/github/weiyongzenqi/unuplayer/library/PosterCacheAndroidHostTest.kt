package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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

/**
 * Android PosterCache 节流回归测试(CR-067 / CA-001)。
 *
 * 跑在 JVM host(androidHostTest), 直接用 internal constructor 注入小 trimIntervalMillis,
 * 避免依赖 Android Context 与 30 秒默认间隔。
 */
class PosterCacheAndroidHostTest {

    @Test
    fun `节流开启时连续publish不触发trim`() = runBlocking {
        val root = Files.createTempDirectory("unu-poster-cache-test-").toFile()
        try {
            // trimIntervalMillis = Long.MAX_VALUE: 节流永不放行(force 除外)
            val cache = PosterCache(root, trimIntervalMillis = Long.MAX_VALUE)
            // 容量 100 字节, 每文件 60 字节, 故意超限; 若 publish 路径真触发 trim, 文件早被删
            repeat(5) { i ->
                cache.get("show", "poster$i.jpg", "id$i", 100) { part ->
                    part.writeBytes(ByteArray(60))
                    true
                }
            }
            // 节流不放行 -> 5 个 final 全部保留
            val finalFiles = root.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".part") }
                .toList()
            assertEquals(5, finalFiles.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `已有超限文件视为缓存未命中且恰好上限文件可复用`() = runBlocking {
        val root = Files.createTempDirectory("unu-poster-cache-limit-").toFile()
        try {
            val cache = PosterCache(root, trimIntervalMillis = Long.MAX_VALUE)
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
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `节流关闭时每次publish都trim`() = runBlocking {
        val root = Files.createTempDirectory("unu-poster-cache-test-").toFile()
        try {
            // trimIntervalMillis = 0: trimIntervalMillis > 0L 为 false, 不节流, 每次 publish 都 trim
            val cache = PosterCache(root, trimIntervalMillis = 0)
            // 容量 100 字节, 每文件 60 字节
            repeat(5) { i ->
                cache.get("show", "poster$i.jpg", "id$i", 100) { part ->
                    part.writeBytes(ByteArray(60))
                    true
                }
            }
            // 不节流 + 容量 100 -> 每次 publish 超限都 trim, 最终总大小 <= 100
            assertTrue(cache.sizeBytes() <= 100L)
            // final 文件数应 <= 2(60 + 60 = 120 > 100, 第二次就 trim 掉前一个)
            val finalFiles = root.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".part") }
                .toList()
            assertTrue(finalFiles.size <= 2)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `applyMaxSize容量下调时force立即trim`() = runBlocking {
        val root = Files.createTempDirectory("unu-poster-cache-test-").toFile()
        try {
            // 节流开启(永不放行), 大容量 1MB 让 get() 内 applyMaxSize 不触发 force trim
            val cache = PosterCache(root, trimIntervalMillis = Long.MAX_VALUE)
            // 填入 5 个文件(每个 60 字节, 共 300 字节), 远低于 1MB 不触发淘汰
            repeat(5) { i ->
                cache.get("show", "poster$i.jpg", "id$i", 1_000_000) { part ->
                    part.writeBytes(ByteArray(60))
                    true
                }
            }
            // 节流开启 + 大容量 -> 5 文件都在
            assertEquals(
                5,
                root.walkTopDown().count { it.isFile && !it.name.endsWith(".part") },
            )
            // 容量下调到 100 字节, applyMaxSize 内 wasLowered=true -> force=true trim
            cache.updateMaxSizeBytes(100)
            // force trim 后总大小 <= 100
            assertTrue(cache.sizeBytes() <= 100L)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `导入图片不放宽容量且收尾按当前上限整理`() = runBlocking {
        val root = Files.createTempDirectory("unu-poster-cache-test-").toFile()
        try {
            val cache = PosterCache(root, trimIntervalMillis = Long.MAX_VALUE)
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
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `集照导入失败保留旧文件且成功时原子替换`() = runBlocking {
        val root = Files.createTempDirectory("unu-poster-cache-test-").toFile()
        try {
            val cache = PosterCache(root, trimIntervalMillis = Long.MAX_VALUE)
            val target = cache.episodeThumbFile("show", 7L).apply {
                writeBytes("old".encodeToByteArray())
            }

            assertNull(cache.importEpisodeThumb("show", 7L, ByteArray(0)))
            assertContentEquals("old".encodeToByteArray(), target.readBytes())
            assertFalse(root.walkTopDown().any { it.isFile && it.name.endsWith(".part") })

            val imported = cache.importEpisodeThumb("show", 7L, "new".encodeToByteArray())
            assertNotNull(imported)
            assertFalse(imported.created, "替换既有集照不得声明为本轮新建文件")
            assertContentEquals("new".encodeToByteArray(), target.readBytes())
            assertFalse(root.walkTopDown().any { it.isFile && it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `首次clearShow期间的旧下载不能复活且重建目录后新下载可发布`() = runBlocking {
        val root = Files.createTempDirectory("unu-poster-cache-test-").toFile()
        try {
            val cache = PosterCache(root, trimIntervalMillis = Long.MAX_VALUE)
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
            assertFalse(root.walkTopDown().any { it.isFile && it.readText() == "old-complete" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `首次clearShow期间的旧导入不能复活且重建目录后新导入可发布`() = runBlocking {
        val root = Files.createTempDirectory("unu-poster-cache-test-").toFile()
        try {
            val cache = PosterCache(root, trimIntervalMillis = Long.MAX_VALUE)
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
            assertFalse(root.walkTopDown().any { it.isFile && it.readText() == "old" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `超长 showKey 用哈希后缀避免前112字符截断碰撞`() = runBlocking {
        val root = Files.createTempDirectory("unu-poster-cache-test-").toFile()
        try {
            val cache = PosterCache(root, trimIntervalMillis = Long.MAX_VALUE)
            val longA = "A".repeat(112) + "-X"
            val longB = "A".repeat(112) + "-Y"
            val first = cache.get(longA, "poster.jpg", "lib1:/same", 1_000_000) { part ->
                part.writeBytes("one".encodeToByteArray())
                true
            }!!
            val second = cache.get(longB, "poster.jpg", "lib2:/same", 1_000_000) { part ->
                part.writeBytes("two".encodeToByteArray())
                true
            }!!
            // 前112字符相同的超长 key 不应碰撞到同一目录(内容各自保留)
            assertTrue(first.canonicalPath != second.canonicalPath)
            assertEquals("one", first.readText())
            assertEquals("two", second.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
