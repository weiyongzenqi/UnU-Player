package io.github.weiyongzenqi.unuplayer.ui.mediaserver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaCatalogSource
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerConnectionService
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerItem
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerItemKind
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerItemsQuery
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerLibrary
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerHttpException
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerImageReference
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerPage
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerPlaybackLocator
import io.github.weiyongzenqi.unuplayer.mediaserver.UNAUTHORIZED_STATUS
import io.github.weiyongzenqi.unuplayer.library.ScrapedImage
import io.github.weiyongzenqi.unuplayer.ui.AppBackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaServerBrowserScreen(
    connectionId: String,
    connectionName: String,
    service: MediaServerConnectionService,
    imageCacheSizeMb: Int,
    onPlay: (MediaServerPlaybackLocator) -> Unit,
    onExit: () -> Unit,
) {
    var catalog by remember(connectionId) { mutableStateOf<MediaCatalogSource?>(null) }
    var path by rememberSaveable(connectionId, stateSaver = BrowserPathSaver) {
        mutableStateOf(emptyList<MediaServerBrowserLevel>())
    }
    var libraries by remember { mutableStateOf(emptyList<MediaServerLibrary>()) }
    var mediaItems by remember { mutableStateOf(emptyList<MediaServerItem>()) }
    var requestedStart by remember { mutableIntStateOf(0) }
    var nextStart by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reloadGeneration by remember { mutableIntStateOf(0) }
    var recursiveFallback by remember { mutableStateOf(false) }

    fun navigateBack() {
        if (path.isEmpty()) {
            onExit()
        } else {
            path = path.dropLast(1)
            requestedStart = 0
        }
    }

    AppBackHandler(onBack = ::navigateBack)

    LaunchedEffect(connectionId, reloadGeneration) {
        if (catalog != null) return@LaunchedEffect
        loading = true
        errorMessage = null
        runSuspendCatching { service.openCatalog(connectionId) }.fold(
            onSuccess = { catalog = it },
            onFailure = {
                errorMessage = browseErrorMessage(it, "无法打开媒体服务器")
                loading = false
            },
        )
    }

    DisposableEffect(catalog) {
        val active = catalog
        onDispose { runCatching { active?.close() } }
    }

    LaunchedEffect(catalog, path, requestedStart, reloadGeneration) {
        val source = catalog ?: return@LaunchedEffect
        val isFirstPage = requestedStart == 0
        if (isFirstPage) loading = true else loadingMore = true
        errorMessage = null

        if (path.isEmpty()) {
            runSuspendCatching { source.listLibraries() }.fold(
                onSuccess = { loaded ->
                    libraries = loaded.distinctBy { it.id }
                    mediaItems = emptyList()
                    hasMore = false
                    nextStart = 0
                },
                onFailure = { errorMessage = browseErrorMessage(it, "媒体库加载失败") },
            )
        } else {
            runSuspendCatching {
                if (isFirstPage) recursiveFallback = false
                val levelId = path.last().id
                val direct = source.listItems(
                    browserItemsQuery(levelId, requestedStart, recursive = recursiveFallback),
                )
                // 真实 Jellyfin(10.11)出现过系列/季子级索引缺失: 直查与官方 /Shows 端点均为空,
                // 只有递归查询能看到条目。首页直查为空时用"递归 + 仅可播类型"兜底一次;
                // 正常空文件夹兜底后仍为空, 行为不变。命中兜底后本层翻页保持递归查询。
                if (isFirstPage && !recursiveFallback && shouldFallbackToRecursive(direct)) {
                    recursiveFallback = true
                    source.listItems(browserItemsQuery(levelId, 0, recursive = true))
                } else {
                    direct
                }
            }.fold(
                onSuccess = { page ->
                    mediaItems = mergeBrowserPageItems(mediaItems, page.items, isFirstPage)
                    nextStart = page.nextStartIndex
                    hasMore = page.hasMore
                },
                onFailure = { errorMessage = browseErrorMessage(it, "目录加载失败") },
            )
        }
        loading = false
        loadingMore = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        path.lastOrNull()?.title ?: connectionName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        requestedStart = 0
                        reloadGeneration++
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        val activeCatalog = catalog
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                errorMessage != null -> ErrorContent(
                    message = errorMessage.orEmpty(),
                    onRetry = { reloadGeneration++ },
                    modifier = Modifier.fillMaxSize(),
                )
                path.isEmpty() && activeCatalog != null -> LibraryList(
                    libraries = libraries,
                    source = activeCatalog,
                    imageCacheSizeMb = imageCacheSizeMb,
                    onOpen = { library ->
                        path = listOf(MediaServerBrowserLevel(library.id, library.name))
                        requestedStart = 0
                    },
                )
                activeCatalog != null -> ItemList(
                    connectionId = connectionId,
                    mediaItems = mediaItems,
                    source = activeCatalog,
                    imageCacheSizeMb = imageCacheSizeMb,
                    hasMore = hasMore,
                    loadingMore = loadingMore,
                    onOpen = { item ->
                        path = path + MediaServerBrowserLevel(item.id, item.name)
                        requestedStart = 0
                    },
                    onPlay = onPlay,
                    onLoadMore = { requestedStart = nextStart },
                )
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun LibraryList(
    libraries: List<MediaServerLibrary>,
    source: MediaCatalogSource,
    imageCacheSizeMb: Int,
    onOpen: (MediaServerLibrary) -> Unit,
) {
    if (libraries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无媒体库") }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(libraries, key = { it.id }) { library ->
            BrowserRow(
                title = library.name,
                subtitle = library.collectionType,
                isFolder = true,
                source = source,
                imageReference = primaryImageReference(source, library.id, library.primaryImageTag),
                imageCacheSizeMb = imageCacheSizeMb,
                onClick = { onOpen(library) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ItemList(
    connectionId: String,
    mediaItems: List<MediaServerItem>,
    source: MediaCatalogSource,
    imageCacheSizeMb: Int,
    hasMore: Boolean,
    loadingMore: Boolean,
    onOpen: (MediaServerItem) -> Unit,
    onPlay: (MediaServerPlaybackLocator) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (mediaItems.isEmpty() && !hasMore) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无内容") }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(mediaItems, key = { it.id }) { item ->
            val canBrowse = item.isFolder || item.kind == MediaServerItemKind.SERIES ||
                item.kind == MediaServerItemKind.SEASON || item.kind == MediaServerItemKind.FOLDER
            val canPlay = !canBrowse && item.kind in PLAYABLE_ITEM_KINDS
            BrowserRow(
                title = item.name,
                subtitle = itemSubtitle(item),
                isFolder = canBrowse,
                source = source,
                imageReference = primaryImageReference(source, item.id, item.primaryImageTag),
                imageCacheSizeMb = imageCacheSizeMb,
                onClick = when {
                    canBrowse -> ({ onOpen(item) })
                    canPlay -> ({
                        onPlay(
                            MediaServerPlaybackLocator(
                                connectionId = connectionId,
                                itemId = item.id,
                                title = item.name,
                                startPositionMs = item.userData
                                    ?.takeUnless { it.played }
                                    ?.playbackPositionMs
                                    ?.coerceAtLeast(0L)
                                    ?: 0L,
                            ),
                        )
                    })
                    else -> null
                },
            )
            HorizontalDivider()
        }
        if (hasMore) {
            item(key = "load-more") {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = onLoadMore, enabled = !loadingMore) {
                        if (loadingMore) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Text("加载更多")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserRow(
    title: String,
    subtitle: String?,
    isFolder: Boolean,
    source: MediaCatalogSource,
    imageReference: MediaServerImageReference?,
    imageCacheSizeMb: Int,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (imageReference == null) {
            Icon(
                if (isFolder) Icons.Filled.Folder else Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            ScrapedImage(
                sourceKind = source.kind,
                libraryId = 0L,
                imagePath = imageReference.cacheKey,
                contentDescription = title,
                modifier = Modifier.width(48.dp).aspectRatio(2f / 3f),
                placeholderText = title,
                imageCacheSizeMb = imageCacheSizeMb,
                downloader = { destination -> source.downloadImage(imageReference, destination) },
                cacheSubdir = MEDIA_SERVER_CACHE_SUBDIR,
                cacheName = "${imageReference.cacheKey}.img",
            )
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("重试") }
    }
}

private fun itemSubtitle(item: MediaServerItem): String? {
    val progress = item.userData?.playedPercentage?.toInt()?.coerceIn(0, 100)
    return when {
        item.seriesName != null && item.indexNumber != null -> "${item.seriesName} · 第 ${item.indexNumber} 集"
        progress != null && progress > 0 -> "已播放 $progress%"
        item.productionYear != null -> item.productionYear.toString()
        else -> item.mediaType
    }
}

private fun primaryImageReference(
    source: MediaCatalogSource,
    itemId: String,
    imageTag: String?,
): MediaServerImageReference? = imageTag?.takeIf { it.isNotBlank() }?.let { tag ->
    source.imageReference(
        itemId = itemId,
        imageTag = tag,
        maxWidth = IMAGE_WIDTH,
        maxHeight = IMAGE_HEIGHT,
    )
}

internal data class MediaServerBrowserLevel(val id: String, val title: String)

private val BrowserPathSaver = Saver<List<MediaServerBrowserLevel>, List<String>>(
    save = { levels -> levels.flatMap { listOf(it.id, it.title) } },
    restore = ::restoreBrowserPath,
)

/** 进程重建的 SavedState 可能损坏为奇数长度，缺配对的残段只能丢弃，不能崩溃。 */
internal fun restoreBrowserPath(values: List<String>): List<MediaServerBrowserLevel> =
    values.chunked(2).mapNotNull { level ->
        if (level.size == 2) MediaServerBrowserLevel(level[0], level[1]) else null
    }

/** 本层浏览查询；递归兜底时只取可播类型，避免把子文件夹重复平铺出来。 */
internal fun browserItemsQuery(parentId: String, startIndex: Int, recursive: Boolean): MediaServerItemsQuery =
    MediaServerItemsQuery(
        parentId = parentId,
        startIndex = startIndex,
        limit = PAGE_SIZE,
        recursive = recursive,
        includeItemTypes = if (recursive) PLAYABLE_ITEM_KINDS else emptySet(),
    )

/** 首页直查为空且无更多页时才兜底；有内容或还有分页说明直查索引正常。 */
internal fun shouldFallbackToRecursive(page: MediaServerPage<MediaServerItem>): Boolean =
    page.items.isEmpty() && !page.hasMore

/** 分页漂移(翻页间服务端增删条目)可产生重复 id；LazyColumn 的 key 冲突会直接抛异常崩页。 */
internal fun mergeBrowserPageItems(
    existing: List<MediaServerItem>,
    pageItems: List<MediaServerItem>,
    isFirstPage: Boolean,
): List<MediaServerItem> =
    (if (isFirstPage) pageItems else existing + pageItems).distinctBy { it.id }

/** 目录源已对 401 做过一次会话重建重试；仍到达 UI 说明存储的 token 已被服务端吊销。 */
internal fun browseErrorMessage(error: Throwable, fallback: String): String =
    if ((error as? MediaServerHttpException)?.statusCode == UNAUTHORIZED_STATUS) {
        "登录已失效，请删除该连接后重新添加"
    } else {
        fallback
    }

private const val PAGE_SIZE = 100
private const val IMAGE_WIDTH = 160
private const val IMAGE_HEIGHT = 240
private const val MEDIA_SERVER_CACHE_SUBDIR = "media-server"
private val PLAYABLE_ITEM_KINDS = setOf(
    MediaServerItemKind.MOVIE,
    MediaServerItemKind.EPISODE,
    MediaServerItemKind.VIDEO,
)
