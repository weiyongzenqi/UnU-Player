package io.github.weiyongzenqi.unuplayer.ui.local

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Movie
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.MediaKeys
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.local.LocalDirectory
import io.github.weiyongzenqi.unuplayer.local.LocalDirectoryRepository
import io.github.weiyongzenqi.unuplayer.local.LocalSource
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepositoryImpl
import io.github.weiyongzenqi.unuplayer.ui.browser.Breadcrumb
import io.github.weiyongzenqi.unuplayer.ui.browser.EntryRow

/** 面包屑一段: 目录路径 + 显示名(SAF 的 path 是 content URI, 无法从路径解析名字, 需额外存)。 */
private data class Crumb(val path: String, val name: String)

private val CrumbListSaver = listSaver<List<Crumb>, String>(
    save = { it.flatMap { listOf(it.path, it.name) } },
    restore = { it.chunked(2).map { Crumb(it[0], it[1]) } },
)

private val LocalDirectorySaver = Saver<LocalDirectory?, List<String>>(
    save = { if (it == null) emptyList() else listOf(it.uri, it.name) },
    restore = { if (it.isEmpty()) null else LocalDirectory(it[0], it[1]) },
)

private val LocalDirectoryListSaver = listSaver<List<LocalDirectory>, String>(
    save = { it.flatMap { listOf(it.uri, it.name) } },
    restore = { it.chunked(2).map { LocalDirectory(it[0], it[1]) } },
)

/**
 * 本地文件浏览 tab。
 *
 * 用 SAF(Storage Access Framework)访问本地目录:
 * - 添加目录: ACTION_OPEN_DOCUMENT_TREE 拿 tree URI + takePersistableUriPermission
 * - 遍历: DocumentFile.listFiles()
 * - 播放: LocalSource 保留 content URI，MpvPlayerEngine 每次 load 时转 fdclose://
 *
 * 无需 MANAGE_EXTERNAL_STORAGE 敏感权限。重启后目录仍在(权限已持久化)。
 *
 * 浏览状态(directories/selectedDir/pathStack)用 rememberSaveable 持久化,
 * 切 tab 或进播放器回来恢复到原位置, 不闪现根目录/空态。
 *
 * 三态(同 WebDav 浏览器): 无目录→空态; 有目录未选→目录列表; 已选→文件树。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun LocalBrowserScreen(
    onPlay: (PlayableMedia) -> Unit,
    repository: LocalDirectoryRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var directories by rememberSaveable(stateSaver = LocalDirectoryListSaver) {
        mutableStateOf(emptyList())
    }
    var selectedDir by rememberSaveable(stateSaver = LocalDirectorySaver) {
        mutableStateOf<LocalDirectory?>(null)
    }
    var pathStack by rememberSaveable(stateSaver = CrumbListSaver) {
        mutableStateOf(emptyList<Crumb>())
    }

    // SAF 目录选择 launcher
    val pickDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                directories = repository.add(uri.toString())
            }
        }
    }

    // 目录列表加载标记: 首次冷启动显示 loading 而非"还没有添加本地目录"空态,
    // 避免切 tab 回来闪现。切回 tab 时 directories 已 saveable 恢复非空→初值 false 不闪。
    var dirLoading by remember { mutableStateOf(directories.isEmpty()) }

    // 首次加载; 已有(saveable 恢复)则不重载, 避免切 tab 回来闪现空态
    LaunchedEffect(Unit) {
        if (directories.isEmpty()) {
            directories = repository.loadAll()
        }
        dirLoading = false
    }

    // 系统返回: 子目录返回上级, 根目录回目录列表(不直接退出 app)
    BackHandler(enabled = selectedDir != null) {
        if (pathStack.size > 1) {
            pathStack = pathStack.dropLast(1)
        } else {
            selectedDir = null
            pathStack = emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectedDir != null && pathStack.isNotEmpty()) {
                        Breadcrumb(
                            names = pathStack.map { it.name },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                selectedDir != null -> LocalFileTree(
                    dir = selectedDir!!,
                    currentPath = pathStack.last().path,
                    onEnter = { crumb -> pathStack = pathStack + crumb },
                    onPlay = onPlay,
                    context = context,
                )
                dirLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
                directories.isEmpty() -> EmptyState(
                    onAdd = { pickDirLauncher.launch(null) },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> DirectoryList(
                    directories = directories,
                    onSelect = {
                        selectedDir = it
                        pathStack = listOf(Crumb(it.uri, it.name))
                    },
                    onAdd = { pickDirLauncher.launch(null) },
                    onRemove = { dir ->
                        scope.launch { directories = repository.remove(dir.uri) }
                    },
                )
            }
        }
    }
}

/** 进入选定目录后的文件树浏览。 */
@Composable
private fun LocalFileTree(
    dir: LocalDirectory,
    currentPath: String,
    onEnter: (Crumb) -> Unit,
    onPlay: (PlayableMedia) -> Unit,
    context: android.content.Context,
) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val source = remember(dir, currentPath) { LocalSource(context, Uri.parse(dir.uri), dir.name) }
    val recordRepo = remember { PlaybackRecordRepositoryImpl.get(context) }
    // 已播放进度: 进目录批量查当前页文件记录(披露式), 切目录重查
    var progressMap by remember { mutableStateOf<Map<String, PlaybackRecord>>(emptyMap()) }
    // 播放回来(ON_RESUME)重查进度: 播放记录在播放器里更新了, 回浏览页要刷新进度条
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && entries.isNotEmpty()) {
                scope.launch {
                    delay(300)  // 等播放器 onDispose finishPlayback 写完记录, 再查最新进度
                    val keys = entries.filter { !it.isDirectory }.map { MediaKeys.local(it.path) }
                    progressMap = runSuspendCatching { recordRepo.getByMediaKeys(keys) }
                        .getOrDefault(progressMap)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(dir, currentPath) {
        loading = true
        error = null
        runSuspendCatching { source.listFolder(currentPath) }
            .onSuccess { loadedEntries ->
                // 披露式: 查当前目录所有文件的播放进度
                val keys = loadedEntries.filter { !it.isDirectory }.map { MediaKeys.local(it.path) }
                val loadedProgress = runSuspendCatching { recordRepo.getByMediaKeys(keys) }
                    .getOrDefault(progressMap)
                entries = loadedEntries
                progressMap = loadedProgress
                loading = false
            }
            .onFailure { error = it.message ?: it.toString(); loading = false }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator()
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("加载失败: $error", color = MaterialTheme.colorScheme.error)
            }
            entries.isEmpty() -> Text("空目录")
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries) { entry ->
                    EntryRow(
                        entry = entry,
                        playbackRecord = if (!entry.isDirectory) progressMap[MediaKeys.local(entry.path)] else null,
                        onClick = {
                            if (entry.isDirectory) {
                                onEnter(Crumb(entry.path, entry.name))
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

@Composable
private fun EmptyState(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("还没有添加本地目录", style = MaterialTheme.typography.titleMedium)
        Text(
            "通过系统文件选择器授权一个目录, 无需存储权限",
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
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
