package io.github.weiyongzenqi.unuplayer.ui.posterwall

import android.content.res.Configuration

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.SettingsState
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import io.github.weiyongzenqi.unuplayer.core.platform.AppNotif
import io.github.weiyongzenqi.unuplayer.domain.ScrapeTriggerMode
import io.github.weiyongzenqi.unuplayer.library.AndroidEpisodeThumbGenerator
import io.github.weiyongzenqi.unuplayer.library.AndroidRemoteImageDownloader
import io.github.weiyongzenqi.unuplayer.library.EpisodeThumbPosition
import io.github.weiyongzenqi.unuplayer.library.EpisodeThumbPositionMode
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.MAX_POSTER_IMAGE_BYTES
import io.github.weiyongzenqi.unuplayer.library.ScanMode
import io.github.weiyongzenqi.unuplayer.library.ListShowsByLibrary
import io.github.weiyongzenqi.unuplayer.library.MediaSourceCache
import io.github.weiyongzenqi.unuplayer.library.MediaSourceFactory
import io.github.weiyongzenqi.unuplayer.library.OnlinePosterLoadGuard
import io.github.weiyongzenqi.unuplayer.library.PosterCache
import io.github.weiyongzenqi.unuplayer.library.PosterCard
import io.github.weiyongzenqi.unuplayer.library.RemoteImageFetcher
import io.github.weiyongzenqi.unuplayer.library.ScrapedImagePathKind
import io.github.weiyongzenqi.unuplayer.library.ScrapeFactory
import io.github.weiyongzenqi.unuplayer.library.PosterWallScanCoordinator
import io.github.weiyongzenqi.unuplayer.library.BatchScrapeCoordinator
import io.github.weiyongzenqi.unuplayer.library.BatchScrapeReason
import io.github.weiyongzenqi.unuplayer.library.ScanConfig
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.cacheKey
import io.github.weiyongzenqi.unuplayer.local.LocalDirectoryRepository
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.smb.SmbConnectionRepository

/** 搜索范围: GLOBAL 跨库, CURRENT_LIBRARY 仅当前选中库。 */
private enum class SearchScope { GLOBAL, CURRENT_LIBRARY }

