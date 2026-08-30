package io.github.weiyongzenqi.unuplayer.ui.posterwall

import android.content.res.Configuration

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.AnimePlaybackContext
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.core.media.withPlaybackQueue
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.SettingsState
import io.github.weiyongzenqi.unuplayer.domain.bangumiEndpoints
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import io.github.weiyongzenqi.unuplayer.domain.ScrapeTriggerMode
import io.github.weiyongzenqi.unuplayer.library.AndroidEpisodeThumbGenerator
import io.github.weiyongzenqi.unuplayer.library.AndroidRemoteImageDownloader
import io.github.weiyongzenqi.unuplayer.library.EpisodeThumbPosition
import io.github.weiyongzenqi.unuplayer.library.EpisodeThumbPositionMode
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.RemotePreviewableImageBox
import io.github.weiyongzenqi.unuplayer.library.MAX_POSTER_IMAGE_BYTES
import io.github.weiyongzenqi.unuplayer.library.ListShowsByLibrary
import io.github.weiyongzenqi.unuplayer.library.mergeLogicalShowCards
import io.github.weiyongzenqi.unuplayer.library.MediaSourceCache
import io.github.weiyongzenqi.unuplayer.library.MediaSourceFactory
import io.github.weiyongzenqi.unuplayer.library.OnlinePosterLoadGuard
import io.github.weiyongzenqi.unuplayer.library.PosterCache
import io.github.weiyongzenqi.unuplayer.library.PosterCard
import io.github.weiyongzenqi.unuplayer.library.RemoteImageFetcher
import io.github.weiyongzenqi.unuplayer.library.ScrapedImagePathKind
import io.github.weiyongzenqi.unuplayer.library.ScrapedEpisode
import io.github.weiyongzenqi.unuplayer.library.ScrapedSeason
import io.github.weiyongzenqi.unuplayer.library.ScrapedShow
import io.github.weiyongzenqi.unuplayer.library.TmdbEpisodeMapping
import io.github.weiyongzenqi.unuplayer.library.ScrapeFactory
import io.github.weiyongzenqi.unuplayer.library.PosterWallScanCoordinator
import io.github.weiyongzenqi.unuplayer.library.BatchScrapeCoordinator
import io.github.weiyongzenqi.unuplayer.library.BatchScrapeReason
import io.github.weiyongzenqi.unuplayer.library.ScanConfig
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.cacheKey
import io.github.weiyongzenqi.unuplayer.library.isOffsetIgnoredEpisode
import io.github.weiyongzenqi.unuplayer.library.isTmdbEpisodeMappingCompatible
import io.github.weiyongzenqi.unuplayer.library.tmdbEpisodeMapping
import io.github.weiyongzenqi.unuplayer.local.LocalDirectoryRepository
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.playback.episodeProgressKey
import io.github.weiyongzenqi.unuplayer.playback.sync.PlaybackSyncTrigger
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.smb.SmbConnectionRepository
import io.github.weiyongzenqi.unuplayer.schedule.QUARTER_START_MONTHS
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleRepository
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleSeasonSnapshot
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleEntry
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleSnapshot
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleStatus
import io.github.weiyongzenqi.unuplayer.schedule.inferScheduleTmdbSeasonNumber
import io.github.weiyongzenqi.unuplayer.schedule.isScheduleTmdbIdentityCompatible
import io.github.weiyongzenqi.unuplayer.schedule.quarterStartMonth
import io.github.weiyongzenqi.unuplayer.schedule.scheduleBangumiSeasonEpisodeNumber
import io.github.weiyongzenqi.unuplayer.schedule.scheduleLocalEpisodeNumber
import io.github.weiyongzenqi.unuplayer.schedule.scheduleMappedTmdbEpisodeNumber
import io.github.weiyongzenqi.unuplayer.schedule.scheduleTmdbEpisodeNumber
import io.github.weiyongzenqi.unuplayer.schedule.currentScheduleLocalDateTime
import io.github.weiyongzenqi.unuplayer.core.platform.AppNotif
import io.github.weiyongzenqi.unuplayer.anirss.AniRssRepository
import io.github.weiyongzenqi.unuplayer.anirss.AniRssSubscription
import io.github.weiyongzenqi.unuplayer.anirss.AniRssConnectionState
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiGatewayHttpException
import io.github.weiyongzenqi.unuplayer.bangumi.bangumiImageModel
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiScrapeApi
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiScrapeSubject
import io.github.weiyongzenqi.unuplayer.bangumi.TmdbScrapeApi
import io.github.weiyongzenqi.unuplayer.bangumi.TmdbTvDetails
import io.github.weiyongzenqi.unuplayer.bangumi.TmdbTvImagePaths
import io.github.weiyongzenqi.unuplayer.bangumi.TmdbSeasonImages
import io.github.weiyongzenqi.unuplayer.bangumi.gatewayEndpointOrNull
import io.github.weiyongzenqi.unuplayer.bangumi.resolveImageUrl
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentApi
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentProvider
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiReview
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiTopic
import io.github.weiyongzenqi.unuplayer.ui.posterwall.bangumiCommentItems
import io.github.weiyongzenqi.unuplayer.ui.posterwall.bangumiCommentBoxItems
import io.github.weiyongzenqi.unuplayer.ui.posterwall.bangumiTopicItems

/** 搜索范围: GLOBAL 跨库, CURRENT_LIBRARY 仅当前选中库。 */
private enum class SearchScope { GLOBAL, CURRENT_LIBRARY }
private enum class AnimePage { LIBRARY, SCHEDULE }

/** 时间表剧集播放解析失败的原因(区分用户可自助解决的库内问题与外部媒体源问题)。 */
private enum class SchedulePlaybackFailure(val message: String) {
    NO_LIBRARY_MATCH("这部番剧尚未关联到媒体库中的剧集，可先在库内搜索番剧并完成关联"),
    NO_LIBRARY("找不到对应的媒体库，可能已被删除"),
    NO_SHOW("找不到媒体库中对应的番剧，请刷新媒体库后重试"),
    NO_SEASON("找不到媒体库中对应的季度，请刷新媒体库后重试"),
    NO_EPISODE("找不到媒体库中对应的本地剧集，请刷新媒体库后重试"),
    MEDIA_SOURCE_UNAVAILABLE("媒体源暂时不可用，请检查网络或媒体服务器连接后重试"),
}

