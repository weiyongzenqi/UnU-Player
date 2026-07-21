package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import io.github.weiyongzenqi.unuplayer.core.platform.AppNotif
import io.github.weiyongzenqi.unuplayer.ui.AppBackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.MediaSourceCache
import io.github.weiyongzenqi.unuplayer.library.ScanConfig
import io.github.weiyongzenqi.unuplayer.library.ScrapedEpisode
import io.github.weiyongzenqi.unuplayer.library.ScrapedImage
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryScanner
import io.github.weiyongzenqi.unuplayer.library.ScrapedSeason
import io.github.weiyongzenqi.unuplayer.library.ScrapedShow
import io.github.weiyongzenqi.unuplayer.library.cacheKey
import io.github.weiyongzenqi.unuplayer.library.sanitizeFileName
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile

/**
 * 番剧详情页: 顶部 fanart 背景 + poster + 标题/元信息, 简介(可展开), 季选择 Tab, 剧集列表(带缩略图+播放进度)。
 *
 * 点剧集 -> 从 [mediaSourceCache] 租用来源 -> [MediaEntry](video_name, video_path)
 * -> resolvePlayMedia -> [onPlay] 拉起播放器, 播放进度通过 media_key 联动 [playbackRepo]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDetailScreen(
    showId: Long,
    library: LibraryConfig,
    scrapedRepo: ScrapedLibraryRepository,
    mediaSourceCache: MediaSourceCache,
    playbackRepo: PlaybackRecordRepository?,
    imageCacheSizeMb: Int,
    showEpisodeThumb: Boolean,
    /** 扫描配置(单番剧刷新用, 由 AnimeScreen 从 settings 映射传入)。 */
    scanConfig: ScanConfig,
    onPlay: (PlayableMedia) -> Unit,
    onShowChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf<ScrapedShow?>(null) }
    var seasons by remember { mutableStateOf<List<ScrapedSeason>>(emptyList()) }
    var selectedSeasonIndex by remember { mutableStateOf(0) }
    var episodes by remember { mutableStateOf<List<ScrapedEpisode>>(emptyList()) }
    var progressMap by remember { mutableStateOf<Map<String, PlaybackRecord>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }

    // 缓存子目录(番剧名-tmdbid), show 加载后算; WebDAV 图片下载到此目录
    val showKey = show?.cacheKey ?: "unknown"

    // downloader 工厂: 每张图只在实际下载期间租用 source，离页清理不会中途关闭活跃下载。
    val downloader: (String?) -> suspend (PlatformFile) -> Boolean = { path ->
        { dest ->
            path?.let { imagePath ->
                mediaSourceCache.withSource(library) { source ->
                    source.downloadToFile(imagePath, dest)
                } ?: false
            } ?: false
        }
    }

    // 加载某季剧集 + 批量查播放进度
    suspend fun loadEpisodes(seasonId: Long) {
        val eps = scrapedRepo.listEpisodes(seasonId)
        episodes = eps
        progressMap = playbackRepo?.let { repo ->
            val keys = eps.mapNotNull { it.media_key }
            if (keys.isNotEmpty()) repo.getByMediaKeys(keys) else emptyMap()
        } ?: emptyMap()
    }

    // 首次加载: show -> seasons -> 首季 episodes
    LaunchedEffect(showId) {
        loading = true
        val s = scrapedRepo.getShow(showId)
        show = s
        val ss = s?.let { scrapedRepo.listSeasons(it.id) } ?: emptyList()
        seasons = ss
        if (ss.isNotEmpty()) {
            selectedSeasonIndex = 0
            loadEpisodes(ss[0].id)
        }
        loading = false
    }

    // 播放任意 MediaEntry(剧集列表用 playEpisode; 原始目录浏览器用 playMediaEntry 直接播)
    fun playMediaEntry(entry: MediaEntry) {
        scope.launch {
            val media = runSuspendCatching {
                mediaSourceCache.withSource(library) { source ->
                    source.resolvePlayMedia(entry)
                }
            }.getOrNull()
            media?.let(onPlay)
        }
    }

    // 播放剧集: 重建 MediaEntry -> playMediaEntry
    fun playEpisode(ep: ScrapedEpisode) {
        playMediaEntry(MediaEntry(name = ep.video_name, path = ep.video_path, isDirectory = false))
    }

    // 刷新此番剧: 单番剧重扫(重新解析 tvshow.nfo + 所有季/剧集), 完成后重新加载详情数据。
    // 详情页复用页面级 source cache，不走 PosterWallScanCoordinator(单番剧快, 用户在场)。
    fun refreshShow() {
        val s = show ?: return
        if (refreshing) return
        scope.launch {
            refreshing = true
            // 清旧图片缓存(防集标题变后旧 SxxExx 旧标题.jpg 残留), 刷新后重新下载
            runSuspendCatching { scrapedRepo.clearShowCache(s.id) }
            runSuspendCatching {
                mediaSourceCache.withSource(library) { source ->
                    val scanner = ScrapedLibraryScanner(source, library, scrapedRepo, scanConfig)
                    scanner.scanOneShow(s.show_path)
                }
            }
            // 重新加载 show 元数据 + seasons + 当前季 episodes
            val updated = scrapedRepo.getShow(s.id)
            show = updated
            val ss = updated?.let { scrapedRepo.listSeasons(it.id) } ?: emptyList()
            seasons = ss
            if (ss.isNotEmpty()) {
                if (selectedSeasonIndex >= ss.size) selectedSeasonIndex = 0
                loadEpisodes(ss[selectedSeasonIndex].id)
            }
            onShowChanged()
            refreshing = false
        }
    }

    // 系统返回
    AppBackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text(
                        text = show?.title ?: "番剧",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    // 刷新此番剧: 单番剧重扫, 重新解析 nfo + 剧集
                    IconButton(onClick = { refreshShow() }, enabled = !refreshing && show != null) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新此番剧")
                        }
                    }
                    // 收藏/取消收藏
                    IconButton(onClick = {
                        scope.launch {
                            val s = show ?: return@launch
                            val nf = !(s.is_favorite == 1L)
                            scrapedRepo.setFavorite(s.id, nf)
                            onShowChanged()
                            show = scrapedRepo.getShow(s.id)
                        }
                    }) {
                        Icon(
                            if (show?.is_favorite == 1L) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "收藏",
                        )
                    }
                    // 隐藏/取消隐藏(临时归档, 列表默认不显示; 顶部「显示已隐藏」可找回)
                    IconButton(onClick = {
                        scope.launch {
                            val s = show ?: return@launch
                            val newHidden = !(s.is_hidden == 1L)
                            scrapedRepo.setHidden(s.id, newHidden)
                            onShowChanged()
                            show = scrapedRepo.getShow(s.id)
                            if (newHidden) {
                                // 首次隐藏提示找回方式(SharedPreferences 记录, 仅首次弹)
                                if (!AppNotif.isFlagSet("hidden_hint_shown")) {
                                    AppNotif.toast("已隐藏，列表顶部「显示已隐藏」可找回")
                                    AppNotif.setFlag("hidden_hint_shown")
                                }
                                onBack()
                            }
                        }
                    }) {
                        Icon(
                            if (show?.is_hidden == 1L) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (show?.is_hidden == 1L) "取消隐藏" else "隐藏",
                        )
                    }
                    // 更多菜单: 屏蔽 / 删除
                    Box {
                        IconButton(onClick = { moreMenuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = moreMenuExpanded,
                            onDismissRequest = { moreMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("屏蔽") },
                                onClick = {
                                    moreMenuExpanded = false
                                    showBlockDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除") },
                                onClick = {
                                    moreMenuExpanded = false
                                    showDeleteDialog = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                // === 顶部头部区: fanart 背景 + 半透明遮罩 + poster + 标题/元信息 ===
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                        ScrapedImage(
                            sourceKind = library.sourceKind,
                            libraryId = library.id,
                            imagePath = show?.fanart_path,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            placeholderText = show?.title ?: "",
                            imageCacheSizeMb = imageCacheSizeMb,
                            downloader = downloader(show?.fanart_path),
                            cacheSubdir = showKey,
                        )
                        // 半透明遮罩让前景文字清晰
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
                        )
                        Row(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            ScrapedImage(
                                sourceKind = library.sourceKind,
                                libraryId = library.id,
                                imagePath = show?.poster_path,
                                contentDescription = show?.title,
                                modifier = Modifier.size(100.dp, 150.dp),
                                placeholderText = show?.title ?: "",
                                imageCacheSizeMb = imageCacheSizeMb,
                                downloader = downloader(show?.poster_path),
                                cacheSubdir = showKey,
                            )
                            Column(
                                modifier = Modifier.padding(start = 16.dp).fillMaxWidth(),
                            ) {
                                Text(
                                    text = show?.title ?: "",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                show?.original_title?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.8f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                // 年份/评分/studio 小字
                                val metaParts = buildList {
                                    show?.year?.let { add(it.toString()) }
                                    show?.rating?.let { add("评分 %.1f".format(it)) }
                                    show?.studios?.takeIf { it.isNotBlank() }?.let { add(it) }
                                }
                                if (metaParts.isNotEmpty()) {
                                    Text(
                                        text = metaParts.joinToString("  "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // === 简介 plot(可展开) ===
                show?.plot?.let { plot ->
                    if (plot.isNotBlank()) {
                        item {
                            Text(
                                text = plot,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = if (expanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expanded = !expanded }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                }

                // === 季选择(多季才显示 TabRow) ===
                if (seasons.size > 1) {
                    item {
                        PrimaryTabRow(
                            selectedTabIndex = selectedSeasonIndex,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            seasons.forEachIndexed { i, s ->
                                Tab(
                                    selected = selectedSeasonIndex == i,
                                    onClick = {
                                        selectedSeasonIndex = i
                                        scope.launch { loadEpisodes(s.id) }
                                    },
                                    text = { Text(s.title ?: "第${s.season_number}季") },
                                )
                            }
                        }
                    }
                }

                // === 剧集列表 ===
                items(episodes) { ep ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { playEpisode(ep) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 左: 缩略图(可选)
                        if (showEpisodeThumb) {
                            // 缓存名: S01E05 标题.jpg (季号取当前选中季, 集号+标题)
                            val seasonNum = seasons.getOrNull(selectedSeasonIndex)?.season_number ?: 0
                            val epLabel = "S${seasonNum.toString().padStart(2, '0')}E${ep.episode_number.toString().padStart(2, '0')}"
                            val epTitle = ep.title?.takeIf { it.isNotBlank() }?.let { " ${sanitizeFileName(it)}" } ?: ""
                            ScrapedImage(
                                sourceKind = library.sourceKind,
                                libraryId = library.id,
                                imagePath = ep.thumb_path,
                                contentDescription = "E${ep.episode_number}",
                                modifier = Modifier.size(120.dp, 68.dp),
                                placeholderText = "E${ep.episode_number}",
                                imageCacheSizeMb = imageCacheSizeMb,
                                downloader = downloader(ep.thumb_path),
                                cacheSubdir = showKey,
                                cacheName = "$epLabel$epTitle.jpg",
                            )
                            Spacer(modifier = Modifier.size(12.dp))
                        }
                        // 中: 集号+标题 + aired + 进度
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "E${ep.episode_number} ${ep.title ?: ""}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            ep.aired?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            // 播放进度
                            ep.media_key?.let { progressMap[it] }?.let { rec ->
                                if (rec.watch_progress > 0.0) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        LinearProgressIndicator(
                                            progress = { rec.watch_progress.toFloat() },
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            "${(rec.watch_progress * 100).toInt()}%",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
                // === 原始目录浏览器(底部兜底: 匹配异常时手动进文件夹播任意视频) ===
                item {
                    DirBrowser(
                        library = library,
                        mediaSourceCache = mediaSourceCache,
                        rootPath = show?.show_path ?: "",
                        onPlay = ::playMediaEntry,
                    )
                }
            }
        }
    }

    // 删除确认框: 删文件夹 / 仅删记录(两者都屏蔽防重扫)
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除番剧") },
            text = {
                Text(
                    "「${show?.title}」\n\n选择删除方式：\n" +
                        "• 删除文件夹：永久删除番剧源文件（含所有季/剧集，不可恢复）\n" +
                        "• 仅删记录：仅从库移除，源文件保留\n\n" +
                        "两种方式都会屏蔽此番剧（防止重新扫描出现）",
                )
            },
            confirmButton = {
                Column {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val s = show ?: return@launch
                                val fileDeleted = runSuspendCatching {
                                    mediaSourceCache.withSource(library) { source ->
                                        source.deleteFile(s.show_path)
                                    } ?: false
                                }.getOrDefault(false)
                                if (!fileDeleted) {
                                    AppNotif.toast("文件删除失败，已屏蔽")
                                }
                                // deleteShowAndBlock 内部已清该番剧图片缓存(Impl 在 androidMain 可见 PosterCache)
                                scrapedRepo.deleteShowAndBlock(s.id)
                                onShowChanged()
                                showDeleteDialog = false
                                onBack()
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("删除文件夹") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                val s = show ?: return@launch
                                // TODO: 清图片缓存需 PosterCache(androidMain), commonMain 不可见
                                scrapedRepo.deleteShowAndBlock(s.id)
                                onShowChanged()
                                showDeleteDialog = false
                                onBack()
                            }
                        },
                    ) { Text("仅删记录") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }

    // 屏蔽确认框
    if (showBlockDialog) {
        AlertDialog(
            onDismissRequest = { showBlockDialog = false },
            title = { Text("屏蔽番剧") },
            text = { Text("「${show?.title}」将从列表移除（记录保留），可在设置-屏蔽管理恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val s = show ?: return@launch
                            scrapedRepo.blockShow(s.id)
                            onShowChanged()
                            onBack()
                        }
                    },
                ) { Text("屏蔽") }
            },
            dismissButton = {
                TextButton(onClick = { showBlockDialog = false }) { Text("取消") }
            },
        )
    }
}
