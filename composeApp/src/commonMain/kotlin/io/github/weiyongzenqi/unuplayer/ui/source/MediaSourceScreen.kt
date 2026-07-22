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
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.library.rememberLocalDirPicker
import io.github.weiyongzenqi.unuplayer.local.LocalDirectory
import io.github.weiyongzenqi.unuplayer.local.LocalDirectoryRepository
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.ui.browser.AddConnectionDialog
import io.github.weiyongzenqi.unuplayer.ui.browser.WebDavBrowserScreen
import io.github.weiyongzenqi.unuplayer.ui.local.LocalBrowserScreen
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository

/**
 * 影视源统一管理页(阶段2a)。
 *
 * 混排所有 WebDAV 连接 + 本地目录, 点击进入各自浏览器(带 initial 锁定, 退出回到本页),
 * 统一"添加源"入口(WebDAV / 本地 / SMB 灰色预留), 删除源。
 *
 * 本阶段尚未接入 tab 路由(阶段2b 接入), 但编译通过、逻辑完整。
 *
 * 注意: 本页不处理"删除源后番剧库的级联失效"(用户删源不删库, 后续库可能失效, 不在本阶段范围)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSourceScreen(
    onPlay: (PlayableMedia) -> Unit,
    webDavRepo: WebDavConnectionRepository,
    localDirRepo: LocalDirectoryRepository,
    settingsRepo: SettingsRepository,
    playbackRepo: PlaybackRecordRepository?,
) {
    val scope = rememberCoroutineScope()
    var webDavConnections by remember { mutableStateOf(emptyList<WebDavConnection>()) }
    var localDirs by remember { mutableStateOf(emptyList<LocalDirectory>()) }
    var loading by remember { mutableStateOf(true) }
    // 当前正在浏览的源; null = 显示源列表。rememberSaveable 保证切后台/重建恢复浏览位置。
    var browsing by rememberSaveable(stateSaver = MediaSourceItemSaver) {
        mutableStateOf<MediaSourceItem?>(null)
    }
    var showAddKindDialog by remember { mutableStateOf(false) }
    var showAddWebDav by remember { mutableStateOf(false) }
    val localPicker = rememberLocalDirPicker()

    // 列表数据加载: 首次进入 + 从浏览器退回(browsing 变 null)时刷新, 反映可能的增删。
    LaunchedEffect(browsing) {
        if (browsing == null) {
            loading = true
            webDavConnections = webDavRepo.loadAll()
            localDirs = localDirRepo.loadAll()
            loading = false
        }
    }

    // 本地目录选择回调: SAF/JFileChooser pickedUri 变化 -> 仓库 add。clear 防止重复触发。
    LaunchedEffect(localPicker.pickedUri) {
        val uri = localPicker.pickedUri ?: return@LaunchedEffect
        localDirs = localDirRepo.add(uri)
        localPicker.clear()
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
            else -> { browsing = null }  // SMB/FTP 等未实现, 回列表
        }
        return
    }

    val items = webDavConnections.map {
        MediaSourceItem(MediaSourceKind.WEBDAV, it.id, it.name, it.baseUrl)
    } + localDirs.map {
        MediaSourceItem(MediaSourceKind.LOCAL, it.uri, it.name, it.uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("影视源") },
                actions = {
                    IconButton(onClick = { showAddKindDialog = true }) {
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
                items.isEmpty() -> EmptyState(
                    onAdd = { showAddKindDialog = true },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(items) { item ->
                        SourceRow(
                            item = item,
                            onClick = { browsing = item },
                            onRemove = {
                                scope.launch {
                                    if (item.kind == MediaSourceKind.WEBDAV) {
                                        webDavConnections = webDavRepo.remove(item.id)
                                    } else {
                                        localDirs = localDirRepo.remove(item.id)
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
            onDismiss = { showAddKindDialog = false },
        )
    }
    if (showAddWebDav) {
        AddConnectionDialog(
            onConfirm = { conn, allowCleartext ->
                scope.launch {
                    webDavConnections = webDavRepo.add(conn, allowCleartext = allowCleartext)
                }
                showAddWebDav = false
            },
            onDismiss = { showAddWebDav = false },
        )
    }
}

/** 列表项: 统一表示一个影视源(WebDAV 连接或本地目录)。 */
private data class MediaSourceItem(
    val kind: MediaSourceKind,
    val id: String,
    val name: String,
    val subtitle: String,
)

/** MediaSourceItem? 的 rememberSaveable Saver(枚举名 + 三个字符串)。 */
private val MediaSourceItemSaver = Saver<MediaSourceItem?, List<String>>(
    save = {
        if (it == null) emptyList()
        else listOf(it.kind.name, it.id, it.name, it.subtitle)
    },
    restore = {
        if (it.isEmpty()) null
        else MediaSourceItem(MediaSourceKind.valueOf(it[0]), it[1], it[2], it[3])
    },
)

@Composable
private fun SourceRow(
    item: MediaSourceItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.kind == MediaSourceKind.WEBDAV) Icons.Filled.Movie else Icons.Filled.Folder,
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
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "删除")
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
        Text("暂无影视源", style = MaterialTheme.typography.titleMedium)
        Text(
            "点右上角添加 WebDAV 连接或本地目录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
        )
        Button(onClick = onAdd, modifier = Modifier.padding(top = 16.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("添加源", modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** 添加源类型选择对话框: WebDAV / 本地 / SMB(灰色预留)。 */
@Composable
private fun AddSourceKindDialog(
    onPickWebDav: () -> Unit,
    onPickLocal: () -> Unit,
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
                TextButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("SMB(即将支持)")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