/**
 * 海报墙(番剧库)主页。
 *
 * - 顶部: 刮削库下拉选择 + 增量扫描 / 更多(全量扫描·编辑当前库·删除当前库) / 添加按钮
 * - 内容: [显示已隐藏]切换 + 正常段(按 min_release_date 的 yyyy-MM 分组, 可配) + 隐藏段(展开时)
 * - item 带 animateItem 丝滑动画; 点番剧 -> AnimeDetailScreen(slide/fade 过渡)
 *
 * **排序**: listShows 按 settings.posterWallSortBy(季度/年份/最近扫描, 拼音回落季度)。
 * 用户计划统一在时间表“已标记番剧”维护，海报墙不再保留独立收藏分段。
 * **屏蔽/隐藏过滤**: listShows 已过滤屏蔽+隐藏(is_hidden=0); 隐藏段单独 listHidden 查(始终加载知数量)。
 * **隐藏段入口**: 列表顶部「显示已隐藏(N)」按钮 toggle(下拉手势不自然, 改按钮更直观)。
 *
 * **扫描状态跨页面保持**: 扫描 job + 状态在 [scanCoordinator](进程级单例)。
 * **滚动位置保持**: 列表用覆盖层模式始终组合(详情为 AnimatedVisibility 覆盖层), gridState 不随详情销毁 ->
 * 滚动位置天然保持(进详情返回不丢); rememberSaveable(LazyGridState.Saver) 兼顾配置变更/切 tab 恢复。
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
    batchScrapeCoordinator: BatchScrapeCoordinator,
    webDavRepo: WebDavConnectionRepository,
    smbRepo: SmbConnectionRepository?,
    localDirRepo: LocalDirectoryRepository,
    settingsRepo: SettingsRepository,
    playbackRepo: PlaybackRecordRepository?,
    playbackSyncTrigger: PlaybackSyncTrigger?,
    scheduleRepo: ScheduleRepository?,
    aniRssRepo: AniRssRepository?,
    initialSchedule: Boolean,
    showPageSwitcher: Boolean,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by settingsRepo.state.collectAsStateWithLifecycle()
    val scanState by scanCoordinator.state.collectAsStateWithLifecycle()
    var animePage by rememberSaveable(initialSchedule) {
        mutableStateOf(if (initialSchedule && scheduleRepo != null) AnimePage.SCHEDULE else AnimePage.LIBRARY)
    }
    val pageCount = if (showPageSwitcher && scheduleRepo != null) AnimePage.entries.size else 1
    val animePagerState = rememberPagerState(
        initialPage = if (initialSchedule && pageCount > 1) AnimePage.SCHEDULE.ordinal else AnimePage.LIBRARY.ordinal,
        pageCount = { pageCount },
    )

    LaunchedEffect(animePage, pageCount) {
        if (!showPageSwitcher) return@LaunchedEffect
        val target = animePage.ordinal.coerceIn(0, pageCount - 1)
        if (animePagerState.currentPage != target) animePagerState.animateScrollToPage(target)
    }
    LaunchedEffect(animePagerState, pageCount) {
        if (!showPageSwitcher) return@LaunchedEffect
        snapshotFlow { animePagerState.currentPage }
            .map { it.coerceIn(0, pageCount - 1) }
            .distinctUntilChanged()
            .collect { page -> animePage = AnimePage.entries[page] }
    }

    var libraries by remember { mutableStateOf<List<LibraryConfig>>(emptyList()) }
    var selectedLibraryId by rememberSaveable { mutableStateOf(settings.posterWallDefaultLibraryId) }
    val selectedLibrary = libraries.firstOrNull { it.id == selectedLibraryId }
    var shows by remember { mutableStateOf<List<ListShowsByLibrary>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var searchScope by remember { mutableStateOf(SearchScope.GLOBAL) }
    var searchResults by remember { mutableStateOf<List<ListShowsByLibrary>>(emptyList()) }
    // 页面级唯一所有者：当前库、跨库搜索和详情页只在操作期间租用 source。
    val mediaSourceCache = remember(mediaSourceFactory) { MediaSourceCache(mediaSourceFactory) }

    suspend fun resolveScheduleEpisodePlayback(entry: ScheduleEntry, localEpisodeNumber: Long): SchedulePlaybackFailure? {
        val match = entry.libraryMatch?.takeIf { it.confirmed } ?: return SchedulePlaybackFailure.NO_LIBRARY_MATCH
        val seasonNumber = match.seasonNumber ?: return SchedulePlaybackFailure.NO_LIBRARY_MATCH
        val library = libraries.firstOrNull { it.id == match.libraryId }
            ?: runSuspendCatching { scrapedRepo.getLibrary(match.libraryId) }.getOrNull()
            ?: return SchedulePlaybackFailure.NO_LIBRARY
        val show = runSuspendCatching { scrapedRepo.getShow(match.showId) }.getOrNull() ?: return SchedulePlaybackFailure.NO_SHOW
        if (show.library_id != match.libraryId) return SchedulePlaybackFailure.NO_SHOW
        val seasons = runSuspendCatching { scrapedRepo.listSeasons(match.showId) }.getOrDefault(emptyList())
        // 时间表关联已经给出季度时只认精确季，禁止回退第一季造成错播。
        val season = seasons.firstOrNull {
            it.season_number == seasonNumber.toLong() && it.bangumi_offset == match.bangumiOffset
        } ?: return SchedulePlaybackFailure.NO_SEASON
        val episodes = runSuspendCatching { scrapedRepo.listEpisodes(season.id) }.getOrDefault(emptyList())
        val episode = episodes.firstOrNull { it.episode_number == localEpisodeNumber } ?: return SchedulePlaybackFailure.NO_EPISODE
        val currentIndex = episodes.indexOfFirst { it.id == episode.id }
        val onlineMeta = runSuspendCatching {
            scrapedRepo.getOnlineMeta(show.library_id, show.show_path, season.season_number.toInt())
        }.getOrNull()
        val tmdbMapping = onlineMeta?.tmdbEpisodeMapping?.takeIf { candidate ->
            val offset = season.bangumi_offset
                .takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
                ?.toInt()
            offset != null && isTmdbEpisodeMappingCompatible(
                mapping = candidate,
                localSeasonNumber = season.season_number.toInt(),
                localEpisodeNumbers = episodes.mapNotNull { item ->
                    item.episode_number.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
                },
                bangumiId = season.bangumi_id,
                bangumiOffset = offset,
            )
        }
        val queueMedia = runSuspendCatching {
            mediaSourceCache.withSource(library) { source ->
                episodes.map { item ->
                    val ignoredEpisode = isOffsetIgnoredEpisode(season.bangumi_offset, item.episode_number)
                    source.resolvePlayMedia(
                        MediaEntry(
                            name = item.video_name,
                            path = item.video_path,
                            isDirectory = false,
                            // 播放记录三元组只能使用本地番剧已经确认的 TMDB 身份；时间表身份不能替本地库背书。
                            tmdbId = show.tmdb_id,
                            seasonNumber = tmdbMapping?.seasonNumber?.toLong() ?: season.season_number,
                            // 被忽略集(先行篇)记 S2E0 独立身份, 防与 E2(映射 TMDB S2E1)三元组互撞
                            episodeNumber = when {
                                ignoredEpisode -> 0L
                                else -> tmdbMapping?.remoteEpisodeNumber(item.episode_number)
                                    ?: item.episode_number
                            },
                        ),
                    ).copy(
                        animeContext = AnimePlaybackContext(
                            seriesTitle = show.title,
                            // 被忽略集(正漂移前 offset 集 = 先行篇)显示原始文件名, 简介不采用错位文本
                            episodeTitle = if (ignoredEpisode) {
                                item.video_name.takeIf { it.isNotBlank() } ?: item.title
                            } else {
                                item.title
                            },
                            episodeDescription = if (ignoredEpisode) {
                                null
                            } else {
                                item.plot
                            },
                            bangumiSubjectId = entry.subjectId,
                            bangumiEpisodeOffset = season.bangumi_offset,
                            localSeasonNumber = season.season_number,
                            localEpisodeNumber = item.episode_number,
                            dandanplayAnimeId = onlineMeta?.dandanplay_id,
                            // 被忽略集(先行篇)恒为 TMDB 外集; 其余按已验证映射内无对应集号判定,
                            // 播放器弹幕自动优先哈希。
                            episodeOutsideTmdb = ignoredEpisode ||
                                tmdbMapping
                                    ?.let { it.remoteEpisodeNumber(item.episode_number) == null } == true,
                        ),
                    )
                }
            }
        }.getOrNull() ?: return SchedulePlaybackFailure.MEDIA_SOURCE_UNAVAILABLE
        onPlay(
            queueMedia[currentIndex].withPlaybackQueue(queueMedia, currentIndex),
        )
        return null
    }

    val episodeThumbGenerator = remember(settings.allowTlsInsecure) {
        AndroidEpisodeThumbGenerator(context.applicationContext, settings.allowTlsInsecure)
    }
    // 在线刮削管线: Bangumi 始终可用; 弹弹按代理/用户凭证启用; TMDB 固定通过 Gateway。
    val onlineScraper = remember(
        settings.dandanplayUseProxy, settings.dandanplayAppId, settings.dandanplayAppSecret,
        settings.bangumiDataSource, settings.posterWallImageCacheSizeMb, settings.scrapeUniqueAutoApply,
        scrapedRepo,
    ) {
        ScrapeFactory.createScraper(
            settings,
            scrapedRepo,
            AndroidRemoteImageDownloader(
                context.applicationContext,
                cacheMaxSizeBytes = settings.posterWallImageCacheSizeMb.coerceIn(50, 2000).toLong() * 1024L * 1024L,
            ),
        )
    }
    var listRefreshToken by remember { mutableLongStateOf(0L) }
    val batchState by batchScrapeCoordinator.state.collectAsStateWithLifecycle()
    val triggerMode = remember(settings.scrapeTriggerMode) {
        runCatching { ScrapeTriggerMode.valueOf(settings.scrapeTriggerMode) }.getOrDefault(ScrapeTriggerMode.LAZY)
    }

    // 批量任务由进程级协调器持有，页面只负责发起和订阅状态。
    fun batchScrape(lib: LibraryConfig) {
        batchScrapeCoordinator.start(
            library = lib,
            scraper = onlineScraper,
            anchorOnly = false,
            concurrency = settings.scrapeConcurrency,
            reason = BatchScrapeReason.MANUAL,
        )
    }

    // 扫描完成后自动补(触发模式 SCAN_ALL / SCAN_ANCHOR_ONLY): 检测 isScanning 从 true -> false 的边缘,
    // 对**刚结束扫描的库**(scanState.libraryId)缺元数据番剧批量在线刮削(命中即应用, 模糊留待手动)。
    // 用 scanState.libraryId 而非当前选中库, 避免"扫描 A 库中切到 B 库"时给没扫过的 B 库误触发。
    var wasScanning by remember { mutableStateOf(scanState.isScanning) }
    LaunchedEffect(scanState.isScanning, triggerMode, selectedLibraryId, onlineScraper, libraries) {
        val finished = wasScanning && !scanState.isScanning
        wasScanning = scanState.isScanning
        if (!finished || batchState.isRunning) return@LaunchedEffect
        if (triggerMode == ScrapeTriggerMode.LAZY) return@LaunchedEffect
        val finishedLibId = scanState.libraryId ?: return@LaunchedEffect
        val lib = libraries.firstOrNull { it.id == finishedLibId } ?: return@LaunchedEffect
        batchScrapeCoordinator.start(
            library = lib,
            scraper = onlineScraper,
            anchorOnly = triggerMode == ScrapeTriggerMode.SCAN_ANCHOR_ONLY,
            concurrency = settings.scrapeConcurrency,
            reason = BatchScrapeReason.AFTER_SCAN,
        )
    }
    LaunchedEffect(batchState.runId, batchState.isRunning, batchState.status) {
        if (!batchState.isRunning && batchState.runId > 0L) {
            listRefreshToken++
        }
    }
    var hiddenShows by remember { mutableStateOf<List<ListShowsByLibrary>>(emptyList()) }
    var showHidden by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var selectedShowId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedShowLibraryId by rememberSaveable { mutableStateOf<Long?>(null) }
    val libraryDetailBackState = remember { PredictiveDetailBackState() }
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var sourceAvailable by remember { mutableStateOf(false) }
    // 对话框用的文件树连接列表
    var webDavConnections by remember { mutableStateOf<List<WebDavConnection>>(emptyList()) }
    var smbConnections by remember { mutableStateOf<List<SmbConnection>>(emptyList()) }

    fun openLibraryDetail(showId: Long, libraryId: Long) {
        libraryDetailBackState.prepareForOpen()
        selectedShowLibraryId = libraryId
        selectedShowId = showId
    }

    fun dismissLibraryDetail(animated: Boolean) {
        if (animated) libraryDetailBackState.prepareForAnimatedDismiss() else libraryDetailBackState.commit()
        selectedShowId = null
        selectedShowLibraryId = null
    }

    PredictiveBackHandler(enabled = selectedShowId != null) { events ->
        var committed = false
        try {
            events.collect { libraryDetailBackState.update(it.progress) }
            committed = true
            dismissLibraryDetail(animated = false)
        } finally {
            if (!committed) libraryDetailBackState.cancel()
        }
    }

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

    // 加载远程连接列表(添加库对话框用)
    LaunchedEffect(Unit) {
        runSuspendCatching { webDavRepo.loadAll() }
            .onSuccess { webDavConnections = it }
        runSuspendCatching { smbRepo?.loadAll().orEmpty() }
            .onSuccess { smbConnections = it }
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
                mergeLogicalShowCards(scrapedRepo.listShows(selectedLibrary.id, settings.posterWallSortBy))
            }.getOrDefault(emptyList())
            val loadedHiddenShows = runSuspendCatching {
                mergeLogicalShowCards(scrapedRepo.listHidden(selectedLibrary.id))
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
    // 在线封面一次性加载(批次C): 无本地封面但有「远程 URL 且无本地文件」季照的番, 每次应用启动期间
    // 只尝试下载一次(OnlinePosterLoadGuard 进程级去重, 失败也计入 → 绝不无限重试); 串行逐部, 不打满并发。
    // 守卫 key 含季号: 多季番每缺封季各占一次会话配额, 补完最高季刷新后低季仍可再试。
    // 先下载后标记: 被 shows 变更取消(CancellationException)不消耗配额(取消≠失败), 下一趟刷新仍可再试。
    // 图片限流退避期(该番图片主机 rateLimitBackoffRemainingMs>0)本趟跳过、不等待(下次会话/详情页重试条再补);
    // 按主机隔离(FP3-13): 仅跳过被限流 CDN 的番, 其它主机的番照常尝试。
    // 任一成功 bump listRefreshToken 让列表重查(card_poster_path 会带上新下载的本地季照)。
    // 收敛性: 刷新产生新列表实例重启本 effect, 但失败/成功者已被守卫标记、成功者已有 card_poster_path,
    // 第二轮过滤后为空即止——不存在无限循环。
    LaunchedEffect(shows, onlineScraper) {
        val pending = shows.filter {
            it.card_poster_path == null && !it.card_remote_poster_url.isNullOrBlank() &&
                it.card_remote_poster_season != null
        }
        if (pending.isEmpty()) return@LaunchedEffect
        var anySuccess = false
        for (show in pending) {
            val seasonNumber = show.card_remote_poster_season?.toInt() ?: continue
            val remoteUrl = show.card_remote_poster_url ?: continue
            // 该番图片主机限流退避中则本趟跳过、不等待(下次会话/详情页重试条再补);
            // 按主机隔离(FP3-13): 仅跳过被限流的 CDN 的番, 其它主机的番照常尝试
            if (RemoteImageFetcher.rateLimitBackoffRemainingMsForUrl(remoteUrl) > 0) continue
            val guardKey = "${show.library_id}|${show.show_path}|$seasonNumber"
            if (OnlinePosterLoadGuard.isAttempted(guardKey)) continue // 本会话已试过(含失败)直接跳过
            try {
                val ok = runSuspendCatching {
                    onlineScraper.tryDownloadOnlinePoster(show.library_id, show.show_path, seasonNumber, remoteUrl)
                }.getOrDefault(false)
                if (ok) anySuccess = true
                // 仅"真实完成/失败"的尝试消耗会话配额; 取消(CancellationException)抛到外层, 不烧额度
                OnlinePosterLoadGuard.markAttempted(guardKey)
            } catch (e: CancellationException) {
                throw e
            }
        }
        if (anySuccess) listRefreshToken++
    }
    // 搜索 debounce 300ms(空查询清空结果, 不搜)
    LaunchedEffect(searchQuery, searchScope, selectedLibraryId, listRefreshToken) {
        if (searchQuery.isBlank()) { searchResults = emptyList(); return@LaunchedEffect }
        delay(300)
        val libId = if (searchScope == SearchScope.CURRENT_LIBRARY) selectedLibraryId else null
        searchResults = runSuspendCatching {
            mergeLogicalShowCards(scrapedRepo.searchShows(searchQuery, libId))
        }.getOrDefault(emptyList())
    }
    val isSearching = searchQuery.isNotBlank()

    // ★流式加载: 扫描中 foundShows 递增 / 扫描完成(isScanning 转换)时刷新列表(番剧陆续出现 + 最终完整)。
    // 重启式 debounce 300ms: foundShows/isScanning 变化即 key 变化重启本 effect, 前一次的 delay(300) 被取消,
    // 只有最后一次变化熬过 300ms 静默期(200-500ms 居中)才触发查询 —— LOCAL 快扫描连续递增合并成少数几批
    // 刷新, 不再每递增重跑 listShows+listHidden 聚合查询连续打 DB; 扫描结束的终态刷新由 trailing 重启天然保证。
    // 仅作用于扫描流式刷新路径 —— 常规进页加载走上方 LaunchedEffect(selectedLibrary, ...) 独立路径, 首载不被延迟。
    LaunchedEffect(selectedLibrary?.id, settings.posterWallSortBy, scanState.foundShows, scanState.isScanning) {
        delay(300)
        val lib = selectedLibrary ?: return@LaunchedEffect
        if (scanState.libraryId == lib.id) {
            val loadedShows = runSuspendCatching {
                mergeLogicalShowCards(scrapedRepo.listShows(lib.id, settings.posterWallSortBy))
            }.getOrDefault(shows)
            val loadedHiddenShows = runSuspendCatching { mergeLogicalShowCards(scrapedRepo.listHidden(lib.id)) }
                .getOrDefault(hiddenShows)
            shows = loadedShows
            hiddenShows = loadedHiddenShows
        }
    }

    // 切换"显示隐藏段": hiddenShows 已始终加载, toggle 仅控制展开
    val onToggleHidden: () -> Unit = { showHidden = !showHidden }

    // 记住最近打开的番剧: 退出动画期间(selectedShowId 已置 null)仍需渲染详情做滑出动画
    var lastShowId by remember { mutableStateOf<Long?>(null) }
    var lastShowLibraryId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(selectedShowId, selectedShowLibraryId) {
        if (selectedShowId != null) {
            lastShowId = selectedShowId
            lastShowLibraryId = selectedShowLibraryId
        }
    }

    // 覆盖层模式: 列表始终组合(gridState 不随详情销毁, 滚动位置天然保持, 无 attach 死锁);
    // 详情作为覆盖层从右滑入/向右滑出。
    Box(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (showPageSwitcher) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    AnimePageSwitcher(
                        selected = animePage,
                        scheduleAvailable = scheduleRepo != null,
                        onSelect = { animePage = it },
                    )
                }
            }
            HorizontalPager(
                state = animePagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                userScrollEnabled = showPageSwitcher && scheduleRepo != null,
            ) { page ->
                val schedulePage = if (showPageSwitcher) {
                    page == AnimePage.SCHEDULE.ordinal
                } else {
                    initialSchedule
                }
                if (schedulePage && scheduleRepo != null) {
                    ScheduleContent(
                        repository = scheduleRepo,
                        playbackSyncTrigger = playbackSyncTrigger,
                        aniRssRepository = aniRssRepo,
                        scrapedRepository = scrapedRepo,
                        playbackRepository = playbackRepo,
                        settings = settings,
                        settingsRepository = settingsRepo,
                        onPlayLocalEpisode = { entry, localEpisodeNumber ->
                            scope.launch {
                                resolveScheduleEpisodePlayback(entry, localEpisodeNumber)?.let { failure ->
                                    AppNotif.toast(failure.message)
                                }
                            }
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
                        onIncrementalScan = { selectedLibrary?.let { scanCoordinator.startScan(it, settings, force = false) } },
                        onFullScan = { selectedLibrary?.let { scanCoordinator.startScan(it, settings, force = true) } },
                        onStopScan = { scanCoordinator.stopScan() },
                        onAddLibrary = { showAddDialog = true },
                        onBatchScrape = { selectedLibrary?.let { batchScrape(it) } },
                        batchScrapeState = batchState,
                        onStopBatchScrape = { batchScrapeCoordinator.stop() },
                        onEditLibrary = { showEditDialog = true },
                        onDeleteLibrary = { showDeleteConfirm = true },
                        onOpenShow = { showId, libraryId ->
                            openLibraryDetail(showId, libraryId)
                        },
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = selectedShowId != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = if (libraryDetailBackState.skipAnimatedExit) {
                ExitTransition.None
            } else {
                slideOutHorizontally { it } + fadeOut()
            },
        ) {
            val sid = selectedShowId ?: lastShowId
            val detailLibrary = libraries.firstOrNull { it.id == (selectedShowLibraryId ?: lastShowLibraryId) }
            if (sid != null && detailLibrary != null) {
                key(detailLibrary.id, sid) {
                    Surface(
                        modifier = Modifier.fillMaxSize().predictiveDetailTransform(libraryDetailBackState),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        AnimeDetailScreen(
                            showId = sid,
                            library = detailLibrary,
                            scrapedRepo = scrapedRepo,
                            mediaSourceCache = mediaSourceCache,
                            playbackRepo = playbackRepo,
                            scheduleRepo = scheduleRepo,
                            imageCacheSizeMb = settings.posterWallImageCacheSizeMb,
                            showEpisodeThumb = settings.posterWallShowEpisodeThumb,
                            autoGenerateEpisodeThumb = settings.posterWallAutoEpisodeThumb,
                            useSeasonPoster = settings.posterWallDetailUseSeasonPoster,
                            badgeShowSeason1 = settings.posterWallBadgeShowSeason1,
                            scanConfig = scanConfig,
                            globalSettings = settings,
                            episodeThumbGenerator = episodeThumbGenerator,
                            episodeThumbPosition = if (settings.posterWallEpisodeThumbPositionMode == EpisodeThumbPositionMode.PERCENT)
                                EpisodeThumbPosition.Percent(settings.posterWallEpisodeThumbAtPercent)
                            else
                                EpisodeThumbPosition.Seconds(settings.posterWallEpisodeThumbAtSeconds),
                            scraper = onlineScraper,
                            scrapeHashProvider = ScrapeFactory.buildHashProvider(detailLibrary, mediaSourceCache),
                            onPlay = onPlay,
                            onShowChanged = { listRefreshToken++ },
                            onScheduleWatchChanged = {
                                playbackSyncTrigger?.scheduleDebouncedPush(settings)
                            },
                            onBack = { dismissLibraryDetail(animated = true) },
                            handleSystemBack = false,
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddLibraryDialog(
            webDavConnections = webDavConnections,
            smbConnections = smbConnections,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnimePageSwitcher(
    selected: AnimePage,
    scheduleAvailable: Boolean,
    onSelect: (AnimePage) -> Unit,
) {
    Row(
        modifier = Modifier.wrapContentWidth().padding(start = 12.dp, top = 2.dp, bottom = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AnimePage.entries.forEach { page ->
            val enabled = page != AnimePage.SCHEDULE || scheduleAvailable
            Column(
                modifier = Modifier.width(68.dp).height(34.dp).clickable(enabled = enabled) { onSelect(page) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (page == AnimePage.LIBRARY) "媒体库" else "时间表",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(Modifier.fillMaxWidth(0.68f).height(2.dp)) {
                    if (selected == page) {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primary) {}
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleContent(
    repository: ScheduleRepository,
    playbackSyncTrigger: PlaybackSyncTrigger?,
    aniRssRepository: AniRssRepository?,
    scrapedRepository: ScrapedLibraryRepository,
    playbackRepository: PlaybackRecordRepository?,
    settings: SettingsState,
    settingsRepository: SettingsRepository,
    onPlayLocalEpisode: (ScheduleEntry, Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bangumiEndpoints = settings.bangumiEndpoints()
    val today = remember { currentScheduleLocalDateTime().weekday }
    var scheduleStatusFilter by rememberSaveable { mutableStateOf<ScheduleStatus?>(null) }
    var markedStatusFilter by rememberSaveable { mutableStateOf<ScheduleStatus?>(null) }
    val initialWeekdayPage = remember { (today - 1).coerceIn(0, WEEKDAY_PAGE_COUNT - 1) }
    var lastWeekdayPage by rememberSaveable { mutableIntStateOf(initialWeekdayPage) }
    val schedulePagerState = rememberPagerState(
        initialPage = initialWeekdayPage,
        pageCount = { if (aniRssRepository == null) MARKED_SCHEDULE_PAGE + 1 else ANI_RSS_SCHEDULE_PAGE + 1 },
    )
    val currentSchedulePage = schedulePagerState.currentPage.coerceIn(0, schedulePagerState.pageCount - 1)
    val scheduleSection = when (currentSchedulePage) {
        in 0 until WEEKDAY_PAGE_COUNT -> ScheduleSection.WEEK
        MARKED_SCHEDULE_PAGE -> ScheduleSection.MARKED
        else -> ScheduleSection.ANI_RSS
    }
    val selectedWeekday = if (currentSchedulePage < WEEKDAY_PAGE_COUNT) {
        currentSchedulePage + 1
    } else {
        lastWeekdayPage + 1
    }
    var snapshot by remember { mutableStateOf<ScheduleSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 历史季度视角: 0 = 本周(默认); 其余为所选季度起始年月(1/4/7/10)
    var selectedSeasonYear by rememberSaveable { mutableIntStateOf(0) }
    var selectedSeasonMonth by rememberSaveable { mutableIntStateOf(0) }
    val selectedSeason = if (selectedSeasonYear in 2000..2100 && selectedSeasonMonth in QUARTER_START_MONTHS) {
        selectedSeasonYear to selectedSeasonMonth
    } else null
    var seasonSnapshot by remember { mutableStateOf<ScheduleSeasonSnapshot?>(null) }
    var seasonLoading by remember { mutableStateOf(false) }
    var seasonError by remember { mutableStateOf<String?>(null) }
    var seasonPickerVisible by remember { mutableStateOf(false) }
    var seasonRetryToken by remember { mutableLongStateOf(0L) }
    var handledSeasonRefreshToken by remember { mutableLongStateOf(0L) }
    var handledSeasonRetryToken by remember { mutableLongStateOf(0L) }
    val seasonNow = remember { currentScheduleLocalDateTime() }
    val currentQuarter = seasonNow.year to quarterStartMonth(seasonNow.month)
    var scheduleReloadToken by remember { mutableLongStateOf(0L) }
    var aniRssReloadToken by remember { mutableLongStateOf(0L) }
    var forceRefreshToken by remember { mutableLongStateOf(0L) }
    var handledForceRefreshToken by remember { mutableLongStateOf(0L) }
    var onlineEntry by remember { mutableStateOf<ScheduleEntry?>(null) }
    var lastOnlineEntry by remember { mutableStateOf<ScheduleEntry?>(null) }
    var detailVisible by remember { mutableStateOf(false) }
    val detailBackState = remember { PredictiveDetailBackState() }
    var actionError by remember { mutableStateOf<String?>(null) }
    val cachedAniRssSession = remember(aniRssRepository) {
        aniRssRepository?.let { AniRssSubscriptionSessionCache.peek() }
    }
    var aniSubscriptions by remember {
        mutableStateOf(cachedAniRssSession?.subscriptions.orEmpty())
    }
    var aniSubscriptionsLoading by remember { mutableStateOf(false) }
    var aniSubscriptionsError by remember { mutableStateOf(cachedAniRssSession?.error) }
    var aniRssConnection by remember { mutableStateOf(cachedAniRssSession?.connection) }
    var animeSearchPurpose by remember { mutableStateOf<AnimeSearchPurpose?>(null) }
    val animeSearchState = rememberOnlineAnimeSearchState()
    var returnToSearchAfterDetail by remember { mutableStateOf(false) }
    var searchSelectionBusy by remember { mutableStateOf(false) }
    var searchSelectionError by remember { mutableStateOf<String?>(null) }
    var directSubscriptionEntry by remember { mutableStateOf<ScheduleEntry?>(null) }
    var markedEntries by remember { mutableStateOf<List<ScheduleEntry>>(emptyList()) }
    var watchesReloadToken by remember { mutableLongStateOf(0L) }
    val persistedWatches by remember(scrapedRepository) {
        scrapedRepository.observeScheduleWatches()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val persistedWatchesBySubject = persistedWatches.associateBy { it.subjectId }
    val currentScheduleEntries = snapshot.orEmptyEntries().map { entry ->
        val watch = persistedWatchesBySubject[entry.subjectId]
        entry.copy(
            watched = watch != null && watch.status != ScheduleStatus.NONE,
            status = watch?.status ?: ScheduleStatus.NONE,
        )
    }
    // 季度视角数据源: 观看标记实时取持久化快照, 标记操作不用重新拉季度数据
    val currentSeasonEntries = if (selectedSeason == null) emptyList() else seasonSnapshot?.entries?.map { entry ->
        val watch = persistedWatchesBySubject[entry.subjectId]
        entry.copy(
            watched = watch != null && watch.status != ScheduleStatus.NONE,
            status = watch?.status ?: ScheduleStatus.NONE,
        )
    }.orEmpty()
    // WEEK 区数据源: 本周=周表三源聚合; 历史季=/sn 聚合(剧场版按设置过滤)
    val weekEntriesSource = when {
        selectedSeason == null -> currentScheduleEntries
        settings.scheduleHideTheatrical -> currentSeasonEntries.filterNot(ScheduleEntry::isTheatrical)
        else -> currentSeasonEntries
    }

    LaunchedEffect(schedulePagerState.settledPage) {
        schedulePagerState.settledPage.takeIf { it in 0 until WEEKDAY_PAGE_COUNT }?.let {
            lastWeekdayPage = it
        }
    }

    LaunchedEffect(currentScheduleEntries, currentSeasonEntries, persistedWatches, watchesReloadToken) {
        // 季度条目也作为已标记页的富信息来源; 同 id 时周表条目优先(含 animeId/精确放送时刻)
        val snapshotBySubject = (currentSeasonEntries + currentScheduleEntries).associateBy { it.subjectId }
        markedEntries = persistedWatches.map { watch ->
            snapshotBySubject[watch.subjectId]?.copy(watched = true, status = watch.status)
                ?: ScheduleEntry(
                    subjectId = watch.subjectId,
                    title = watch.title,
                    originalTitle = null,
                    weekday = watch.airWeekday,
                    broadcastTime = null,
                    airDate = null,
                    posterUrl = null,
                    rating = null,
                    rank = null,
                    watchingCount = null,
                    animeId = watch.animeId,
                    tmdbId = watch.tmdbId,
                    libraryMatch = null,
                    watched = true,
                    status = watch.status,
                )
        }.sortedWith(compareBy<ScheduleEntry> { it.status.ordinal }.thenBy { it.title })
    }

    LaunchedEffect(onlineEntry) {
        if (onlineEntry != null) lastOnlineEntry = onlineEntry
    }

    fun openOnlineDetail(entry: ScheduleEntry) {
        detailBackState.prepareForOpen()
        lastOnlineEntry = entry
        detailVisible = true
        onlineEntry = entry
    }

    /**
     * 切换季度视角: 立即清空旧季度快照进入加载态——否则新季度加载期间
     * (冷缓存可达十几秒)界面会一直展示上一个季度的列表, 看起来像切换失效。
     * 旧加载由 LaunchedEffect 的 key 变化自动取消, 不会用旧结果覆盖新选择。
     * 重复点击当前已选视角时直接忽略: 快照已清空而 key 不变会让加载协程
     * 不重启, 界面落入既无数据也无加载指示的假空态。
     */
    fun selectSeason(season: Pair<Int, Int>?) {
        if (season == selectedSeason) return
        selectedSeasonYear = season?.first ?: 0
        selectedSeasonMonth = season?.second ?: 0
        seasonSnapshot = null
        seasonError = null
    }

    fun dismissOnlineDetail(animated: Boolean) {
        if (animated) detailBackState.prepareForAnimatedDismiss() else detailBackState.commit()
        detailVisible = false
        onlineEntry = null
        if (returnToSearchAfterDetail) {
            returnToSearchAfterDetail = false
            scope.launch {
                delay(if (animated) 320L else 32L)
                animeSearchPurpose = AnimeSearchPurpose.DETAILS
            }
        }
    }

    fun resolveSearchEntry(entry: ScheduleEntry, purpose: AnimeSearchPurpose) {
        if (searchSelectionBusy) return
        scope.launch {
            searchSelectionBusy = true
            searchSelectionError = null
            runSuspendCatching { repository.resolveAnime(entry.subjectId) ?: entry }
                .onSuccess { resolved ->
                    animeSearchPurpose = null
                    when (purpose) {
                        AnimeSearchPurpose.DETAILS -> {
                            returnToSearchAfterDetail = true
                            openOnlineDetail(resolved)
                        }
                        AnimeSearchPurpose.ANI_RSS -> directSubscriptionEntry = resolved
                    }
                }
                .onFailure { cause ->
                    searchSelectionError = "番剧详情核对失败：${cause.message ?: "请稍后重试"}"
                }
            searchSelectionBusy = false
        }
    }

    fun openAnimeSearch(purpose: AnimeSearchPurpose) {
        searchSelectionError = null
        animeSearchPurpose = purpose
    }

    fun updateSearchHistory(history: List<String>) {
        scope.launch {
            settingsRepository.update { it.copy(scheduleSearchHistory = history) }
        }
    }

    fun saveStatus(entry: ScheduleEntry, status: ScheduleStatus) {
        scope.launch {
            runSuspendCatching { repository.setStatus(entry, status) }
                .onSuccess {
                    actionError = null
                    scheduleReloadToken++
                    watchesReloadToken++
                    playbackSyncTrigger?.scheduleDebouncedPush(settings)
                }
                .onFailure { cause ->
                    actionError = "保存时间表状态失败：${cause.message ?: "请稍后重试"}"
                }
        }
    }

    fun resolveAndOpenSubject(subjectId: Long) {
        scope.launch {
            actionError = null
            runSuspendCatching { repository.resolveAnime(subjectId) }
                .onSuccess { resolved ->
                    if (resolved != null) openOnlineDetail(resolved)
                    else actionError = "找不到 Bangumi #$subjectId 的在线详情"
                }
                .onFailure { cause ->
                    actionError = "在线详情加载失败：${cause.message ?: "请稍后重试"}"
                }
        }
    }

    PredictiveBackHandler(enabled = onlineEntry != null) { events ->
        var committed = false
        try {
            events.collect { detailBackState.update(it.progress) }
            committed = true
            // 提交后保持终点位移，直到覆盖层被移除，避免归零时闪回原位。
            dismissOnlineDetail(animated = false)
        } finally {
            if (!committed) detailBackState.cancel()
        }
    }

    // 订阅快照由进程会话缓存持有；即使外层海报墙 Pager 暂时销毁时间表组合，返回时也不重复联网。
    // 连接配置发生变化、用户手动刷新或完成启停/删除/新增后才重新读取远端列表。
    LaunchedEffect(aniRssRepository, aniRssReloadToken) {
        if (aniRssRepository == null) return@LaunchedEffect
        aniSubscriptionsLoading = true
        aniSubscriptionsError = null
        runSuspendCatching { aniRssRepository.connectionState() }
            .onSuccess { state ->
                aniRssConnection = state
                val cached = aniRssReloadToken.takeIf { it == 0L }
                    ?.let { AniRssSubscriptionSessionCache.read(state) }
                when {
                    cached != null -> {
                        aniSubscriptions = cached.subscriptions
                        aniSubscriptionsError = cached.error
                    }
                    state.configured -> {
                        val retained = AniRssSubscriptionSessionCache.read(state)?.subscriptions.orEmpty()
                        aniSubscriptions = retained
                        runSuspendCatching { aniRssRepository.listSubscriptions() }
                            .onSuccess { subscriptions ->
                                aniSubscriptions = subscriptions
                                AniRssSubscriptionSessionCache.publish(state, subscriptions, null)
                            }
                            .onFailure { cause ->
                                val message = cause.message ?: "Ani-RSS 订阅列表加载失败"
                                aniSubscriptionsError = message
                                AniRssSubscriptionSessionCache.publish(state, retained, message)
                            }
                    }
                    else -> {
                        aniSubscriptions = emptyList()
                        AniRssSubscriptionSessionCache.publish(state, emptyList(), null)
                    }
                }
            }
            .onFailure { cause ->
                aniRssConnection = null
                aniSubscriptions = emptyList()
                aniSubscriptionsError = cause.message ?: "Ani-RSS 连接状态读取失败"
            }
        aniSubscriptionsLoading = false
    }

    LaunchedEffect(repository, scheduleReloadToken, forceRefreshToken) {
        val forceRefresh = forceRefreshToken != handledForceRefreshToken
        if (forceRefresh) handledForceRefreshToken = forceRefreshToken
        loading = true
        error = null
        runSuspendCatching { repository.load(forceRefresh = forceRefresh) }
            .onSuccess { snapshot = it }
            .onFailure { error = "时间表加载失败，请检查网关配置" }
        loading = false
    }

    // 季度数据加载: 只在选中历史季度时触发; 刷新按钮与季度重试共用 handled token 机制,
    // 重试过的季度不影响之后切换的缓存命中。/sn 冷缓存首击由网关聚合上游(约 10~20 秒),
    // UI 侧保持加载态不超时打断。
    LaunchedEffect(selectedSeason, forceRefreshToken, seasonRetryToken) {
        val season = selectedSeason ?: run {
            handledSeasonRefreshToken = forceRefreshToken
            handledSeasonRetryToken = seasonRetryToken
            return@LaunchedEffect
        }
        val forceRefresh = forceRefreshToken != handledSeasonRefreshToken || seasonRetryToken != handledSeasonRetryToken
        handledSeasonRefreshToken = forceRefreshToken
        handledSeasonRetryToken = seasonRetryToken
        seasonLoading = true
        seasonError = null
        runSuspendCatching { repository.loadSeason(season.first, season.second, forceRefresh = forceRefresh) }
            .onSuccess { seasonSnapshot = it }
            .onFailure { cause ->
                seasonError = when ((cause as? BangumiGatewayHttpException)?.statusCode) {
                    404 -> "网关版本过旧，暂不支持季度浏览"
                    504 -> "网关聚合该季度数据超时，请稍后重试"
                    else -> "季度数据加载失败：${cause.message ?: "请检查网关配置"}"
                }
            }
        seasonLoading = false
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (scheduleSection) {
                    ScheduleSection.WEEK -> selectedSeason?.let(::scheduleSeasonTitle) ?: "本周时间表"
                    ScheduleSection.MARKED -> "已标记番剧"
                    ScheduleSection.ANI_RSS -> "Ani-RSS 订阅"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (scheduleSection != ScheduleSection.ANI_RSS) {
                IconButton(
                    onClick = { openAnimeSearch(AnimeSearchPurpose.DETAILS) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索任意番剧")
                }
                ScheduleStatusFilterButton(
                    selected = if (scheduleSection == ScheduleSection.WEEK) scheduleStatusFilter else markedStatusFilter,
                    allLabel = if (scheduleSection == ScheduleSection.WEEK) "全部" else "全部标记",
                    onSelect = {
                        if (scheduleSection == ScheduleSection.WEEK) scheduleStatusFilter = it
                        else markedStatusFilter = it
                    },
                )
                if (scheduleSection == ScheduleSection.WEEK) {
                    IconButton(onClick = { forceRefreshToken++ }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新时间表")
                    }
                }
            } else {
                val aniRssConnected = aniRssConnection?.configured == true
                Text(
                    text = when {
                        aniRssConnected -> "已连接"
                        aniSubscriptionsLoading -> "连接中"
                        aniRssConnection != null -> "未连接"
                        aniSubscriptionsError != null -> "连接异常"
                        else -> "连接中"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (aniRssConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                IconButton(
                    onClick = { openAnimeSearch(AnimeSearchPurpose.ANI_RSS) },
                    enabled = aniRssConnected,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索并添加 Ani-RSS 订阅")
                }
                IconButton(
                    onClick = { aniRssReloadToken++ },
                    enabled = !aniSubscriptionsLoading,
                    modifier = Modifier.size(36.dp),
                ) {
                    if (aniSubscriptionsLoading) {
                        CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新 Ani-RSS 订阅")
                    }
                }
            }
        }
        actionError?.let { message ->
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (scheduleSection == ScheduleSection.WEEK) {
            ScheduleSeasonBar(
                selectedSeason = selectedSeason,
                currentQuarter = currentQuarter,
                onSelectSeason = { season -> selectSeason(season) },
                onOpenEarlierPicker = { seasonPickerVisible = true },
            )
        }
        if (seasonPickerVisible) {
            ScheduleSeasonPickerDialog(
                currentQuarter = currentQuarter,
                onSelectSeason = { season ->
                    seasonPickerVisible = false
                    selectSeason(season)
                },
                onDismiss = { seasonPickerVisible = false },
            )
        }
        ScheduleSectionTabs(
            selected = scheduleSection,
            enabledAniRss = aniRssRepository != null,
            onSelect = { section ->
                val targetPage = when (section) {
                    ScheduleSection.WEEK -> lastWeekdayPage
                    ScheduleSection.MARKED -> MARKED_SCHEDULE_PAGE
                    ScheduleSection.ANI_RSS -> ANI_RSS_SCHEDULE_PAGE
                }
                scope.launch { schedulePagerState.animateScrollToPage(targetPage) }
            },
        )
        HorizontalPager(
            state = schedulePagerState,
            modifier = Modifier.fillMaxSize().weight(1f),
            beyondViewportPageCount = 1,
            key = { it },
        ) { sectionPage ->
            when (sectionPage) {
                ANI_RSS_SCHEDULE_PAGE -> {
                    aniRssRepository?.let { repository ->
                        AniRssSubscriptionManager(
                            repository = repository,
                            connection = aniRssConnection,
                            subscriptions = aniSubscriptions,
                            scheduleEntries = currentScheduleEntries,
                            loading = aniSubscriptionsLoading,
                            error = aniSubscriptionsError,
                            onRefresh = { aniRssReloadToken++ },
                            resolvePosterUrl = { bangumiEndpoints.resolveImageUrl(it) },
                            onOpenSubject = ::resolveAndOpenSubject,
                        )
                    }
                }

                MARKED_SCHEDULE_PAGE -> {
                    val visibleMarked = markedEntries.filter {
                        markedStatusFilter == null || it.status == markedStatusFilter
                    }
                    if (visibleMarked.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (markedEntries.isEmpty()) "还没有标记番剧" else "没有这个标记类型的番剧",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            modifier = Modifier.fillMaxSize(),
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
                        ) {
                            items(visibleMarked, key = { it.subjectId }) { entry ->
                                ScheduleCard(
                                    entry = entry,
                                    posterUrl = bangumiEndpoints.resolveImageUrl(entry.posterUrl),
                                    onOpen = { resolveAndOpenSubject(entry.subjectId) },
                                    onStatusSelected = { saveStatus(entry, it) },
                                )
                            }
                        }
                    }
                }

                else -> Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        WEEKDAY_LABELS.forEachIndexed { index, label ->
                            val selected = selectedWeekday == index + 1
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clickable { scope.launch { schedulePagerState.animateScrollToPage(index) } },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = if (selected) 1.dp else 0.dp,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    snapshot?.partialWarnings?.takeIf { it.isNotEmpty() && selectedSeason == null }?.let { warnings ->
                        Text(
                            warnings.joinToString("；"),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 网关侧单月 200 条封顶截断(正常年份不触发), 提示数据可能不完整
                    seasonSnapshot?.takeIf { selectedSeason != null && it.truncated }?.let {
                        Text(
                            "该季度数据超出网关上限，列表可能不完整",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 已有快照时刷新失败不再静默: 顶部显示错误横幅 + 重试, 下方继续展示上一次成功的数据;
                    // 刷新进行中则显示细进度条。仅在无快照时才整页切换 loading/error。周表与季度视角同款。
                    if (loading && snapshot != null && selectedSeason == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                    }
                    if (seasonLoading && seasonSnapshot != null && selectedSeason != null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                    }
                    error?.takeIf { snapshot != null && selectedSeason == null }?.let { message ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                message,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { forceRefreshToken++ }) { Text("重试") }
                        }
                    }
                    seasonError?.takeIf { seasonSnapshot != null && selectedSeason != null }?.let { message ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                message,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { seasonRetryToken++ }) { Text("重试") }
                        }
                    }
                    when {
                        // 季度视角: 独立的整页加载/错误(冷缓存首击约 10~20 秒, 文案管理预期)
                        selectedSeason != null && seasonLoading && seasonSnapshot == null -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Text(
                                    "正在从 Bangumi 聚合该季度数据\n首次约 10~20 秒，之后长期秒开",
                                    modifier = Modifier.padding(top = 12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        selectedSeason != null && seasonError != null && seasonSnapshot == null -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(seasonError!!)
                                TextButton(onClick = { seasonRetryToken++ }) { Text("重试") }
                            }
                        }

                        loading && snapshot == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }

                        error != null && snapshot == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(error!!)
                                TextButton(onClick = { forceRefreshToken++ }) { Text("重试") }
                            }
                        }

                        else -> {
                            val entries = weekEntriesSource.filter {
                                it.weekday == sectionPage + 1 &&
                                    (scheduleStatusFilter == null || it.status == scheduleStatusFilter)
                            }
                            androidx.compose.animation.AnimatedContent(
                                targetState = entries,
                                modifier = Modifier.fillMaxSize(),
                                label = "schedule-day-content",
                            ) { visibleEntries ->
                                if (visibleEntries.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            when {
                                                scheduleStatusFilter != null -> "这一天没有${scheduleStatusFilter?.label}的番剧"
                                                selectedSeason != null -> "这一天没有收录番剧"
                                                else -> "这一天暂无在播番剧"
                                            },
                                        )
                                    }
                                } else {
                                    LazyVerticalGrid(
                                        modifier = Modifier.fillMaxSize(),
                                        columns = GridCells.Fixed(3),
                                        contentPadding = PaddingValues(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
                                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
                                    ) {
                                        items(visibleEntries, key = { it.subjectId }) { entry ->
                                            ScheduleCard(
                                                entry = entry,
                                                posterUrl = bangumiEndpoints.resolveImageUrl(entry.posterUrl),
                                                onOpen = { openOnlineDetail(entry) },
                                                onStatusSelected = { saveStatus(entry, it) },
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
        AnimatedVisibility(
            visible = detailVisible,
            modifier = Modifier.fillMaxSize(),
            enter = slideInHorizontally { it } + fadeIn(),
            exit = if (detailBackState.skipAnimatedExit) ExitTransition.None else slideOutHorizontally { it } + fadeOut(),
        ) {
            lastOnlineEntry?.let { selectedEntry ->
                Surface(
                    modifier = Modifier.fillMaxSize().predictiveDetailTransform(detailBackState),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ScheduleOnlineDetailPage(
                        entry = selectedEntry,
                        settings = settings,
                        aniRssRepository = aniRssRepository,
                        scrapedRepository = scrapedRepository,
                        playbackRepository = playbackRepository,
                        onPlayLocalEpisode = onPlayLocalEpisode,
                        onDismiss = {
                            // 顶部返回不是预测性手势，保留一次正常的返回转场。
                            dismissOnlineDetail(animated = true)
                        },
                        onSubscribed = { aniRssReloadToken++ },
                        onStatusChanged = { status ->
                            scope.launch {
                                runSuspendCatching { repository.setStatus(selectedEntry, status) }
                                    .onSuccess {
                                        actionError = null
                                        scheduleReloadToken++
                                        watchesReloadToken++
                                        playbackSyncTrigger?.scheduleDebouncedPush(settings)
                                    }
                                    .onFailure { error ->
                                        actionError = "保存时间表状态失败：${error.message ?: "请稍后重试"}"
                                    }
                            }
                        },
                    )
                }
            }
        }
        animeSearchPurpose?.let { purpose ->
            OnlineAnimeSearchDialog(
                repository = repository,
                settings = settings,
                state = animeSearchState,
                history = settings.scheduleSearchHistory,
                onHistoryChange = ::updateSearchHistory,
                selectionBusy = searchSelectionBusy,
                selectionError = searchSelectionError,
                onDismiss = {
                    if (!searchSelectionBusy) {
                        searchSelectionError = null
                        animeSearchPurpose = null
                    }
                },
                onSelect = { entry -> resolveSearchEntry(entry, purpose) },
            )
        }
        directSubscriptionEntry?.let { entry ->
            aniRssRepository?.let { repository ->
                AniRssSubscriptionWizard(
                    entry = entry,
                    repository = repository,
                    resolvePosterUrl = { bangumiEndpoints.resolveImageUrl(it) },
                    onDismiss = { directSubscriptionEntry = null },
                    onAdded = {
                        directSubscriptionEntry = null
                        aniRssReloadToken++
                    },
                )
            }
        }
    }
}

private const val WEEKDAY_PAGE_COUNT = 7
private const val MARKED_SCHEDULE_PAGE = WEEKDAY_PAGE_COUNT
private const val ANI_RSS_SCHEDULE_PAGE = MARKED_SCHEDULE_PAGE + 1

private data class AniRssSubscriptionSessionSnapshot(
    val connection: AniRssConnectionState,
    val subscriptions: List<AniRssSubscription>,
    val error: String?,
)

/** 仅保存本次应用进程内的非敏感订阅快照，不持久化地址、密钥或服务响应。 */
private object AniRssSubscriptionSessionCache {
    private var snapshot: AniRssSubscriptionSessionSnapshot? = null

    fun peek(): AniRssSubscriptionSessionSnapshot? = snapshot

    fun read(connection: AniRssConnectionState): AniRssSubscriptionSessionSnapshot? =
        snapshot?.takeIf { it.connection == connection }

    fun publish(
        connection: AniRssConnectionState,
        subscriptions: List<AniRssSubscription>,
        error: String?,
    ) {
        snapshot = AniRssSubscriptionSessionSnapshot(connection, subscriptions, error)
    }
}

private enum class ScheduleSection { WEEK, MARKED, ANI_RSS }
private enum class AnimeSearchPurpose { DETAILS, ANI_RSS }

/** 季度名(日漫档期): 1月=冬 4月=春 7月=夏 10月=秋。 */
private fun scheduleQuarterName(quarterMonth: Int): String = when (quarterMonth) {
    1 -> "冬"
    4 -> "春"
    7 -> "夏"
    10 -> "秋"
    else -> ""
}

/** 季度 chip 短标签: "2025 夏"。 */
private fun scheduleSeasonChipLabel(season: Pair<Int, Int>): String =
    "${season.first} ${scheduleQuarterName(season.second)}"

/** 顶栏标题全称: "2025年夏季番"。 */
private fun scheduleSeasonTitle(season: Pair<Int, Int>): String =
    "${season.first}年${scheduleQuarterName(season.second)}季番"

/** 季度起始月往回退一档(跨年回退)。 */
private fun previousQuarter(season: Pair<Int, Int>): Pair<Int, Int> {
    val month = season.second - 3
    return if (month < 1) season.first - 1 to 10 else season.first to month
}

/**
 * 季度视角切换条: "本周" + 最近 3 个季度 + "更早…"入口。
 * 视觉沿用星期标签的 pill 形态(选中=主题容器色), 不引入新控件语言。
 */
@Composable
private fun ScheduleSeasonBar(
    selectedSeason: Pair<Int, Int>?,
    currentQuarter: Pair<Int, Int>,
    onSelectSeason: (Pair<Int, Int>?) -> Unit,
    onOpenEarlierPicker: () -> Unit,
) {
    val seasons = buildList<Pair<Int, Int>?> {
        add(null)
        var cursor = currentQuarter
        repeat(3) {
            add(cursor)
            cursor = previousQuarter(cursor)
        }
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lazyItems(seasons, key = { it?.let { (year, month) -> "season-$year-$month" } ?: "season-current" }) { season ->
            val selected = selectedSeason == season
            Surface(
                modifier = Modifier
                    .height(30.dp)
                    .clickable { onSelectSeason(season) },
                shape = RoundedCornerShape(15.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = if (selected) 1.dp else 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(
                        text = season?.let(::scheduleSeasonChipLabel) ?: "本周",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "season-earlier") {
            Surface(
                modifier = Modifier
                    .height(30.dp)
                    .clickable(onClick = onOpenEarlierPicker),
                shape = RoundedCornerShape(15.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(
                        text = "更早…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 全历史选季弹窗: 年份倒序(当前年→2000), 每年四档; 未来季度不可选。 */
@Composable
private fun ScheduleSeasonPickerDialog(
    currentQuarter: Pair<Int, Int>,
    onSelectSeason: (Pair<Int, Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择季度") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            ) {
                lazyItems((currentQuarter.first downTo 2000).toList()) { year ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            "$year 年",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(1 to "冬", 4 to "春", 7 to "夏", 10 to "秋").forEach { (month, label) ->
                                val future = year > currentQuarter.first ||
                                    (year == currentQuarter.first && month > currentQuarter.second)
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp)
                                        .clickable(enabled = !future) { onSelectSeason(year to month) },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (future) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surfaceContainer,
                                    tonalElevation = 0.dp,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            "${label}季",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (future) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ScheduleSectionTabs(
    selected: ScheduleSection,
    enabledAniRss: Boolean,
    onSelect: (ScheduleSection) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal) {
        Tab(selected = selected == ScheduleSection.WEEK, onClick = { onSelect(ScheduleSection.WEEK) }, text = { Text("本周时间表") })
        Tab(selected = selected == ScheduleSection.MARKED, onClick = { onSelect(ScheduleSection.MARKED) }, text = { Text("已标记番剧") })
        Tab(
            selected = selected == ScheduleSection.ANI_RSS,
            enabled = enabledAniRss,
            onClick = { onSelect(ScheduleSection.ANI_RSS) },
            text = { Text("Ani-RSS 订阅") },
        )
    }
}

@Composable
private fun ScheduleStatusFilterButton(
    selected: ScheduleStatus?,
    allLabel: String,
    onSelect: (ScheduleStatus?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { expanded = true },
            label = { Text(selected?.label ?: allLabel, style = MaterialTheme.typography.labelSmall) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(allLabel) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
                leadingIcon = { if (selected == null) Icon(Icons.Filled.CheckCircle, contentDescription = null) },
            )
            ScheduleStatus.entries.filter { it != ScheduleStatus.NONE }.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.label) },
                    onClick = {
                        onSelect(status)
                        expanded = false
                    },
                    leadingIcon = { if (selected == status) Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun ScheduleCard(
    entry: ScheduleEntry,
    posterUrl: String?,
    onOpen: () -> Unit,
    onStatusSelected: (ScheduleStatus) -> Unit,
) {
    val context = LocalContext.current
    val posterModel = remember(posterUrl) {
        posterUrl?.let { url -> bangumiImageModel(context, url) }
    }
    Card(
        modifier = Modifier.clickable(onClick = onOpen),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.70f)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.title.firstOrNull()?.toString().orEmpty(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                )
                posterModel?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = entry.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (entry.status != ScheduleStatus.NONE) {
                    var statusMenuExpanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                        Surface(
                            modifier = Modifier.clickable { statusMenuExpanded = true },
                            shape = RoundedCornerShape(8.dp),
                            color = when (entry.status) {
                                ScheduleStatus.NONE -> Color.Transparent
                                ScheduleStatus.WANT -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f)
                                ScheduleStatus.WATCHING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.94f)
                                ScheduleStatus.DROPPED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f)
                            },
                        ) {
                            Text(
                                entry.status.label,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        DropdownMenu(expanded = statusMenuExpanded, onDismissRequest = { statusMenuExpanded = false }) {
                            ScheduleStatus.entries.forEach { status ->
                                DropdownMenuItem(
                                    text = { Text(status.label) },
                                    onClick = {
                                        onStatusSelected(status)
                                        statusMenuExpanded = false
                                    },
                                    leadingIcon = {
                                        if (entry.status == status) Icon(Icons.Filled.CheckCircle, contentDescription = null)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Text(
                entry.title,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private data class ScheduleLocalEpisodeState(
    val episode: ScrapedEpisode,
    val progress: Double?,
    val isCompleted: Boolean,
)

private data class ScheduleLocalSeasonContext(
    val show: ScrapedShow,
    val season: ScrapedSeason,
    val tmdbMapping: TmdbEpisodeMapping?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleOnlineDetailPage(
    entry: ScheduleEntry,
    settings: SettingsState,
    aniRssRepository: AniRssRepository?,
    scrapedRepository: ScrapedLibraryRepository,
    playbackRepository: PlaybackRecordRepository?,
    onPlayLocalEpisode: (ScheduleEntry, Long) -> Unit,
    onDismiss: () -> Unit,
    onSubscribed: () -> Unit,
    onStatusChanged: (ScheduleStatus) -> Unit,
) {
    val context = LocalContext.current
    val endpoints = settings.bangumiEndpoints()
    val commentProvider = remember(endpoints.identity) {
        BangumiCommentProvider(
            api = BangumiCommentApi(
                officialBaseUrl = endpoints.apiBaseUrl,
                nextBaseUrl = endpoints.nextApiBaseUrl,
                gateway = endpoints.gatewayEndpointOrNull(),
            ),
            allowedAvatarHosts = endpoints.allowedAvatarHosts,
            imageBaseUrl = endpoints.imageBaseUrl,
        )
    }
    val commentState = rememberBangumiCommentUiState(commentProvider)
    val commentBoxState = rememberBangumiCommentBoxUiState(commentProvider)
    val topicState = rememberBangumiTopicUiState(commentProvider)
    val detailPagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val episodeListState = rememberLazyListState()
    val commentListState = rememberLazyListState()
    val commentBoxListState = rememberLazyListState()
    val topicListState = rememberLazyListState()
    val detailScope = rememberCoroutineScope()
    val tmdbApi = remember { TmdbScrapeApi() }
    var subject by remember { mutableStateOf<BangumiScrapeSubject?>(null) }
    var tmdb by remember { mutableStateOf<TmdbTvDetails?>(null) }
    var tmdbImages by remember { mutableStateOf<TmdbTvImagePaths?>(null) }
    var seasonImages by remember { mutableStateOf<TmdbSeasonImages?>(null) }
    var seasonImagesNotice by remember { mutableStateOf<String?>(null) }
    var episodes by remember { mutableStateOf<List<io.github.weiyongzenqi.unuplayer.bangumi.BangumiScrapeEpisode>>(emptyList()) }
    val localCommentEpisodes = remember(episodes) {
        episodes.mapNotNull { episode ->
            // 评论端点属于当前 Bangumi subject，必须使用该 subject 内的季内 ep。
            // sort 可能是跨季度连续编号（例如第二季 E1 的 sort=12），不能冒充本地集号。
            val number = scheduleBangumiSeasonEpisodeNumber(episode.episode) ?: return@mapNotNull null
            LocalCommentEpisode(
                id = number,
                number = number,
                title = episode.title,
            )
        }.distinctBy { it.id }
    }
    val detailListState = rememberLazyListState()
    val episodesCollapseConnection = rememberScheduleHeaderCollapseConnection(episodeListState, detailListState)
    val commentCollapseConnection = rememberScheduleHeaderCollapseConnection(commentListState, detailListState)
    val commentBoxCollapseConnection = rememberScheduleHeaderCollapseConnection(commentBoxListState, detailListState)
    val topicCollapseConnection = rememberScheduleHeaderCollapseConnection(topicListState, detailListState)
    var tabRowHeightPx by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reviewTarget by remember { mutableStateOf<BangumiReview?>(null) }
    var topicTarget by remember { mutableStateOf<BangumiTopic?>(null) }
    var showSubscription by remember { mutableStateOf(false) }
    var statusMenuExpanded by remember { mutableStateOf(false) }
    var currentStatus by remember(entry.subjectId) { mutableStateOf(entry.status) }
    var localEpisodesByNumber by remember(entry.subjectId, entry.libraryMatch) {
        mutableStateOf<Map<Long, ScheduleLocalEpisodeState>>(emptyMap())
    }
    var localSeasonContext by remember(entry.subjectId, entry.libraryMatch) {
        mutableStateOf<ScheduleLocalSeasonContext?>(null)
    }
    var localEpisodesError by remember(entry.subjectId, entry.libraryMatch) { mutableStateOf<String?>(null) }

    suspend fun loadLocalSeasonContext(): ScheduleLocalSeasonContext? {
        val match = entry.libraryMatch?.takeIf { it.confirmed }
        val seasonNumber = match?.seasonNumber ?: return null
        val showSnapshot = scrapedRepository.getShow(match.showId)
            ?: error("找不到已关联的本地番剧")
        check(showSnapshot.library_id == match.libraryId) { "本地番剧与媒体库关联不一致" }
        val season = scrapedRepository.listSeasons(match.showId)
            .firstOrNull {
                it.season_number == seasonNumber.toLong() && it.bangumi_offset == match.bangumiOffset
            }
            ?: error("找不到已关联的本地季度")
        val storedMapping = scrapedRepository.getOnlineMeta(
            showSnapshot.library_id,
            showSnapshot.show_path,
            season.season_number.toInt(),
        )?.tmdbEpisodeMapping
        val bangumiOffset = season.bangumi_offset
            .takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
            ?.toInt()
        val mapping = storedMapping?.takeIf { candidate ->
            bangumiOffset != null && isTmdbEpisodeMappingCompatible(
                mapping = candidate,
                localSeasonNumber = season.season_number.toInt(),
                localEpisodeNumbers = scrapedRepository.listEpisodes(season.id)
                    .mapNotNull { episode -> episode.episode_number.takeIf { number -> number in 1L..Int.MAX_VALUE }?.toInt() },
                bangumiId = season.bangumi_id,
                bangumiOffset = bangumiOffset,
            )
        }
        return ScheduleLocalSeasonContext(showSnapshot, season, mapping)
    }

    suspend fun reloadLocalEpisodes() {
        if (entry.libraryMatch?.takeIf { it.confirmed }?.seasonNumber == null) {
            localEpisodesByNumber = emptyMap()
            localSeasonContext = null
            localEpisodesError = null
            return
        }
        runSuspendCatching {
            val context = loadLocalSeasonContext() ?: error("找不到已关联的本地季度")
            val showSnapshot = context.show
            val season = context.season
            val tmdbMapping = context.tmdbMapping
            val localEpisodes = scrapedRepository.listEpisodes(season.id)
            val ownProgress = playbackRepository?.let { repository ->
                val keys = localEpisodes.mapNotNull { it.media_key }
                if (keys.isEmpty()) emptyMap() else repository.getByMediaKeys(keys)
            }.orEmpty()
            val semanticProgress = if (playbackRepository != null && showSnapshot.tmdb_id != null) {
                val tripleKeys = localEpisodes
                    .filter { it.episode_number > 0L }
                    .map {
                        episodeProgressKey(
                            showSnapshot.tmdb_id,
                            tmdbMapping?.seasonNumber?.toLong() ?: season.season_number,
                            tmdbMapping?.remoteEpisodeNumber(it.episode_number) ?: it.episode_number,
                        )
                    }
                if (tripleKeys.isEmpty()) emptyMap() else playbackRepository.getEpisodeProgressByTriples(tripleKeys)
            } else {
                emptyMap()
            }
            localEpisodes
                .filter { it.episode_number > 0L }
                .associate { episode ->
                    val own = episode.media_key?.let { ownProgress[it] }
                    val semantic = showSnapshot.tmdb_id?.let { tmdbId ->
                        semanticProgress[
                            episodeProgressKey(
                                tmdbId,
                                tmdbMapping?.seasonNumber?.toLong() ?: season.season_number,
                                tmdbMapping?.remoteEpisodeNumber(episode.episode_number) ?: episode.episode_number,
                            )
                        ]
                    }
                    val resolvedProgress = when {
                        semantic == null -> own?.let { it.watch_progress to it.is_completed }
                        own == null -> semantic.watch_progress to semantic.is_completed
                        semantic.last_played_at > own.last_played_at -> semantic.watch_progress to semantic.is_completed
                        else -> own.watch_progress to own.is_completed
                    }
                    episode.episode_number to ScheduleLocalEpisodeState(
                        episode = episode,
                        progress = resolvedProgress?.first,
                        isCompleted = resolvedProgress?.second == 1L,
                    )
                }.let { context to it }
        }.fold(
            onSuccess = { (context, loaded) ->
                localSeasonContext = context
                localEpisodesByNumber = loaded
                localEpisodesError = null
            },
            onFailure = { cause ->
                localSeasonContext = null
                localEpisodesByNumber = emptyMap()
                localEpisodesError = "本地剧集读取失败：${cause.message ?: "请刷新媒体库后重试"}"
            },
        )
    }

    LaunchedEffect(entry.subjectId, entry.libraryMatch, playbackRepository) {
        var observedVersion = playbackRepository?.changeVersion?.value
        reloadLocalEpisodes()
        playbackRepository?.changeVersion?.collect { version ->
            if (version != observedVersion) {
                observedVersion = version
                reloadLocalEpisodes()
            }
        }
    }

    LaunchedEffect(entry.subjectId, entry.tmdbId, entry.libraryMatch, endpoints.identity) {
        loading = true
        error = null
        subject = null
        tmdb = null
        tmdbImages = null
        seasonImages = null
        seasonImagesNotice = null
        episodes = emptyList()
        coroutineScope {
            val bangumiApi = BangumiScrapeApi(
                baseUrl = endpoints.apiBaseUrl,
                gateway = endpoints.gatewayEndpointOrNull(),
            )
            val subjectDeferred = async { runSuspendCatching { bangumiApi.getSubject(entry.subjectId) }.getOrNull() }
            val episodesDeferred = async { runSuspendCatching { bangumiApi.getEpisodes(entry.subjectId) }.getOrDefault(emptyList()) }
            val localContextDeferred = async {
                runSuspendCatching { loadLocalSeasonContext() }.getOrNull()
            }
            subject = subjectDeferred.await()
            episodes = episodesDeferred.await()
            val exactLocalContext = localContextDeferred.await()
            if (exactLocalContext != null) localSeasonContext = exactLocalContext
            val tvId = exactLocalContext?.show?.tmdb_id?.takeIf { it > 0L }
                ?: entry.tmdbId?.takeIf { it > 0L }
            val tmdbCandidate = tvId?.let { id ->
                runSuspendCatching { tmdbApi.fetchTvDetails(id) }.getOrNull()
            }
            val confirmedLocalIdentity = exactLocalContext?.show?.tmdb_id == tvId && tvId != null
            val tmdbIdentityAccepted = tmdbCandidate != null && isScheduleTmdbIdentityCompatible(
                confirmedLocalIdentity = confirmedLocalIdentity,
                bangumiTitle = subject?.title ?: entry.title,
                bangumiOriginalTitle = subject?.originalTitle ?: entry.originalTitle,
                bangumiAirDate = subject?.date ?: entry.airDate,
                tmdbTitle = tmdbCandidate.name,
                tmdbOriginalTitle = tmdbCandidate.originalName,
                tmdbFirstAirDate = tmdbCandidate.firstAirDate,
            )
            tmdb = tmdbCandidate?.takeIf { tmdbIdentityAccepted }
            val validatedTvId = tvId?.takeIf { tmdbIdentityAccepted }
            tmdbImages = validatedTvId?.let { id ->
                runSuspendCatching { tmdbApi.fetchTvImagePaths(id) }.getOrNull()
            }
            val tmdbMapping = exactLocalContext?.tmdbMapping
            val inferredSeasonNumber = tmdbMapping?.seasonNumber ?: inferScheduleTmdbSeasonNumber(
                confirmedSeasonNumber = exactLocalContext?.season?.season_number?.toInt(),
                title = subject?.title ?: entry.title,
                originalTitle = subject?.originalTitle ?: entry.originalTitle,
                bangumiAirDate = subject?.date ?: entry.airDate,
                tmdbFirstAirDate = tmdb?.firstAirDate,
            )
            fun mappedEpisodeNumber(
                episode: io.github.weiyongzenqi.unuplayer.bangumi.BangumiScrapeEpisode,
            ): Int? = if (tmdbMapping != null) {
                scheduleMappedTmdbEpisodeNumber(
                    bangumiSort = episode.sort,
                    bangumiEpisode = episode.episode,
                    bangumiOffset = exactLocalContext.season.bangumi_offset,
                    tmdbEpisodeOffset = tmdbMapping.episodeOffset,
                )
            } else {
                scheduleTmdbEpisodeNumber(episode.sort)
            }
            when {
                tvId != null && tmdbCandidate == null -> {
                    seasonImages = null
                    seasonImagesNotice = "TMDB 详情暂时无法确认，已保留 Bangumi 图片。"
                }
                tvId != null && !tmdbIdentityAccepted -> {
                    seasonImages = null
                    seasonImagesNotice = "TMDB 身份与当前 Bangumi 条目不一致，已保留 Bangumi 图片。"
                }
                validatedTvId == null -> {
                    seasonImages = null
                    seasonImagesNotice = "没有可靠的 TMDB 映射，剧集暂用番剧图片。"
                }
                inferredSeasonNumber == null -> {
                    seasonImages = null
                    seasonImagesNotice = "无法可靠确认 TMDB 季号，为避免错配没有猜测第一季。"
                }
                else -> runSuspendCatching { tmdbApi.fetchSeasonImages(validatedTvId, inferredSeasonNumber) }
                    .fold(
                        onSuccess = { loaded ->
                            seasonImages = loaded
                            val episodeNumbers = episodes.mapNotNull(::mappedEpisodeNumber).toSet()
                            val covered = episodeNumbers.count { it in loaded.stillPaths }
                            seasonImagesNotice = when {
                                loaded.stillPaths.isEmpty() -> "TMDB 本季暂未提供逐集剧照，已使用番剧图片补位。"
                                covered < episodeNumbers.size -> "TMDB 只提供了部分剧集的剧照，其余使用番剧图片补位。"
                                else -> null
                            }
                        },
                        onFailure = {
                            seasonImages = null
                            seasonImagesNotice = "TMDB 集照暂时无法加载，已使用番剧图片补位。"
                        },
                    )
            }
        }
        if (subject == null && tmdb == null) error = "在线详情暂时不可用，请稍后重试"
        loading = false
    }
    LaunchedEffect(entry.subjectId, localCommentEpisodes, detailPagerState.settledPage, endpoints.identity) {
        val page = detailPagerState.settledPage
        commentState.configure(
            key = entry.subjectId,
            subject = entry.subjectId,
            episodes = localCommentEpisodes,
            active = page == 1,
            preloadFirstPage = true,
            initialMode = BangumiCommentMode.REVIEWS,
        )
        commentBoxState.configure(entry.subjectId, active = page == 2)
        topicState.configure(entry.subjectId, active = page == 3)
    }
    BangumiCommentAutoLoadEffect(commentState, commentListState, enabled = detailPagerState.settledPage == 1)
    BangumiAutoLoadMoreEffect(
        listState = commentBoxListState,
        enabled = detailPagerState.settledPage == 2,
        hasMore = commentBoxState.hasMore,
        error = commentBoxState.error,
        onLoadMore = commentBoxState::loadMore,
        restartKey = "box-${entry.subjectId}",
    )
    BangumiAutoLoadMoreEffect(
        listState = topicListState,
        enabled = detailPagerState.settledPage == 3,
        hasMore = topicState.hasMore,
        error = topicState.error,
        onLoadMore = topicState::loadMore,
        restartKey = "topic-${entry.subjectId}",
    )

    val resolvedBackdropUrl = (tmdb?.backdropPath ?: tmdbImages?.backdropPath)
        ?.let { tmdbApi.imageUrl(it, "w1280") }
    val resolvedPosterUrl = (tmdb?.posterPath ?: tmdbImages?.posterPath)
        ?.let { tmdbApi.imageUrl(it, "w780") }
        ?: endpoints.resolveImageUrl(subject?.posterUrl ?: entry.posterUrl)
    val resolvedSeasonPosterUrl = seasonImages?.posterPath?.let { tmdbApi.imageUrl(it, "w780") }
    val episodeFallbackUrl = resolvedBackdropUrl ?: resolvedSeasonPosterUrl ?: resolvedPosterUrl

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text(
                        text = subject?.title ?: tmdb?.name ?: entry.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    Box {
                        TextButton(onClick = { statusMenuExpanded = true }) {
                            Text(if (currentStatus == ScheduleStatus.NONE) "标记" else currentStatus.label)
                        }
                        DropdownMenu(expanded = statusMenuExpanded, onDismissRequest = { statusMenuExpanded = false }) {
                            ScheduleStatus.entries.forEach { status ->
                                DropdownMenuItem(
                                    text = { Text(status.label) },
                                    onClick = {
                                        statusMenuExpanded = false
                                        currentStatus = status
                                        onStatusChanged(status)
                                    },
                                )
                            }
                        }
                    }
                    if (aniRssRepository != null && entry.subjectId > 0) {
                        IconButton(onClick = { showSubscription = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "添加 Ani-RSS")
                        }
                    }
                    if (loading) {
                        CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(20.dp), strokeWidth = 2.dp)
                    }
                },
            )
        },
    ) { contentPadding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(contentPadding)) {
            val density = LocalDensity.current
            val tabRowHeight = with(density) { tabRowHeightPx.toDp() }.takeIf { it > 0.dp } ?: 48.dp
            val pagerHeight = (maxHeight - tabRowHeight).coerceAtLeast(0.dp)
            LazyColumn(state = detailListState, modifier = Modifier.fillMaxSize()) {
            item {
                OnlineScheduleHeader(
                    entry = entry,
                    subject = subject,
                    tmdb = tmdb,
                    backdropUrl = resolvedBackdropUrl,
                    posterUrl = resolvedPosterUrl,
                )
            }
            item {
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                localEpisodesError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            stickyHeader {
                val tabs = listOf("剧集", "评论", "吐槽", "讨论版")
                PrimaryTabRow(
                    selectedTabIndex = detailPagerState.currentPage,
                    modifier = Modifier.onSizeChanged { tabRowHeightPx = it.height },
                ) {
                    tabs.forEachIndexed { index, label ->
                        Tab(
                            selected = detailPagerState.currentPage == index,
                            onClick = { detailScope.launch { detailPagerState.animateScrollToPage(index) } },
                            text = { Text(label) },
                        )
                    }
                }
            }
            item {
                HorizontalPager(
                    state = detailPagerState,
                    modifier = Modifier.fillMaxWidth().height(pagerHeight),
                    beyondViewportPageCount = 1,
                ) { page ->
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val distance = detailPagerState.getOffsetDistanceInPages(page).coerceIn(-1f, 1f)
                            translationX = distance * 24.dp.toPx()
                            alpha = 1f - kotlin.math.abs(distance) * 0.2f
                        },
                    ) {
                    when (page) {
                        0 -> LazyColumn(state = episodeListState, modifier = Modifier.fillMaxSize().nestedScroll(episodesCollapseConnection)) {
                            item { Text("剧集", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp)) }
                            seasonImagesNotice?.let { notice ->
                                item {
                                    Text(
                                        notice,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (episodes.isEmpty() && !loading) {
                                item { Text("暂无剧集数据", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            } else {
                                lazyItems(episodes, key = { "online-ep-${it.sort}" }) { episode ->
                                        val exactLocalSeasonMatch = entry.libraryMatch
                                            ?.takeIf { it.confirmed && it.seasonNumber != null && it.seasonNumber >= 0 }
                                        val localEpisodeNumber = exactLocalSeasonMatch
                                            ?.let { scheduleLocalEpisodeNumber(episode.sort, it.bangumiOffset) }
                                    val localEpisode = localEpisodeNumber?.let { localEpisodesByNumber[it] }
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = localEpisode != null) {
                                                onPlayLocalEpisode(entry, localEpisode!!.episode.episode_number)
                                            }
                                            .padding(
                                                horizontal = AnimeDetailLayout.episodeRowHorizontalPadding,
                                                vertical = AnimeDetailLayout.episodeRowVerticalPadding,
                                            ),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        val mappingContext = localSeasonContext
                                        val tmdbEpisodeNumber = if (mappingContext?.tmdbMapping != null) {
                                            scheduleMappedTmdbEpisodeNumber(
                                                bangumiSort = episode.sort,
                                                bangumiEpisode = episode.episode,
                                                bangumiOffset = mappingContext.season.bangumi_offset,
                                                tmdbEpisodeOffset = mappingContext.tmdbMapping.episodeOffset,
                                            )
                                        } else {
                                            scheduleTmdbEpisodeNumber(episode.sort)
                                        }
                                        val stillUrl = tmdbEpisodeNumber
                                            ?.let { seasonImages?.stillPaths?.get(it) }
                                            ?.let { tmdbApi.imageUrl(it, "w300") }
                                        val episodeImageUrl = stillUrl ?: episodeFallbackUrl
                                        if (episodeImageUrl != null) {
                                            val episodeImageModel = remember(episodeImageUrl) {
                                                bangumiImageModel(context, episodeImageUrl)
                                            }
                                            RemotePreviewableImageBox(
                                                imageUrl = episodeImageUrl,
                                                contentDescription = if (stillUrl != null) {
                                                    "E${episode.sort.toInt()} 剧照"
                                                } else {
                                                    "${subject?.title ?: entry.title} 番剧图片"
                                                },
                                                saveFileStem = if (stillUrl != null) {
                                                    "${subject?.title ?: entry.title} E${episode.sort.toInt()} 剧照"
                                                } else {
                                                    "${subject?.title ?: entry.title} 番剧图片"
                                                },
                                                onPreviewTap = localEpisode?.let { playable ->
                                                    { onPlayLocalEpisode(entry, playable.episode.episode_number) }
                                                },
                                                modifier = Modifier.size(
                                                    AnimeDetailLayout.episodeThumbWidth,
                                                    AnimeDetailLayout.episodeThumbHeight,
                                                ),
                                            ) {
                                                AsyncImage(
                                                    model = episodeImageModel,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium),
                                                )
                                            }
                                        } else {
                                            Surface(
                                                modifier = Modifier.size(
                                                    AnimeDetailLayout.episodeThumbWidth,
                                                    AnimeDetailLayout.episodeThumbHeight,
                                                ),
                                                shape = MaterialTheme.shapes.medium,
                                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        "E${episode.sort.toInt()}",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "E${episode.sort.toInt()} ${episode.title.orEmpty()}".trim(),
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            episode.plot?.takeIf { it.isNotBlank() }?.let {
                                                Text(
                                                    it,
                                                    maxLines = 3,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(top = 2.dp),
                                                )
                                            }
                                            localEpisode?.let { playable ->
                                                val progress = playable.progress
                                                if (!playable.isCompleted && progress != null && progress > 0.0) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    ) {
                                                        LinearProgressIndicator(
                                                            progress = { progress.toFloat().coerceIn(0f, 1f) },
                                                            modifier = Modifier.weight(1f),
                                                        )
                                                        Text(
                                                            "${(progress * 100).toInt().coerceIn(0, 100)}%",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        localEpisode?.let { playable ->
                                            Surface(
                                                shape = RoundedCornerShape(999.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Icon(
                                                        if (playable.isCompleted) Icons.Filled.CheckCircle else Icons.Filled.PlayArrow,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                    Text(
                                                        if (playable.isCompleted) "已看完" else "本地",
                                                        style = MaterialTheme.typography.labelSmall,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                        1 -> LazyColumn(state = commentListState, modifier = Modifier.fillMaxSize().nestedScroll(commentCollapseConnection)) {
                            bangumiCommentItems(state = commentState, onOpenBangumiLink = {}, onOpenReview = { reviewTarget = it }, showEpisodePicker = true, sourceLabel = endpoints.sourceLabel, emojiBaseUrl = endpoints.imageBaseUrl, allowedImageHosts = endpoints.allowedAvatarHosts)
                        }
                        2 -> LazyColumn(state = commentBoxListState, modifier = Modifier.fillMaxSize().nestedScroll(commentBoxCollapseConnection)) {
                            bangumiCommentBoxItems(state = commentBoxState, onOpenBangumiLink = {}, sourceLabel = endpoints.sourceLabel, emojiBaseUrl = endpoints.imageBaseUrl, allowedImageHosts = endpoints.allowedAvatarHosts)
                        }
                        else -> LazyColumn(state = topicListState, modifier = Modifier.fillMaxSize().nestedScroll(topicCollapseConnection)) {
                            bangumiTopicItems(state = topicState, onOpenBangumiLink = {}, onOpenTopic = { topicTarget = it }, sourceLabel = endpoints.sourceLabel)
                        }
                    }
                    }
                }
            }
            }
        }
    }
    reviewTarget?.let { review ->
        BangumiReviewDialog(
            review = review,
            provider = commentProvider,
            emojiBaseUrl = endpoints.imageBaseUrl,
            allowedImageHosts = endpoints.allowedAvatarHosts,
            sourceLabel = endpoints.sourceLabel,
            onDismiss = { reviewTarget = null },
        )
    }
    topicTarget?.let { topic ->
        BangumiTopicDialog(
            topic = topic,
            provider = commentProvider,
            emojiBaseUrl = endpoints.imageBaseUrl,
            allowedImageHosts = endpoints.allowedAvatarHosts,
            sourceLabel = endpoints.sourceLabel,
            onDismiss = { topicTarget = null },
        )
    }
    if (showSubscription && aniRssRepository != null) {
        AniRssSubscriptionWizard(
            entry = entry,
            repository = aniRssRepository,
            resolvePosterUrl = { endpoints.resolveImageUrl(it) },
            onDismiss = { showSubscription = false },
            onAdded = { showSubscription = false; onSubscribed() },
        )
    }
}

@Composable
private fun OnlineScheduleHeader(
    entry: ScheduleEntry,
    subject: BangumiScrapeSubject?,
    tmdb: TmdbTvDetails?,
    backdropUrl: String?,
    posterUrl: String?,
) {
    val context = LocalContext.current
    fun imageModel(url: String?): Any? = url?.let { bangumiImageModel(context, it) }
    val backdropModel = remember(backdropUrl) { imageModel(backdropUrl) }
    val posterModel = remember(posterUrl) { imageModel(posterUrl) }
    var backdropFailed by remember(backdropUrl) { mutableStateOf(false) }
    var summaryExpanded by rememberSaveable(entry.subjectId) { mutableStateOf(false) }
    val usesPosterAsBackdrop = backdropModel == null || backdropFailed
    val visibleBackdropModel = if (usesPosterAsBackdrop) posterModel else backdropModel
    val visibleBackdropUrl = if (usesPosterAsBackdrop) posterUrl else backdropUrl
    val weekdayLabel = WEEKDAY_LABELS.getOrNull(entry.weekday - 1)
    val firstAirDate = tmdb?.firstAirDate ?: subject?.date ?: entry.airDate
    Box(
        Modifier
            .fillMaxWidth()
            .height(AnimeDetailLayout.headerHeight)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        RemotePreviewableImageBox(
            imageUrl = visibleBackdropUrl,
            contentDescription = "${entry.title} 背景图",
            saveFileStem = "${entry.title} 背景图",
            clickOpensPreview = true,
            modifier = Modifier.fillMaxSize(),
        ) {
            AsyncImage(
                model = visibleBackdropModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { backdropFailed = true },
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Color.Black.copy(alpha = if (usesPosterAsBackdrop) 0.55f else 0.4f),
            ),
        )
        Row(
            Modifier.fillMaxSize().padding(AnimeDetailLayout.headerPadding),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .size(AnimeDetailLayout.posterWidth, AnimeDetailLayout.posterHeight)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.title.firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                RemotePreviewableImageBox(
                    imageUrl = posterUrl,
                    contentDescription = entry.title,
                    saveFileStem = "${entry.title} 海报",
                    clickOpensPreview = true,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AsyncImage(
                        model = posterModel,
                        contentDescription = entry.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(
                Modifier.padding(start = AnimeDetailLayout.headerContentSpacing).weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    subject?.title ?: tmdb?.name ?: entry.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                (subject?.originalTitle ?: tmdb?.originalName)?.takeIf {
                    it != subject?.title && it != tmdb?.name
                }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val scheduleText = buildString {
                    weekdayLabel?.let { append("周").append(it) }
                    entry.broadcastTime?.let {
                        if (isNotEmpty()) append(" ")
                        append(it)
                    }
                }.ifBlank { "播出时间未知" }
                Text(
                    "$scheduleText  ·  首播 ${firstAirDate ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    subject?.rating?.let { Text("Bangumi %.1f".format(it), color = Color.White) }
                    tmdb?.voteAverage?.let { Text("TMDB %.1f".format(it), color = Color.White) }
                }
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(
            horizontal = AnimeDetailLayout.summaryHorizontalPadding,
            vertical = AnimeDetailLayout.summaryVerticalPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tmdb?.genres?.takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        (tmdb?.overview ?: subject?.summary)?.takeIf { it.isNotBlank() }?.let { summary ->
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (summaryExpanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { summaryExpanded = !summaryExpanded },
            )
        }
    }
}

private fun ScheduleSnapshot?.orEmptyEntries(): List<ScheduleEntry> = this?.entries.orEmpty()
private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 两类番剧详情使用同一跟手位移/缩放/透明度曲线。 */
private fun Modifier.predictiveDetailTransform(state: PredictiveDetailBackState): Modifier = graphicsLayer {
    val progress = state.progress.coerceIn(0f, 1f)
    translationX = size.width * progress
    scaleX = 1f - 0.035f * progress
    scaleY = 1f - 0.035f * progress
    alpha = 1f - 0.08f * progress
}

@Composable
private fun rememberScheduleHeaderCollapseConnection(
    innerListState: LazyListState,
    headerListState: LazyListState,
): NestedScrollConnection = remember(innerListState, headerListState) {
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val shouldConsume = available.y < 0f || (
                available.y > 0f &&
                    innerListState.firstVisibleItemIndex == 0 &&
                    innerListState.firstVisibleItemScrollOffset == 0
                )
            if (!shouldConsume) return Offset.Zero
            val consumed = headerListState.dispatchRawDelta(-available.y)
            return Offset(0f, -consumed)
        }
    }
}

/**
 * 海报墙列表态(AnimeScreen 的列表分支, 抽出避免 AnimeScreen 内联过深)。
 *
 * 顶部 TopAppBar: 库下拉 + 增量扫描 + 更多(全量扫描/编辑当前库/删除当前库) + 添加。
 * 内容: loading 转圈 / 无库引导添加 / 无番剧引导扫描 / LazyVerticalGrid
 * (显示已隐藏切换 + 正常段[季度分组 or 平铺] + 隐藏段[展开时])。
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
    onIncrementalScan: () -> Unit,
    onFullScan: () -> Unit,
    onStopScan: () -> Unit,
    onAddLibrary: () -> Unit,
    onEditLibrary: () -> Unit,
    onDeleteLibrary: () -> Unit,
    onOpenShow: (Long, Long) -> Unit,
    onBatchScrape: () -> Unit,
    batchScrapeState: BatchScrapeCoordinator.State,
    onStopBatchScrape: () -> Unit,
    isSearching: Boolean,
    searchQuery: String,
    searchScope: SearchScope,
    searchResults: List<ListShowsByLibrary>,
    onSearchQueryChange: (String) -> Unit,
    onSearchScopeChange: (SearchScope) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    // gridState 在列表分支内 rememberSaveable: 覆盖层模式下列表始终组合, gridState 不随详情销毁,
    // 滚动位置天然保持(进详情返回不丢); Saver 兼顾配置变更/切 tab 恢复 firstVisibleItemIndex/offset。
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
                    // 顶部刷新只发现新增番剧，避免重复读取已记录番剧目录。
                    IconButton(onClick = onIncrementalScan, enabled = canScan) {
                        Icon(Icons.Filled.Refresh, contentDescription = "增量扫描")
                    }
                    // 更多: 全量扫描 / 编辑当前库 / 删除当前库
                    Box {
                        IconButton(onClick = { moreMenuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = moreMenuExpanded,
                            onDismissRequest = { moreMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("全量扫描") },
                                enabled = canScan,
                                onClick = { moreMenuExpanded = false; onFullScan() },
                            )
                            DropdownMenuItem(
                                text = { Text("编辑当前库") },
                                enabled = selectedLibrary != null,
                                onClick = { moreMenuExpanded = false; onEditLibrary() },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (batchScrapeState.isRunning) "批量补刮中…" else "批量补刮缺元数据番剧")
                                },
                                enabled = selectedLibrary != null && !batchScrapeState.isRunning,
                                onClick = { moreMenuExpanded = false; onBatchScrape() },
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
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 56.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text(
                                        text = scanStatus.ifBlank { "正在扫描媒体库..." },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 8.dp),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                TextButton(
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                    onClick = onStopScan,
                                ) { Text("停止") }
                            }
                        }
                        if (batchScrapeState.runId > 0L &&
                            (batchScrapeState.isRunning || batchScrapeState.libraryId == selectedLibrary.id)
                        ) {
                            BatchScrapeStatus(
                                progress = BatchScrapeProgress(
                                    batchScrapeState.completed,
                                    batchScrapeState.total,
                                    batchScrapeState.currentTitle,
                                ),
                                status = batchScrapeState.status,
                                isRunning = batchScrapeState.isRunning,
                                isStopping = batchScrapeState.isStopping,
                                onStop = onStopBatchScrape,
                            )
                        }
                        if (!isSearching && shows.isEmpty() && hiddenShows.isEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        if (isScanning) "扫描中..." else "无番剧，点增量扫描添加",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (!isScanning) {
                                        Button(
                                            onClick = onIncrementalScan,
                                            modifier = Modifier.padding(top = 12.dp),
                                        ) { Text("增量扫描") }
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
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    // 搜索框之外的内容区域是明确的“点击空白取消焦点”区域。
                                    // 不消费指针事件，避免影响网格滚动和卡片点击。
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            var isTap = true
                                            do {
                                                val event = awaitPointerEvent()
                                                isTap = isTap && event.changes.none { it.isConsumed }
                                            } while (event.changes.any { it.pressed })
                                            if (isTap) {
                                                focusManager.clearFocus()
                                            }
                                        }
                                    },
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
                                val normal = shows

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

                                // === 正常段: 季度分组 or 平铺 ===
                                if (settings.posterWallGroupByQuarter) {
                                    // 按 min_release_date 的 yyyy-MM 分组；groupBy 保留查询顺序。
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
        posterPath = show.card_poster_path,
        posterPathKind = ScrapedImagePathKind.fromStorage(show.card_poster_path_kind),
        fallbackPosterPath = show.card_online_poster_path,
        fallbackFanartPath = show.card_online_fanart_path,
        imageCacheSizeMb = settings.posterWallImageCacheSizeMb,
        downloader = { dest ->
            show.card_poster_path?.let { path ->
                mediaSourceCache.withSource(lib) { source ->
                    source.downloadToFile(path, dest, MAX_POSTER_IMAGE_BYTES)
                } ?: false
            } ?: false
        },
        onClick = { onOpenShow(show.id, lib.id) },
        modifier = modifier,
        cacheSubdir = show.cacheKey,
        seasonBadge = show.card_season_number?.takeIf { if (settings.posterWallBadgeShowSeason1) it >= 1 else it >= 2 }?.let { "第${it}季" },
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
        posterPath = show.card_poster_path,
        posterPathKind = ScrapedImagePathKind.fromStorage(show.card_poster_path_kind),
        fallbackPosterPath = show.card_online_poster_path,
        fallbackFanartPath = show.card_online_fanart_path,
        imageCacheSizeMb = settings.posterWallImageCacheSizeMb,
        downloader = { dest ->
            if (library == null) {
                false
            } else {
                show.card_poster_path?.let { path ->
                    mediaSourceCache.withSource(library) { source ->
                        source.downloadToFile(path, dest, MAX_POSTER_IMAGE_BYTES)
                    } ?: false
                } ?: false
            }
        },
        onClick = { onOpenShow(show.id, show.library_id) },
        modifier = modifier,
        cacheSubdir = show.cacheKey,
        seasonBadge = show.card_season_number?.takeIf { if (settings.posterWallBadgeShowSeason1) it >= 1 else it >= 2 }?.let { "第${it}季" },
    )
}
