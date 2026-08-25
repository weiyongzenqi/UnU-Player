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
    /** 目录读取保持并发；整部番剧事务写入与在线 meta 重放串行，避免 Desktop SQLite 并发写锁冲突。 */
    private val repositoryWriteMutex = Mutex()

    private data class DirectoryTask(
        val path: String,
        val depth: Int,
        val inheritedSeasonNumber: Int? = null,
        val prefetchedEntries: List<MediaEntry>? = null,
    )

    private data class SeasonDirectoryProbe(
        val entry: MediaEntry,
        val marker: SeasonDirectoryMarker,
        val entries: List<MediaEntry>?,
    ) {
        val directVideos: List<MediaEntry>
            get() = entries.orEmpty().filter { !it.isDirectory && isVideoFile(it.name) }
        val hasSeasonNfo: Boolean
            get() = entries.orEmpty().any { !it.isDirectory && it.name.equals("season.nfo", true) }
        val hasChildDirectories: Boolean
            get() = entries.orEmpty().any { it.isDirectory }
        val representsShowSeason: Boolean
            get() = entries != null && (directVideos.isNotEmpty() || hasSeasonNfo || entries.isEmpty())
        val representsWrapper: Boolean
            get() = entries != null && !representsShowSeason && hasChildDirectories
    }

    /** 区分可安全接纳的季度与读取/识别失败；失败季度必须保留数据库旧值。 */
    private sealed interface SeasonProcessResult {
        data class Success(val data: SeasonScanData) : SeasonProcessResult
        data object Skipped : SeasonProcessResult
        data object Failed : SeasonProcessResult
    }

    /** 从 library.rootPath 递归；普通扫描在目录读取前剪枝已记录番剧，force=true 强制刷新全部。 */
    suspend fun scan(
        force: Boolean = false,
        onProgress: (scanned: Int, foundShows: Int, foundEpisodes: Int) -> Unit = { _, _, _ -> },
        onStopRequested: () -> Boolean = { false },
    ): ScanResult {
        try {
            val knownShowPaths = if (force) {
                emptySet()
            } else {
                runCatchingPreservingCancellation { repo.listShowPaths(library.id).toSet() }
                    .getOrDefault(emptySet())
            }
            traverseDirectories(
                initialTasks = sequenceOf(
                    DirectoryTask(
                        path = library.rootPath,
                        depth = 0,
                        inheritedSeasonNumber = seasonNumberHint(pathLeafName(library.rootPath)),
                    ),
                ),
                force = force,
                knownShowPaths = knownShowPaths,
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
        val inheritedSeasonNumber = seasonNumberHint(pathLeafName(dirPath))
        // 未记录的子目录才扫(已记录的跳过, 增量)
        try {
            traverseDirectories(
                initialTasks = entries.asSequence()
                    .filter { it.isDirectory && it.path !in existingPaths }
                    .map { entry ->
                        DirectoryTask(
                            path = entry.path,
                            depth = 1,
                            inheritedSeasonNumber = parseSeasonDirectoryMarker(entry.name)?.inheritedSeasonNumber
                                ?: inheritedSeasonNumber,
                        )
                    },
                force = false,
                knownShowPaths = existingPaths,
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
        reapplyOnlineMeta: Boolean = true,
    ): ScanResult = withContext(cpuDispatcher) {
        scanOneShowInBackground(showPath, onProgress, onStopRequested, reapplyOnlineMeta)
    }

    private suspend fun scanOneShowInBackground(
        showPath: String,
        onProgress: (scanned: Int, foundShows: Int, foundEpisodes: Int) -> Unit,
        onStopRequested: () -> Boolean,
        reapplyOnlineMeta: Boolean,
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
            val directVideos = entries.filter { !it.isDirectory && isVideoFile(it.name) }
            val seasonProbes = probeSeasonDirectories(entries)
            val likelySeasonProbes = seasonProbes.filter {
                it.representsShowSeason &&
                    isLikelySeasonOfShow(normalizeSeasonTitleHint(pathLeafName(showPath)), it.marker)
            }
            val selectedSeasonProbes = when {
                anchorEntry != null || directVideos.isNotEmpty() -> seasonProbes.filter { it.representsShowSeason }
                else -> likelySeasonProbes
            }
            if (seasonProbes.any { it.entries == null }) {
                return makeResult(onStopRequested())
            }
            if (anchorEntry != null || directVideos.isNotEmpty() || selectedSeasonProbes.isNotEmpty()) {
                val directSeasonNumber = if (directVideos.isNotEmpty() && selectedSeasonProbes.isEmpty()) {
                    resolveDirectSeasonNumber(showPath) ?: return makeResult(onStopRequested())
                } else {
                    seasonNumberHint(pathLeafName(showPath)) ?: 1
                }
                processAnchorShow(
                    showPath = showPath,
                    entries = entries,
                    anchorEntry = anchorEntry,
                    seasonProbes = selectedSeasonProbes,
                    directSeasonNumber = directSeasonNumber,
                    force = true,
                    onProgress = onProgress,
                    onStop = onStopRequested,
                    reapplyOnlineMeta = reapplyOnlineMeta,
                )
            } else {
                recordError("番剧目录缺少封面锚点、可识别季目录或直接视频")
            }
        } else {
            val tvshowEntry = entries.firstOrNull { !it.isDirectory && it.name.equals("tvshow.nfo", true) }
            if (tvshowEntry != null) {
                processShow(
                    showPath, entries, tvshowEntry, force = true, onProgress, onStopRequested, reapplyOnlineMeta,
                )
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
        knownShowPaths: Set<String>,
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
                    inspectDirectory(task, force, knownShowPaths, onProgress, onStop).forEach { child ->
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
        knownShowPaths: Set<String>,
        onProgress: (Int, Int, Int) -> Unit,
        onStop: () -> Boolean,
    ): Sequence<DirectoryTask> {
        if (onStop() || timedOut()) return emptySequence()
        if (!force && task.path in knownShowPaths) {
            metrics.recordSkipped()
            return emptySequence()
        }

        val entries = task.prefetchedEntries ?: withLimit {
            runCatchingPreservingCancellation { source.listFolderAll(task.path) }.getOrElse {
                recordError("读取目录 ${task.path} 失败", it)
                return@withLimit emptyList()
            }
        }
        if (entries.isEmpty()) return emptySequence()
        metrics.recordScanned().also { progress ->
            onProgress(progress.scannedDirs, progress.foundShows, progress.foundEpisodes)
        }

        // ANCHOR 先按“锚点/直接视频/季目录内容”判定目录角色，避免把“第N季/番剧名/视频”外层误作番剧。
        if (library.scanMode == ScanMode.ANCHOR) {
            val anchorEntry = entries.findAnchor(library.anchorFilenames)
            val directVideos = entries.filter { !it.isDirectory && isVideoFile(it.name) }
            val entriesToProbe = if (force || knownShowPaths.isEmpty()) {
                entries
            } else {
                entries.filterNot { it.isDirectory && it.path in knownShowPaths }
            }
            val seasonProbes = probeSeasonDirectories(entriesToProbe)
            val likelySeasonProbes = seasonProbes.filter {
                it.representsShowSeason &&
                    isLikelySeasonOfShow(normalizeSeasonTitleHint(pathLeafName(task.path)), it.marker)
            }
            val selectedSeasonProbes = when {
                anchorEntry != null || directVideos.isNotEmpty() -> seasonProbes.filter { it.representsShowSeason }
                else -> likelySeasonProbes
            }
            val isDirectLeafShow = directVideos.isNotEmpty() && task.depth > 0
            val isShow = anchorEntry != null || isDirectLeafShow || selectedSeasonProbes.isNotEmpty()
            if (isShow) {
                // 任一候选季读取失败时保持旧番剧数据，避免强制重扫用残缺季度覆盖。
                if (seasonProbes.any { it.entries == null }) return emptySequence()
                val directSeasonNumber = task.inheritedSeasonNumber
                    ?: seasonNumberHint(pathLeafName(task.path))
                    ?: 1
                processAnchorShow(
                    showPath = task.path,
                    entries = entries,
                    anchorEntry = anchorEntry,
                    seasonProbes = selectedSeasonProbes,
                    directSeasonNumber = directSeasonNumber,
                    force = force,
                    onProgress = onProgress,
                    onStop = onStop,
                    reapplyOnlineMeta = true,
                )
                return emptySequence()
            }

            val parsedDirectoryPaths = seasonProbes.mapTo(mutableSetOf()) { it.entry.path }
            val inheritedTasks = seasonProbes.asSequence()
                .filter { it.entries != null && (it.representsWrapper || it.representsShowSeason) }
                .map { probe ->
                    DirectoryTask(
                        path = probe.entry.path,
                        depth = task.depth + 1,
                        inheritedSeasonNumber = probe.marker.inheritedSeasonNumber ?: task.inheritedSeasonNumber,
                        prefetchedEntries = probe.entries,
                    )
                }
            val ordinaryTasks = entries.asSequence()
                .filter { it.isDirectory && it.path !in parsedDirectoryPaths }
                .map { DirectoryTask(it.path, task.depth + 1, task.inheritedSeasonNumber) }
            return inheritedTasks + ordinaryTasks
        } else {
            val tvshowEntry = entries.firstOrNull { !it.isDirectory && it.name.equals("tvshow.nfo", true) }
            if (tvshowEntry != null) {
                processShow(task.path, entries, tvshowEntry, force, onProgress, onStop, reapplyOnlineMeta = true)
                return emptySequence()  // 番剧文件夹不深递归(Season N 在 processShow 内处理), 防重复
            }
        }

        return entries.asSequence()
            .filter { it.isDirectory }
            .map { DirectoryTask(it.path, task.depth + 1, task.inheritedSeasonNumber) }
    }

    /** 处理番剧文件夹: 读 tvshow.nfo + 各 Season, upsert 入库。 */
    private suspend fun processShow(
        showPath: String, entries: List<MediaEntry>, tvshowEntry: MediaEntry,
        force: Boolean,
        onProgress: (Int, Int, Int) -> Unit,
        onStop: () -> Boolean,
        reapplyOnlineMeta: Boolean,
    ) {
        if (onStop() || timedOut()) return
        // 屏蔽跳过(优先于增量检查; 屏蔽的番剧不重新入库, 防"删除/屏蔽"后又扫回来)
        if (runCatchingPreservingCancellation { repo.isBlocked(library.id, showPath) }.getOrDefault(false)) {
            metrics.recordSkipped()
            return
        }
        // force 重扫也要知道旧记录是否存在：部分季失败时只更新成功季，不能删掉失败季旧数据。
        val showAlreadyExists = runCatchingPreservingCancellation {
            repo.showExists(library.id, showPath)
        }.getOrDefault(false)
        // 增量: 已记录且非 force 跳过
        if (!force && showAlreadyExists) {
            metrics.recordSkipped()
            return
        }

        val tvshowXml = withLimit { source.readTextFile(tvshowEntry.path) }
        if (tvshowXml == null) { recordError("无法读取 ${tvshowEntry.path}"); return }
        val tvshow = computeCpu { NfoParser.parseTvShowNfo(tvshowXml) }
        if (tvshow == null || tvshow.title.isBlank()) {
            recordError("tvshow.nfo 无法解析或缺少标题: ${tvshowEntry.path}")
            return
        }

        val parentTitleHints = listOfNotNull(
            tvshow.title,
            tvshow.originalTitle,
            pathLeafName(showPath),
        ).map(::normalizeSeasonTitleHint).filter(String::isNotBlank).distinct()

        val posterPath = entries.findFile("poster.jpg")?.path
        val fanartPath = entries.findFile("fanart.jpg")?.path
        val clearlogoPath = entries.findFile("clearlogo.png")?.path

        // 季子目录允许显式季标记前后带发布组、番剧名、清晰度等附加文本；季号仍以 season.nfo 为准。
        val seasonDirs = entries.filter {
            it.isDirectory && isSeasonDir(it.name)
        }
        val seasonsData = mutableListOf<SeasonScanData>()
        val seenSeasonNumbers = mutableSetOf<Int>()
        var hadSeasonFailure = false
        for (seasonDir in seasonDirs) {
            if (onStop() || timedOut()) return
            when (val result = processSeason(seasonDir, entries, onStop, parentTitleHints)) {
                is SeasonProcessResult.Success -> {
                    val seasonNumber = result.data.nfo.seasonNumber
                    if (!seenSeasonNumbers.add(seasonNumber)) {
                        // D-P2-7: "Season 1"+"Season 01" 同号重复目录, UNIQUE(show_id, season_number)
                        // 冲突会让 upsertShow 的 transactionWithResult 整部回滚失败。
                        // 按季号去重, 保留第一个目录, 重复目录跳过并记录。
                        recordError("跳过重复季目录 ${seasonDir.path}: 季号 $seasonNumber 已由其他目录提供")
                    } else {
                        seasonsData.add(result.data)
                    }
                }
                SeasonProcessResult.Skipped -> continue
                SeasonProcessResult.Failed -> {
                    hadSeasonFailure = true
                    continue
                }
            }
        }

        if (hadSeasonFailure && !showAlreadyExists) {
            // 全新番剧若先写入残缺外壳，后续普通增量扫描会因 showExists 永久跳过，失败季无法自动修复。
            // 保持未入库，让下一轮扫描自然重试；已有番剧则在下方做按季合并并保留失败季旧数据。
            return
        }

        val folderName = pathLeafName(showPath)
        repositoryWriteMutex.withLock {
            runCatchingPreservingCancellation {
                repo.upsertShow(
                    libraryId = library.id, sourceKind = library.sourceKind, tmdbId = tvshow.tmdbId,
                    folderName = folderName, showPath = showPath,
                    title = tvshow.title, originalTitle = tvshow.originalTitle,
                    year = tvshow.year, plot = tvshow.plot, rating = tvshow.rating, releaseDate = tvshow.releaseDate,
                    genres = tvshow.genres, studios = tvshow.studios,
                    posterPath = posterPath, fanartPath = fanartPath, clearlogoPath = clearlogoPath,
                    scannedAt = platformTimeMillis(), seasons = seasonsData,
                    replaceAllSeasons = !hadSeasonFailure,
                )
                metrics.recordShow(seasonsData.sumOf { it.episodes.size }).also { progress ->
                    onProgress(progress.scannedDirs, progress.foundShows, progress.foundEpisodes)
                }
            }.onFailure { recordError("保存番剧 ${tvshow.title} 失败", it) }
            // 扫描 upsertShow 删季重插后，重放在线文本/身份；在线图片仍留在 meta 由 UI 回退。
            // 失败不阻断扫描: meta 是持久 source of truth, 下次扫描/详情页重放兜底。
            if (reapplyOnlineMeta) {
                runSuspendCatching { repo.reapplyOnlineMeta(library.id, showPath) }
            }
        }
    }

    /**
     * 处理一季：优先读 season.nfo；缺失时仅在“显式季目录 + 视频 Sxx 一致”时合成最小季度。
     * bangumi.ini 与单集 NFO 均可空。任何歧义都按失败处理，以便已有番剧保留旧季度、全新番剧保持可重试。
     */
    private suspend fun processSeason(
        seasonDir: MediaEntry,
        showEntries: List<MediaEntry>,
        onStop: () -> Boolean,
        parentTitleHints: List<String>,
    ): SeasonProcessResult {
        val seasonEntries = withLimit {
            runCatchingPreservingCancellation { source.listFolderAll(seasonDir.path) }.getOrElse {
                recordError("读取季度目录 ${seasonDir.path} 失败", it)
                return@withLimit null
            }
        }
        if (seasonEntries == null) return SeasonProcessResult.Failed
        val seasonIndex = computeCpu { indexSeasonEntries(seasonEntries) }
        val videoFiles = seasonIndex.videoFiles

        // season.nfo 优先；Ani-RSS 在 TMDB 合并季不存在时可能只留下显式季目录、视频与 bangumi.ini。
        val seasonNfoEntry = seasonEntries.firstOrNull { !it.isDirectory && it.name.equals("season.nfo", true) }
        val seasonNfo = if (seasonNfoEntry != null) {
            val seasonXml = withLimit { source.readTextFile(seasonNfoEntry.path) }
            if (seasonXml == null) {
                recordError("无法读取 ${seasonNfoEntry.path}")
                return SeasonProcessResult.Failed
            }
            computeCpu { NfoParser.parseSeasonNfo(seasonXml) } ?: run {
                recordError("season.nfo 无法解析: ${seasonNfoEntry.path}")
                return SeasonProcessResult.Failed
            }
        } else {
            val canInferSeason = canInferSeasonNfoForShow(parentTitleHints, seasonDir.name)
            computeCpu {
                if (canInferSeason) inferSeasonNfoFromDirectoryAndVideos(seasonDir.name, videoFiles) else null
            } ?: run {
                recordError("缺少 season.nfo，且季目录或视频季号无法唯一确认: ${seasonDir.path}")
                return SeasonProcessResult.Failed
            }
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
     * 处理番剧文件夹(ANCHOR 模式): 锚点文件=封面(可空, 仅 Season 子目录命中时无封面), 文件夹名=番剧名,
     * 不读 nfo/TMDB。
     * 显式季标记子文件夹分季(季号从文件夹名提取, 命名变体见 [parseSeasonDirectoryMarker], 不读 season.nfo);
     * 直接子视频归 [directSeasonNumber]，用于普通叶子番剧和“第N季/番剧名/视频”包装结构。
     * tmdb_id/元数据全 null, 复用 Show/Season/Episode 表; 在线刮削补全见 AnimeScraper。
     */
    private suspend fun processAnchorShow(
        showPath: String, entries: List<MediaEntry>, anchorEntry: MediaEntry?,
        seasonProbes: List<SeasonDirectoryProbe>,
        directSeasonNumber: Int,
        force: Boolean,
        onProgress: (Int, Int, Int) -> Unit,
        onStop: () -> Boolean,
        reapplyOnlineMeta: Boolean,
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
        // 无封面锚点文件、仅 Season 子目录命中时 poster 为空(海报墙先走占位, 在线刮削补季照)
        val posterPath = anchorEntry?.path

        val seasonsData = mutableListOf<SeasonScanData>()
        val seenSeasonNumbers = mutableSetOf<Int>()
        for (probe in seasonProbes) {
            if (onStop() || timedOut()) return
            val seasonEntries = probe.entries ?: return
            when (
                val result = processAnchorSeason(
                    seasonDir = probe.entry,
                    seasonNumber = probe.marker.seasonNumber,
                    seasonEntries = seasonEntries,
                    onStop = onStop,
                )
            ) {
                is SeasonProcessResult.Success -> {
                    if (seenSeasonNumbers.add(result.data.nfo.seasonNumber)) {
                        seasonsData.add(result.data)
                    } else {
                        recordError(
                            "跳过重复季目录 ${probe.entry.path}: 季号 ${result.data.nfo.seasonNumber} 已由其他目录提供",
                        )
                    }
                }
                SeasonProcessResult.Skipped -> continue
                SeasonProcessResult.Failed -> return
            }
        }
        val directVideos = entries.filter { !it.isDirectory && isVideoFile(it.name) }
        if (directVideos.isNotEmpty() && seenSeasonNumbers.add(directSeasonNumber)) {
            val episodes = buildAnchorEpisodes(directVideos, directSeasonNumber, onStop) ?: return
            seasonsData.add(
                SeasonScanData(
                    nfo = SeasonNfo(
                        seasonNumber = directSeasonNumber,
                        title = null,
                        year = null,
                        releaseDate = null,
                    ),
                    bangumi = null, seasonPath = showPath, seasonPosterPath = null, episodes = episodes,
                ),
            )
        }

        repositoryWriteMutex.withLock {
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
            // 扫描 upsertShow 删季重插后，重放在线文本/身份；在线图片仍留在 meta 由 UI 回退。
            // 失败不阻断扫描: meta 是持久 source of truth, 下次扫描/详情页重放兜底。
            if (reapplyOnlineMeta) {
                runSuspendCatching { repo.reapplyOnlineMeta(library.id, showPath) }
            }
        }
    }

    /** 处理一季(ANCHOR 模式): 季号由统一目录解析器提供, 集号从文件名提取。 */
    private fun processAnchorSeason(
        seasonDir: MediaEntry,
        seasonNumber: Int,
        seasonEntries: List<MediaEntry>,
        onStop: () -> Boolean,
    ): SeasonProcessResult {
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

    /** 只预读带显式季标记的子目录；结果同时用于目录角色判定和季度解析，避免远程目录重复读取。 */
    private suspend fun probeSeasonDirectories(entries: List<MediaEntry>): List<SeasonDirectoryProbe> {
        val probes = mutableListOf<SeasonDirectoryProbe>()
        for (entry in entries) {
            if (!entry.isDirectory) continue
            val marker = parseSeasonDirectoryMarker(entry.name) ?: continue
            val seasonEntries = withLimit {
                runCatchingPreservingCancellation { source.listFolderAll(entry.path) }.getOrElse {
                    recordError("读取季度候选目录 ${entry.path} 失败", it)
                    return@withLimit null
                }
            }
            probes += SeasonDirectoryProbe(entry, marker, seasonEntries)
        }
        return probes
    }

    /** 单番剧刷新时恢复叶子视频目录的季号，避免“第2季/番剧名”刷新后回落成第1季。 */
    private suspend fun resolveDirectSeasonNumber(showPath: String): Int? {
        parseSeasonDirectoryMarker(pathLeafName(showPath))?.let { return it.inheritedSeasonNumber ?: 1 }
        pathParentLeafName(showPath)?.let { parentName ->
            parseSeasonDirectoryMarker(parentName)?.let { return it.inheritedSeasonNumber ?: 1 }
        }

        val show = runCatchingPreservingCancellation { repo.getShowByPath(library.id, showPath) }
            .getOrElse {
                recordError("读取番剧现有季号失败", it)
                return null
            }
            ?: return 1
        val seasons = runCatchingPreservingCancellation { repo.listSeasons(show.id) }
            .getOrElse {
                recordError("读取番剧现有季度失败", it)
                return null
            }
        return seasons.singleOrNull()?.season_number?.toInt() ?: 1
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

    private fun pathParentLeafName(path: String): String? {
        val decoded = decodeUrlComponentPreservingPlus(path).trimEnd('/', '\\')
        val lastSeparator = maxOf(decoded.lastIndexOf('/'), decoded.lastIndexOf('\\'))
        if (lastSeparator <= 0) return null
        val parent = decoded.substring(0, lastSeparator).trimEnd('/', '\\')
        val parentSeparator = maxOf(parent.lastIndexOf('/'), parent.lastIndexOf('\\'))
        return parent.substring(parentSeparator + 1).takeIf { it.isNotBlank() }
    }

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

internal data class SeasonDirectoryMarker(
    val seasonNumber: Int,
    val inheritedSeasonNumber: Int?,
    val titleHint: String,
)

private enum class SeasonMarkerKind { SEASON, QUARTER }

private data class SeasonMarkerCandidate(
    val seasonNumber: Int,
    val kind: SeasonMarkerKind,
    val markerRange: IntRange,
)

private val CHINESE_ARABIC_SEASON_REGEX = Regex("(第\\s*(\\d{1,3})\\s*(季度|季))")
private val CHINESE_NUMERAL_SEASON_REGEX = Regex(
    "(第\\s*([零〇一二两三四五六七八九十百]+)\\s*(季度|季))",
)
/** 去掉季标记后的纯数字/中文数字前缀("1.第一季" -> "1"; "一.第一季" -> "一")。 */
private val NUMERIC_SEASON_TITLE_HINT_REGEX = Regex("(\\d{1,3}|[零〇一二两三四五六七八九十]{1,3})")
private val ENGLISH_SEASON_REGEX = Regex(
    "(^|[^A-Za-z])(season[\\s._-]*(\\d{1,3}))(?![A-Za-z0-9])",
    RegexOption.IGNORE_CASE,
)
private val SHORT_SEASON_REGEX = Regex(
    "(^|[^A-Za-z0-9])(s[\\s._-]*(\\d{1,3}))(?![A-Za-z0-9])",
    RegexOption.IGNORE_CASE,
)
private val CALENDAR_YEAR_REGEX = Regex("(?:19|20)\\d{2}\\s*年?")
private val BRACKET_DECORATION_REGEX = Regex("\\[[^]]*]|【[^】]*】|\\([^)]*\\)|（[^）]*）")
private val QUALITY_DECORATION_REGEX = Regex(
    "(?i)\\b(?:bd|bdrip|bluray|web-?dl|webrip|1080p|2160p|4k|hevc|x26[45]|chs|cht|complete)\\b",
)
private val SEASON_DECORATION_WORDS = listOf(
    "完结", "全集", "合集", "正片", "蓝光", "修复版", "简中", "繁中", "字幕版", "新番",
)

/**
 * 从目录名任意位置提取显式季标记。支持 `Season 02`、`S02`、`第2季/季度` 和中文数字；
 * 同一名称出现冲突季号时拒绝识别。带年份或“新番”的“季度”视为自然季度分组，不向下继承季号。
 */
internal fun parseSeasonDirectoryMarker(dirName: String): SeasonDirectoryMarker? {
    val candidates = mutableListOf<SeasonMarkerCandidate>()

    CHINESE_ARABIC_SEASON_REGEX.findAll(dirName).forEach { match ->
        val number = match.groupValues[2].toIntOrNull() ?: return@forEach
        if (number !in 0..999) return@forEach
        candidates += SeasonMarkerCandidate(
            seasonNumber = number,
            kind = if (match.groupValues[3] == "季度") SeasonMarkerKind.QUARTER else SeasonMarkerKind.SEASON,
            markerRange = match.groups[1]?.range ?: return@forEach,
        )
    }
    CHINESE_NUMERAL_SEASON_REGEX.findAll(dirName).forEach { match ->
        val number = parseChineseSeasonNumber(match.groupValues[2]) ?: return@forEach
        if (number !in 0..999) return@forEach
        candidates += SeasonMarkerCandidate(
            seasonNumber = number,
            kind = if (match.groupValues[3] == "季度") SeasonMarkerKind.QUARTER else SeasonMarkerKind.SEASON,
            markerRange = match.groups[1]?.range ?: return@forEach,
        )
    }
    ENGLISH_SEASON_REGEX.findAll(dirName).forEach { match ->
        val number = match.groupValues[3].toIntOrNull() ?: return@forEach
        candidates += SeasonMarkerCandidate(
            seasonNumber = number,
            kind = SeasonMarkerKind.SEASON,
            markerRange = match.groups[2]?.range ?: return@forEach,
        )
    }
    SHORT_SEASON_REGEX.findAll(dirName).forEach { match ->
        val number = match.groupValues[3].toIntOrNull() ?: return@forEach
        candidates += SeasonMarkerCandidate(
            seasonNumber = number,
            kind = SeasonMarkerKind.SEASON,
            markerRange = match.groups[2]?.range ?: return@forEach,
        )
    }

    val seasonNumbers = candidates.map { it.seasonNumber }.distinct()
    if (seasonNumbers.size != 1) return null
    val seasonNumber = seasonNumbers.single()
    var titleRemainder = dirName
    candidates.map { it.markerRange }.distinct().sortedByDescending { it.first }.forEach { range ->
        titleRemainder = titleRemainder.removeRange(range)
    }
    val isCalendarGrouping = candidates.any { it.kind == SeasonMarkerKind.QUARTER } &&
        (CALENDAR_YEAR_REGEX.containsMatchIn(dirName) || dirName.contains("新番", ignoreCase = true))
    return SeasonDirectoryMarker(
        seasonNumber = seasonNumber,
        inheritedSeasonNumber = seasonNumber.takeUnless { isCalendarGrouping },
        titleHint = normalizeSeasonTitleHint(titleRemainder),
    )
}

/** 季子目录判定；显式季标记可位于目录名前、中、后部。 */
internal fun isSeasonDir(dirName: String): Boolean = parseSeasonDirectoryMarker(dirName) != null

/** 从季目录名提取季号；自然季度分组仍返回数字，是否向下继承由 marker 单独表达。 */
internal fun extractSeasonNumber(dirName: String): Int? = parseSeasonDirectoryMarker(dirName)?.seasonNumber

private fun seasonNumberHint(dirName: String): Int? =
    parseSeasonDirectoryMarker(dirName)?.inheritedSeasonNumber

/**
 * 带番剧名的"某番 第2季"既可能是当前番剧的季目录，也可能本身就是叶子番剧目录。
 * 去掉季标记和发布装饰后无标题，或剩余标题与父目录一致时，才把它归到当前番剧。
 * 纯数字/中文数字前缀("1.第一季"/"10.第2季")是排序前缀而非另一部番剧的标题，也归到当前番剧——
 * 否则无封面锚点的 "番剧名/1.第一季" 结构会被拆成独立 show("1.第一季"), 番剧名丢失且刮削无从下手。
 *
 * @param parentTitleHint 父目录名经 [normalizeSeasonTitleHint] 归一化后的标题提示。
 */
internal fun isLikelySeasonOfShow(parentTitleHint: String, marker: SeasonDirectoryMarker): Boolean {
    if (marker.inheritedSeasonNumber == null) return false
    if (marker.titleHint.isBlank()) return true
    if (NUMERIC_SEASON_TITLE_HINT_REGEX.matches(marker.titleHint)) return true
    return parentTitleHint.isNotBlank() &&
        (marker.titleHint.contains(parentTitleHint) || parentTitleHint.contains(marker.titleHint))
}

internal fun normalizeSeasonTitleHint(value: String): String {
    var normalized = BRACKET_DECORATION_REGEX.replace(value.lowercase(), " ")
    normalized = QUALITY_DECORATION_REGEX.replace(normalized, " ")
    normalized = CALENDAR_YEAR_REGEX.replace(normalized, " ")
    SEASON_DECORATION_WORDS.forEach { word -> normalized = normalized.replace(word, " ") }
    return normalized.replace(Regex("[^\\p{L}\\p{N}]+"), "")
}

private fun parseChineseSeasonNumber(value: String): Int? {
    val digits = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    if (value.none { it == '十' || it == '百' }) {
        return value.fold(0) { total, char -> total * 10 + (digits[char] ?: return null) }
    }
    var total = 0
    var currentDigit = 0
    value.forEach { char ->
        when (char) {
            '十' -> {
                total += (if (currentDigit == 0) 1 else currentDigit) * 10
                currentDigit = 0
            }
            '百' -> {
                total += (if (currentDigit == 0) 1 else currentDigit) * 100
                currentDigit = 0
            }
            else -> currentDigit = digits[char] ?: return null
        }
    }
    return total + currentDigit
}

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

/** 缺少 season.nfo 时，只有能与父番剧标题建立归属关系的季目录才允许合成季度。 */
internal fun canInferSeasonNfoForShow(parentTitleHints: List<String>, directoryName: String): Boolean {
    val marker = parseSeasonDirectoryMarker(directoryName) ?: return false
    return parentTitleHints.any { parentTitleHint ->
        isLikelySeasonOfShow(parentTitleHint, marker)
    }
}

/**
 * 仅为 Ani-RSS/TMDB 合并季导致的缺 NFO 场景提供有界降级：目录必须是可继承的显式季标记，
 * 至少一个视频必须携带 SxxExx，且所有可提取的 Sxx 都与目录季号一致。
 */
internal fun inferSeasonNfoFromDirectoryAndVideos(
    directoryName: String,
    videoFiles: List<MediaEntry>,
): SeasonNfo? {
    val directorySeason = parseSeasonDirectoryMarker(directoryName)?.inheritedSeasonNumber ?: return null
    if (videoFiles.isEmpty()) return null
    val videoSeasons = videoFiles.map { video ->
        EpisodeNumberExtractor.extractSeason(video.name) ?: return null
    }
    if (videoSeasons.any { it != directorySeason }) return null
    return SeasonNfo(
        seasonNumber = directorySeason,
        title = "第 ${directorySeason} 季",
        year = null,
        releaseDate = null,
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
