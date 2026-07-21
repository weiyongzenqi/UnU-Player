package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.core.platform.decodeUrlComponentPreservingPlus
import io.github.weiyongzenqi.unuplayer.domain.EpisodeNumberExtractor
import io.github.weiyongzenqi.unuplayer.webdav.isVideoFile

/**
 * 刮削库扫描器(commonMain, 面向 [MediaSource] 统一处理 WebDAV/本地)。
 *
 * 扫描策略: 递归列目录, 找含 `tvshow.nfo` 的文件夹作为番剧锚点。
 *  - 含 tvshow.nfo -> 番剧文件夹: 解析 tvshow.nfo + Season N/(season.nfo + bangumi.ini + 剧集), upsert 入库
 *  - 不含 tvshow.nfo -> 递归子目录(并发)
 *  - 番剧文件夹识别后不再深递归(Season N 在 processShow 内处理), 天然终止
 *
 * 健壮性(见 plan §5/§12):
 *  - 防死循环: [visited] 路径集合去重 + 深度上限([ScanConfig.depth]); 番剧文件夹不深递归
 *  - 不异常请求: 每个 listFolderAll/readTextFile 用保留取消语义的 Result 包裹, 失败跳过不崩;
 *    [ScanConfig.requestIntervalMs] 限流(delay) + [Semaphore] 并发上限 + 墙钟超时
 *  - 增量: 默认跳过已记录 show_path(force=false); force=true 强制刷新整番剧(删子表重插)
 *  - 可取消: [onStopRequested] 多处检查，协程取消会继续向上游传播。
 *
 * 限流语义: 每次远程操作前 delay(requestIntervalMs)，本地文件不等待；并发受 Semaphore(concurrency) 限制,
 * 实际 QPS ≈ concurrency / (requestIntervalMs/1000)。
 *
 * media_key 一致性: 剧集 mediaKey 与播放器写 PlaybackRecord 同公式
 * (WebDAV=`MediaKeys.webDav(connId, path)`, 本地=`MediaKeys.local(contentUri)`), 保证进度联动。
 */
