package io.github.weiyongzenqi.unuplayer.ui.posterwall

import android.content.res.Configuration

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.SettingsState
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.ScanMode
import io.github.weiyongzenqi.unuplayer.library.ListShowsByLibrary
import io.github.weiyongzenqi.unuplayer.library.MediaSourceCache
import io.github.weiyongzenqi.unuplayer.library.MediaSourceFactory
import io.github.weiyongzenqi.unuplayer.library.PosterCache
import io.github.weiyongzenqi.unuplayer.library.PosterCard
import io.github.weiyongzenqi.unuplayer.library.PosterWallScanCoordinator
import io.github.weiyongzenqi.unuplayer.library.ScanConfig
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.cacheKey
import io.github.weiyongzenqi.unuplayer.local.LocalDirectoryRepository
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository

/** 搜索范围: GLOBAL 跨库, CURRENT_LIBRARY 仅当前选中库。 */
private enum class SearchScope { GLOBAL, CURRENT_LIBRARY }

/**
 * 海报墙(番剧库)主页。
 *
 * - 顶部: 刮削库下拉选择 + 扫描 / 更多(重扫当前目录·编辑当前库·删除当前库) / 添加 按钮
 * - 内容: [显示已隐藏]切换 + 收藏置顶段 + 正常段(按 min_release_date 的 yyyy-MM 分组, 可配) + 隐藏段(展开时)
 * - item 带 animateItem 丝滑动画; 点番剧 -> AnimeDetailScreen(slide/fade 过渡)
 *
 * **排序**: listShows 按 settings.posterWallSortBy(季度/年份/最近扫描, 拼音回落季度)。
 * **收藏置顶**: is_favorite=1 置顶"我的收藏"段, 内部按 favorited_at DESC(SQL 已排)。
 * **屏蔽/隐藏过滤**: listShows 已过滤屏蔽+隐藏(is_hidden=0); 隐藏段单独 listHidden 查(始终加载知数量)。
 * **隐藏段入口**: 列表顶部「显示已隐藏(N)」按钮 toggle(下拉手势不自然, 改按钮更直观)。
 *
 * **扫描状态跨页面保持**: 扫描 job + 状态在 [scanCoordinator](进程级单例)。
 * **滚动位置保持**: gridState 在列表分支内 rememberSaveable(LazyGridState.Saver), 返回不丢位置且避免跨 AnimatedContent 复用 attach 死锁。
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
    webDavRepo: WebDavConnectionRepository,
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
    var hiddenShows by remember { mutableStateOf<List<ListShowsByLibrary>>(emptyList()) }
    var showHidden by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var selectedShowId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedShowLibraryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var listRefreshToken by remember { mutableLongStateOf(0L) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var sourceAvailable by remember { mutableStateOf(false) }
    // 对话框用的 WebDAV 连接列表
    var webDavConnections by remember { mutableStateOf<List<WebDavConnection>>(emptyList()) }

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

    // 加载 WebDAV 连接列表(添加库对话框用)
    LaunchedEffect(Unit) {
        runSuspendCatching { webDavRepo.loadAll() }
            .onSuccess { webDavConnections = it }
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
    // 搜索 debounce 300ms(空查询清空结果, 不搜)
    LaunchedEffect(searchQuery, searchScope, selectedLibraryId, listRefreshToken) {
        if (searchQuery.isBlank()) { searchResults = emptyList(); return@LaunchedEffect }
        delay(300)
        val libId = if (searchScope == SearchScope.CURRENT_LIBRARY) selectedLibraryId else null
        searchResults = runSuspendCatching { scrapedRepo.searchShows(searchQuery, libId) }.getOrDefault(emptyList())
    }
    val isSearching = searchQuery.isNotBlank()

    // ★流式加载: 扫描中 foundShows 变化 / 扫描完成(isScanning 转换)时刷新列表(番剧陆续出现 + 最终完整)
    // 仅刷新被扫描的当前库; listShows 本地 DB 查询有索引, 扫描节奏天然限速, 不刷爆
    LaunchedEffect(scanState.foundShows, scanState.isScanning) {
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

    // AnimatedContent: 列表态 <-> 详情态, 带滑入/滑出过渡动画
    AnimatedContent(
        targetState = selectedShowId,
        transitionSpec = {
            if (targetState != null && initialState == null) {
                // 进详情: 详情从右滑入, 列表向左淡出
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it } + fadeOut())
            } else {
                // 返回: 列表从左滑入, 详情向右滑出
                (slideInHorizontally { -it } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            }
        },
        contentAlignment = Alignment.TopCenter,
        label = "poster_detail",
    ) { target ->
        val detailLibrary = libraries.firstOrNull { it.id == selectedShowLibraryId }
        if (target != null && detailLibrary != null) {
            AnimeDetailScreen(
                showId = target,
                library = detailLibrary,
                scrapedRepo = scrapedRepo,
                mediaSourceCache = mediaSourceCache,
                playbackRepo = playbackRepo,
                imageCacheSizeMb = settings.posterWallImageCacheSizeMb,
                showEpisodeThumb = settings.posterWallShowEpisodeThumb,
                scanConfig = scanConfig,
                onPlay = onPlay,
                onShowChanged = { listRefreshToken++ },
                onBack = {
                    selectedShowId = null
                    selectedShowLibraryId = null
                },
            )
        } else {
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
                onScan = { selectedLibrary?.let { scanCoordinator.startScan(it, settings, force = false) } },
                onRescanCurrent = { selectedLibrary?.let { scanCoordinator.rescanCurrent(it, settings) } },
                onStopScan = { scanCoordinator.stopScan() },
                onAddLibrary = { showAddDialog = true },
                onEditLibrary = { showEditDialog = true },
                onDeleteLibrary = { showDeleteConfirm = true },
                onOpenShow = { showId, libraryId ->
                    selectedShowLibraryId = libraryId
                    selectedShowId = showId
                },
            )
        }
    }

    if (showAddDialog) {
        AddLibraryDialog(
            webDavConnections = webDavConnections,
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
 * 海报墙列表态(AnimeScreen 的列表分支, 抽出避免 AnimatedContent 内联过深)。
 *
 * 顶部 TopAppBar: 库下拉 + 扫描 + 更多(重扫当前目录/编辑当前库/删除当前库) + 添加。
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
    onScan: () -> Unit,
    onRescanCurrent: () -> Unit,
    onStopScan: () -> Unit,
    onAddLibrary: () -> Unit,
    onEditLibrary: () -> Unit,
    onDeleteLibrary: () -> Unit,
    onOpenShow: (Long, Long) -> Unit,
    isSearching: Boolean,
    searchQuery: String,
    searchScope: SearchScope,
    searchResults: List<ListShowsByLibrary>,
    onSearchQueryChange: (String) -> Unit,
    onSearchScopeChange: (SearchScope) -> Unit,
) {
    // gridState 在列表分支内 rememberSaveable: 返回时是新实例(避免跨 AnimatedContent 复用 attach 死锁致无法滚动);
    // Saver 保存 firstVisibleItemIndex/offset 保持滚动位置(进详情返回/切 tab 都不丢)。
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
                    // 扫描(主操作, 直接增量全盘扫描选中库)
                    IconButton(onClick = onScan, enabled = canScan) {
                        Icon(Icons.Filled.Refresh, contentDescription = "扫描")
                    }
                    // 更多: 重扫当前目录 / 编辑当前库 / 删除当前库
                    Box {
                        IconButton(onClick = { moreMenuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = moreMenuExpanded,
                            onDismissRequest = { moreMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("重扫当前目录") },
                                enabled = canScan,
                                onClick = { moreMenuExpanded = false; onRescanCurrent() },
                            )
                            DropdownMenuItem(
                                text = { Text("编辑当前库") },
                                enabled = selectedLibrary != null,
                                onClick = { moreMenuExpanded = false; onEditLibrary() },
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = scanStatus.ifBlank { "扫描中..." },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = onStopScan) { Text("停止") }
                            }
                        }
                        if (!isSearching && shows.isEmpty() && hiddenShows.isEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        if (isScanning) "扫描中..." else "无番剧, 点扫描添加",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (!isScanning) {
                                        Button(
                                            onClick = onScan,
                                            modifier = Modifier.padding(top = 12.dp),
                                        ) { Text("扫描") }
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
                                modifier = Modifier.weight(1f).fillMaxSize(),
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
        posterPath = show.poster_path,
        imageCacheSizeMb = settings.posterWallImageCacheSizeMb,
        downloader = { dest ->
            show.poster_path?.let { path ->
                mediaSourceCache.withSource(lib) { source ->
                    source.downloadToFile(path, dest)
                } ?: false
            } ?: false
        },
        onClick = { onOpenShow(show.id, lib.id) },
        modifier = modifier,
        cacheSubdir = show.cacheKey,
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
        posterPath = show.poster_path,
        imageCacheSizeMb = settings.posterWallImageCacheSizeMb,
        downloader = { dest ->
            if (library == null) {
                false
            } else {
                show.poster_path?.let { path ->
                    mediaSourceCache.withSource(library) { source ->
                        source.downloadToFile(path, dest)
                    } ?: false
                } ?: false
            }
        },
        onClick = { onOpenShow(show.id, show.library_id) },
        modifier = modifier,
        cacheSubdir = show.cacheKey,
    )
}
