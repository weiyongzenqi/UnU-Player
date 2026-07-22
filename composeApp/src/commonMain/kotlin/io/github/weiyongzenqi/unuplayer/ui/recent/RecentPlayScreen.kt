package io.github.weiyongzenqi.unuplayer.ui.recent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.MediaSourceCache
import io.github.weiyongzenqi.unuplayer.library.MediaSourceFactory
import io.github.weiyongzenqi.unuplayer.library.PosterCard
import io.github.weiyongzenqi.unuplayer.library.RecentShow
import io.github.weiyongzenqi.unuplayer.library.ScanConfig
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.ui.posterwall.AnimeDetailScreen

/**
 * 最近播放页(跨库海报墙, commonMain 单实现, 两端共用)。
 *
 * - 跨库混排所有最近播放番剧(按 lastPlayedAt 倒序, 上限 100), 复用 [PosterCard] 展示。
 * - 点海报 -> [AnimeDetailScreen] 详情覆盖层(从右滑入/滑出, 复用海报墙机制)。
 * - 跨库: [libraries] = 所有刮削库; 详情页 [detailLibrary] 按选中番剧的 libraryId 查找。
 * - 离页时关闭页面级 [MediaSourceCache](停止新租用, 活跃下载结束后关闭最后一个引用)。
 * - 返回详情页时重载列表, 反映刚播放的番剧排到前面(last_played_at 更新)。
 *
 * 横竖屏列数用 [BoxWithConstraints](maxWidth > maxHeight 判横屏), 避免 Android 专有 Configuration API。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentPlayScreen(
    onPlay: (PlayableMedia) -> Unit,
    scrapedRepo: ScrapedLibraryRepository,
    mediaSourceFactory: MediaSourceFactory,
    settingsRepo: SettingsRepository,
    playbackRepo: PlaybackRecordRepository?,
) {
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.state.collectAsStateWithLifecycle()

    // 页面级媒体来源缓存(跨库租用, 离页关闭)
    val mediaSourceCache = remember(mediaSourceFactory) { MediaSourceCache(mediaSourceFactory) }

    var shows by remember { mutableStateOf<List<RecentShow>>(emptyList()) }
    var libraries by remember { mutableStateOf<List<LibraryConfig>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedShowId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedShowLibraryId by rememberSaveable { mutableStateOf<Long?>(null) }

    // 扫描配置(详情页单番剧刷新用)
    val scanConfig = remember(
        settings.posterWallScanRequestIntervalMs,
        settings.posterWallScanConcurrency,
        settings.posterWallScanDepth,
        settings.posterWallScanTimeoutSeconds,
    ) {
        ScanConfig(
            requestIntervalMs = settings.posterWallScanRequestIntervalMs,
            concurrency = settings.posterWallScanConcurrency,
            depth = settings.posterWallScanDepth,
            timeoutSeconds = settings.posterWallScanTimeoutSeconds,
        )
    }

    // 离开页面时停止新租用; 活跃下载/扫描完成后由缓存关闭最后一个引用。
    LaunchedEffect(mediaSourceCache) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                mediaSourceCache.close()
            }
        }
    }

    // 初始加载: 所有库 + 最近播放列表
    LaunchedEffect(Unit) {
        runSuspendCatching {
            libraries = scrapedRepo.listLibraries()
            shows = scrapedRepo.listRecentlyPlayed(null, 100)
        }
        loading = false
    }

    // 重载最近播放列表(详情页返回/番剧变化后刷新排序)
    suspend fun reloadShows() {
        runSuspendCatching { shows = scrapedRepo.listRecentlyPlayed(null, 100) }
    }

    // 记住最近打开的番剧: 退出动画期间(selectedShowId 已置 null)仍需渲染详情做滑出动画
    var lastShowId by remember { mutableStateOf<Long?>(null) }
    var lastShowLibraryId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(selectedShowId, selectedShowLibraryId) {
        if (selectedShowId != null) {
            lastShowId = selectedShowId
            lastShowLibraryId = selectedShowLibraryId
        }
    }

    val gridState = rememberLazyGridState()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("最近播放") },
                )
            },
        ) { padding ->
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                val isLandscape = maxWidth > maxHeight
                val columns = (if (isLandscape) {
                    settings.posterWallPosterColumnsLandscape
                } else {
                    settings.posterWallPosterColumnsPortrait
                }).coerceAtLeast(1)

                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    shows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "暂无播放记录",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "去影视源播一部吧",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    else -> LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(shows, key = { it.id }) { show ->
                            val lib = libraries.firstOrNull { it.id == show.libraryId }
                            PosterCard(
                                title = show.title,
                                sourceKind = show.sourceKind,
                                libraryId = show.libraryId,
                                posterPath = show.cardPosterPath,
                                imageCacheSizeMb = settings.posterWallImageCacheSizeMb,
                                downloader = { dest ->
                                    lib?.let { library ->
                                        show.cardPosterPath?.let { path ->
                                            mediaSourceCache.withSource(library) { source ->
                                                source.downloadToFile(path, dest)
                                            } ?: false
                                        } ?: false
                                    } ?: false
                                },
                                onClick = {
                                    selectedShowId = show.id
                                    selectedShowLibraryId = show.libraryId
                                },
                                modifier = Modifier,
                                cacheSubdir = show.cacheKey,
                                seasonBadge = show.cardSeasonNumber
                                    ?.takeIf { if (settings.posterWallBadgeShowSeason1) it >= 1 else it >= 2 }
                                    ?.let { "第${it}季" },
                            )
                        }
                    }
                }
            }
        }

        // 详情覆盖层(复用海报墙机制: 从右滑入/向右滑出)
        AnimatedVisibility(
            visible = selectedShowId != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            val sid = selectedShowId ?: lastShowId
            val detailLibrary = libraries.firstOrNull {
                it.id == (selectedShowLibraryId ?: lastShowLibraryId)
            }
            if (sid != null && detailLibrary != null) {
                AnimeDetailScreen(
                    showId = sid,
                    library = detailLibrary,
                    scrapedRepo = scrapedRepo,
                    mediaSourceCache = mediaSourceCache,
                    playbackRepo = playbackRepo,
                    imageCacheSizeMb = settings.posterWallImageCacheSizeMb,
                    showEpisodeThumb = settings.posterWallShowEpisodeThumb,
                    useSeasonPoster = settings.posterWallDetailUseSeasonPoster,
                    badgeShowSeason1 = settings.posterWallBadgeShowSeason1,
                    scanConfig = scanConfig,
                    onPlay = onPlay,
                    onShowChanged = {
                        scope.launch { runSuspendCatching { reloadShows() } }
                    },
                    onBack = {
                        selectedShowId = null
                        selectedShowLibraryId = null
                        // 返回时重载, 反映刚播的集(last_played_at 更新, 排到前面)
                        scope.launch { runSuspendCatching { reloadShows() } }
                    },
                )
            }
        }
    }
}
