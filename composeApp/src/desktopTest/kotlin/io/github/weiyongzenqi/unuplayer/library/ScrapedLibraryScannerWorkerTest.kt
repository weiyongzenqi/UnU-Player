package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrapedLibraryScannerWorkerTest {

    @Test
    fun `宽树峰值并发受 worker 数限制且每个路径只访问一次`() = runBlocking {
        val children = (1..120).map { "dir-$it" }
        val tree = buildMap {
            put("root", directoryEntries("root", children))
            children.forEach { put(it, directoryEntries(it)) }
        }

        val concurrentSource = FakeTreeSource(tree, delayMs = 20)
        val concurrent = scanner(concurrentSource, concurrency = 4).scan()
        assertTrue(concurrentSource.peakActive.get() in 2..4)
        assertEquals(121, concurrent.scannedDirs)
        assertEquals((listOf("root") + children).toSet(), concurrentSource.callSnapshot().toSet())
        assertEquals(121, concurrentSource.callSnapshot().size)

        val singleSource = FakeTreeSource(tree, delayMs = 1)
        val single = scanner(singleSource, concurrency = 1).scan()
        assertEquals(1, singleSource.peakActive.get())
        assertEquals(121, single.scannedDirs)
    }

    @Test
    fun `小型有界队列在并发一到八时无死锁且队列峰值受限`() = runBlocking {
        val children = (1..400).map { "dir-$it" }
        val tree = buildMap {
            put("root", directoryEntries("root", children))
            children.forEach { put(it, directoryEntries(it)) }
        }

        for (concurrency in 1..8) {
            val source = FakeTreeSource(tree)
            val result = withTimeout(5_000) {
                scanner(source, concurrency = concurrency, queueCapacity = 4).scan()
            }
            val calls = source.callSnapshot()

            assertEquals(401, result.scannedDirs, "concurrency=$concurrency")
            assertEquals(401, result.visitedDirs, "concurrency=$concurrency")
            assertTrue(result.peakQueuedDirs in 1..4, "concurrency=$concurrency, peak=${result.peakQueuedDirs}")
            assertFalse(result.directoryLimitReached, "concurrency=$concurrency")
            assertEquals(401, calls.size, "concurrency=$concurrency")
            assertTrue(calls.groupingBy { it }.eachCount().values.all { it == 1 }, "concurrency=$concurrency")
        }
    }

    @Test
    fun `visited达到硬上限后停止接纳目录并记录原因`() = runBlocking {
        val children = (1..200).map { "dir-$it" }
        val source = FakeTreeSource(
            tree = buildMap {
                put("root", directoryEntries("root", children))
                children.forEach { put(it, directoryEntries(it)) }
            },
        )

        val result = withTimeout(5_000) {
            scanner(
                source = source,
                concurrency = 4,
                queueCapacity = 4,
                maxVisitedDirectories = 50,
            ).scan()
        }
        val calls = source.callSnapshot()

        assertTrue(result.directoryLimitReached)
        assertEquals(50, result.visitedDirs)
        assertTrue(calls.size <= 50, "实际访问 ${calls.size} 个目录")
        assertTrue(result.scannedDirs <= 50, "实际扫描 ${result.scannedDirs} 个目录")
        assertTrue("root" in calls)
        assertTrue(result.errors >= 1)
        assertTrue(result.firstErrorMessage?.contains("50") == true)
    }

    @Test
    fun `DAG共享路径只访问一次且异常兄弟不拖垮全局`() = runBlocking {
        val tree = mapOf(
            "root" to directoryEntries("root", listOf("a", "broken", "b")),
            "a" to directoryEntries("a", listOf("shared")),
            "b" to directoryEntries("b", listOf("shared")),
            "shared" to directoryEntries("shared"),
        )
        val source = FakeTreeSource(tree, delayMs = 5, throwPaths = setOf("broken"))

        val result = scanner(source, concurrency = 3).scan()

        assertEquals(1, source.callSnapshot().count { it == "shared" })
        assertEquals(1, result.errors)
        assertTrue(result.firstErrorMessage?.contains("broken") == true)
        assertEquals(4, result.scannedDirs)
        assertTrue(source.callSnapshot().containsAll(listOf("root", "a", "b", "shared", "broken")))
    }

    @Test
    fun `增量重扫读取索引或目录失败时返回错误而不是伪装完成`() = runBlocking {
        val unusedSource = FakeTreeSource(mapOf("root" to directoryEntries("root")))
        val indexFailure = scanner(
            source = unusedSource,
            concurrency = 1,
            repo = repository(listShowPaths = { throw IOException("index unavailable") }),
        ).rescanDir("root")

        assertEquals(1, indexFailure.errors)
        assertTrue(indexFailure.firstErrorMessage?.contains("已有番剧索引") == true)
        assertTrue(unusedSource.callSnapshot().isEmpty())

        val failingSource = FakeTreeSource(emptyMap(), throwPaths = setOf("root"))
        val directoryFailure = scanner(
            source = failingSource,
            concurrency = 1,
            repo = repository(),
        ).rescanDir("root")

        assertEquals(1, directoryFailure.errors)
        assertTrue(directoryFailure.firstErrorMessage?.contains("root") == true)
        assertEquals(0, directoryFailure.scannedDirs)
    }

    @Test
    fun `强制重扫季度读取失败时不以残缺数据覆盖旧番剧`() = runBlocking {
        val showPath = "show"
        val seasonPath = "$showPath/Season 1"
        val source = FakeTreeSource(
            tree = mapOf(
                showPath to listOf(
                    MediaEntry("tvshow.nfo", "$showPath/tvshow.nfo", false),
                    MediaEntry("Season 1", seasonPath, true),
                ),
            ),
            throwPaths = setOf(seasonPath),
            textFiles = mapOf(
                "$showPath/tvshow.nfo" to "<tvshow><title>测试番剧</title><year>2026</year></tvshow>",
            ),
        )
        val upsertCalls = AtomicInteger(0)

        val result = scanner(
            source = source,
            concurrency = 1,
            repo = repository(upsertCalls = upsertCalls),
        ).scanOneShow(showPath)

        assertEquals(1, result.errors)
        assertEquals(0, result.foundShows)
        assertEquals(0, upsertCalls.get(), "季度读取失败时不能调用会删除旧子表的 upsertShow")
        assertTrue(result.firstErrorMessage?.contains(seasonPath) == true)
    }

    @Test
    fun `单番剧刷新整体运行在指定CPU调度器`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "scanner-cpu-test") }
        val dispatcher = executor.asCoroutineDispatcher()
        val observedThreads = Collections.synchronizedList(mutableListOf<String>())
        val showPath = "show"
        val source = FakeTreeSource(
            tree = mapOf(
                showPath to listOf(MediaEntry("tvshow.nfo", "$showPath/tvshow.nfo", false)),
            ),
            textFiles = mapOf(
                "$showPath/tvshow.nfo" to "<tvshow><title>测试番剧</title></tvshow>",
            ),
            onStarted = { observedThreads += Thread.currentThread().name },
        )
        try {
            val result = scanner(
                source = source,
                concurrency = 1,
                repo = repository(),
                cpuDispatcher = dispatcher,
            ).scanOneShow(showPath)

            assertEquals(0, result.errors)
            assertEquals(1, result.foundShows)
            assertTrue(observedThreads.isNotEmpty())
            assertTrue(observedThreads.all { it.startsWith("scanner-cpu-test") }, observedThreads.toString())
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `深度上限只访问零到配置深度`() = runBlocking {
        val tree = mapOf(
            "root" to directoryEntries("root", listOf("d1")),
            "d1" to directoryEntries("d1", listOf("d2")),
            "d2" to directoryEntries("d2", listOf("d3")),
            "d3" to directoryEntries("d3", listOf("d4")),
            "d4" to directoryEntries("d4"),
        )
        val source = FakeTreeSource(tree)

        val result = scanner(source, concurrency = 2, depth = 2).scan()

        assertEquals(listOf("root", "d1", "d2"), source.callSnapshot())
        assertEquals(3, result.scannedDirs)
    }

    @Test
    fun `stop标志停止新IO并正常返回部分结果`() = runBlocking {
        val children = (1..100).map { "dir-$it" }
        val started = CompletableDeferred<Unit>()
        val source = FakeTreeSource(
            tree = buildMap {
                put("root", directoryEntries("root", children))
                children.forEach { put(it, directoryEntries(it)) }
            },
            delayMs = 80,
            onStarted = { if (it == "root") started.complete(Unit) },
        )
        val stop = AtomicBoolean(false)
        val deferred = async {
            scanner(source, concurrency = 4).scan(onStopRequested = stop::get)
        }

        withTimeout(2_000) { started.await() }
        stop.set(true)
        val result = withTimeout(3_000) { deferred.await() }
        val callsAtReturn = source.callSnapshot().size
        delay(150)

        assertTrue(result.stopped)
        assertEquals(callsAtReturn, source.callSnapshot().size)
        assertEquals(0, source.active.get())
    }

    @Test
    fun `外部取消立即取消worker且不返回普通结果`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val source = FakeTreeSource(
            tree = mapOf("root" to directoryEntries("root")),
            delayMs = 5_000,
            onStarted = { started.complete(Unit) },
        )
        val deferred = async { scanner(source, concurrency = 4).scan() }

        withTimeout(2_000) { started.await() }
        deferred.cancelAndJoin()

        assertTrue(deferred.isCancelled)
        assertEquals(0, source.active.get())
    }

    @Test
    fun `墙钟超时保留部分结果并停止新IO`() = runBlocking {
        val children = (1..100).map { "dir-$it" }
        val source = FakeTreeSource(
            tree = buildMap {
                put("root", directoryEntries("root", children))
                children.forEach { put(it, directoryEntries(it)) }
            },
            delayMs = 180,
        )

        val result = withTimeout(3_000) {
            scanner(source, concurrency = 4, timeoutSeconds = 1).scan()
        }
        val calls = source.callSnapshot().size

        assertTrue(result.timedOut)
        assertTrue(calls in 1 until 101, "超时应只访问部分目录，实际 $calls")
        assertTrue(result.scannedDirs in 1 until 101)
        assertEquals(0, source.active.get())
    }

    @Test
    fun `单worker按FIFO稳定遍历`() = runBlocking {
        val tree = mapOf(
            "root" to directoryEntries("root", listOf("a", "b")),
            "a" to directoryEntries("a", listOf("a1")),
            "b" to directoryEntries("b", listOf("b1")),
            "a1" to directoryEntries("a1"),
            "b1" to directoryEntries("b1"),
        )
        val source = FakeTreeSource(tree)

        val result = scanner(source, concurrency = 1).scan()

        assertFalse(result.stopped)
        assertFalse(result.timedOut)
        assertEquals(listOf("root", "a", "b", "a1", "b1"), source.callSnapshot())
    }

    private fun scanner(
        source: MediaSource,
        concurrency: Int,
        depth: Int = 8,
        timeoutSeconds: Int = 30,
        queueCapacity: Int = 64,
        maxVisitedDirectories: Int = 100_000,
        repo: ScrapedLibraryRepository = failingRepository(),
        cpuDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    ) = ScrapedLibraryScanner(
        source = source,
        library = LibraryConfig(
            id = 1,
            name = "worker-test",
            sourceKind = MediaSourceKind.WEBDAV,
            connectionId = "test",
            localUri = null,
            rootPath = "root",
            scanDepth = depth,
            lastScannedAt = null,
            createdAt = 0,
        ),
        repo = repo,
        config = ScanConfig(
            requestIntervalMs = 0,
            concurrency = concurrency,
            depth = depth,
            timeoutSeconds = timeoutSeconds,
            directoryQueueCapacity = queueCapacity,
            maxVisitedDirectories = maxVisitedDirectories,
        ),
        cpuDispatcher = cpuDispatcher,
    )

    @Suppress("UNCHECKED_CAST")
    private fun failingRepository(): ScrapedLibraryRepository = Proxy.newProxyInstance(
        ScrapedLibraryRepository::class.java.classLoader,
        arrayOf(ScrapedLibraryRepository::class.java),
    ) { _, method, _ ->
        error("无番剧目录的 worker 测试不应调用 repository.${method.name}")
    } as ScrapedLibraryRepository

    @Suppress("UNCHECKED_CAST")
    private fun repository(
        listShowPaths: () -> List<String> = { emptyList() },
        upsertCalls: AtomicInteger = AtomicInteger(0),
    ): ScrapedLibraryRepository = Proxy.newProxyInstance(
        ScrapedLibraryRepository::class.java.classLoader,
        arrayOf(ScrapedLibraryRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "listShowPaths" -> listShowPaths()
            "isBlocked", "showExists" -> false
            "upsertShow" -> {
                upsertCalls.incrementAndGet()
                1L
            }
            else -> error("测试未配置 repository.${method.name}")
        }
    } as ScrapedLibraryRepository

    private class FakeTreeSource(
        private val tree: Map<String, List<MediaEntry>>,
        private val delayMs: Long = 0,
        private val throwPaths: Set<String> = emptySet(),
        private val textFiles: Map<String, String> = emptyMap(),
        private val onStarted: (String) -> Unit = {},
    ) : MediaSource {
        override val kind = MediaSourceKind.WEBDAV
        override val displayName = "fake-tree"
        val active = AtomicInteger(0)
        val peakActive = AtomicInteger(0)
        private val calls = Collections.synchronizedList(mutableListOf<String>())

        override suspend fun listFolder(path: String): List<MediaEntry> = listFolderAll(path)

        override suspend fun listFolderAll(path: String): List<MediaEntry> {
            calls += path
            val now = active.incrementAndGet()
            peakActive.updateAndGet { current -> maxOf(current, now) }
            onStarted(path)
            try {
                if (delayMs > 0) delay(delayMs)
                if (path in throwPaths) throw IOException("fake failure: $path")
                return tree[path] ?: directoryEntries(path)
            } finally {
                active.decrementAndGet()
            }
        }

        override suspend fun resolvePlayMedia(entry: MediaEntry): PlayableMedia =
            error("worker 测试不播放媒体")

        override suspend fun readTextFile(path: String): String? = textFiles[path]

        override suspend fun testConnection(): Boolean = true

        override fun close() = Unit

        fun callSnapshot(): List<String> = synchronized(calls) { calls.toList() }
    }

    companion object {
        private fun directoryEntries(path: String, children: List<String> = emptyList()): List<MediaEntry> =
            children.map { child -> MediaEntry(name = child, path = child, isDirectory = true) } +
                MediaEntry(name = "README.txt", path = "$path/README.txt", isDirectory = false)
    }
}
