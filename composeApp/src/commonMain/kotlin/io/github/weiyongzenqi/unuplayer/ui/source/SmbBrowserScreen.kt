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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.MediaKeys
import io.github.weiyongzenqi.unuplayer.core.media.resolvePlayMediaWithQueue
import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.MediaSourceFactory
import io.github.weiyongzenqi.unuplayer.library.ScanMode
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SMB 浏览器只依赖 MediaSourceFactory，避免 commonMain 直接引用 Android SMBJ 类型。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbBrowserScreen(
    onPlay: (PlayableMedia) -> Unit,
    connection: SmbConnection,
    mediaSourceFactory: MediaSourceFactory,
    playbackRepository: PlaybackRecordRepository? = null,
    onExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var currentPath by rememberSaveable(connection.id) { mutableStateOf("/") }
    var source by remember(connection.id) { mutableStateOf<MediaSource?>(null) }
    var entries by remember { mutableStateOf<List<MediaEntry>?>(null) }
    var progressMap by remember { mutableStateOf<Map<String, PlaybackRecord>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var retryTrigger by remember { mutableStateOf(0) }
    val sourceSlot = remember(connection.id) { OwnedMediaSourceSlot() }

    LaunchedEffect(connection.id, retryTrigger) {
        source = null
        entries = null
        error = null
        sourceSlot.clear()
        runSuspendCatching {
            // NonCancellable 只覆盖“创建后交给唯一所有者”这一小段；若页面已释放，slot 会立即关闭新 source。
            withContext(NonCancellable + Dispatchers.IO) {
                mediaSourceFactory.create(
                    LibraryConfig(
                        id = 0L,
                        name = connection.name,
                        sourceKind = io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind.SMB,
                        connectionId = connection.id,
                        localUri = null,
                        rootPath = "/",
                        scanDepth = 0,
                        lastScannedAt = null,
                        createdAt = 0L,
                        scanMode = ScanMode.ANCHOR,
                    ),
                )
            } ?: error("SMB 来源不可用")
        }.fold(
            onSuccess = { created ->
                if (sourceSlot.replace(created)) source = created
            },
            onFailure = { error = "SMB 来源创建失败" },
        )
    }
    DisposableEffect(sourceSlot) {
        onDispose { sourceSlot.close() }
    }

    LaunchedEffect(source, currentPath, retryTrigger) {
        val currentSource = source ?: return@LaunchedEffect
        entries = null
        error = null
        runSuspendCatching {
            withContext(Dispatchers.IO) { currentSource.listFolderAll(currentPath) }
        }.fold(
            onSuccess = { list ->
                entries = list
                progressMap = if (playbackRepository == null) emptyMap() else runSuspendCatching {
                    playbackRepository.getByMediaKeys(
                        list.filterNot { it.isDirectory }.map { MediaKeys.smb(connection.id, it.path) },
                    )
                }.getOrDefault(emptyMap())
            },
            onFailure = { error = "SMB 目录读取失败" },
        )
    }

    fun navigateBack() {
        val trimmed = currentPath.trim('/').trim('\\')
        if (trimmed.isEmpty()) onExit() else {
            val parent = trimmed.substringBeforeLast('/', missingDelimiterValue = "")
            currentPath = if (parent.isEmpty()) "/" else "/$parent/"
        }
    }

    io.github.weiyongzenqi.unuplayer.ui.AppBackHandler { navigateBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = ::navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text(connection.name) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                error != null -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                    Button(onClick = { retryTrigger++ }) { Text("重试") }
                }
                entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                entries.orEmpty().isEmpty() -> Text("（空）", modifier = Modifier.padding(16.dp))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries.orEmpty().filter { it.isDirectory }.sortedBy { it.name.lowercase() }) { entry ->
                        SmbEntryRow(entry, null) { currentPath = joinPath(currentPath, entry.name) }
                        HorizontalDivider()
                    }
                    val videos = entries.orEmpty().filter { !it.isDirectory && isVideoFile(it.name) }.sortedBy { it.name.lowercase() }
                    items(videos) { entry ->
                        SmbEntryRow(entry, progressMap[MediaKeys.smb(connection.id, entry.path)]) {
                            if (!playing) {
                                playing = true
                                scope.launch {
                                    try {
                                        runSuspendCatching {
                                            withContext(Dispatchers.IO) {
                                                requireNotNull(source).resolvePlayMediaWithQueue(entry, videos)
                                            }
                                        }.onSuccess(onPlay).onFailure { error = "SMB 媒体打开失败" }
                                    } finally {
                                        playing = false
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SmbEntryRow(entry: MediaEntry, record: PlaybackRecord?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Movie, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (record != null && record.duration_ms > 0) {
                Text(
                    "${(record.watch_progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun joinPath(parent: String, child: String): String =
    "/" + listOf(parent.trim('/', '\\'), child.trim('/', '\\')).filter { it.isNotEmpty() }.joinToString("/") + "/"

private fun isVideoFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("mkv", "mp4", "avi", "mov", "webm", "ts", "m2ts", "wmv")

/** Compose 页面持有的唯一 MediaSource 所有权槽；关闭后到达的迟到实例会被立即回收。 */
internal class OwnedMediaSourceSlot {
    private var closed = false
    var current: MediaSource? = null
        private set

    fun replace(next: MediaSource): Boolean {
        if (closed) {
            next.close()
            return false
        }
        val previous = current
        current = next
        if (previous !== next) previous?.close()
        return true
    }

    fun clear() {
        val previous = current
        current = null
        previous?.close()
    }

    fun close() {
        if (closed) return
        closed = true
        clear()
    }
}