/**
 * 海报墙(番剧库)主页。
 *
 * - 顶部: 刮削库下拉选择 + 增量扫描 / 更多(全量扫描·编辑当前库·删除当前库) / 添加按钮
 * - 内容: [显示已隐藏]切换 + 收藏置顶段 + 正常段(按 min_release_date 的 yyyy-MM 分组, 可配) + 隐藏段(展开时)
 * - item 带 animateItem 丝滑动画; 点番剧 -> AnimeDetailScreen(slide/fade 过渡)
 *
 * **排序**: listShows 按 settings.posterWallSortBy(季度/年份/最近扫描, 拼音回落季度)。
 * **收藏置顶**: is_favorite=1 置顶"我的收藏"段, 内部按 favorited_at DESC(SQL 已排)。
 * **屏蔽/隐藏过滤**: listShows 已过滤屏蔽+隐藏(is_hidden=0); 隐藏段单独 listHidden 查(始终加载知数量)。
 * **隐藏段入口**: 列表顶部「显示已隐藏(N)」按钮 toggle(下拉手势不自然, 改按钮更直观)。
 *
 * **扫描状态跨页面保持**: 扫描 job + 状态在 [scanCoordinator](进程级单例)。
 * **滚动位置保持**: 列表用覆盖层模式始终组合(详情为 AnimatedVisibility 覆盖层), gridState 不随详情销毁 ->
 * 滚动位置天然保持(进详情返回不丢); rememberSaveable(LazyGridState.Saver) 兼顾配置变更/切 tab 恢复。
 *
 * 注: 本文件在 androidMain, 签名含 [LocalDirectoryRepository](androidMain 专有)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun AnimeScreen(
    onPlay: (PlayableMedia) -> Unit,
    scrapedRepo: ScrapedLibraryRepository,
    mediaSourceFactory: MediaSourceFactory,
    scanCoordinator: PosterWallScanCoordinator,
    batchScrapeCoordinator: BatchScrapeCoordinator,
    webDavRepo: WebDavConnectionRepository,
    smbRepo: SmbConnectionRepository?,
    localDirRepo: LocalDirectoryRepository,
    settingsRepo: SettingsRepository,
    playbackRepo: PlaybackRecordRepository?,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by settingsRepo.state.collectAsStateWithLifecycle()
    val scanState by scanCoordinator.state.collectAsStateWithLifecycle()

    var libraries by remember { mutableStateOf<List<LibraryConfig>>(emptyList()) }
    var selectedLibraryId by rememberSaveable { mutableStateOf(settings.posterWallDefaultLibraryId) }
    val selectedLibrary = libraries.firstOrNull { it.id == selectedLibraryId }
    var shows by remember { mutableStateOf<List<ListShowsByLibrary>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var searchScope by remember { mutableStateOf(SearchScope.GLOBAL) }
    var searchResults by remember { mutableStateOf<List<ListShowsByLibrary>>(emptyList()) }
    // 页面级唯一所有者：当前库、跨库搜索和详情页只在操作期间租用 source。
    val mediaSourceCache = remember(mediaSourceFactory) { MediaSourceCache(mediaSourceFactory) }
    val episodeThumbGenerator = remember(settings.allowTlsInsecure) {
        AndroidEpisodeThumbGenerator(context.applicationContext, settings.allowTlsInsecure)
    }
    // 在线刮削管线: Bangumi 始终可用; 弹弹按代理/用户凭证启用; TMDB 固定通过 Gateway。
    val onlineScraper = remember(
        settings.dandanplayUseProxy, settings.dandanplayAppId, settings.dandanplayAppSecret,
        settings.bangumiDataSource, settings.posterWallImageCacheSizeMb, settings.scrapeUniqueAutoApply,
        scrapedRepo,
    ) {
        ScrapeFactory.createScraper(
            settings,
            scrapedRepo,
            AndroidRemoteImageDownloader(
                context.applicationContext,
                cacheMaxSizeBytes = settings.posterWallImageCacheSizeMb.coerceIn(50, 2000).toLong() * 1024L * 1024L,
            ),
        )
    }
    var listRefreshToken by remember { mutableLongStateOf(0L) }
    val batchState by batchScrapeCoordinator.state.collectAsStateWithLifecycle()
    val triggerMode = remember(settings.scrapeTriggerMode) {
        runCatching { ScrapeTriggerMode.valueOf(settings.scrapeTriggerMode) }.getOrDefault(ScrapeTriggerMode.LAZY)
    }

    // 批量任务由进程级协调器持有，页面只负责发起和订阅状态。
    fun batchScrape(lib: LibraryConfig) {
        batchScrapeCoordinator.start(
            library = lib,
            scraper = onlineScraper,
            anchorOnly = false,
            concurrency = settings.scrapeConcurrency,
            reason = BatchScrapeReason.MANUAL,
        )
    }

    // 扫描完成后自动补(触发模式 SCAN_ALL / SCAN_ANCHOR_ONLY): 检测 isScanning 从 true -> false 的边缘,
    // 对**刚结束扫描的库**(scanState.libraryId)缺元数据番剧批量在线刮削(命中即应用, 模糊留待手动)。
    // 用 scanState.libraryId 而非当前选中库, 避免"扫描 A 库中切到 B 库"时给没扫过的 B 库误触发。
    var wasScanning by remember { mutableStateOf(scanState.isScanning) }
    LaunchedEffect(scanState.isScanning, triggerMode, selectedLibraryId, onlineScraper, libraries) {
        val finished = wasScanning && !scanState.isScanning
        wasScanning = scanState.isScanning
        if (!finished || batchState.isRunning) return@LaunchedEffect
        if (triggerMode == ScrapeTriggerMode.LAZY) return@LaunchedEffect
        val finishedLibId = scanState.libraryId ?: return@LaunchedEffect
        val lib = libraries.firstOrNull { it.id == finishedLibId } ?: return@LaunchedEffect
        batchScrapeCoordinator.start(
            library = lib,
            scraper = onlineScraper,
            anchorOnly = triggerMode == ScrapeTriggerMode.SCAN_ANCHOR_ONLY,
            concurrency = settings.scrapeConcurrency,
            reason = BatchScrapeReason.AFTER_SCAN,
        )
    }
    LaunchedEffect(batchState.runId, batchState.isRunning, batchState.status) {
        if (!batchState.isRunning && batchState.runId > 0L) {
            listRefreshToken++
        }
    }
    var hiddenShows by remember { mutableStateOf<List<ListShowsByLibrary>>(emptyList()) }
    var showHidden by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var selectedShowId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedShowLibraryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var sourceAvailable by remember { mutableStateOf(false) }
    // 对话框用的文件树连接列表
    var webDavConnections by remember { mutableStateOf<List<WebDavConnection>>(emptyList()) }
    var smbConnections by remember { mutableStateOf<List<SmbConnection>>(emptyList()) }

    val isScanning = scanState.isScanning && scanState.libraryId == selectedLibraryId
    val canScan = !isScanning && selectedLibrary != null && sourceAvailable

    // 扫描配置(详情页单番剧刷新用; 主页扫描走 coordinator, coordinator 内部自建 config)
    val scanConfig = remember(settings.posterWallScanRequestIntervalMs, settings.posterWallScanConcurrency,
        settings.posterWallScanDepth, settings.posterWallScanTimeoutSeconds) {
        ScanConfig(
            requestIntervalMs = settings.posterWallScanRequestIntervalMs,
            concurrency = settings.posterWallScanConcurrency,
            depth = settings.posterWallScanDepth,
            timeoutSeconds = settings.posterWallScanTimeoutSeconds,
        )
    }

    // 离开页面时停止新租用；活跃下载/扫描完成后由缓存关闭最后一个引用。
    LaunchedEffect(mediaSourceCache) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                mediaSourceCache.close()
            }
        }
    }

    // 加载远程连接列表(添加库对话框用)
    LaunchedEffect(Unit) {
        runSuspendCatching { webDavRepo.loadAll() }
            .onSuccess { webDavConnections = it }
        runSuspendCatching { smbRepo?.loadAll().orEmpty() }
            .onSuccess { smbConnections = it }
    }

    // 加载刮削库列表; 首次未选默认取首个, 已选但被删则回落首个
    LaunchedEffect(Unit) {
        libraries = runSuspendCatching { scrapedRepo.listLibraries() }.getOrDefault(emptyList())
        when {
            selectedLibraryId == null && libraries.isNotEmpty() ->
                selectedLibraryId = libraries.first().id
            selectedLibraryId != null && libraries.none { it.id == selectedLibraryId } ->
                selectedLibraryId = libraries.firstOrNull()?.id
        }
    }

    LaunchedEffect(selectedLibrary) {
        sourceAvailable = false
        sourceAvailable = selectedLibrary?.let { library ->
            runSuspendCatching { mediaSourceCache.prepare(library) }.getOrDefault(false)
        } ?: false
    }

    // 加载番剧列表 + 隐藏段(选中库变化时); 隐藏段始终加载以显示数量
    LaunchedEffect(selectedLibrary, settings.posterWallSortBy, listRefreshToken) {
        if (selectedLibrary != null) {
            loading = true
            val loadedShows = runSuspendCatching {
                scrapedRepo.listShows(selectedLibrary.id, settings.posterWallSortBy)
            }.getOrDefault(emptyList())
            val loadedHiddenShows = runSuspendCatching {
                scrapedRepo.listHidden(selectedLibrary.id)
            }.getOrDefault(emptyList())
            shows = loadedShows
            hiddenShows = loadedHiddenShows
            loading = false
        } else {
            shows = emptyList()
            hiddenShows = emptyList()
            loading = false
        }
    }
    // 在线封面一次性加载(批次C): 无本地封面但有「远程 URL 且无本地文件」季照的番, 每次应用启动期间
    // 只尝试下载一次(OnlinePosterLoadGuard 进程级去重, 失败也计入 → 绝不无限重试); 串行逐部, 不打满并发。
    // 守卫 key 含季号: 多季番每缺封季各占一次会话配额, 补完最高季刷新后低季仍可再试。
    // 先下载后标记: 被 shows 变更取消(CancellationException)不消耗配额(取消≠失败), 下一趟刷新仍可再试。
    // 图片限流退避期(该番图片主机 rateLimitBackoffRemainingMs>0)本趟跳过、不等待(下次会话/详情页重试条再补);
    // 按主机隔离(FP3-13): 仅跳过被限流 CDN 的番, 其它主机的番照常尝试。
    // 任一成功 bump listRefreshToken 让列表重查(card_poster_path 会带上新下载的本地季照)。
    // 收敛性: 刷新产生新列表实例重启本 effect, 但失败/成功者已被守卫标记、成功者已有 card_poster_path,
    // 第二轮过滤后为空即止——不存在无限循环。
    LaunchedEffect(shows, onlineScraper) {
        val pending = shows.filter {
            it.card_poster_path == null && !it.card_remote_poster_url.isNullOrBlank() &&
                it.card_remote_poster_season != null
        }
        if (pending.isEmpty()) return@LaunchedEffect
        var anySuccess = false
        for (show in pending) {
            val seasonNumber = show.card_remote_poster_season?.toInt() ?: continue
            val remoteUrl = show.card_remote_poster_url ?: continue
            // 该番图片主机限流退避中则本趟跳过、不等待(下次会话/详情页重试条再补);
            // 按主机隔离(FP3-13): 仅跳过被限流的 CDN 的番, 其它主机的番照常尝试
            if (RemoteImageFetcher.rateLimitBackoffRemainingMsForUrl(remoteUrl) > 0) continue
            val guardKey = "${show.library_id}|${show.show_path}|$seasonNumber"
            if (OnlinePosterLoadGuard.isAttempted(guardKey)) continue // 本会话已试过(含失败)直接跳过
            try {
                val ok = runSuspendCatching {
                    onlineScraper.tryDownloadOnlinePoster(show.library_id, show.show_path, seasonNumber, remoteUrl)
                }.getOrDefault(false)
                if (ok) anySuccess = true
                // 仅"真实完成/失败"的尝试消耗会话配额; 取消(CancellationException)抛到外层, 不烧额度
                OnlinePosterLoadGuard.markAttempted(guardKey)
            } catch (e: CancellationException) {
                throw e
            }
        }
        if (anySuccess) listRefreshToken++
    }
    // 搜索 debounce 300ms(空查询清空结果, 不搜)
    LaunchedEffect(searchQuery, searchScope, selectedLibraryId, listRefreshToken) {
        if (searchQuery.isBlank()) { searchResults = emptyList(); return@LaunchedEffect }
        delay(300)
        val libId = if (searchScope == SearchScope.CURRENT_LIBRARY) selectedLibraryId else null
        searchResults = runSuspendCatching { scrapedRepo.searchShows(searchQuery, libId) }.getOrDefault(emptyList())
    }
    val isSearching = searchQuery.isNotBlank()

    // ★流式加载: 扫描中 foundShows 递增 / 扫描完成(isScanning 转换)时刷新列表(番剧陆续出现 + 最终完整)。
    // 重启式 debounce 300ms: foundShows/isScanning 变化即 key 变化重启本 effect, 前一次的 delay(300) 被取消,
    // 只有最后一次变化熬过 300ms 静默期(200-500ms 居中)才触发查询 —— LOCAL 快扫描连续递增合并成少数几批
    // 刷新, 不再每递增重跑 listShows+listHidden 聚合查询连续打 DB; 扫描结束的终态刷新由 trailing 重启天然保证。
    // 仅作用于扫描流式刷新路径 —— 常规进页加载走上方 LaunchedEffect(selectedLibrary, ...) 独立路径, 首载不被延迟。
    LaunchedEffect(selectedLibrary?.id, settings.posterWallSortBy, scanState.foundShows, scanState.isScanning) {
        delay(300)
        val lib = selectedLibrary ?: return@LaunchedEffect
        if (scanState.libraryId == lib.id) {
            val loadedShows = runSuspendCatching {
                scrapedRepo.listShows(lib.id, settings.posterWallSortBy)
            }.getOrDefault(shows)
            val loadedHiddenShows = runSuspendCatching { scrapedRepo.listHidden(lib.id) }
                .getOrDefault(hiddenShows)
            shows = loadedShows
            hiddenShows = loadedHiddenShows
        }
    }

    // 切换"显示隐藏段": hiddenShows 已始终加载, toggle 仅控制展开
    val onToggleHidden: () -> Unit = { showHidden = !showHidden }

    // 记住最近打开的番剧: 退出动画期间(selectedShowId 已置 null)仍需渲染详情做滑出动画
    var lastShowId by remember { mutableStateOf<Long?>(null) }
    var lastShowLibraryId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(selectedShowId, selectedShowLibraryId) {
        if (selectedShowId != null) {
            lastShowId = selectedShowId
            lastShowLibraryId = selectedShowLibraryId
        }
    }

    // 覆盖层模式: 列表始终组合(gridState 不随详情销毁, 滚动位置天然保持, 无 attach 死锁);
    // 详情作为覆盖层从右滑入/向右滑出。
    Box(modifier = Modifier.fillMaxSize()) {
        PosterWallListContent(
            libraries = libraries,
            selectedLibrary = selectedLibrary,
            shows = shows,
            isSearching = isSearching,
            searchQuery = searchQuery,
            searchScope = searchScope,
            searchResults = searchResults,
            onSearchQueryChange = { searchQuery = it },
            onSearchScopeChange = { searchScope = it },
            mediaSourceCache = mediaSourceCache,
            hiddenShows = hiddenShows,
            showHidden = showHidden,
            onToggleHidden = onToggleHidden,
            loading = loading,
            isScanning = isScanning,
            scanStatus = scanState.status,
            settings = settings,
            canScan = canScan,
            onSelectLibrary = { selectedLibraryId = it },
            onIncrementalScan = { selectedLibrary?.let { scanCoordinator.startScan(it, settings, force = false) } },
            onFullScan = { selectedLibrary?.let { scanCoordinator.startScan(it, settings, force = true) } },
            onStopScan = { scanCoordinator.stopScan() },
            onAddLibrary = { showAddDialog = true },
            onBatchScrape = { selectedLibrary?.let { batchScrape(it) } },
            batchScrapeState = batchState,
            onStopBatchScrape = { batchScrapeCoordinator.stop() },
            onEditLibrary = { showEditDialog = true },
            onDeleteLibrary = { showDeleteConfirm = true },
            onOpenShow = { showId, libraryId ->
                selectedShowLibraryId = libraryId
                selectedShowId = showId
            },
        )
        AnimatedVisibility(
            visible = selectedShowId != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            val sid = selectedShowId ?: lastShowId
            val detailLibrary = libraries.firstOrNull { it.id == (selectedShowLibraryId ?: lastShowLibraryId) }
            if (sid != null && detailLibrary != null) {
                key(detailLibrary.id, sid) {
                    AnimeDetailScreen(
                        showId = sid,
                        library = detailLibrary,
                        scrapedRepo = scrapedRepo,
                        mediaSourceCache = mediaSourceCache,
                        playbackRepo = playbackRepo,
                        imageCacheSizeMb = settings.posterWallImageCacheSizeMb,
                        showEpisodeThumb = settings.posterWallShowEpisodeThumb,
                        autoGenerateEpisodeThumb = settings.posterWallAutoEpisodeThumb,
                        useSeasonPoster = settings.posterWallDetailUseSeasonPoster,
                        badgeShowSeason1 = settings.posterWallBadgeShowSeason1,
                        scanConfig = scanConfig,
                        globalSettings = settings,
                        episodeThumbGenerator = episodeThumbGenerator,
                        episodeThumbPosition = if (settings.posterWallEpisodeThumbPositionMode == EpisodeThumbPositionMode.PERCENT)
                            EpisodeThumbPosition.Percent(settings.posterWallEpisodeThumbAtPercent)
                        else
                            EpisodeThumbPosition.Seconds(settings.posterWallEpisodeThumbAtSeconds),
                        scraper = onlineScraper,
                        scrapeHashProvider = ScrapeFactory.buildHashProvider(detailLibrary, mediaSourceCache),
                        onPlay = onPlay,
                        onShowChanged = { listRefreshToken++ },
                        onBack = {
                            selectedShowId = null
                            selectedShowLibraryId = null
                        },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddLibraryDialog(
            webDavConnections = webDavConnections,
            smbConnections = smbConnections,
            onConfirm = { name, sourceKind, connectionId, localUri, rootPath, scanMode, anchorFilenames ->
                scope.launch {
                    val newId = scrapedRepo.addLibrary(
                        name = name,
                        sourceKind = sourceKind,
                        connectionId = connectionId,
                        localUri = localUri,
                        rootPath = rootPath,
                        scanDepth = settings.posterWallScanDepth,
                        scanMode = scanMode,
                        anchorFilenames = anchorFilenames,
                    )
                    libraries = runSuspendCatching { scrapedRepo.listLibraries() }.getOrDefault(libraries)
                    selectedLibraryId = newId
                }
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    if (showEditDialog && selectedLibrary != null) {
        val editing = selectedLibrary
        EditLibraryDialog(
            library = editing,
            onConfirm = { name, rootPath ->
                scope.launch {
                    runSuspendCatching { scrapedRepo.updateLibrary(editing.id, name, rootPath, editing.scanDepth) }
                    libraries = runSuspendCatching { scrapedRepo.listLibraries() }.getOrDefault(libraries)
                }
                showEditDialog = false
            },
            onDismiss = { showEditDialog = false },
        )
    }

    if (showDeleteConfirm && selectedLibrary != null) {
        val deleting = selectedLibrary
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除刮削库") },
            text = { Text("确定删除「${deleting.name}」? 番剧/季/剧集数据将一并删除(级联), 图片缓存同步清除。") },
            confirmButton = {
                TextButton(onClick = {
                    val delId = deleting.id
                    scope.launch {
                        // 删库前清该库所有番剧图片缓存(逐 showKey, 避免误清其他库)
                        val cache = PosterCache.get(context)
                        val showsInLib = runSuspendCatching {
                            scrapedRepo.listShows(delId, settings.posterWallSortBy)
                        }.getOrDefault(emptyList())
                        showsInLib.forEach { cache.clearShow(it.cacheKey) }
                        runSuspendCatching { scrapedRepo.deleteLibrary(delId) }
                        mediaSourceCache.invalidate(delId)
                        libraries = runSuspendCatching { scrapedRepo.listLibraries() }.getOrDefault(libraries)
                        selectedLibraryId = libraries.firstOrNull()?.id
                    }
                    showDeleteConfirm = false
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 海报墙列表态(AnimeScreen 的列表分支, 抽出避免 AnimeScreen 内联过深)。
 *
 * 顶部 TopAppBar: 库下拉 + 增量扫描 + 更多(全量扫描/编辑当前库/删除当前库) + 添加。
 * 内容: loading 转圈 / 无库引导添加 / 无番剧引导扫描 / LazyVerticalGrid
 * (显示已隐藏切换 + 收藏置顶段 + 正常段[季度分组 or 平铺] + 隐藏段[展开时])。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PosterWallListContent(
    libraries: List<LibraryConfig>,
    selectedLibrary: LibraryConfig?,
    shows: List<ListShowsByLibrary>,
    hiddenShows: List<ListShowsByLibrary>,
    showHidden: Boolean,
    onToggleHidden: () -> Unit,
    loading: Boolean,
    isScanning: Boolean,
    scanStatus: String,
    settings: SettingsState,
    mediaSourceCache: MediaSourceCache,
    canScan: Boolean,
    onSelectLibrary: (Long) -> Unit,
    onIncrementalScan: () -> Unit,
    onFullScan: () -> Unit,
    onStopScan: () -> Unit,
    onAddLibrary: () -> Unit,
    onEditLibrary: () -> Unit,
    onDeleteLibrary: () -> Unit,
    onOpenShow: (Long, Long) -> Unit,
    onBatchScrape: () -> Unit,
    batchScrapeState: BatchScrapeCoordinator.State,
    onStopBatchScrape: () -> Unit,
    isSearching: Boolean,
    searchQuery: String,
    searchScope: SearchScope,
    searchResults: List<ListShowsByLibrary>,
    onSearchQueryChange: (String) -> Unit,
    onSearchScopeChange: (SearchScope) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    // gridState 在列表分支内 rememberSaveable: 覆盖层模式下列表始终组合, gridState 不随详情销毁,
    // 滚动位置天然保持(进详情返回不丢); Saver 兼顾配置变更/切 tab 恢复 firstVisibleItemIndex/offset。
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }

    var libMenuExpanded by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Row(
                            modifier = Modifier.clickable { libMenuExpanded = true },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(selectedLibrary?.name ?: "番剧")
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "选择刮削库")
                        }
                        DropdownMenu(
                            expanded = libMenuExpanded,
                            onDismissRequest = { libMenuExpanded = false },
                        ) {
                            if (libraries.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("还没有刮削库") },
                                    onClick = { libMenuExpanded = false },
                                )
                            } else {
                                libraries.forEach { lib ->
                                    DropdownMenuItem(
                                        text = { Text(lib.name) },
                                        onClick = {
                                            onSelectLibrary(lib.id)
                                            libMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    // 顶部刷新只发现新增番剧，避免重复读取已记录番剧目录。
                    IconButton(onClick = onIncrementalScan, enabled = canScan) {
                        Icon(Icons.Filled.Refresh, contentDescription = "增量扫描")
                    }
                    // 更多: 全量扫描 / 编辑当前库 / 删除当前库
                    Box {
                        IconButton(onClick = { moreMenuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = moreMenuExpanded,
                            onDismissRequest = { moreMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("全量扫描") },
                                enabled = canScan,
                                onClick = { moreMenuExpanded = false; onFullScan() },
                            )
                            DropdownMenuItem(
                                text = { Text("编辑当前库") },
                                enabled = selectedLibrary != null,
                                onClick = { moreMenuExpanded = false; onEditLibrary() },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (batchScrapeState.isRunning) "批量补刮中…" else "批量补刮缺元数据番剧")
                                },
                                enabled = selectedLibrary != null && !batchScrapeState.isRunning,
                                onClick = { moreMenuExpanded = false; onBatchScrape() },
                            )
                            DropdownMenuItem(
                                text = { Text("删除当前库") },
                                enabled = selectedLibrary != null,
                                onClick = { moreMenuExpanded = false; onDeleteLibrary() },
                            )
                        }
                    }
                    // 添加
                    IconButton(onClick = onAddLibrary) {
                        Icon(Icons.Filled.Add, contentDescription = "添加刮削库")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                libraries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有刮削库", style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = onAddLibrary,
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text("添加", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
                selectedLibrary == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    val lib = selectedLibrary
                    Column(Modifier.fillMaxSize()) {
                        if (isScanning) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 56.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text(
                                        text = scanStatus.ifBlank { "正在扫描媒体库..." },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 8.dp),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                TextButton(
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                    onClick = onStopScan,
                                ) { Text("停止") }
                            }
                        }
                        if (batchScrapeState.runId > 0L &&
                            (batchScrapeState.isRunning || batchScrapeState.libraryId == selectedLibrary.id)
                        ) {
                            BatchScrapeStatus(
                                progress = BatchScrapeProgress(
                                    batchScrapeState.completed,
                                    batchScrapeState.total,
                                    batchScrapeState.currentTitle,
                                ),
                                status = batchScrapeState.status,
                                isRunning = batchScrapeState.isRunning,
                                isStopping = batchScrapeState.isStopping,
                                onStop = onStopBatchScrape,
                            )
                        }
                        if (!isSearching && shows.isEmpty() && hiddenShows.isEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        if (isScanning) "扫描中..." else "无番剧，点增量扫描添加",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (!isScanning) {
                                        Button(
                                            onClick = onIncrementalScan,
                                            modifier = Modifier.padding(top = 12.dp),
                                        ) { Text("增量扫描") }
                                    }
                                }
                            }
                        } else {
                                // 搜索框 + 范围切换(全局/当前库)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = onSearchQueryChange,
                                        placeholder = { Text("搜索番剧…") },
                                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                        trailingIcon = {
                                            if (searchQuery.isNotEmpty()) {
                                                IconButton(onClick = { onSearchQueryChange("") }) {
                                                    Icon(Icons.Filled.Clear, contentDescription = "清除")
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    FilterChip(
                                        selected = searchScope == SearchScope.CURRENT_LIBRARY,
                                        onClick = {
                                            onSearchScopeChange(
                                                if (searchScope == SearchScope.CURRENT_LIBRARY) SearchScope.GLOBAL else SearchScope.CURRENT_LIBRARY
                                            )
                                        },
                                        label = { Text(if (searchScope == SearchScope.CURRENT_LIBRARY) "当前库" else "全局") },
                                    )
                                }
                                val configuration = LocalConfiguration.current
                                val columns = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                    settings.posterWallPosterColumnsLandscape
                                } else {
                                    settings.posterWallPosterColumnsPortrait
                                }.coerceAtLeast(1)
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Fixed(columns),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    // 搜索框之外的内容区域是明确的“点击空白取消焦点”区域。
                                    // 不消费指针事件，避免影响网格滚动和卡片点击。
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            var isTap = true
                                            do {
                                                val event = awaitPointerEvent()
                                                isTap = isTap && event.changes.none { it.isConsumed }
                                            } while (event.changes.any { it.pressed })
                                            if (isTap) {
                                                focusManager.clearFocus()
                                            }
                                        }
                                    },
                                contentPadding = PaddingValues(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (isSearching) {
                                    if (searchResults.isEmpty()) {
                                        item(span = { GridItemSpan(maxLineSpan) }, key = "search_empty") {
                                            Text(
                                                "无匹配番剧",
                                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                                textAlign = TextAlign.Center,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    } else {
                                        items(searchResults, key = { "search_${it.id}" }) { show ->
                                            SearchGridItem(
                                                show = show,
                                                library = libraries.firstOrNull { it.id == show.library_id },
                                                settings = settings,
                                                mediaSourceCache = mediaSourceCache,
                                                onOpenShow = onOpenShow,
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                    }
                                } else {
                                val favorites = shows.filter { it.is_favorite == 1L }
                                val normal = shows.filter { it.is_favorite != 1L }

                                // === 顶部: 显示/收起已隐藏段切换(有隐藏番剧才显示) ===
                                if (hiddenShows.isNotEmpty()) {
                                    item(span = { GridItemSpan(maxLineSpan) }, key = "toggle_hidden") {
                                        TextButton(
                                            onClick = onToggleHidden,
                                            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                                        ) {
                                            Text(if (showHidden) "收起已隐藏" else "显示已隐藏 (${hiddenShows.size})")
                                        }
                                    }
                                }

                                // === 收藏置顶段 ===
                                if (favorites.isNotEmpty()) {
                                    item(span = { GridItemSpan(maxLineSpan) }, key = "header_favorites") {
                                        Text(
                                            text = "我的收藏",
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier
                                                .padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                                        )
                                    }
                                    items(favorites, key = { "fav_${it.id}" }) { show ->
                                        PosterGridItem(
                                            show = show,
                                            lib = lib,
                                            settings = settings,
                                            mediaSourceCache = mediaSourceCache,
                                            onOpenShow = onOpenShow,
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }

                                // === 正常段: 季度分组 or 平铺 ===
                                if (settings.posterWallGroupByQuarter) {
                                    // 按 min_release_date 的 yyyy-MM 分组; listShows 已按
                                    // 收藏置顶+min_release_date DESC+title ASC 排, groupBy 保留首现顺序
                                    val groups = normal.groupBy { it.min_release_date?.take(7) }
                                    groups.forEach { (key, groupShows) ->
                                        item(span = { GridItemSpan(maxLineSpan) }, key = "header_$key") {
                                            Text(
                                                text = formatQuarterLabel(key),
                                                style = MaterialTheme.typography.titleSmall,
                                                modifier = Modifier
                                                    .padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                                            )
                                        }
                                        items(groupShows, key = { "show_${it.id}" }) { show ->
                                            PosterGridItem(
                                                show = show,
                                                lib = lib,
                                                settings = settings,
                                                mediaSourceCache = mediaSourceCache,
                                                onOpenShow = onOpenShow,
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                    }
                                } else {
                                    items(normal, key = { "show_${it.id}" }) { show ->
                                        PosterGridItem(
                                            show = show,
                                            lib = lib,
                                            settings = settings,
                                            mediaSourceCache = mediaSourceCache,
                                            onOpenShow = onOpenShow,
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }

                                // === 隐藏段(展开时显示) ===
                                if (showHidden && hiddenShows.isNotEmpty()) {
                                    item(span = { GridItemSpan(maxLineSpan) }, key = "header_hidden") {
                                        Text(
                                            text = "已隐藏",
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier
                                                .padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                                        )
                                    }
                                    items(hiddenShows, key = { "hidden_${it.id}" }) { show ->
                                        PosterGridItem(
                                            show = show,
                                            lib = lib,
                                            settings = settings,
                                            mediaSourceCache = mediaSourceCache,
                                            onOpenShow = onOpenShow,
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 海报墙网格 item(收藏/正常/隐藏段共用, 去重 PosterCard 调用)。
 * cacheSubdir 用 [ListShowsByLibrary.cacheKey] 扩展(番剧名-tmdbid, 统一公式)。
 */