class ScrapedLibraryScanner(
    private val source: MediaSource,
    private val library: LibraryConfig,
    private val repo: ScrapedLibraryRepository,
    private val config: ScanConfig,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val metrics = ScanMetrics()

    private val startTime = platformTimeMillis()
    private fun timedOut(): Boolean = config.timeoutSeconds > 0 &&
        (platformTimeMillis() - startTime) / 1000 >= config.timeoutSeconds

    private val semaphore = Semaphore(config.concurrency.coerceIn(1, 8))
    /** 固定 worker 并发入队时，普通 MutableSet 不能跨线程直接 add。 */
    private val visitedMutex = Mutex()

    private data class DirectoryTask(
        val path: String,
        val depth: Int,
    )

    /** 区分“目录合法但没有 season.nfo”与“读取失败”，避免失败时用残缺数据覆盖旧剧集。 */
    private sealed interface SeasonProcessResult {
        data class Success(val data: SeasonScanData) : SeasonProcessResult
        data object Skipped : SeasonProcessResult
        data object Failed : SeasonProcessResult
    }

    /** 全盘扫描(从 library.rootPath 递归)。force=true 强制刷新已记录番剧。 */
    suspend fun scan(
        force: Boolean = false,
        onProgress: (scanned: Int, foundShows: Int, foundEpisodes: Int) -> Unit = { _, _, _ -> },
        onStopRequested: () -> Boolean = { false },
    ): ScanResult {
        try {
            traverseDirectories(
                initialTasks = sequenceOf(DirectoryTask(library.rootPath, 0)),
                force = force,
                onProgress = onProgress,
                onStop = onStopRequested,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            recordError("扫描目录失败", error)
        }
        return makeResult(onStopRequested())
    }

    /**
     * 重扫当前目录(增量): 列 [dirPath] 子目录, 对比数据库已记录 show_path, 仅扫未记录的。
     * 用于"新增番剧后只扫该季度目录"场景, 不全盘重扫。
     */
    suspend fun rescanDir(
        dirPath: String,
        onProgress: (scanned: Int, foundShows: Int, foundEpisodes: Int) -> Unit = { _, _, _ -> },
        onStopRequested: () -> Boolean = { false },
    ): ScanResult {
        val existingPaths = runCatchingPreservingCancellation { repo.listShowPaths(library.id) }
            .getOrElse {
                recordError("读取已有番剧索引失败", it)
                return makeResult(onStopRequested())
            }
            .toSet()
        val entries = withLimit {
            runCatchingPreservingCancellation { source.listFolderAll(dirPath) }.getOrElse {
                recordError("读取重扫目录 $dirPath 失败", it)
                return@withLimit null
            }
        }
        if (entries == null) return makeResult(onStopRequested())
        metrics.recordScanned()
        // 未记录的子目录才扫(已记录的跳过, 增量)
        try {
            traverseDirectories(
                initialTasks = entries.asSequence()
                    .filter { it.isDirectory && it.path !in existingPaths }
                    .map { DirectoryTask(it.path, 1) },
                force = false,
                onProgress = onProgress,
                onStop = onStopRequested,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            recordError("重扫目录失败", error)
        }
        return makeResult(onStopRequested())
    }

    /**
     * 重扫单个番剧(force=true, 重新解析其 tvshow.nfo + 所有季/剧集)。
     *
     * 番剧详情页"刷新"用: 不全盘重扫, 只对该番剧文件夹走一次 [processShow](force=true),
     * 复用 upsertShow 幂等(命中现有 show 则 updateShow 元数据 + 删子表重插)。
     * showPath 来自 ScrapedShow.show_path(扫描时记录的番剧文件夹路径)。
     */
    suspend fun scanOneShow(
        showPath: String,
        onProgress: (scanned: Int, foundShows: Int, foundEpisodes: Int) -> Unit = { _, _, _ -> },
        onStopRequested: () -> Boolean = { false },
    ): ScanResult = withContext(cpuDispatcher) {
        scanOneShowInBackground(showPath, onProgress, onStopRequested)
    }

    private suspend fun scanOneShowInBackground(
        showPath: String,
        onProgress: (scanned: Int, foundShows: Int, foundEpisodes: Int) -> Unit,
        onStopRequested: () -> Boolean,
    ): ScanResult {
        val entries = withLimit {
            runCatchingPreservingCancellation { source.listFolderAll(showPath) }.getOrElse {
                recordError("读取番剧目录 $showPath 失败", it)
                return@withLimit null
            }
        }
        if (entries == null) return makeResult(onStopRequested())
        metrics.recordScanned().also { progress ->
            onProgress(progress.scannedDirs, progress.foundShows, progress.foundEpisodes)
        }
        if (library.scanMode == ScanMode.ANCHOR) {
            val anchorEntry = entries.findAnchor(library.anchorFilenames)
            if (anchorEntry != null) {
                processAnchorShow(showPath, entries, anchorEntry, force = true, onProgress, onStopRequested)
            } else {
                recordError("番剧目录缺少配置的封面锚点")
            }
        } else {
            val tvshowEntry = entries.firstOrNull { !it.isDirectory && it.name.equals("tvshow.nfo", true) }
            if (tvshowEntry != null) {
                processShow(showPath, entries, tvshowEntry, force = true, onProgress, onStopRequested)
            } else {
                recordError("番剧目录缺少 tvshow.nfo")
            }
        }
        return makeResult(onStopRequested())
    }

    /**
     * 固定 worker 使用有界 Channel 遍历目录；队列满时在当前 worker/seed 协程内联处理，
     * 不阻塞 send。pending 在父任务提交完全部子任务后结算，visited 另有目录数量硬上限。
     */
    private suspend fun traverseDirectories(
        initialTasks: Sequence<DirectoryTask>,
        force: Boolean,
        onProgress: (Int, Int, Int) -> Unit,
        onStop: () -> Boolean,
    ) = coroutineScope {
        val workerCount = config.concurrency.coerceIn(1, 8)
        val maxDepth = config.depth.coerceAtMost(MAX_DIRECTORY_TRAVERSAL_DEPTH)
        val queueCapacity = config.directoryQueueCapacity.coerceIn(1, 1024)
        val maxVisited = config.maxVisitedDirectories.coerceIn(1, 100_000)
        val queue = Channel<DirectoryTask>(queueCapacity)
        val queueSlots = Semaphore(queueCapacity)
        val queueStateMutex = Mutex()
        var queued = 0
        var pending = 1
        var directoryLimitReached = false
        val visited = mutableSetOf<String>()

        suspend fun completePending() {
            val shouldClose = queueStateMutex.withLock {
                pending--
                pending == 0
            }
            if (shouldClose) queue.close()
        }

        lateinit var processTask: suspend (DirectoryTask) -> Unit

        suspend fun submit(task: DirectoryTask) {
            if (task.depth > maxDepth || onStop() || timedOut()) return
            var limitReachedNow = false
            var visitedCount: Int? = null
            val admitted = visitedMutex.withLock {
                when {
                    task.path in visited -> false
                    visited.size >= maxVisited -> {
                        if (!directoryLimitReached) limitReachedNow = true
                        directoryLimitReached = true
                        false
                    }
                    else -> {
                        visited.add(task.path)
                        visitedCount = visited.size
                        true
                    }
                }
            }
            visitedCount?.let { metrics.setVisited(it) }
            if (limitReachedNow) {
                metrics.markDirectoryLimitReached()
                recordError("扫描目录数量达到 $maxVisited 上限，已停止接纳新目录")
            }
            if (!admitted) return

            queueStateMutex.withLock { pending++ }
            if (queueSlots.tryAcquire()) {
                val currentQueued = queueStateMutex.withLock {
                    queued++
                    queued
                }
                metrics.updatePeakQueued(currentQueued)
                if (queue.trySend(task).isFailure) {
                    queueStateMutex.withLock { queued-- }
                    queueSlots.release()
                    completePending()
                }
            } else {
                processTask(task)
            }
        }

        processTask = { task ->
            try {
                if (!onStop() && !timedOut()) {
                    inspectDirectory(task, force, onProgress, onStop).forEach { child ->
                        if (!onStop() && !timedOut()) submit(child)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                recordError("处理目录 ${task.path} 失败", error)
            } finally {
                completePending()
            }
        }

        val workers = List(workerCount) {
            launch {
                for (task in queue) {
                    queueStateMutex.withLock { queued-- }
                    queueSlots.release()
                    processTask(task)
                }
            }
        }

        try {
            initialTasks.forEach { submit(it) }
            completePending()
            workers.joinAll()
        } finally {
            queue.cancel()
        }
    }

    /** 检查单个目录；番剧目录在本 worker 内处理，普通目录只返回下一层轻量任务。 */
    private suspend fun inspectDirectory(
        task: DirectoryTask,
        force: Boolean,
        onProgress: (Int, Int, Int) -> Unit,
        onStop: () -> Boolean,
    ): Sequence<DirectoryTask> {
        if (onStop() || timedOut()) return emptySequence()

        val entries = withLimit {
            runCatchingPreservingCancellation { source.listFolderAll(task.path) }.getOrElse {
                recordError("读取目录 ${task.path} 失败", it)
                return@withLimit emptyList()
            }
        }
        if (entries.isEmpty()) return emptySequence()
        metrics.recordScanned().also { progress ->
            onProgress(progress.scannedDirs, progress.foundShows, progress.foundEpisodes)
        }

        // 番剧锚点(存在性最可靠): NFO=tvshow.nfo, ANCHOR=用户配置的锚点封面文件(多候选大小写不敏感)
        if (library.scanMode == ScanMode.ANCHOR) {
            val anchorEntry = entries.findAnchor(library.anchorFilenames)
            if (anchorEntry != null) {
                processAnchorShow(task.path, entries, anchorEntry, force, onProgress, onStop)
                return emptySequence()  // 番剧文件夹不深递归(Season N 在 processAnchorShow 内处理), 防重复
            }
        } else {
            val tvshowEntry = entries.firstOrNull { !it.isDirectory && it.name.equals("tvshow.nfo", true) }
            if (tvshowEntry != null) {
                processShow(task.path, entries, tvshowEntry, force, onProgress, onStop)
                return emptySequence()  // 番剧文件夹不深递归(Season N 在 processShow 内处理), 防重复
            }
        }

        return entries.asSequence()
            .filter { it.isDirectory }
            .map { DirectoryTask(it.path, task.depth + 1) }
    }

    /** 处理番剧文件夹: 读 tvshow.nfo + 各 Season, upsert 入库。 */
    private suspend fun processShow(
        showPath: String, entries: List<MediaEntry>, tvshowEntry: MediaEntry,
        force: Boolean,
        onProgress: (Int, Int, Int) -> Unit,
        onStop: () -> Boolean,
    ) {
        if (onStop() || timedOut()) return
        // 屏蔽跳过(优先于增量检查; 屏蔽的番剧不重新入库, 防"删除/屏蔽"后又扫回来)
        if (runCatchingPreservingCancellation { repo.isBlocked(library.id, showPath) }.getOrDefault(false)) {
            metrics.recordSkipped()
            return
        }
        // 增量: 已记录且非 force 跳过
        if (!force) {
            val exists = runCatchingPreservingCancellation { repo.showExists(library.id, showPath) }.getOrDefault(false)
            if (exists) {
                metrics.recordSkipped()
                return
            }
        }

        val tvshowXml = withLimit { source.readTextFile(tvshowEntry.path) }
        if (tvshowXml == null) { recordError("无法读取 ${tvshowEntry.path}"); return }
        val tvshow = computeCpu { NfoParser.parseTvShowNfo(tvshowXml) }
        if (tvshow == null || tvshow.title.isBlank()) {
            recordError("tvshow.nfo 无法解析或缺少标题: ${tvshowEntry.path}")
            return
        }

        val posterPath = entries.findFile("poster.jpg")?.path
        val fanartPath = entries.findFile("fanart.jpg")?.path
        val clearlogoPath = entries.findFile("clearlogo.png")?.path

        // Season N 子目录(刮削格式 "Season 1", 大小写/空格兼容)
        val seasonDirs = entries.filter {
            it.isDirectory && it.name.matches(Regex("Season\\s*\\d+", RegexOption.IGNORE_CASE))
        }
        val seasonsData = mutableListOf<SeasonScanData>()
        for (seasonDir in seasonDirs) {
            if (onStop() || timedOut()) return
            when (val result = processSeason(seasonDir, entries, onStop)) {
                is SeasonProcessResult.Success -> seasonsData.add(result.data)
                SeasonProcessResult.Skipped -> continue
                SeasonProcessResult.Failed -> return
            }
        }

        val folderName = pathLeafName(showPath)
        runCatchingPreservingCancellation {
            repo.upsertShow(
                libraryId = library.id, sourceKind = library.sourceKind, tmdbId = tvshow.tmdbId,
                folderName = folderName, showPath = showPath,
                title = tvshow.title, originalTitle = tvshow.originalTitle,
                year = tvshow.year, plot = tvshow.plot, rating = tvshow.rating, releaseDate = tvshow.releaseDate,
                genres = tvshow.genres, studios = tvshow.studios,
                posterPath = posterPath, fanartPath = fanartPath, clearlogoPath = clearlogoPath,
                scannedAt = platformTimeMillis(), seasons = seasonsData,
            )
            metrics.recordShow(seasonsData.sumOf { it.episodes.size }).also { progress ->
                onProgress(progress.scannedDirs, progress.foundShows, progress.foundEpisodes)
            }
        }.onFailure { recordError("保存番剧 ${tvshow.title} 失败", it) }
    }

    /** 处理一季: 读 season.nfo + bangumi.ini(可空) + 剧集列表，并区分跳过与读取失败。 */
    private suspend fun processSeason(
        seasonDir: MediaEntry, showEntries: List<MediaEntry>, onStop: () -> Boolean,
    ): SeasonProcessResult {
        val seasonEntries = withLimit {
            runCatchingPreservingCancellation { source.listFolderAll(seasonDir.path) }.getOrElse {
                recordError("读取季度目录 ${seasonDir.path} 失败", it)
                return@withLimit null
            }
        }
        if (seasonEntries == null) return SeasonProcessResult.Failed
        // season.nfo(必需)
        val seasonNfoEntry = seasonEntries.firstOrNull { !it.isDirectory && it.name.equals("season.nfo", true) }
            ?: return SeasonProcessResult.Skipped
        val seasonXml = withLimit { source.readTextFile(seasonNfoEntry.path) }
        if (seasonXml == null) {
            recordError("无法读取 ${seasonNfoEntry.path}")
            return SeasonProcessResult.Failed
        }
        val seasonNfo = computeCpu { NfoParser.parseSeasonNfo(seasonXml) }
        if (seasonNfo == null) {
            recordError("season.nfo 无法解析: ${seasonNfoEntry.path}")
            return SeasonProcessResult.Failed
        }

        // bangumi.ini(可空! 文件不存在 -> bangumi=null, 不影响识别, 仅少一条 bangumi 映射)
        val bangumiEntry = seasonEntries.firstOrNull { !it.isDirectory && it.name.equals("bangumi.ini", true) }
        val bangumi = bangumiEntry?.let {
            val text = withLimit { source.readTextFile(it.path) }
            text?.let { content -> computeCpu { NfoParser.parseBangumiIni(content) } }
        }

        // season poster: seasonXX-poster.jpg 在番剧文件夹(showEntries)按 seasonNumber 匹配
        val seasonPosterName = "season${seasonNfo.seasonNumber.toString().padStart(2, '0')}-poster.jpg"
        val seasonPosterPath = showEntries.findFile(seasonPosterName)?.path

        // 剧集: .mkv + 同名 .nfo + 同名 -thumb.jpg
        val seasonIndex = computeCpu { indexSeasonEntries(seasonEntries) }
        val videoFiles = seasonIndex.videoFiles
        data class PendingEpisode(
            val video: MediaEntry,
            val parsedNfo: EpisodeNfo?,
            val thumbPath: String?,
            val candidateNumber: Int?,
        )
        val pending = mutableListOf<PendingEpisode>()
        for (video in videoFiles) {
            if (onStop()) return SeasonProcessResult.Failed
            val baseName = video.name.substringBeforeLast('.')
            val nfoEntry = seasonIndex.firstFile("$baseName.nfo")
            val thumbEntry = seasonIndex.firstFile("$baseName-thumb.jpg")
            val episodeNfo = nfoEntry?.let {
                val xml = withLimit { source.readTextFile(it.path) }
                xml?.let { content -> computeCpu { NfoParser.parseEpisodeNfo(content) } }
            }
            pending += PendingEpisode(
                video = video,
                parsedNfo = episodeNfo,
                thumbPath = thumbEntry?.path,
                candidateNumber = episodeNfo?.episode ?: EpisodeNumberExtractor.extractEpisode(video.name),
            )
        }

        val assignedNumbers = computeCpu {
            assignStableEpisodeNumbers(
                candidates = pending.map { it.candidateNumber },
                preferred = pending.map { it.parsedNfo?.episode != null },
            )
        }
        val episodes = mutableListOf<Pair<EpisodeNfo, EpisodeFile>>()
        pending.forEachIndexed { index, item ->
            val finalNumber = assignedNumbers[index]
            val finalNfo = item.parsedNfo?.copy(episode = finalNumber) ?: EpisodeNfo(
                title = null, plot = null, rating = null, year = null, aired = null,
                episode = finalNumber, season = seasonNfo.seasonNumber, runtime = null,
            )
            val mediaKey = computeMediaKey(item.video.path)
            episodes.add(finalNfo to EpisodeFile(
                videoPath = item.video.path, videoName = item.video.name,
                thumbPath = item.thumbPath, mediaKey = mediaKey, fileSize = item.video.size,
            ))
        }
        computeCpu { episodes.sortBy { it.first.episode ?: Int.MAX_VALUE } }
        return SeasonProcessResult.Success(
            SeasonScanData(seasonNfo, bangumi, seasonDir.path, seasonPosterPath, episodes),
        )
    }

    /**
     * 处理番剧文件夹(ANCHOR 模式): 锚点文件=封面, 文件夹名=番剧名, 不读 nfo/TMDB。
     * Season N 子文件夹分季(季号从文件夹名提取, 不读 season.nfo); 无 Season 文件夹时
     * 番剧文件夹直接子视频归季1(混合情况忽略直接子视频, 二期再合并)。
     * tmdb_id/元数据全 null, 复用 Show/Season/Episode 表。
     */
    private suspend fun processAnchorShow(
        showPath: String, entries: List<MediaEntry>, anchorEntry: MediaEntry,
        force: Boolean,
        onProgress: (Int, Int, Int) -> Unit,
        onStop: () -> Boolean,
    ) {
        if (onStop() || timedOut()) return
        // 屏蔽跳过(优先于增量检查; 屏蔽的番剧不重新入库, 防"删除/屏蔽"后又扫回来)
        if (runCatchingPreservingCancellation { repo.isBlocked(library.id, showPath) }.getOrDefault(false)) {
            metrics.recordSkipped()
            return
        }
        // 增量: 已记录且非 force 跳过
        if (!force) {
            val exists = runCatchingPreservingCancellation { repo.showExists(library.id, showPath) }.getOrDefault(false)
            if (exists) {
                metrics.recordSkipped()
                return
            }
        }

        val folderName = anchorFolderName(showPath)
        val posterPath = anchorEntry.path

        // Season N 子目录(季号从文件夹名提取, 不读 season.nfo)
        val seasonDirs = entries.filter {
            it.isDirectory && it.name.matches(Regex("Season\\s*\\d+", RegexOption.IGNORE_CASE))
        }
        val seasonsData = mutableListOf<SeasonScanData>()
        for (seasonDir in seasonDirs) {
            if (onStop() || timedOut()) return
            when (val result = processAnchorSeason(seasonDir, onStop)) {
                is SeasonProcessResult.Success -> seasonsData.add(result.data)
                SeasonProcessResult.Skipped -> continue
                SeasonProcessResult.Failed -> return
            }
        }
        // 无 Season 文件夹时, 番剧文件夹直接子视频归季1
        if (seasonDirs.isEmpty()) {
            val directVideos = entries.filter { !it.isDirectory && isVideoFile(it.name) }
            if (directVideos.isNotEmpty()) {
                val episodes = buildAnchorEpisodes(directVideos, 1, onStop) ?: return
                seasonsData.add(SeasonScanData(
                    nfo = SeasonNfo(seasonNumber = 1, title = null, year = null, releaseDate = null),
                    bangumi = null, seasonPath = showPath, seasonPosterPath = null, episodes = episodes,
                ))
            }
        }

        runCatchingPreservingCancellation {
            repo.upsertShow(
                libraryId = library.id, sourceKind = library.sourceKind, tmdbId = null,
                folderName = folderName, showPath = showPath,
                title = folderName, originalTitle = null,
                year = null, plot = null, rating = null, releaseDate = null,
                genres = emptyList(), studios = emptyList(),
                posterPath = posterPath, fanartPath = null, clearlogoPath = null,
                scannedAt = platformTimeMillis(), seasons = seasonsData,
            )
            metrics.recordShow(seasonsData.sumOf { it.episodes.size }).also { progress ->
                onProgress(progress.scannedDirs, progress.foundShows, progress.foundEpisodes)
            }
        }.onFailure { recordError("保存番剧 $folderName 失败", it) }
    }

    /** 处理一季(ANCHOR 模式): 不读 season.nfo, 季号从 "Season N" 文件夹名提取, 集号从文件名提取。 */
    private suspend fun processAnchorSeason(
        seasonDir: MediaEntry, onStop: () -> Boolean,
    ): SeasonProcessResult {
        val seasonEntries = withLimit {
            runCatchingPreservingCancellation { source.listFolderAll(seasonDir.path) }.getOrElse {
                recordError("读取季度目录 ${seasonDir.path} 失败", it)
                return@withLimit null
            }
        }
        if (seasonEntries == null) return SeasonProcessResult.Failed
        val seasonNumber = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(seasonDir.name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val videos = seasonEntries.filter { !it.isDirectory && isVideoFile(it.name) }
        val episodes = buildAnchorEpisodes(videos, seasonNumber, onStop)
            ?: return SeasonProcessResult.Failed
        return SeasonProcessResult.Success(
            SeasonScanData(
                nfo = SeasonNfo(seasonNumber = seasonNumber, title = null, year = null, releaseDate = null),
                bangumi = null,
                seasonPath = seasonDir.path,
                seasonPosterPath = null,
                episodes = episodes,
            ),
        )
    }

    /**
     * ANCHOR 模式集列表: 集号 EpisodeNumberExtractor 提取, 缺则顺序号兜底(index+1);
     * mediaKey 复用 computeMediaKey(与播放记录同公式, 进度联动)。无 nfo/thumb。
     * @return 集列表(按集号排序) 或 null(onStop 中断)
     */
    private fun buildAnchorEpisodes(
        videos: List<MediaEntry>, seasonNumber: Int, onStop: () -> Boolean,
    ): List<Pair<EpisodeNfo, EpisodeFile>>? {
        val episodes = mutableListOf<Pair<EpisodeNfo, EpisodeFile>>()
        val sortedVideos = videos.sortedBy { it.name.lowercase() }
        val assignedNumbers = assignStableEpisodeNumbers(
            sortedVideos.map { EpisodeNumberExtractor.extractEpisode(it.name) },
        )
        sortedVideos.forEachIndexed { index, video ->
            if (onStop()) return null
            val epNum = assignedNumbers[index]
            val mediaKey = computeMediaKey(video.path)
            val epNfo = EpisodeNfo(
                title = null, plot = null, rating = null, year = null, aired = null,
                episode = epNum, season = seasonNumber, runtime = null,
            )
            val epFile = EpisodeFile(
                videoPath = video.path, videoName = video.name,
                thumbPath = null, mediaKey = mediaKey, fileSize = video.size,
            )
            episodes.add(epNfo to epFile)
        }
        episodes.sortBy { it.first.episode ?: Int.MAX_VALUE }
        return episodes
    }

    /** ANCHOR 模式锚点匹配: 候选文件名(大小写不敏感)任一存在即命中, 返回首个。空候选返 null。 */
    private fun List<MediaEntry>.findAnchor(candidates: List<String>): MediaEntry? {
        if (candidates.isEmpty()) return null
        val lower = candidates.mapNotNull { it.trim().lowercase().takeIf { s -> s.isNotEmpty() } }.toSet()
        if (lower.isEmpty()) return null
        return firstOrNull { !it.isDirectory && it.name.lowercase() in lower }
    }

    /**
     * 番剧文件夹名(ANCHOR): 取 showPath 末段。LOCAL 为 SAF content URI, 末段是 URL 编码的
     * document id(含 %2F 编码的 / 与 %XX 编码的中文), 需解码后再取末段才是真实文件夹名;
     * WebDAV 路径末段已是原文(中文 UTF-8)直接返回。非法 %XX 解码失败兜底返回原末段。
     */
    private fun anchorFolderName(showPath: String): String {
        val raw = showPath.trimEnd('/', '\\').let { path ->
            path.substring(maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)
        }
        if (raw.isBlank()) return showPath
        // showPath 末段: LOCAL=SAF content URI document id, WebDAV=PROPFIND href, 均百分号编码
        // (%XX 中文 / %2F 编码的 /)。common 解码器保留字面量 +，避免名称被误改为空格；
        // LOCAL document id 含编码的 /(%2F) 解码后需再取末段。
        return decodeUrlComponentPreservingPlus(raw)
            .trimEnd('/', '\\')
            .let { decoded -> decoded.substring(maxOf(decoded.lastIndexOf('/'), decoded.lastIndexOf('\\')) + 1) }
            .ifBlank { raw }
    }

    private fun pathLeafName(path: String): String = anchorFolderName(path).ifBlank { path }

    /** media_key: 与播放器写 PlaybackRecord 同公式, 保证进度联动。 */
    private fun computeMediaKey(videoPath: String): String? = MediaIdentityResolver.mediaKey(
        sourceKind = library.sourceKind,
        connectionId = library.connectionId,
        path = videoPath,
    )

    /** 限流+并发控制: Semaphore 限同时操作数, delay 限 QPS。 */
    private suspend fun <T> withLimit(block: suspend () -> T): T = semaphore.withPermit {
        if (source.kind != MediaSourceKind.LOCAL && config.requestIntervalMs > 0) {
            delay(config.requestIntervalMs.toLong())
        }
        block()
    }

    /** suspend 失败可降级为 Result，但协程取消必须继续向上游传播。 */
    private suspend fun <T> runCatchingPreservingCancellation(block: suspend () -> T): Result<T> =
        runSuspendCatching(block)

    /** XML/INI 解析、文件名索引和排序统一离开 Main/EDT，取消在返回前继续传播。 */
    private suspend fun <T> computeCpu(block: () -> T): T = withContext(cpuDispatcher) { block() }

    /** 记录错误计数，并保留第一条可展示原因；换行折叠后限制长度，避免状态栏被异常文本撑开。 */
    private suspend fun recordError(context: String, error: Throwable? = null) {
        val detail = error?.message
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val message = buildString {
            append(context.replace(Regex("\\s+"), " ").trim())
            if (detail != null) append(": ").append(detail)
        }.take(240)
        metrics.recordError(message)
    }

    private fun List<MediaEntry>.findFile(name: String): MediaEntry? =
        firstOrNull { !it.isDirectory && it.name.equals(name, true) }

    private suspend fun makeResult(stopped: Boolean): ScanResult {
        val snapshot = metrics.snapshot()
        return ScanResult(
            scannedDirs = snapshot.scannedDirs,
            foundShows = snapshot.foundShows,
            foundEpisodes = snapshot.foundEpisodes,
            skippedShows = snapshot.skippedShows,
            errors = snapshot.errors,
            timedOut = timedOut(),
            stopped = stopped,
            firstErrorMessage = snapshot.firstErrorMessage,
            peakQueuedDirs = snapshot.peakQueuedDirs,
            visitedDirs = snapshot.visitedDirs,
            directoryLimitReached = snapshot.directoryLimitReached,
        )
    }
}

private const val MAX_DIRECTORY_TRAVERSAL_DEPTH = 256

internal data class SeasonEntryIndex(
    val videoFiles: List<MediaEntry>,
    private val firstFileByLowerName: Map<String, MediaEntry>,
) {
    fun firstFile(name: String): MediaEntry? = firstFileByLowerName[name.lowercase()]
}

/** 一次构建季度文件名索引；重复文件名保留目录返回顺序中的第一项。 */
internal fun indexSeasonEntries(entries: List<MediaEntry>): SeasonEntryIndex {
    val firstFileByLowerName = LinkedHashMap<String, MediaEntry>()
    val videos = mutableListOf<Pair<String, MediaEntry>>()
    entries.forEach { entry ->
        if (!entry.isDirectory) {
            val lowerName = entry.name.lowercase()
            firstFileByLowerName.putIfAbsent(lowerName, entry)
            if (isVideoFile(entry.name)) videos += lowerName to entry
        }
    }
    videos.sortBy { it.first }
    return SeasonEntryIndex(
        videoFiles = videos.map { it.second },
        firstFileByLowerName = firstFileByLowerName,
    )
}

/**
 * 保留第一次出现的明确集号；重复或缺失集号从所有明确集号之后分配稳定唯一值，
 * 避免 UNIQUE(season_id, episode_number) 让整部番剧事务回滚。
 */
internal fun assignStableEpisodeNumbers(
    candidates: List<Int?>,
    preferred: List<Boolean> = List(candidates.size) { false },
): List<Int> {
    require(preferred.size == candidates.size)
    val reserved = candidates.filterNotNull().toSet()
    val used = mutableSetOf<Int>()
    val assigned = arrayOfNulls<Int>(candidates.size)
    candidates.indices.forEach { index ->
        val candidate = candidates[index]
        if (preferred[index] && candidate != null && used.add(candidate)) assigned[index] = candidate
    }
    candidates.indices.forEach { index ->
        val candidate = candidates[index]
        if (assigned[index] == null && candidate != null && used.add(candidate)) assigned[index] = candidate
    }
    var fallback = ((reserved.maxOrNull() ?: 0).coerceAtLeast(0)) + 1
    return assigned.map { existing ->
        existing ?: run {
            while (fallback in reserved || fallback in used) fallback++
            fallback.also {
                used += it
                fallback++
            }
        }
    }
}
