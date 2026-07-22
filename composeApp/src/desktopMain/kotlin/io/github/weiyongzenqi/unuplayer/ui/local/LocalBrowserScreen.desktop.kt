package io.github.weiyongzenqi.unuplayer.ui.local

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.library.rememberLocalDirPicker
import io.github.weiyongzenqi.unuplayer.local.DesktopLocalSource
import io.github.weiyongzenqi.unuplayer.local.LocalDirectory
import io.github.weiyongzenqi.unuplayer.local.LocalDirectoryRepository
import io.github.weiyongzenqi.unuplayer.ui.browser.Breadcrumb
import io.github.weiyongzenqi.unuplayer.ui.browser.EntryRow
import java.io.File

/**
 * 桌面 actual: 本地文件浏览(java.io.File)。
 *
 * 对应 androidMain 的 LocalBrowserScreen(SAF DocumentFile)。桌面用 [rememberLocalDirPicker]
 * (JFileChooser)选目录, [DesktopLocalSource] 遍历文件系统。复用 commonMain 的
 * [EntryRow]/[Breadcrumb] 保持列表项/面包屑视觉一致。
 *
 * 三态(同 android): 无目录->空态; 有目录未选->目录列表; 已选->文件树。
 * 浏览状态用 remember(切 tab 回来重新加载, 首版不持久化; 后续可加 rememberSaveable)。
 * 播放进度: 桌面 playbackRepository 未接入, 暂不显示(EntryRow playbackRecord 默认 null)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun LocalBrowserScreen(
    onPlay: (PlayableMedia) -> Unit,
    repository: LocalDirectoryRepository,
    initialUri: String?,
    onExit: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val picker = rememberLocalDirPicker()
    var directories by remember { mutableStateOf(emptyList<LocalDirectory>()) }
    var selectedDir by remember { mutableStateOf<LocalDirectory?>(null) }
    var pathStack by remember { mutableStateOf(emptyList<String>()) }  // 绝对路径栈
    var dirLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        directories = repository.loadAll()
        // initialUri 非 null(MediaSourceScreen 嵌入模式): 初次进入直接浏览该目录而非目录选择列表
        if (selectedDir == null && initialUri != null) {
            val found = directories.firstOrNull { it.uri == initialUri }
            if (found != null) {
                selectedDir = found
                pathStack = listOf(found.uri)
            } else if (onExit != null) {
                // initial 指定目录已不存在(被删) -> 退回调用方
                onExit()
            }
        }
        dirLoading = false
    }

    // 选了目录 -> add(pickedUri 变化触发; 首次 null 跳过)
    LaunchedEffect(picker.pickedUri) {
        val uri = picker.pickedUri ?: return@LaunchedEffect
        directories = repository.add(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectedDir != null && pathStack.isNotEmpty()) {
                        Breadcrumb(
                            names = pathStack.map { File(it).name },
                            onNavigate = { idx -> pathStack = pathStack.subList(0, idx + 1).toList() },
                        )
                    } else {
                        Text("本地文件")
                    }
                },
                navigationIcon = {
                    if (selectedDir != null) {
                        IconButton(onClick = {
                            if (pathStack.size > 1) {
                                pathStack = pathStack.dropLast(1)
                            } else if (initialUri != null && onExit != null) {
                                onExit()
                            } else {
                                selectedDir = null
                                pathStack = emptyList()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                selectedDir != null -> LocalFileTree(
                    rootPath = selectedDir!!.uri,
                    currentPath = pathStack.last(),
                    onEnter = { pathStack = pathStack + it },
                    onPlay = onPlay,
                )
                dirLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                directories.isEmpty() -> EmptyState(onAdd = { picker.pick() })
                else -> DirectoryList(
                    directories = directories,
                    onSelect = {
                        selectedDir = it
                        pathStack = listOf(it.uri)
                    },
                    onAdd = { picker.pick() },
                    onRemove = { dir -> scope.launch { directories = repository.remove(dir.uri) } },
                )
            }
        }
    }
}

/** 进入选定目录后的文件树浏览。 */
@Composable
private fun LocalFileTree(
    rootPath: String,
    currentPath: String,
    onEnter: (String) -> Unit,
    onPlay: (PlayableMedia) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var entries by remember(currentPath) { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var loading by remember(currentPath) { mutableStateOf(true) }
    var error by remember(currentPath) { mutableStateOf<String?>(null) }
    val source = remember(rootPath) { DesktopLocalSource(rootPath) }

    LaunchedEffect(currentPath) {
        loading = true
        error = null
        runSuspendCatching { source.listFolder(currentPath) }
            .onSuccess { entries = it; loading = false }
            .onFailure { error = it.message ?: it.toString(); loading = false }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator()
            error != null -> Text("加载失败: $error", color = MaterialTheme.colorScheme.error)
            entries.isEmpty() -> Text("空目录")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(entries) { entry ->
                    EntryRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDirectory) {
                                onEnter(entry.path)
                            } else {
                                scope.launch { onPlay(source.resolvePlayMedia(entry)) }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("还没有添加本地目录", style = MaterialTheme.typography.titleMedium)
        Text(
            "选择一个目录, 桌面版直接读取文件系统(无需权限)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
        )
        Button(onClick = onAdd, modifier = Modifier.padding(top = 16.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("添加目录", modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun DirectoryList(
    directories: List<LocalDirectory>,
    onSelect: (LocalDirectory) -> Unit,
    onAdd: () -> Unit,
    onRemove: (LocalDirectory) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("添加目录", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        items(directories) { dir ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(dir) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    dir.name,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                IconButton(onClick = { onRemove(dir) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "移除")
                }
            }
            HorizontalDivider()
        }
    }
}