@Composable
private fun PosterGridItem(
    show: ListShowsByLibrary,
    lib: LibraryConfig,
    settings: SettingsState,
    mediaSourceCache: MediaSourceCache,
    onOpenShow: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    PosterCard(
        title = show.title,
        sourceKind = lib.sourceKind,
        libraryId = lib.id,
        posterPath = show.card_poster_path,
        posterPathKind = ScrapedImagePathKind.fromStorage(show.card_poster_path_kind),
        fallbackPosterPath = show.card_online_poster_path,
        fallbackFanartPath = show.card_online_fanart_path,
        imageCacheSizeMb = settings.posterWallImageCacheSizeMb,
        downloader = { dest ->
            show.card_poster_path?.let { path ->
                mediaSourceCache.withSource(lib) { source ->
                    source.downloadToFile(path, dest, MAX_POSTER_IMAGE_BYTES)
                } ?: false
            } ?: false
        },
        onClick = { onOpenShow(show.id, lib.id) },
        modifier = modifier,
        cacheSubdir = show.cacheKey,
        seasonBadge = show.card_season_number?.takeIf { if (settings.posterWallBadgeShowSeason1) it >= 1 else it >= 2 }?.let { "第${it}季" },
    )
}

/** yyyy-MM -> "yyyy年M月"; null/异常 -> "未知"。 */
private fun formatQuarterLabel(key: String?): String {
    if (key == null) return "未知"
    val dash = key.indexOf('-')
    if (dash <= 0 || dash >= key.length - 1) return key
    val year = key.substring(0, dash)
    val month = key.substring(dash + 1).toIntOrNull() ?: return key
    return "${year}年${month}月"
}

