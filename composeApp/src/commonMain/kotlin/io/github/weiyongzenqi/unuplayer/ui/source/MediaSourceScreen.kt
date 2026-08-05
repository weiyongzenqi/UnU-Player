package io.github.weiyongzenqi.unuplayer.ui.source

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.library.rememberLocalDirPicker
import io.github.weiyongzenqi.unuplayer.local.LocalDirectory
import io.github.weiyongzenqi.unuplayer.local.LocalDirectoryRepository
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerConnectionService
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerConnectionSummary
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerPlaybackLocator
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerVendor
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.ui.browser.AddConnectionDialog
import io.github.weiyongzenqi.unuplayer.ui.browser.WebDavBrowserScreen
import io.github.weiyongzenqi.unuplayer.ui.local.LocalBrowserScreen
import io.github.weiyongzenqi.unuplayer.ui.mediaserver.AddMediaServerConnectionDialog
import io.github.weiyongzenqi.unuplayer.ui.mediaserver.MediaServerBrowserScreen
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 影视源统一管理页(阶段2a)。
 *
 * 混排 WebDAV 连接、本地目录与平台注入的媒体服务器连接，点击进入各自浏览器，统一添加和删除。
 *
 * 注意: 本页不处理"删除源后番剧库的级联失效"(用户删源不删库, 后续库可能失效, 不在本阶段范围)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSourceScreen(
    onPlay: (PlayableMedia) -> Unit,
    onPlayMediaServer: (MediaServerPlaybackLocator) -> Unit,
    webDavRepo: WebDavConnectionRepository,
    localDirRepo: LocalDirectoryRepository,
    settingsRepo: SettingsRepository,
    playbackRepo: PlaybackRecordRepository?,
    mediaServerService: MediaServerConnectionService? = null,
    supportedMediaServerVendors: Set<MediaServerVendor> = emptySet(),
    mediaServerImageCacheSizeMb: Int = 200,
) {
    val enabledMediaServerVendors = supportedMediaServerVendors.takeIf { mediaServerService != null }.orEmpty()
    val scope = rememberCoroutineScope()
    val sourceOperationMutex = remember { Mutex() }
    var sources by remember { mutableStateOf(MediaSourceCollections()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var sourceMutationInProgress by remember { mutableStateOf(false) }
    var reloadGeneration by remember { mutableStateOf(0) }
    // 当前正在浏览的源; null = 显示源列表。rememberSaveable 保证切后台/重建恢复浏览位置。
    var browsing by rememberSaveable(stateSaver = MediaSourceItemSaver) {
        mutableStateOf<MediaSourceItem?>(null)
    }
    var showAddKindDialog by remember { mutableStateOf(false) }
    var showAddWebDav by remember { mutableStateOf(false) }
    var editingWebDav by remember { mutableStateOf<WebDavConnection?>(null) }
    var addMediaServerVendor by remember { mutableStateOf<MediaServerVendor?>(null) }
    val localPicker = rememberLocalDirPicker()

    // 列表数据加载: 首次进入 + 从浏览器退回(browsing 变 null)时刷新, 反映可能的增删。
    LaunchedEffect(browsing, reloadGeneration) {
        if (browsing == null) {
            loading = true
            loadError = null
            runSuspendCatching {
                sourceOperationMutex.withLock {
                    sources = loadMediaSourceCollections(
                        webDavRepo,
                        localDirRepo,
                        mediaServerService,
                        enabledMediaServerVendors,
                    )
                }
            }.fold(
                onSuccess = { loadError = null },
                onFailure = { loadError = "影视源加载失败" },
            )
            loading = false
        }
    }

    // 本地目录选择回调: SAF/JFileChooser pickedUri 变化 -> 仓库 add。clear 防止重复触发。
    LaunchedEffect(localPicker.pickedUri) {
        val uri = localPicker.pickedUri ?: return@LaunchedEffect
        sourceMutationInProgress = true
        try {
            runSuspendCatching {
                sourceOperationMutex.withLock {
                    sources = sources.copy(localDirectories = localDirRepo.add(uri))
                }
            }.fold(
                onSuccess = { loadError = null },
                onFailure = { loadError = "本地目录添加失败" },
            )
        } finally {
            sourceMutationInProgress = false
            localPicker.clear()
        }
    }

    // 锁定浏览某源: 渲染对应浏览器(initial=该源 id, onExit=回本页); 否则渲染源列表。
    val browsingItem = browsing
    if (browsingItem != null) {
        when (browsingItem.kind) {
            MediaSourceKind.WEBDAV -> WebDavBrowserScreen(
                onPlay = onPlay,
                repository = webDavRepo,
                settingsRepository = settingsRepo,
                playbackRepository = playbackRepo,
                initialConnectionId = browsingItem.id,
                onExit = { browsing = null },
            )
            MediaSourceKind.LOCAL -> LocalBrowserScreen(
                onPlay = onPlay,
                repository = localDirRepo,
                initialUri = browsingItem.id,
                onExit = { browsing = null },
            )
            MediaSourceKind.JELLYFIN, MediaSourceKind.EMBY -> {
                val service = mediaServerService
                if (service == null) {
                    LaunchedEffect(browsingItem) { browsing = null }
                } else {
                    MediaServerBrowserScreen(
                        connectionId = browsingItem.id,
                        connectionName = browsingItem.name,
                        service = service,
                        imageCacheSizeMb = mediaServerImageCacheSizeMb,
                        onPlay = onPlayMediaServer,
                        onExit = { browsing = null },
                    )
                }
            }
            else -> LaunchedEffect(browsingItem) { browsing = null }
        }
        return
    }

    val items = sources.webDavConnections.map {
        MediaSourceItem(MediaSourceKind.WEBDAV, it.id, it.name, it.baseUrl)
    } + sources.localDirectories.map {
        MediaSourceItem(MediaSourceKind.LOCAL, it.uri, it.name, it.uri)
    } + sources.mediaServerConnections.map {
        MediaSourceItem(
            kind = it.vendor.sourceKind,
            id = it.id,
            name = it.name,
            subtitle = it.baseUrl,
            credentialUnavailable = it.credentialUnavailable,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("影视源") },
                actions = {
                    IconButton(
                        onClick = { showAddKindDialog = true },
                        enabled = !loading && loadError == null && !sourceMutationInProgress,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "添加源")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                loadError != null -> SourceLoadError(
                    message = loadError.orEmpty(),
                    onRetry = { reloadGeneration++ },
                    modifier = Modifier.fillMaxSize(),
                )
                items.isEmpty() -> EmptyState(
                    onAdd = { showAddKindDialog = true },
                    enabled = !sourceMutationInProgress,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(items) { item ->
                        SourceRow(
                            item = item,
                            enabled = !sourceMutationInProgress,
                            onClick = { browsing = item },
                            onEdit = if (item.kind == MediaSourceKind.WEBDAV) {
                                { editingWebDav = sources.webDavConnections.firstOrNull { it.id == item.id } }
                            } else null,
                            onRemove = {
                                if (!sourceMutationInProgress) {
                                    sourceMutationInProgress = true
                                    scope.launch {
                                        try {
                                            runSuspendCatching {
                                                sourceOperationMutex.withLock {
                                                    when (item.kind) {
                                                        MediaSourceKind.WEBDAV -> sources = sources.copy(
                                                            webDavConnections = webDavRepo.remove(item.id),
                                                        )
                                                        MediaSourceKind.LOCAL -> sources = sources.copy(
                                                            localDirectories = localDirRepo.remove(item.id),
                                                        )
                                                        MediaSourceKind.JELLYFIN, MediaSourceKind.EMBY -> sources =
                                                            sources.copy(
                                                                mediaServerConnections = requireNotNull(
                                                                    mediaServerService,
                                                                ).remove(item.id),
                                                            )
                                                        else -> Unit
                                                    }
                                                }
                                            }.fold(
                                                onSuccess = { loadError = null },
                                                onFailure = { loadError = "影视源删除失败" },
                                            )
                                        } finally {
                                            sourceMutationInProgress = false
                                        }
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

    if (showAddKindDialog) {
        AddSourceKindDialog(
            onPickWebDav = { showAddKindDialog = false; showAddWebDav = true },
            onPickLocal = { showAddKindDialog = false; localPicker.pick() },
            onPickJellyfin = if (MediaServerVendor.JELLYFIN !in enabledMediaServerVendors) null else ({
                showAddKindDialog = false
                addMediaServerVendor = MediaServerVendor.JELLYFIN
            }),
            onPickEmby = if (MediaServerVendor.EMBY !in enabledMediaServerVendors) null else ({
                showAddKindDialog = false
                addMediaServerVendor = MediaServerVendor.EMBY
            }),
            onDismiss = { showAddKindDialog = false },
        )
    }
    if (showAddWebDav) {
        AddConnectionDialog(
            onConfirm = { conn, allowCleartext ->
                if (!sourceMutationInProgress) {
                    sourceMutationInProgress = true
                    scope.launch {
                        try {
                            runSuspendCatching {
                                sourceOperationMutex.withLock {
                                    sources = sources.copy(
                                        webDavConnections = webDavRepo.add(
                                            conn,
                                            allowCleartext = allowCleartext,
                                        ),
                                    )
                                }
                            }.fold(
                                onSuccess = { loadError = null },
                                onFailure = { loadError = "WebDAV 添加失败" },
                            )
                        } finally {
                            sourceMutationInProgress = false
                        }
                    }
                }
                showAddWebDav = false
            },
            onDismiss = { showAddWebDav = false },
        )
    }
    editingWebDav?.let { connection ->
        AddConnectionDialog(
            initialConnection = connection,
            onConfirm = { conn, allowCleartext ->
                if (!sourceMutationInProgress) {
                    sourceMutationInProgress = true
                    scope.launch {
                        try {
                            runSuspendCatching {
                                sourceOperationMutex.withLock {
                                    sources = sources.copy(
                                        webDavConnections = webDavRepo.update(
                                            conn,
                                            allowCleartext = allowCleartext,
                                        ),
                                    )
                                }
                            }.fold(
                                onSuccess = { loadError = null },
                                onFailure = { loadError = "WebDAV 编辑失败" },
                            )
                        } finally {
                            sourceMutationInProgress = false
                            editingWebDav = null
                        }
                    }
                }
            },
            onDismiss = { editingWebDav = null },
        )
    }
    val vendorToAdd = addMediaServerVendor
    if (vendorToAdd != null) {
        val service = mediaServerService
        if (service != null) {
            AddMediaServerConnectionDialog(
                vendor = vendorToAdd,
                connect = { submission ->
                    sourceMutationInProgress = true
                    try {
                        sourceOperationMutex.withLock {
                            service.connect(
                                vendor = submission.vendor,
                                name = submission.name,
                                baseUrl = submission.baseUrl,
                                username = submission.username,
                                password = submission.password,
                                allowCleartext = submission.allowCleartext,
                            ).also { summary ->
                                sources = sources.copy(
                                    mediaServerConnections =
                                        (sources.mediaServerConnections + summary).distinctBy { it.id },
                                )
                            }
                        }
                    } finally {
                        sourceMutationInProgress = false
                    }
                },
                onConnected = {
                    loadError = null
                    addMediaServerVendor = null
                },
                onDismiss = { addMediaServerVendor = null },
            )
        }
    }
}

private data class MediaSourceCollections(
    val webDavConnections: List<WebDavConnection> = emptyList(),
    val localDirectories: List<LocalDirectory> = emptyList(),
    val mediaServerConnections: List<MediaServerConnectionSummary> = emptyList(),
)

private suspend fun loadMediaSourceCollections(
    webDavRepo: WebDavConnectionRepository,
    localDirRepo: LocalDirectoryRepository,
    mediaServerService: MediaServerConnectionService?,
    supportedMediaServerVendors: Set<MediaServerVendor>,
) = MediaSourceCollections(
    webDavConnections = webDavRepo.loadAll(),
    localDirectories = localDirRepo.loadAll(),
    mediaServerConnections = mediaServerService?.listConnections().orEmpty()
        .filter { it.vendor in supportedMediaServerVendors },
)

/** 列表项: 统一表示一个影视源(WebDAV 连接或本地目录)。 */
private data class MediaSourceItem(
    val kind: MediaSourceKind,
    val id: String,
    val name: String,
    val subtitle: String,
    val credentialUnavailable: Boolean = false,
)

/** MediaSourceItem? 的 rememberSaveable Saver。 */
private val MediaSourceItemSaver = Saver<MediaSourceItem?, List<String>>(
    save = {
        if (it == null) emptyList()
        else listOf(it.kind.name, it.id, it.name, it.subtitle, it.credentialUnavailable.toString())
    },
    restore = {
        if (it.isEmpty()) null
        else MediaSourceItem(
            kind = MediaSourceKind.valueOf(it[0]),
            id = it[1],
            name = it[2],
            subtitle = it[3],
            credentialUnavailable = it.getOrNull(4)?.toBooleanStrictOrNull() ?: false,
        )
    },
)

@Composable
private fun SourceRow(
    item: MediaSourceItem,
    enabled: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && !item.credentialUnavailable, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.kind == MediaSourceKind.LOCAL) Icons.Filled.Folder else Icons.Filled.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.credentialUnavailable) {
                Text(
                    "凭据失效，请编辑并重新输入密码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑")
            }
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Filled.Delete, contentDescription = "删除")
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("暂无影视源", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onAdd, enabled = enabled, modifier = Modifier.padding(top = 16.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("添加源", modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun SourceLoadError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("重试") }
    }
}

/** 添加源类型选择对话框。 */
@Composable
private fun AddSourceKindDialog(
    onPickWebDav: () -> Unit,
    onPickLocal: () -> Unit,
    onPickJellyfin: (() -> Unit)?,
    onPickEmby: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加影视源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onPickWebDav, modifier = Modifier.fillMaxWidth()) {
                    Text("WebDAV 连接")
                }
                TextButton(onClick = onPickLocal, modifier = Modifier.fillMaxWidth()) {
                    Text("本地目录")
                }
                if (onPickJellyfin != null) {
                    TextButton(onClick = onPickJellyfin, modifier = Modifier.fillMaxWidth()) {
                        Text("Jellyfin")
                    }
                }
                if (onPickEmby != null) {
                    TextButton(onClick = onPickEmby, modifier = Modifier.fillMaxWidth()) {
                        Text("Emby")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
