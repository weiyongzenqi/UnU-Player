package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