/** 搜索结果 item: 跨库, 用 show 自身 source_kind/library_id + 页面缓存加载封面。 */
@Composable
private fun SearchGridItem(
    show: ListShowsByLibrary,
    library: LibraryConfig?,
    settings: SettingsState,
    mediaSourceCache: MediaSourceCache,
    onOpenShow: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceKind = runCatching { MediaSourceKind.valueOf(show.source_kind) }.getOrDefault(MediaSourceKind.WEBDAV)
    PosterCard(
        title = show.title,
        sourceKind = sourceKind,
        libraryId = show.library_id,
        posterPath = show.card_poster_path,
        posterPathKind = ScrapedImagePathKind.fromStorage(show.card_poster_path_kind),
        fallbackPosterPath = show.card_online_poster_path,
        fallbackFanartPath = show.card_online_fanart_path,
        imageCacheSizeMb = settings.posterWallImageCacheSizeMb,
        downloader = { dest ->
            if (library == null) {
                false
            } else {
                show.card_poster_path?.let { path ->
                    mediaSourceCache.withSource(library) { source ->
                        source.downloadToFile(path, dest, MAX_POSTER_IMAGE_BYTES)
                    } ?: false
                } ?: false
            }
        },
        onClick = { onOpenShow(show.id, show.library_id) },
        modifier = modifier,
        cacheSubdir = show.cacheKey,
        seasonBadge = show.card_season_number?.takeIf { if (settings.posterWallBadgeShowSeason1) it >= 1 else it >= 2 }?.let { "第${it}季" },
    )
}
