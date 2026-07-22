package io.github.weiyongzenqi.unuplayer.ui.browser

import io.github.weiyongzenqi.unuplayer.ui.AppBackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.MediaKeys
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavSource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.SettingsState
import io.github.weiyongzenqi.unuplayer.domain.EpisodeNumberExtractor
import io.github.weiyongzenqi.unuplayer.domain.FileFormatUtil
import io.github.weiyongzenqi.unuplayer.webdav.WebDavFileSorter
import io.github.weiyongzenqi.unuplayer.webdav.SeasonFolderMatcher
import io.github.weiyongzenqi.unuplayer.webdav.WebDavSearchResult
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

/** List<String> Saver(导航历史栈持久化用)。 */
private val StringListSaver = listSaver<List<String>, String>(
    save = { it.toList() },
    restore = { it.toList() },
)

/**
 * WebDAV 浏览器页。
 *
 * 三态:
 * - 无连接: 空态 + 添加连接
 * - 有连接未选: 连接列表
 * - 已选: 文件浏览器(目录树)
 *
 * 只把 selectedConnectionId/currentPath/pathHistory 放入 SavedState；包含密码的连接对象只在内存中
 * remember，并在页面重建时从加密仓库重载，避免密码进入 Bundle/SavedState。
 *
 * 设置入口已移到底部导航 tab, 此页不再有设置齿轮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavBrowserScreen(
    onPlay: (PlayableMedia) -> Unit,
    repository: WebDavConnectionRepository,
    settingsRepository: SettingsRepository,
    playbackRepository: PlaybackRecordRepository? = null,
    initialConnectionId: String? = null,
    onExit: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.state.collectAsStateWithLifecycle()
    var connections by remember { mutableStateOf(emptyList<WebDavConnection>()) }
    // initialConnectionId 非 null 时锁定该连接(MediaSourceScreen 嵌入模式); null = 现 tab 行为不变。
    var selectedConnectionId by rememberSaveable { mutableStateOf(initialConnectionId) }
    val selectedConn = connections.firstOrNull { it.id == selectedConnectionId }
    var showAddDialog by remember { mutableStateOf(false) }
    // 连接列表加载标记: 首次冷启动 connections 空→加载中显示 loading 而非"还没有连接"空态,
    // 避免切 tab/进播放器回来闪现空态。切回 tab 时 connections 已 saveable 恢复非空→初值 false 不闪。
    var connLoading by remember { mutableStateOf(true) }

    // 连接对象不进入 SavedState，页面每次重建都从仓库取回并用已保存的 id 恢复选择。
    LaunchedEffect(Unit) {
        connections = repository.loadAll()
        // initialConnectionId 非 null 时锁定, 不被 settings 默认覆盖; 仅 tab 模式(initial=null)沿用默认。
        if (selectedConnectionId == null && initialConnectionId == null &&
            settings.webdavDefaultConnectionId != null) {
            selectedConnectionId = settings.webdavDefaultConnectionId
        }
        if (connections.none { it.id == selectedConnectionId }) {
            if (initialConnectionId != null && onExit != null) {
                // 锁定模式下连接已不存在(被删) -> 退回调用方(MediaSourceScreen), 不落回连接列表
                onExit()
            } else {
                selectedConnectionId = null
            }
        }
        connLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UnU Player") },
            )
        },
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {
            when {
                selectedConn != null -> FileBrowser(
                    conn = selectedConn,
                    onBack = if (initialConnectionId != null && onExit != null) {
                        onExit
                    } else {
                        { selectedConnectionId = null }
                    },
                    onPlay = onPlay,
                    settings = settings,
                    playbackRepository = playbackRepository,
                )
                connLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
                connections.isEmpty() -> EmptyState(
                    onAdd = { showAddDialog = true },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> ConnectionList(
                    connections = connections,
                    onSelect = { selectedConnectionId = it.id },
                    onAdd = { showAddDialog = true },
                    onRemove = { conn ->
                        scope.launch {
                            connections = repository.remove(conn.id)
                        }
                    },
                )
            }
        }
    }

    if (showAddDialog) {
        AddConnectionDialog(
            onConfirm = { conn, allowCleartext ->
                scope.launch {
                    connections = repository.add(conn, allowCleartext = allowCleartext)
                }
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

/** 文件浏览器: 进入选中连接后, 浏览目录树。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileBrowser(
    conn: WebDavConnection,
    onBack: () -> Unit,
    onPlay: (PlayableMedia) -> Unit,
    settings: SettingsState,
    playbackRepository: PlaybackRecordRepository? = null,
) {
    val scope = rememberCoroutineScope()
    // 导航: currentPath=当前显示路径(列目录/面包屑用); pathHistory=手动导航历史(仅手动点入,
    // Season 自动进入不入栈)。返回 pop history 跳过自动进入层, 复刻 NipaPlay _pathHistory。
    var currentPath by rememberSaveable { mutableStateOf(settings.webdavDefaultDirectory) }
    var pathHistory by rememberSaveable(stateSaver = StringListSaver) { mutableStateOf(emptyList()) }
    var entries by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryTrigger by remember { mutableStateOf(0) }
    // 已播放进度: 进目录批量查当前页文件记录(披露式), 切目录重查
    var progressMap by remember { mutableStateOf<Map<String, PlaybackRecord>>(emptyMap()) }
    // 播放回来(ON_RESUME)重查进度: 播放记录在播放器里更新了, 回浏览页要刷新进度条
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && playbackRepository != null && entries.isNotEmpty()) {
                scope.launch {
                    delay(300)  // 等播放器 onDispose finishPlayback 写完记录, 再查最新进度
                    val keys = entries.filter { !it.isDirectory }.map { MediaKeys.webDav(conn.id, it.path) }
                    progressMap = runSuspendCatching { playbackRepository.getByMediaKeys(keys) }
                        .getOrDefault(progressMap)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 搜索状态
    var isSearchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchResults = remember { mutableStateListOf<WebDavSearchResult>() }
    var isSearching by remember { mutableStateOf(false) }
    var searchedCount by remember { mutableStateOf(0) }
    var maxResultsReached by remember { mutableStateOf(false) }
    var stopSearch by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val source = remember(conn) { WebDavSource(conn) }
    // 离开 FileBrowser / 切换连接(conn 变)时调 source.close(): HttpClient 进程级共享单例,
    // close() 已 no-op 不释放共享实例; 保留调用满足 AutoCloseable 契约。
    DisposableEffect(source) {
        onDispose { runCatching { source.close() } }
    }

    // 返回: 优先 pop 手动导航历史(跳过 Season 自动进入层), 否则回上级目录, 到根回连接列表。
    // 复刻 NipaPlay _navigateBack: Season 自动进入不入 history, 故返回直达手动导航上一站。
    fun navigateBack() {
        if (pathHistory.isNotEmpty()) {
            currentPath = pathHistory.last()
            pathHistory = pathHistory.dropLast(1)
        } else {
            val segments = currentPath.trim('/').split('/').filter { it.isNotEmpty() }
            if (segments.isNotEmpty()) {
                currentPath = if (segments.size <= 1) "/" else
                    "/" + segments.subList(0, segments.size - 1).joinToString("/") + "/"
            } else {
                onBack()
            }
        }
    }

    // 系统返回: 子目录返回上级, 根目录回连接列表(不直接退出 app)
    AppBackHandler { navigateBack() }

    LaunchedEffect(conn, currentPath, retryTrigger) {
        loading = true
        error = null
        runSuspendCatching {
            val raw = source.listFolder(currentPath)
            val loadedEntries = WebDavFileSorter.sortInBackground(raw, settings.webdavSortPreset)
            // 披露式: 查当前目录所有文件的播放进度(只查本页, 切目录重查)
            val loadedProgress = if (playbackRepository != null) {
                val keys = loadedEntries.filter { !it.isDirectory }
                    .map { MediaKeys.webDav(conn.id, it.path) }
                runSuspendCatching { playbackRepository.getByMediaKeys(keys) }.getOrDefault(emptyMap())
            } else emptyMap()
            loadedEntries to loadedProgress
        }.onSuccess { (loadedEntries, loadedProgress) ->
            entries = loadedEntries
            progressMap = loadedProgress
            loading = false
            // Season 自动进入: 每次列目录后, 若有匹配 pattern 的子文件夹则自动进入
            // (移植 NipaPlay: 无"只一次"限制, 靠 Season 内无匹配子文件夹自然停;
            //  软上限 10 防病态 Season* 嵌套结构导致连续下钻)
            if (settings.webdavAutoEnterSeasonFolder && loadedEntries.isNotEmpty() &&
                currentPath.trim('/').split('/').filter { it.isNotEmpty() }.size < 15) {
                val folders = loadedEntries.filter { it.isDirectory }.map { it.name }
                SeasonFolderMatcher.findMatch(settings.webdavSeasonFolderPattern, folders)?.let { match ->
                    val base = currentPath.trimEnd('/')
                    // 只改 currentPath, 不入 pathHistory: 返回时跳过此层(复刻 NipaPlay)
                    currentPath = "$base/$match/"
                }
            }
        }.onFailure { error = it.message ?: it.toString(); loading = false }
    }

    fun startSearch() {
        val kw = searchQuery.trim()
        if (kw.isBlank()) return
        searchResults.clear()
        searchedCount = 0
        maxResultsReached = false
        stopSearch = false
        isSearching = true
        searchJob?.cancel()
        searchJob = scope.launch {
            source.searchFiles(
                keyword = kw,
                startPath = currentPath,
                scope = settings.webdavSearchScope,
                depthLimit = settings.webdavSearchDepthLimit,
                searchTargets = settings.webdavSearchTargets,
                timeoutSeconds = settings.webdavSearchTimeout.seconds,
                requestIntervalMs = settings.webdavSearchRequestInterval,
                onProgress = { s, _ -> searchedCount = s },
                onResultFound = { r ->
                    if (!stopSearch && !maxResultsReached &&
                        searchResults.size < settings.webdavSearchMaxResults) {
                        searchResults.add(r)
                        if (searchResults.size >= settings.webdavSearchMaxResults) {
                            maxResultsReached = true
                        }
                    }
                },
                onStopRequested = { stopSearch || maxResultsReached },
            )
            isSearching = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchMode) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("搜索文件名") },
                            // 回车/软键盘搜索键触发搜索(否则只点右上角按钮才能搜)
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { startSearch() }),
                        )
                    } else if (settings.webdavShowBreadcrumb) {
                        // 面包屑: [连接名] + currentPath 完整段, 支持多层 defaultDirectory 显示与跳转
                        val segments = listOf(conn.name) +
                            currentPath.trim('/').split('/').filter { it.isNotEmpty() }
                                .map { decodePercent(it) }
                        Breadcrumb(
                            names = segments,
                            onNavigate = { idx ->
                                pathHistory = pathHistory + currentPath
                                currentPath = if (idx == 0) "/" else {
                                    val parts = currentPath.trim('/').split('/').filter { it.isNotEmpty() }
                                    "/" + parts.subList(0, idx).joinToString("/") + "/"
                                }
                            },
                        )
                    } else {
                        Text(decodePercent(currentPath.trimEnd('/').substringAfterLast('/')).ifEmpty { conn.name })
                    }
                },
                navigationIcon = {
                    if (isSearchMode) {
                        IconButton(onClick = {
                            stopSearch = true
                            searchJob?.cancel()
                            isSearching = false
                            isSearchMode = false
                            searchResults.clear()
                            searchQuery = ""
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出搜索")
                        }
                    } else {
                        IconButton(onClick = { navigateBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (isSearchMode) {
                        IconButton(onClick = { startSearch() }) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                    } else if (settings.webdavEnableSearch) {
                        IconButton(onClick = {
                            isSearchMode = true
                            searchResults.clear()
                        }) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (isSearchMode) {
                SearchPanel(
                    results = searchResults,
                    isSearching = isSearching,
                    searchedCount = searchedCount,
                    maxResultsReached = maxResultsReached,
                    onStop = { stopSearch = true },
                    onNavigate = { result ->
                        // 文件夹: 进入其本身; 文件: 跳到父目录(复刻 NipaPlay _navigateToPathFromSearch)
                        val targetPath = if (result.file.isDirectory) {
                            val p = result.file.path
                            if (p.endsWith("/")) p else "$p/"
                        } else {
                            val parent = result.fullPath.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
                            if (parent.endsWith("/")) parent else "$parent/"
                        }
                        // 跳转即离开搜索, 终止搜索协程(用户已不看搜索结果)
                        stopSearch = true
                        searchJob?.cancel()
                        isSearching = false
                        isSearchMode = false
                        searchResults.clear()
                        pathHistory = pathHistory + currentPath
                        currentPath = targetPath
                    },
                    onPlay = { result ->
                        if (!result.file.isDirectory) {
                            scope.launch {
                                val pm = source.resolvePlayMedia(result.file)
                                onPlay(pm)
                            }
                        }
                    },
                )
            } else {
                when {
                    loading -> CircularProgressIndicator()
                    error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("加载失败: $error", color = MaterialTheme.colorScheme.error)
                        Button(onClick = { retryTrigger++ }) { Text("重试") }
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(entries) { entry ->
                            EntryRow(
                                entry = entry,
                                playbackRecord = if (!entry.isDirectory) progressMap[MediaKeys.webDav(conn.id, entry.path)] else null,
                                onClick = {
                                    if (entry.isDirectory) {
                                        // 手动进入子目录: 入历史栈 + 切路径
                                        val base = currentPath.trimEnd('/')
                                        pathHistory = pathHistory + currentPath
                                        currentPath = "$base/${entry.name}/"
                                    } else {
                                        scope.launch {
                                            val pm = source.resolvePlayMedia(entry)
                                            onPlay(pm)
                                        }
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** 搜索面板: 搜索中进度 + 结果列表(跳转/播放)。 */
@Composable
private fun SearchPanel(
    results: List<WebDavSearchResult>,
    isSearching: Boolean,
    searchedCount: Int,
    maxResultsReached: Boolean,
    onStop: () -> Unit,
    onNavigate: (WebDavSearchResult) -> Unit,
    onPlay: (WebDavSearchResult) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    isSearching -> "搜索中... 已扫 $searchedCount 项, 找到 ${results.size} 个"
                    results.isEmpty() -> "未找到结果"
                    else -> "找到 ${results.size} 个结果" + if (maxResultsReached) "(已达上限)" else ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isSearching) TextButton(onClick = onStop) { Text("停止") }
        }
        if (isSearching) {
            CircularProgressIndicator(modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(results) { result ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (result.file.isDirectory) Icons.Filled.Folder else Icons.Filled.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Box(
                            modifier = Modifier.heightIn(min = 40.dp).fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                result.file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val sxxExx = remember(result.file.name) {
                            EpisodeNumberExtractor.formatSxxExx(result.file.name)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (sxxExx != null && !result.file.isDirectory) SxxExxBadge(sxxExx)
                            Text(
                                result.relativePath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    TextButton(onClick = { onNavigate(result) }) { Text("跳转") }
                    if (!result.file.isDirectory) {
                        TextButton(onClick = { onPlay(result) }) { Text("播放") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("还没有 WebDAV 连接", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onAdd, modifier = Modifier.padding(top = 16.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("添加连接", modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun ConnectionList(
    connections: List<WebDavConnection>,
    onSelect: (WebDavConnection) -> Unit,
    onAdd: () -> Unit,
    onRemove: (WebDavConnection) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("添加", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        items(connections) { conn ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !conn.credentialUnavailable) { onSelect(conn) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(conn.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        conn.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    if (conn.credentialUnavailable) {
                        Text(
                            "凭据失效，请删除后重新添加",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                IconButton(onClick = { onRemove(conn) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddConnectionDialog(
    onConfirm: (WebDavConnection, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val form = remember { AddWebDavConnectionState(generateUuid()) }
    val urlValidation = form.urlValidation

    form.pendingCleartextConnection?.let {
        AlertDialog(
            onDismissRequest = form::returnToForm,
            title = { Text("确认使用明文 HTTP") },
            text = {
                Text(
                    "HTTP 不会加密传输。用户名、密码、浏览目录和视频流可能被同一网络中的设备观察或篡改。" +
                        "仅建议在可信局域网或 VPN 内使用。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    form.confirmCleartext()?.let { submission ->
                        onConfirm(submission.connection, submission.allowCleartext)
                    }
                }) {
                    Text("仍然添加")
                }
            },
            dismissButton = {
                TextButton(onClick = form::returnToForm) {
                    Text("返回")
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 WebDAV 连接") },
        text = {
            Column {
                OutlinedTextField(
                    value = form.name, onValueChange = { form.name = it },
                    label = { Text("名称(别名)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = form.baseUrl,
                    onValueChange = { form.baseUrl = it },
                    label = { Text("服务器地址") },
                    singleLine = true,
                    isError = !urlValidation.isValid,
                    supportingText = when {
                        !urlValidation.isValid -> ({ Text(urlValidation.errorMessage.orEmpty()) })
                        urlValidation.requiresCleartextConfirmation -> ({
                            Text("HTTP 为明文连接，保存前需要单独确认风险")
                        })
                        else -> null
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = form.username, onValueChange = { form.username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = form.password, onValueChange = { form.password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    form.requestSubmit()?.let { submission ->
                        onConfirm(submission.connection, submission.allowCleartext)
                    }
                },
                enabled = form.canSubmit,
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 生成 UUID(跨平台: 用 Kotlin 的 toString + 计数, 简单够用; Android 实际可用 java.util.UUID)。 */
private fun generateUuid(): String =
    kotlin.random.Random.nextBytes(16).joinToString("") { "%02x".format(it) }

/** 简易 percent-decode: 解码 PROPFIND href 的编码段(面包屑/标题显示用, 与 WebDavClient.percentDecode 同逻辑)。 */
private fun decodePercent(s: String): String {
    val out = StringBuilder()
    val bytes = ArrayList<Byte>()
    fun flush() {
        if (bytes.isNotEmpty()) {
            out.append(ByteArray(bytes.size) { bytes[it] }.decodeToString())
            bytes.clear()
        }
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length) {
            val b = s.substring(i + 1, i + 3).toIntOrNull(16)
            if (b != null) { bytes.add(b.toByte()); i += 3; continue }
        }
        flush()
        out.append(c)
        i++
    }
    flush()
    return out.toString()
}
