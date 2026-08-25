package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import io.github.weiyongzenqi.unuplayer.core.platform.AppNotif
import io.github.weiyongzenqi.unuplayer.ui.AppBackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.core.media.AnimePlaybackContext
import io.github.weiyongzenqi.unuplayer.core.media.withPlaybackQueue
import io.github.weiyongzenqi.unuplayer.domain.SettingsState
import io.github.weiyongzenqi.unuplayer.library.AnimeScraper
import io.github.weiyongzenqi.unuplayer.library.EpisodeThumbCoordinator
import io.github.weiyongzenqi.unuplayer.library.EpisodeThumbGenerator
import io.github.weiyongzenqi.unuplayer.library.EpisodeThumbPosition
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.MAX_POSTER_IMAGE_BYTES
import io.github.weiyongzenqi.unuplayer.library.MediaSourceCache
import io.github.weiyongzenqi.unuplayer.library.ScanConfig
import io.github.weiyongzenqi.unuplayer.library.ScanResult
import io.github.weiyongzenqi.unuplayer.library.ScanMode
import io.github.weiyongzenqi.unuplayer.library.ScrapeSource
import io.github.weiyongzenqi.unuplayer.library.ScrapedEpisode
import io.github.weiyongzenqi.unuplayer.library.ScrapedImage
import io.github.weiyongzenqi.unuplayer.library.ScrapedImageCandidate
import io.github.weiyongzenqi.unuplayer.library.ScrapedImagePathKind
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryScanner
import io.github.weiyongzenqi.unuplayer.library.mergeLogicalShowCards
import io.github.weiyongzenqi.unuplayer.library.ScrapedOnlineEpisode
import io.github.weiyongzenqi.unuplayer.library.TmdbEpisodeMapping
import io.github.weiyongzenqi.unuplayer.library.ScrapedOnlineMeta
import io.github.weiyongzenqi.unuplayer.library.isOffsetIgnoredEpisode
import io.github.weiyongzenqi.unuplayer.library.ScrapedSeason
import io.github.weiyongzenqi.unuplayer.library.ScrapedShow
import io.github.weiyongzenqi.unuplayer.library.getStoredBangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.library.TmdbAutoMatchFailureState
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideIdentity
import io.github.weiyongzenqi.unuplayer.library.cacheKey
import io.github.weiyongzenqi.unuplayer.library.decodedEpisodes
import io.github.weiyongzenqi.unuplayer.library.validatedTmdbEpisodeMapping
import io.github.weiyongzenqi.unuplayer.library.isMissingLocalFilePath
import io.github.weiyongzenqi.unuplayer.library.matchesTmdbStillCoordinates
import io.github.weiyongzenqi.unuplayer.library.sanitizeFileName
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.playback.episodeProgressKey
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonIdentity
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentApi
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentProvider
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiTopic
import io.github.weiyongzenqi.unuplayer.bangumi.resolveEffectiveBangumiLink
import io.github.weiyongzenqi.unuplayer.bangumi.gatewayEndpointOrNull
import io.github.weiyongzenqi.unuplayer.domain.bangumiEndpoints
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleEntry
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleRepository
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleStatus
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleWatch

/** 海报墙详情与 Android 在线详情共用的几何规范，避免两套页面再次漂移。 */
internal object AnimeDetailLayout {
    val headerHeight = 200.dp
    val headerPadding = 16.dp
    val posterWidth = 100.dp
    val posterHeight = 150.dp
    val headerContentSpacing = 16.dp
    val summaryHorizontalPadding = 16.dp
    val summaryVerticalPadding = 12.dp
    val episodeThumbWidth = 120.dp
    val episodeThumbHeight = 68.dp
    val episodeRowHorizontalPadding = 16.dp
    val episodeRowVerticalPadding = 10.dp
}

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
    scheduleRepo: ScheduleRepository? = null,
    imageCacheSizeMb: Int,
    showEpisodeThumb: Boolean,
    /** 生成层开关: 是否对无刮削集照的剧集本地抽帧生成(与 [showEpisodeThumb] 展示层解耦; 关闭后不重新生成, 已生成的照常显示)。 */
    autoGenerateEpisodeThumb: Boolean,
    /** 详情页头部海报是否改用当前季 seasonXX-poster.jpg; false=用 show.poster_path。 */
    useSeasonPoster: Boolean,
    /** 季徽章是否显示第1季(false=第1季不显示徽章, 仅第2季起)。 */
    badgeShowSeason1: Boolean,
    /** 扫描配置(单番剧刷新用, 由 AnimeScreen 从 settings 映射传入)。 */
    scanConfig: ScanConfig,
    /** 全局设置(本部专属设置弹窗的叠加基准, 弹幕派生 toDanmakuConfig + 字幕/音轨字段)。 */
    globalSettings: SettingsState,
    /** 集照生成器(null=不生成, desktop 传 null); 非 null 时对无刮削集照的集懒加载本地抽帧。 */
    episodeThumbGenerator: EpisodeThumbGenerator? = null,
    /** 集照抽帧位置(百分比/秒数, 由调用方从设置项构造; generator 非 null 时生效)。 */
    episodeThumbPosition: EpisodeThumbPosition = EpisodeThumbPosition.Percent(10),
    /** 在线刮削管线(null=不可用, 如未配置弹幕凭证; 懒触发与手动"刮削/纠正"入口都需要)。 */
    scraper: AnimeScraper? = null,
    /** 刮削 hash 提供者(每季至多 1 文件前 16MB MD5 + size; null=跳过 hash 回落文件名; 平台注入)。 */
    scrapeHashProvider: (suspend (videoPath: String) -> Pair<Long, String>?)? = null,
    onPlay: (PlayableMedia) -> Unit,
    onShowChanged: () -> Unit,
    /** 标记成功后让平台复用播放记录的自动同步防抖通道。 */
    onScheduleWatchChanged: () -> Unit = {},
    onBack: () -> Unit,
    /** Android 海报墙由父覆盖层接管预测性返回时关闭内部普通 BackHandler。 */
    handleSystemBack: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val recognizeAnimeState = rememberUpdatedState(globalSettings.recognizeAnime)
    val bangumiEndpoints = globalSettings.bangumiEndpoints()
    val commentProvider = remember(bangumiEndpoints.identity) {
        BangumiCommentProvider(
            api = BangumiCommentApi(
                officialBaseUrl = bangumiEndpoints.apiBaseUrl,
                nextBaseUrl = bangumiEndpoints.nextApiBaseUrl,
                gateway = bangumiEndpoints.gatewayEndpointOrNull(),
            ),
            isEnabled = { recognizeAnimeState.value },
            allowedAvatarHosts = bangumiEndpoints.allowedAvatarHosts,
            imageBaseUrl = bangumiEndpoints.imageBaseUrl,
        )
    }
    val commentState = rememberBangumiCommentUiState(commentProvider)
    val commentBoxState = rememberBangumiCommentBoxUiState(commentProvider)
    val topicState = rememberBangumiTopicUiState(commentProvider)
    // 内容四 Tab Pager: 剧集 | 评论 | 吐槽 | 讨论版, 默认停在「剧集」(index 0)
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { DetailTabPage.values().size })
    val detailListState = rememberLazyListState()
    val episodesListState = rememberLazyListState()
    val commentListState = rememberLazyListState()
    val commentBoxListState = rememberLazyListState()
    val topicListState = rememberLazyListState()
    // 内层列表滚动联动外层头部收起: 上滑先把滚动量喂给外层(头部收起), 列表在顶部时下滑也喂外层(头部展开)。
    // 展开必须在 onPreScroll(外→内分发, 先于列表内部 overscroll 效果)消费, 否则 Android 顶部下拉的
    // overscroll 发光/弹性会先吞掉滚动量, 头部收不回(表现为下拉颤抖但保持收起)。
    val episodesCollapseConnection = rememberHeaderCollapseConnection(episodesListState, detailListState)
    val commentCollapseConnection = rememberHeaderCollapseConnection(commentListState, detailListState)
    val commentBoxCollapseConnection = rememberHeaderCollapseConnection(commentBoxListState, detailListState)
    val topicCollapseConnection = rememberHeaderCollapseConnection(topicListState, detailListState)
    // 回到顶部目标列表: 番剧识别开启时跟随当前 Pager 页, 关闭时回落外层头部列表
    val currentDetailPage = DetailTabPage.fromIndex(pagerState.currentPage)
    val backToTopListState = if (recognizeAnimeState.value) {
        detailTabListState(
            currentDetailPage,
            episodesListState,
            commentListState,
            commentBoxListState,
            topicListState,
        )
    } else {
        detailListState  // 番剧识别关闭: 剧集直出在外层列表, 回到外层顶部
    }
    val showBackToTop by remember {
        derivedStateOf {
            val list = if (recognizeAnimeState.value) {
                detailTabListState(
                    DetailTabPage.fromIndex(pagerState.currentPage),
                    episodesListState,
                    commentListState,
                    commentBoxListState,
                    topicListState,
                )
            } else {
                detailListState
            }
            val innerScrolled = list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 300
            val headerCollapsed = detailListState.firstVisibleItemIndex > 0 ||
                detailListState.firstVisibleItemScrollOffset > 300
            innerScrolled || (recognizeAnimeState.value && headerCollapsed)
        }
    }

    // 番剧识别关闭后旧 Pager 页不再渲染; 重新开启时始终从剧集页开始, 不恢复已清空数据的旧页。
    LaunchedEffect(globalSettings.recognizeAnime) {
        if (!globalSettings.recognizeAnime && pagerState.currentPage != DetailTabPage.EPISODES.index) {
            pagerState.scrollToPage(DetailTabPage.EPISODES.index)
        }
    }
    var show by remember { mutableStateOf<ScrapedShow?>(null) }
    var seasons by remember { mutableStateOf<List<ScrapedSeason>>(emptyList()) }
    var selectedSeasonIndex by remember { mutableStateOf(0) }
    var episodes by remember { mutableStateOf<List<ScrapedEpisode>>(emptyList()) }
    var ownerShowsById by remember { mutableStateOf<Map<Long, ScrapedShow>>(emptyMap()) }
    var onlineMetaBySeasonId by remember { mutableStateOf<Map<Long, ScrapedOnlineMeta>>(emptyMap()) }
    var onlineShowMetaByShowId by remember { mutableStateOf<Map<Long, ScrapedOnlineMeta>>(emptyMap()) }
    var scrapeSeasonTargets by remember { mutableStateOf<List<ScrapeSeasonTarget>>(emptyList()) }
    var progressMap by remember { mutableStateOf<Map<String, PlaybackRecord>>(emptyMap()) }
    // 剧集显示进度(跨库双向跟随): 有三元组的集已解析为"本文件/跨库 last_played_at 较新者"的 watch_progress;
    // 无三元组的集不在其中, UI 回落本文件 progressMap。
    var crossLibProgress by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    // 集照懒加载触发 token: loadEpisodes 后自增, LaunchedEffect(thumbTrigger) 据此触发 coordinator(切季自动取消上一个)
    var thumbTrigger by remember { mutableLongStateOf(0L) }
    // U-2: loadEpisodes 世代令牌(快速切季竞态防护, 见 loadEpisodes)
    var loadEpisodesGeneration by remember { mutableLongStateOf(0L) }
    var episodeThumbFallbackDecision by remember(showId) {
        mutableStateOf(EpisodeThumbFallbackDecision.WAIT_FOR_ONLINE_MATCH)
    }
    var loading by remember { mutableStateOf(true) }
    var detailsReady by remember(showId) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showOverrideDialog by remember { mutableStateOf(false) }
    var showBangumiLinkDialog by remember { mutableStateOf(false) }
    var topicDialogTarget by remember { mutableStateOf<BangumiTopic?>(null) }
    var reviewDialogTarget by remember { mutableStateOf<io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiReview?>(null) }
    var showScrapeDialog by remember { mutableStateOf(false) }
    var scrapeDialogInitialSource by remember { mutableStateOf(defaultScrapeDialogSource) }
    var scrapeDialogAutoSearch by remember { mutableStateOf(false) }
    var scrapeDialogBlocksThumbFallback by remember { mutableStateOf(false) }
    var scrapeDialogIsAutoTmdbPrompt by remember { mutableStateOf(false) }
    var showRestoreNfoDialog by remember { mutableStateOf(false) }
    var automaticScrapeInProgress by remember { mutableStateOf(false) }
    var directTmdbScrapeInProgress by remember { mutableStateOf(false) }
    var scrapeDialogSearchInProgress by remember { mutableStateOf(false) }
    var scrapeDialogApplyInProgress by remember { mutableStateOf(false) }
    var generatingEpisodeThumbs by remember { mutableStateOf(false) }
    var episodeThumbGenerationProgress by remember {
        mutableStateOf<EpisodeThumbCoordinator.Progress?>(null)
    }
    val manualScrapeInProgress = directTmdbScrapeInProgress ||
        scrapeDialogSearchInProgress || scrapeDialogApplyInProgress
    val scrapeInProgress = isOnlineScrapeBusy(automaticScrapeInProgress, manualScrapeInProgress)
    val detailOperationInProgress = scrapeInProgress || generatingEpisodeThumbs
    var scrapeMessage by remember { mutableStateOf<String?>(null) }
    var scrapeProgressMessage by remember { mutableStateOf<String?>(null) }
    // 自动刮削横幅控制(2026-08-14): 进行中可「停止」, 未命中/需确认后横幅可「单次关闭/永久关闭」。
    var autoScrapeBannerMessage by remember(showId) { mutableStateOf<String?>(null) }
    var autoScrapeBannerMenuExpanded by remember { mutableStateOf(false) }
    var autoScrapeJob by remember { mutableStateOf<Job?>(null) }
    var autoScrapeSuppressed by remember(showId) { mutableStateOf(false) }
    var showStopAutoScrapeDialog by remember { mutableStateOf(false) }
    // 本次进入详情页用户已「停止」自动刮削(单次/永久关闭): 深探测重扫成功也不复活自动刮削,
    // 与停止确认/横幅的"本次进入不再自动刮削"承诺一致(显式刷新不走此标记)。
    var autoScrapeStoppedThisVisit by remember(showId) { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var scheduleStatusMenuExpanded by remember { mutableStateOf(false) }
    var scheduleStatusSaving by remember { mutableStateOf(false) }
    var bangumiLinkVersion by remember { mutableLongStateOf(0L) }
    var commentSubjectId by remember { mutableStateOf<Long?>(null) }
    var commentSubjectResolutionKey by remember { mutableStateOf<String?>(null) }
    var commentSubjectConfiguredKey by remember { mutableStateOf<String?>(null) }
    // 封面重试条(批次C): 在线海报/头图有远程 URL 但本地文件缺失(下载失败/文件丢失)时显示;
    // 仅用户点「重试」才恢复, 不自动循环。
    var posterRestoreNeeded by remember(showId) { mutableStateOf(false) }
    var posterRestoreInProgress by remember(showId) { mutableStateOf(false) }

    val selectedSeason = seasons.getOrNull(selectedSeasonIndex)
    // 详情页可以在同一季的上下部分间切换；所有有路径归属的操作必须跟随当前页签的物理 Show，
    // 不能继续使用进入逻辑卡时的代表 Show。
    val activeShow = if (selectedSeason != null) {
        ownerShowsById[selectedSeason.show_id] ?: show?.takeIf { it.id == selectedSeason.show_id }
    } else {
        show
    }
    val activeShowOnlineMeta = activeShow?.let { onlineShowMetaByShowId[it.id] }

    // 缓存子目录必须跟随当前物理分段，避免同 TMDB 同季的上下部分复用错误图片。
    val showKey = activeShow?.cacheKey ?: "unknown"

    // 每张媒体源图片只在实际下载期间租用 source，离页清理不会中途关闭活跃下载。
    val imageDownloader: suspend (String, PlatformFile) -> Boolean = { imagePath, dest ->
        mediaSourceCache.withSource(library) { source ->
            source.downloadToFile(imagePath, dest, MAX_POSTER_IMAGE_BYTES)
        } ?: false
    }

    suspend fun loadPlaybackProgress(
        eps: List<ScrapedEpisode>,
        showSnapshot: ScrapedShow?,
        season: ScrapedSeason?,
    ) {
        progressMap = playbackRepo?.let { repo ->
            val keys = eps.mapNotNull { it.media_key }
            // U-1: 读失败(SQLite 异常/与扫描事务并发)降级为空进度, 不向 LaunchedEffect 抛
            // (同屏 loadOnlineMeta/loadMergedSeasons 已用同款防护, 此处补齐纪律一致性)。
            if (keys.isNotEmpty()) {
                runSuspendCatching { repo.getByMediaKeys(keys) }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
        } ?: emptyMap()
        // 跨库进度与本文件记录取较新者，保证跨库/跨设备续播能反映到详情页。
        val tmdbMapping = season?.let { currentSeason ->
            onlineMetaBySeasonId[currentSeason.id]?.validatedTmdbEpisodeMapping(
                localSeasonNumber = currentSeason.season_number.toInt(),
                localEpisodeNumbers = eps.mapNotNull { episode ->
                    episode.episode_number.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
                },
                bangumiId = currentSeason.bangumi_id,
                bangumiOffset = currentSeason.bangumi_offset.toInt(),
            )
        }
        val tmdbSeasonNumber = tmdbMapping?.seasonNumber?.toLong() ?: season?.season_number
        crossLibProgress = if (playbackRepo != null && showSnapshot?.tmdb_id != null && tmdbSeasonNumber != null) {
            val tmdbId = showSnapshot.tmdb_id
            runSuspendCatching {
                val withTriple = eps.filter { it.media_key != null && it.episode_number > 0 }
                if (withTriple.isNotEmpty()) {
                    val tripleKeys = withTriple.map { ep ->
                        val tmdbEpisode = tmdbMapping?.remoteEpisodeNumber(ep.episode_number) ?: ep.episode_number
                        episodeProgressKey(tmdbId, tmdbSeasonNumber, tmdbEpisode)
                    }
                    val episodeProgress = playbackRepo.getEpisodeProgressByTriples(tripleKeys)
                    withTriple.mapNotNull { ep ->
                        val mk = ep.media_key!!
                        val tmdbEpisode = tmdbMapping?.remoteEpisodeNumber(ep.episode_number) ?: ep.episode_number
                        val key = episodeProgressKey(tmdbId, tmdbSeasonNumber, tmdbEpisode)
                        val cross = episodeProgress[key]
                        val own = progressMap[mk]
                        val resolved = when {
                            cross == null -> own?.watch_progress
                            own == null -> cross.watch_progress
                            cross.last_played_at > own.last_played_at -> cross.watch_progress
                            else -> own.watch_progress
                        }
                        resolved?.let { mk to it }
                    }.toMap()
                } else emptyMap()
            }.getOrDefault(emptyMap())
        } else emptyMap()
    }

    // 加载某季剧集 + 批量查播放进度
    suspend fun loadEpisodes(seasonId: Long) {
        // U-2: 世代令牌。快速切季时慢的旧季加载完成后不得覆盖新季已显示的剧集列表与进度
        // (修复前两个并发 loadEpisodes 乱序完成会把列表写回旧季, 与选中 Tab 不一致)。
        val generation = ++loadEpisodesGeneration
        // U-1: 读失败降级为空列表(同 U-1 纪律), 快速切季竞态时也不因 DB 瞬时异常崩详情页。
        val eps = runSuspendCatching { scrapedRepo.listEpisodes(seasonId) }.getOrDefault(emptyList())
        if (generation != loadEpisodesGeneration) return
        episodes = eps
        val targetSeason = seasons.firstOrNull { it.id == seasonId }
        loadPlaybackProgress(
            eps = eps,
            showSnapshot = if (targetSeason != null) {
                ownerShowsById[targetSeason.show_id] ?: show?.takeIf { it.id == targetSeason.show_id }
            } else {
                show
            },
            season = targetSeason,
        )
        thumbTrigger++  // 触发集照懒加载(切季/首次加载后)
    }

    suspend fun loadOnlineMeta(s: ScrapedShow?, seasonSnapshot: List<ScrapedSeason>) {
        if (s == null) {
            onlineMetaBySeasonId = emptyMap()
            ownerShowsById = emptyMap()
            onlineShowMetaByShowId = emptyMap()
            scrapeSeasonTargets = emptyList()
            return
        }
        val ownerShows = buildMap<Long, ScrapedShow> {
            (seasonSnapshot.map { it.show_id } + s.id).distinct().forEach { ownerId ->
                val owner = if (ownerId == s.id) s else {
                    runSuspendCatching { scrapedRepo.getShow(ownerId) }.getOrNull()
                }
                if (owner != null) put(ownerId, owner)
            }
        }
        ownerShowsById = ownerShows
        val metaByShowId = ownerShows.mapValues { (_, owner) ->
            runSuspendCatching { scrapedRepo.listOnlineMeta(owner.library_id, owner.show_path) }
                .getOrDefault(emptyList())
                .associateBy { it.season_number }
        }
        onlineShowMetaByShowId = metaByShowId.mapNotNull { (ownerId, metas) ->
            metas[0L]?.let { ownerId to it }
        }.toMap()
        onlineMetaBySeasonId = buildMap {
            seasonSnapshot.forEach { season ->
                metaByShowId[season.show_id]?.get(season.season_number)?.let { put(season.id, it) }
            }
        }
        val labels = buildSeasonTabLabels(seasonSnapshot)
        scrapeSeasonTargets = seasonSnapshot.mapNotNull { season ->
            ownerShows[season.show_id]?.show_path?.let { path ->
                ScrapeSeasonTarget(
                    seasonId = season.id,
                    seasonNumber = season.season_number.toInt(),
                    showPath = path,
                    label = labels.getValue(season.id),
                )
            }
        }
    }

    /**
     * 详情页展示同库、同 TMDB 的全部物理季度(含跨季号目录)；海报墙外层卡片仍由
     * [mergeLogicalShowCards] 按同季分段规则合并，两处口径独立。多季物理目录(一个
     * 目录自带多个 Season)只展示自己的季度，避免与其它目录混合产生重复页签。
     */
    suspend fun loadMergedSeasons(s: ScrapedShow?): List<ScrapedSeason> {
        if (s == null) return emptyList()
        val own = runSuspendCatching { scrapedRepo.listSeasons(s.id) }.getOrDefault(emptyList())
        if (s.tmdb_id == null || own.size != 1) return sortLogicalSeasons(own)
        val allTmdbSeasons = runSuspendCatching {
            scrapedRepo.listSeasonsByTmdb(library.id, s.tmdb_id)
        }.getOrDefault(own)
        return sortLogicalSeasons(allTmdbSeasons.ifEmpty { own })
    }

    // 首次加载: show -> seasons -> 首季 episodes
    LaunchedEffect(showId) {
        loading = true
        detailsReady = false
        try {
            // U-1: show 读失败(库已被删/SQLite 异常)降级为"无数据详情页"(show=null, 空季列表,
            // 该状态 UI 已能正常渲染), 不向 LaunchedEffect 抛致 Recomposer 崩溃。
            val s = runSuspendCatching { scrapedRepo.getShow(showId) }.getOrNull()
            show = s
            val merged = loadMergedSeasons(s)
            seasons = merged
            loadOnlineMeta(s, merged)
            if (merged.isNotEmpty()) {
                // 从哪张物理卡进入就默认选其所属分段；逻辑卡仍能切到同 TMDB 的其它目录。
                val idx = merged.indexOfFirst { it.show_id == s?.id }.coerceAtLeast(0)
                selectedSeasonIndex = idx
                loadEpisodes(merged[idx].id)
            }
        } finally {
            detailsReady = true
            loading = false
        }
    }

    val selectedSeasonOnlineMeta = selectedSeason?.let { onlineMetaBySeasonId[it.id] }
    val selectedTmdbEpisodeMapping = if (selectedSeason != null && selectedSeasonOnlineMeta != null) {
        selectedSeasonOnlineMeta.validatedTmdbEpisodeMapping(
            localSeasonNumber = selectedSeason.season_number.toInt(),
            localEpisodeNumbers = episodes.mapNotNull { episode ->
                episode.episode_number.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
            },
            bangumiId = selectedSeason.bangumi_id,
            bangumiOffset = selectedSeason.bangumi_offset.toInt(),
        )
    } else {
        null
    }
    val scheduleWatches by remember(scrapedRepo) {
        scrapedRepo.observeScheduleWatches()
    }.collectAsState(initial = emptyList())
    val selectedScheduleWatch = commentSubjectId?.let { subjectId ->
        scheduleWatches.firstOrNull { it.subjectId == subjectId }
    }
    val selectedScheduleStatus = selectedScheduleWatch?.status ?: ScheduleStatus.NONE
    val seasonTabLabels = remember(seasons) { buildSeasonTabLabels(seasons) }
    val onlineEpisodeByNumber = remember(selectedSeasonOnlineMeta?.episode_json) {
        selectedSeasonOnlineMeta?.decodedEpisodes.orEmpty().associateBy { it.episodeNumber.toLong() }
    }

    val activeShowPath = activeShow?.show_path
    val autoScrapeTriggered = remember(showId, activeShowPath) { mutableStateOf(false) }
    val forceAutoScrape = remember(showId, activeShowPath) { mutableStateOf(false) }
    var libraryRefreshReady by remember(showId, activeShowPath) { mutableStateOf(false) }
    val autoTmdbPromptHandled = remember(showId, activeShowPath) { mutableStateOf(false) }
    var autoScrapeGeneration by remember(showId, activeShowPath) { mutableLongStateOf(0L) }
    val localCommentEpisodes = remember(episodes, selectedSeason?.bangumi_offset) {
        val commentSeasonOffset = selectedSeason?.bangumi_offset ?: 0L
        episodes.map { ep ->
            // 被忽略集(先行篇)在评论集选择器里同样显示原始文件名, 与集列表保持一致
            LocalCommentEpisode(
                id = ep.id,
                number = ep.episode_number,
                title = if (isOffsetIgnoredEpisode(commentSeasonOffset, ep.episode_number)) ep.video_name else ep.title,
            )
        }
    }

    // 懒触发在线刮削(定义在 reloadAfterRefresh 之后; 见其下方, 因局部函数不支持前向引用)

    // 标记和评论都只接受数据库/扫描器已经确认的季度关联；切季或关联变更后立即重读，不猜 subject ID。
    // 即使关闭在线识别也读取本地关联，保证标记是本地数据库操作，不因评论开关失效或触发联网。
    LaunchedEffect(activeShow, selectedSeason, bangumiLinkVersion, detailsReady) {
        if (!detailsReady) return@LaunchedEffect
        val currentShow = activeShow
        val currentSeason = selectedSeason
        commentSubjectResolutionKey = null
        commentSubjectConfiguredKey = null
        commentSubjectId = null
        if (currentShow == null || currentSeason == null) return@LaunchedEffect
        val identityKey = BangumiSeasonIdentity.keyFor(currentShow, currentSeason)
        val persisted = runSuspendCatching {
            scrapedRepo.getStoredBangumiSeasonLink(currentShow, currentSeason).link
        }.getOrNull()
        commentSubjectId = resolveEffectiveBangumiLink(persisted, currentSeason.bangumi_id)?.subjectId
        commentSubjectResolutionKey = identityKey
    }

    LaunchedEffect(
        selectedSeason?.id,
        commentSubjectId,
        localCommentEpisodes,
        pagerState.settledPage,
        globalSettings.recognizeAnime,
        commentSubjectResolutionKey,
        detailsReady,
    ) {
        if (!globalSettings.recognizeAnime || !detailsReady) {
            commentState.deactivate()
            commentBoxState.deactivate()
            topicState.deactivate()
            commentProvider.clear()
            return@LaunchedEffect
        }
        val currentSeason = selectedSeason
        if (currentSeason == null) {
            commentState.deactivate()
            commentBoxState.deactivate()
            topicState.deactivate()
            return@LaunchedEffect
        }
        val currentShow = activeShow
        val identityKey = currentShow?.let { BangumiSeasonIdentity.keyFor(it, currentSeason) }
        if (identityKey == null || commentSubjectResolutionKey != identityKey) {
            commentState.deactivate()
            commentBoxState.deactivate()
            topicState.deactivate()
            commentSubjectConfiguredKey = null
            return@LaunchedEffect
        }
        val page = pagerState.settledPage
        commentState.configure(
            key = currentSeason.id,
            subject = commentSubjectId,
            episodes = localCommentEpisodes,
            active = page == 1,
            preloadFirstPage = true,
            initialMode = BangumiCommentMode.REVIEWS,
            bangumiEpisodeOffset = currentSeason.bangumi_offset,
        )
        commentBoxState.configure(subject = commentSubjectId, active = page == 2)
        topicState.configure(subject = commentSubjectId, active = page == 3)
        commentSubjectConfiguredKey = identityKey
    }

    val currentCommentIdentityKey = activeShow?.let { currentShow ->
        selectedSeason?.let { currentSeason -> BangumiSeasonIdentity.keyFor(currentShow, currentSeason) }
    }
    val commentSubjectResolving = globalSettings.recognizeAnime && currentCommentIdentityKey != null &&
        (commentSubjectResolutionKey != currentCommentIdentityKey || commentSubjectConfiguredKey != currentCommentIdentityKey)

    BangumiCommentAutoLoadEffect(
        state = commentState,
        listState = commentListState,
        enabled = globalSettings.recognizeAnime && !commentSubjectResolving &&
            pagerState.settledPage == DetailTabPage.COMMENTS.index,
    )
    BangumiAutoLoadMoreEffect(
        listState = commentBoxListState,
        enabled = globalSettings.recognizeAnime && !commentSubjectResolving &&
            pagerState.settledPage == DetailTabPage.COMMENT_BOX.index,
        hasMore = commentBoxState.hasMore,
        error = commentBoxState.error,
        onLoadMore = commentBoxState::loadMore,
        restartKey = commentBoxState.subjectId,
    )
    BangumiAutoLoadMoreEffect(
        listState = topicListState,
        enabled = globalSettings.recognizeAnime && !commentSubjectResolving &&
            pagerState.settledPage == DetailTabPage.TOPICS.index,
        hasMore = topicState.hasMore,
        error = topicState.error,
        onLoadMore = topicState::loadMore,
        restartKey = topicState.subjectId,
    )

    // 两端播放器都通过仓库版本通知写入完成；只重读当前季进度，不重复加载剧集或生成集照。
    LaunchedEffect(playbackRepo) {
        playbackRepo?.changeVersion?.collect { version ->
            if (version == 0L) return@collect
            val currentSeason = seasons.getOrNull(selectedSeasonIndex)
            // activeShow 是普通派生 val, 本 effect 只随 playbackRepo 重启, 闭包会捕获首帧的
            // null(分段归属改造时误换引入回归: 播放返回后进度不再刷新); 必须用 State 委托源
            // (ownerShowsById/show)在此重算, 语义与 activeShow 定义一致。
            val currentShow = currentSeason?.let { season ->
                ownerShowsById[season.show_id] ?: show?.takeIf { it.id == season.show_id }
            } ?: show
            val currentEpisodes = episodes
            if (currentShow != null && currentSeason != null && currentEpisodes.isNotEmpty()) {
                loadPlaybackProgress(currentEpisodes, currentShow, currentSeason)
            }
        }
    }

    // 本地集照只作为在线匹配失败后的回退；匹配期间及命中后均不启动，避免争用媒体源带宽。
    LaunchedEffect(
        thumbTrigger,
        episodeThumbFallbackDecision,
        scrapeInProgress,
        generatingEpisodeThumbs,
        autoGenerateEpisodeThumb,
        episodeThumbGenerator,
        episodeThumbPosition,
    ) {
        if (thumbTrigger == 0L) return@LaunchedEffect
        if (episodeThumbFallbackDecision != EpisodeThumbFallbackDecision.GENERATE_IF_ENABLED) return@LaunchedEffect
        if (scrapeInProgress || generatingEpisodeThumbs) return@LaunchedEffect
        val s = activeShow ?: return@LaunchedEffect
        // 被忽略集(先行篇)只认 NFO 集照, 抽帧结果不进候选, 直接排除免得白下载解码
        val eps = episodes.filterNot { ep ->
            isOffsetIgnoredEpisode(seasons.getOrNull(selectedSeasonIndex)?.bangumi_offset ?: 0L, ep.episode_number)
        }
        // 生成层闸门用 autoGenerateEpisodeThumb(展示层 showEpisodeThumb 仅控制剧集列表是否渲染缩略图)
        if (eps.isEmpty() || episodeThumbGenerator == null || !autoGenerateEpisodeThumb) return@LaunchedEffect
        val seasonId = seasons.getOrNull(selectedSeasonIndex)?.id
        val onlineThumbEpisodeNumbers = buildSet {
            seasonId?.let { onlineMetaBySeasonId[it] }
                ?.decodedEpisodes
                .orEmpty()
                .forEach { onlineEpisode ->
                    val path = onlineEpisode.thumbPath
                    if (!path.isNullOrBlank() && !isMissingLocalFilePath(path)) {
                        add(onlineEpisode.episodeNumber.toLong())
                    }
                }
        }
        runSuspendCatching {
            EpisodeThumbCoordinator.ensureThumbs(
                episodes = eps,
                onlineThumbEpisodeNumbers = onlineThumbEpisodeNumbers,
                showKey = s.cacheKey,
                library = library,
                mediaSourceCache = mediaSourceCache,
                generator = episodeThumbGenerator,
                position = episodeThumbPosition,
                scrapedRepo = scrapedRepo,
            ) { id, path ->
                episodes = episodes.map { ep -> if (ep.id == id) ep.copy(local_thumb_path = path) else ep }
            }
        }
    }

    // 播放任意 MediaEntry(剧集列表用 playEpisode; 原始目录浏览器用 playMediaEntry 直接播)
    fun playMediaEntry(entry: MediaEntry, animeContext: AnimePlaybackContext? = null) {
        scope.launch {
            val media = runSuspendCatching {
                mediaSourceCache.withSource(library) { source ->
                    source.resolvePlayMedia(entry)
                }
            }.getOrNull()
            media?.copy(animeContext = animeContext)?.let(onPlay)
        }
    }

    // 播放剧集: 当前季数据库顺序即选集顺序，并随播放请求带入完整会话队列。
    fun playEpisode(ep: ScrapedEpisode) {
        val s = activeShow
        val selected = seasons.getOrNull(selectedSeasonIndex)
        val tmdbMapping = selectedTmdbEpisodeMapping
        val tmdbSeasonNumber = tmdbMapping?.seasonNumber?.toLong() ?: selected?.season_number
        val currentIndex = episodes.indexOfFirst { it.id == ep.id }
        if (currentIndex < 0) return
        scope.launch {
            val queueMedia = runSuspendCatching {
                mediaSourceCache.withSource(library) { source ->
                    episodes.map { item ->
                        val onlineEpisode = onlineEpisodeByNumber[item.episode_number]
                        val ignoredEpisode = isOffsetIgnoredEpisode(selected?.bangumi_offset ?: 0L, item.episode_number)
                        source.resolvePlayMedia(
                            MediaEntry(
                                name = item.video_name,
                                path = item.video_path,
                                isDirectory = false,
                                tmdbId = s?.tmdb_id,
                                seasonNumber = tmdbSeasonNumber,
                                // 被忽略集(先行篇)在 TMDB 无对应集: 记 S2E0 独立身份, 不得回落
                                // 本地号 1——否则与 E2(恰好映射 TMDB S2E1)三元组同键, 播放进度互撞。
                                episodeNumber = when {
                                    ignoredEpisode -> 0L
                                    else -> tmdbMapping?.remoteEpisodeNumber(item.episode_number)
                                        ?: item.episode_number
                                },
                            ),
                        ).copy(
                            animeContext = AnimePlaybackContext(
                                seriesTitle = s?.title.orEmpty(),
                                // 被忽略集(正漂移前 N 集 = 先行篇)连标题带简介都显示原始文件名体系:
                                // NFO 文本与在线文本都按错误坐标生成, 全部不采用, 简介留空。
                                episodeTitle = if (ignoredEpisode) {
                                    item.video_name.takeIf { it.isNotBlank() } ?: item.title
                                } else {
                                    onlineEpisode?.title?.takeIf { it.isNotBlank() } ?: item.title
                                },
                                episodeDescription = if (ignoredEpisode) {
                                    null
                                } else {
                                    onlineEpisode?.plot?.takeIf { it.isNotBlank() } ?: item.plot
                                },
                                // 关联解析的异步窗口内用扫描 bangumi.ini 的季度 id 兜底, 避免空
                                // subject 快照让播放页评论区/弹幕身份约束失效; 解析已完成仍为 null
                                // (用户禁用/无关联)时保持 null, 不得让兜底重新启用被禁用的关联。
                                bangumiSubjectId = commentSubjectId
                                    ?: selected?.bangumi_id?.takeIf {
                                        currentCommentIdentityKey == null ||
                                            commentSubjectResolutionKey != currentCommentIdentityKey
                                    },
                                bangumiEpisodeOffset = selected?.bangumi_offset ?: 0L,
                                localSeasonNumber = selected?.season_number,
                                localEpisodeNumber = item.episode_number,
                                dandanplayAnimeId = selectedSeasonOnlineMeta?.dandanplay_id,
                                // 被忽略集(正漂移前 N 集)恒为 TMDB 外集; 其余集按已验证映射内
                                // 无对应集号判定。这两类集各源话数体系分裂, 播放器弹幕自动优先哈希。
                                episodeOutsideTmdb = ignoredEpisode ||
                                    selectedTmdbEpisodeMapping
                                        ?.let { it.remoteEpisodeNumber(item.episode_number) == null } == true,
                            ),
                        )
                    }
                }
            }.getOrNull() ?: return@launch
            queueMedia.getOrNull(currentIndex)
                ?.withPlaybackQueue(queueMedia, currentIndex)
                ?.let(onPlay)
        }
    }

    suspend fun scanShowOnce(target: ScrapedShow, reapplyOnlineMeta: Boolean = true): ScanResult? =
        runSuspendCatching {
            mediaSourceCache.withSource(library) { source ->
                ScrapedLibraryScanner(source, library, scrapedRepo, scanConfig)
                    .scanOneShow(target.show_path, reapplyOnlineMeta = reapplyOnlineMeta)
            }
        }.getOrNull()

    // 刷新后重载 show 元数据 + seasons + 当前季 episodes(普通刷新与清缓存刷新复用)。
    // 注: 局部函数不能用 private 修饰符, 且须定义在调用方之前(Kotlin 局部函数不支持前向引用)。
    // DB 读失败降级为旧数据继续渲染(U-1 纪律), 不向调用方抛异常(自动刮削 job 无 catch, 异常会击穿协程)。
    suspend fun reloadAfterRefresh(s: ScrapedShow) {
        val updated = runSuspendCatching { scrapedRepo.getShow(s.id) }.getOrDefault(s)
        show = updated
        // 锚定物理目录+季号而非完整 selectionKey(含漂移): 手动改漂移后同季键会失配,
        // 页签不应因此跳回第一个。
        val currentAnchor = seasons.getOrNull(selectedSeasonIndex)
            ?.let { it.show_id to it.season_number }
        val merged = loadMergedSeasons(updated)
        seasons = merged
        loadOnlineMeta(updated, merged)
        if (merged.isNotEmpty()) {
            val idx = if (currentAnchor != null)
                merged.indexOfFirst { it.show_id == currentAnchor.first && it.season_number == currentAnchor.second } else -1
            selectedSeasonIndex = if (idx >= 0) idx else 0
            loadEpisodes(merged[selectedSeasonIndex].id)
        }
        onShowChanged()
    }

    // Bangumi 关联弹窗改动(含手动修正集数漂移)后重读季快照: 评论 subject、TMDB 映射与
    // 播放上下文的 offset 都以 Season 行为权威, 弹窗回调只递增版本号。
    LaunchedEffect(bangumiLinkVersion) {
        if (bangumiLinkVersion <= 0L) return@LaunchedEffect
        val current = activeShow ?: return@LaunchedEffect
        reloadAfterRefresh(current)
    }

    // 每次进入该番剧详情页后按番剧自身的扫描时间做低频深探测；海报墙顶部增量扫描不进入已记录番剧目录。
    LaunchedEffect(activeShow?.id, detailsReady) {
        if (!detailsReady || libraryRefreshReady) return@LaunchedEffect
        val current = activeShow
        if (current == null) {
            libraryRefreshReady = true
            return@LaunchedEffect
        }
        if (shouldAutoRescanShow(current.scanned_at, platformTimeMillis())) {
            refreshing = true
            try {
                val result = scanShowOnce(current)
                if (result != null && result.errors == 0 && !result.timedOut && !result.stopped) {
                    reloadAfterRefresh(current)
                    // 深探测可能发现此前详情快照中不存在的新集。让自动刮削 effect
                    // 重新读取全季缺项；若当前自动任务仍在运行，代次变更会取消旧快照
                    // 并由仲裁器在旧任务收尾后启动新一轮。
                    // 用户本次进入已「停止」(单次/永久关闭)时不复活, 与停止确认的承诺一致。
                    if (!autoScrapeStoppedThisVisit) {
                        autoScrapeTriggered.value = false
                        autoScrapeGeneration++
                    }
                }
            } finally {
                refreshing = false
                libraryRefreshReady = true
            }
        } else {
            libraryRefreshReady = true
        }
    }

    // 该番剧是否已被用户「永久关闭自动刮削」(菜单恢复项显示用)。
    LaunchedEffect(activeShow?.id, activeShow?.show_path) {
        val s = activeShow ?: return@LaunchedEffect
        autoScrapeSuppressed = runSuspendCatching {
            scrapedRepo.isAutoScrapeSuppressed(library.id, s.show_path)
        }.getOrDefault(false)
    }

    // 封面重试条判据重求值(批次C): 初始加载/任意刷新(reloadAfterRefresh→loadOnlineMeta)/自动刮削
    // 完成后 onlineMetaBySeasonId 变化都会重跑; needsPosterRestore 内含真实文件存在性复核,
    // 「本地路径仍在但文件已删」也会命中提示。
    LaunchedEffect(activeShow?.id, scraper, onlineMetaBySeasonId, detailsReady) {
        if (!detailsReady) return@LaunchedEffect
        val s = activeShow ?: return@LaunchedEffect
        val scr = scraper
        posterRestoreNeeded = if (scr == null) {
            false
        } else {
            runSuspendCatching { scr.needsPosterRestore(library.id, s.show_path) }.getOrDefault(false)
        }
    }

    /** 手动重试恢复在线封面(批次C): 走 restoreOnlineImages 完整恢复通道, 完成后复查 needs 并刷新显示。 */
    fun retryPosterRestore() {
        val s = activeShow ?: return
        val scr = scraper ?: return
        if (posterRestoreInProgress) return
        scope.launch {
            posterRestoreInProgress = true
            try {
                runSuspendCatching {
                    scr.restoreOnlineImages(library = library, showPath = s.show_path)
                }
                // 复查: 成功且文件就位 → false 提示条消失; 失败保持 true 可再试(详情页重试不受海报墙会话守卫约束)。
                posterRestoreNeeded = runSuspendCatching {
                    scr.needsPosterRestore(library.id, s.show_path)
                }.getOrDefault(posterRestoreNeeded)
                // 复用现有刷新流: 重载详情图片显示, onShowChanged() 顺带通知海报墙重查卡片封面。
                reloadAfterRefresh(s)
            } finally {
                posterRestoreInProgress = false
            }
        }
    }

    /**
     * 停止进行中的自动刮削并弹出「单次关闭/永久关闭自动刮削」确认(2026-08-14 用户要求:
     * 停止不是默默结束, 必须给出单次/永久选择)。取消经 runSuspendCatching 向上传播,
     * 不会走 getOrElse 误报"自动刮削失败"。
     */
    fun stopAutoScrape() {
        autoScrapeJob?.cancel()
        autoScrapeTriggered.value = true
        autoScrapeStoppedThisVisit = true
        autoScrapeBannerMessage = null
        // 取消路径不经过 outcome 分支, 集照回退决策需手动收口(与 NoMatch 同款: 允许本地生成兜底)。
        episodeThumbFallbackDecision = EpisodeThumbFallbackDecision.GENERATE_IF_ENABLED
        showStopAutoScrapeDialog = true
    }

    /** 永久关闭本番剧自动刮削: 写抑制表(仅抑制详情页自动触发, 手动路径不受影响), 菜单可重新开启。 */
    fun suppressAutoScrapeForever() {
        val target = activeShow ?: return
        scope.launch {
            runSuspendCatching {
                scrapedRepo.suppressAutoScrape(library.id, target.show_path, platformTimeMillis())
            }
            autoScrapeSuppressed = true
        }
    }

    suspend fun currentSeasonNeedsEpisodeThumb(): Boolean {
        val season = seasons.getOrNull(selectedSeasonIndex) ?: return false
        return hasMissingEpisodeThumbCandidate(
            nfoThumbsByEpisode = episodes.associate { it.episode_number to it.thumb_path },
            onlineThumbsByEpisode = onlineMetaBySeasonId[season.id]
                ?.decodedEpisodes
                .orEmpty()
                .associate { it.episodeNumber.toLong() to it.thumbPath },
            nfoThumbsTrustworthy = when {
                selectedTmdbEpisodeMapping != null -> selectedTmdbEpisodeMapping.episodeOffset == 0
                season.bangumi_id != null && season.bangumi_offset != 0L -> false
                else -> true
            },
        )
    }

    fun openScrapeDialog(
        initialSource: ScrapeSource,
        autoSearch: Boolean,
        blockThumbFallback: Boolean = true,
        isAutoTmdbPrompt: Boolean = false,
    ) {
        scrapeDialogInitialSource = initialSource
        scrapeDialogAutoSearch = autoSearch
        scrapeDialogBlocksThumbFallback = blockThumbFallback
        scrapeDialogIsAutoTmdbPrompt = isAutoTmdbPrompt
        if (blockThumbFallback) {
            episodeThumbFallbackDecision = EpisodeThumbFallbackDecision.WAIT_FOR_ONLINE_MATCH
        }
        showScrapeDialog = true
    }

    suspend fun openPendingTmdbPrompt(s: ScrapedShow, scr: AnimeScraper): Boolean {
        if (showScrapeDialog || autoTmdbPromptHandled.value) return false
        val failure = runSuspendCatching {
            scrapedRepo.getTmdbAutoMatchFailure(library.id, s.show_path)
        }.getOrNull()
        if (!shouldOpenTmdbFailurePrompt(failure, s.tmdb_id, scr.hasTmdb, autoTmdbPromptHandled.value)) {
            return false
        }
        // 刚失败窗口(5 分钟)内本进程已提示过该番剧则不重复弹/重复自动搜索——
        // autoTmdbPromptHandled 是每次进详情页的组合态, 离开再进入会丢失, 必须进程级记住。
        val now = platformTimeMillis()
        val promptKey = "${library.id}\u0000${s.show_path}"
        val lastShown = tmdbFailurePromptShownAt[promptKey] ?: 0L
        if (now - lastShown < TMDB_FAILURE_PROMPT_JUST_FAILED_MS) return false
        tmdbFailurePromptShownAt[promptKey] = now
        autoTmdbPromptHandled.value = true
        openScrapeDialog(
            initialSource = ScrapeSource.TMDB,
            autoSearch = true,
            isAutoTmdbPrompt = true,
        )
        return true
    }

    fun generateAllEpisodeThumbs() {
        val generator = episodeThumbGenerator
        if (generator == null || generatingEpisodeThumbs || scrapeInProgress || refreshing) return
        val currentSeasonId = seasons.getOrNull(selectedSeasonIndex)?.id
        generatingEpisodeThumbs = true
        episodeThumbGenerationProgress = null
        episodeThumbFallbackDecision = EpisodeThumbFallbackDecision.SKIP_AFTER_ONLINE_MATCH
        scope.launch {
            try {
                val targets = buildEpisodeThumbTargets(
                    seasons = seasons,
                    currentShow = activeShow,
                    onlineMetaBySeasonId = onlineMetaBySeasonId,
                    scrapedRepo = scrapedRepo,
                )
                val result = runSuspendCatching {
                    EpisodeThumbCoordinator.ensureThumbs(
                        targets = targets,
                        library = library,
                        mediaSourceCache = mediaSourceCache,
                        generator = generator,
                        position = episodeThumbPosition,
                        scrapedRepo = scrapedRepo,
                        onUpdated = { id, path ->
                            episodes = episodes.map { episode ->
                                if (episode.id == id) episode.copy(local_thumb_path = path) else episode
                            }
                        },
                        onProgress = { progress ->
                            episodeThumbGenerationProgress = progress
                        },
                    )
                }.getOrNull()
                if (currentSeasonId != null) loadEpisodes(currentSeasonId)
                when {
                    result == null -> AppNotif.toast("本部集照生成失败，请稍后重试")
                    result.total == 0 -> AppNotif.toast("本部没有需要生成的集照")
                    result.generated == result.total ->
                        AppNotif.toast("本部集照生成完成: ${result.generated}/${result.total}")
                    else -> AppNotif.toast("本部集照生成完成: ${result.generated}/${result.total}，部分集生成失败")
                }
            } finally {
                episodeThumbGenerationProgress = null
                generatingEpisodeThumbs = false
            }
        }
    }

    // 懒触发在线刮削: 打开详情页对"缺元数据"番剧自动尝试(需 scraper 注入; 24h 节流; 单部恒并发 1)。
    // 命中即应用(唯一候选/hash); 模糊/未命中给提示, 用菜单"在线刮削"手动纠正。只跑一次(remember 去重)。
    // 修复(2026-08-13): 不再等待 48h 深探测(libraryRefreshReady)完成——老番剧每次进入先做完整
    // 深扫描(逐季 PROPFIND, 慢源可分钟级)且失败不自愈, 刮削被门住即"点进番剧概率性不刮削、
    // 反复进出几次才开始"。深探测降级为后台补充刷新, 与本效果并行; 刮削只依赖初始详情加载。
    LaunchedEffect(activeShow?.id, scraper, detailsReady, autoScrapeGeneration) {
        if (!detailsReady) return@LaunchedEffect
        val s = activeShow ?: return@LaunchedEffect
        if (autoScrapeTriggered.value) return@LaunchedEffect
        val selectedSeasonNeedsEpisodeThumb = hasMissingEpisodeThumbCandidate(
            nfoThumbsByEpisode = episodes.associate { it.episode_number to it.thumb_path },
            onlineThumbsByEpisode = onlineEpisodeByNumber.mapValues { it.value.thumbPath },
        )
        val scr = scraper
        if (scr == null) {
            autoScrapeTriggered.value = true
            episodeThumbFallbackDecision = initialEpisodeThumbFallbackDecision(
                needsOnlineScrape = true,
                canRunAutoScrape = false,
                hasMissingEpisodeThumb = selectedSeasonNeedsEpisodeThumb,
            )
            return@LaunchedEffect
        }
        val autoScrapeMode = if (forceAutoScrape.value) {
            AnimeScraper.AutoScrapeMode.FULL
        } else {
            runSuspendCatching { scr.autoScrapeMode(library.id, s.show_path) }.getOrElse {
                scrapeMessage = "自动刮削状态读取失败, 菜单里可手动重试"
                autoScrapeTriggered.value = true
                episodeThumbFallbackDecision = initialEpisodeThumbFallbackDecision(
                    needsOnlineScrape = true,
                    canRunAutoScrape = false,
                    hasMissingEpisodeThumb = selectedSeasonNeedsEpisodeThumb,
                )
                return@LaunchedEffect
            }
        }
        if (autoScrapeMode == AnimeScraper.AutoScrapeMode.NONE) {
            autoScrapeTriggered.value = true
            episodeThumbFallbackDecision = initialEpisodeThumbFallbackDecision(
                needsOnlineScrape = true,
                canRunAutoScrape = false,
                hasMissingEpisodeThumb = selectedSeasonNeedsEpisodeThumb,
            )
            scrapeMessage = if (openPendingTmdbPrompt(s, scr)) {
                "TMDB 未能自动确定作品，请手动选择"
            } else {
                null
            }
            return@LaunchedEffect
        }
        autoScrapeTriggered.value = true
        forceAutoScrape.value = false
        autoScrapeBannerMessage = null
        episodeThumbFallbackDecision = EpisodeThumbFallbackDecision.WAIT_FOR_ONLINE_MATCH
        automaticScrapeInProgress = true
        scrapeMessage = null
        scrapeProgressMessage = if (autoScrapeMode == AnimeScraper.AutoScrapeMode.IMAGES_ONLY) {
            "正在恢复在线图片..."
        } else {
            "正在匹配在线信息..."
        }
        // 自动刮削放进独立 Job(LaunchedEffect 协程作用域的子 job): 横幅「停止」可取消本次任务,
        // 离开页面/代次变更(刷新)时随 effect 一起取消, 保持原有的"代次变更取消旧快照"语义。
        // 取消经 runSuspendCatching 向上传播, 不会走 getOrElse 误报"自动刮削失败"。
        val job = launch {
            try {
                val outcome = runSuspendCatching {
                    when (autoScrapeMode) {
                        AnimeScraper.AutoScrapeMode.IMAGES_ONLY -> scr.restoreOnlineImages(
                            library = library,
                            showPath = s.show_path,
                            onProgress = { message -> scrapeProgressMessage = message },
                        )
                        AnimeScraper.AutoScrapeMode.FULL -> scr.scrapeAuto(
                            library = library,
                            showPath = s.show_path,
                            hashProvider = scrapeHashProvider,
                            onProgress = { message -> scrapeProgressMessage = message },
                        )
                        AnimeScraper.AutoScrapeMode.NONE -> AnimeScraper.AutoScrapeOutcome.Skipped
                    }
                }.getOrElse {
                    scrapeMessage = "自动刮削失败, 菜单里可手动重试"
                    autoScrapeBannerMessage = scrapeMessage
                    episodeThumbFallbackDecision = initialEpisodeThumbFallbackDecision(
                        needsOnlineScrape = true,
                        canRunAutoScrape = false,
                        hasMissingEpisodeThumb = selectedSeasonNeedsEpisodeThumb,
                    )
                    return@launch
                }
                var pendingCandidateSource: ScrapeSource? = null
                when (outcome) {
                    is AnimeScraper.AutoScrapeOutcome.Done -> {
                        scrapeMessage = if (autoScrapeMode == AnimeScraper.AutoScrapeMode.IMAGES_ONLY) {
                            "已恢复在线图片"
                        } else {
                            "已在线补全元数据"
                        }
                        reloadAfterRefresh(s)
                        val missingThumb = currentSeasonNeedsEpisodeThumb()
                        episodeThumbFallbackDecision = episodeThumbFallbackDecisionAfter(outcome, missingThumb)
                        if (missingThumb) scrapeMessage = "在线身份已匹配，但仍有集照缺失"
                    }
                    is AnimeScraper.AutoScrapeOutcome.Partial -> {
                        reloadAfterRefresh(s)
                        val missingThumb = currentSeasonNeedsEpisodeThumb()
                        episodeThumbFallbackDecision = episodeThumbFallbackDecisionAfter(outcome, missingThumb)
                        scrapeMessage = "已补全部分在线数据，其余内容稍后继续重试"
                    }
                    is AnimeScraper.AutoScrapeOutcome.NoMatch -> {
                        episodeThumbFallbackDecision = episodeThumbFallbackDecisionAfter(outcome)
                        scrapeMessage = "自动刮削未命中，可从菜单手动纠正"
                        autoScrapeBannerMessage = scrapeMessage
                    }
                    is AnimeScraper.AutoScrapeOutcome.NeedsConfirmation -> {
                        episodeThumbFallbackDecision = episodeThumbFallbackDecisionAfter(outcome)
                        pendingCandidateSource = candidateDialogSourceAfter(outcome)
                        scrapeMessage = if (outcome.candidates.size == 1) {
                            "已找到候选作品，请确认匹配是否正确"
                        } else {
                            "候选不唯一，请手动选择正确作品"
                        }
                        autoScrapeBannerMessage = scrapeMessage
                    }
                    AnimeScraper.AutoScrapeOutcome.RetryableFailure -> {
                        episodeThumbFallbackDecision = episodeThumbFallbackDecisionAfter(outcome)
                        scrapeMessage = "在线服务暂时不可用，稍后进入详情页会自动重试"
                        autoScrapeBannerMessage = scrapeMessage
                    }
                    AnimeScraper.AutoScrapeOutcome.Skipped -> {
                        episodeThumbFallbackDecision = episodeThumbFallbackDecisionAfter(outcome)
                        scrapeMessage = "在线刮削正在其他任务中进行"
                    }
                }
                if (openPendingTmdbPrompt(s, scr)) {
                    scrapeMessage = "TMDB 未能自动确定作品，请手动选择"
                } else if (pendingCandidateSource != null) {
                    // 消息已在 NeedsConfirmation 分支按候选数设置, 此处仅弹出候选弹窗。
                    openScrapeDialog(pendingCandidateSource, autoSearch = true)
                }
            } finally {
                scrapeProgressMessage = null
                automaticScrapeInProgress = false
            }
        }
        autoScrapeJob = job
        job.join()
        autoScrapeJob = null
    }

    // 确定刷新目标 show: 跟随当前选中季所在文件夹。同 TMDB、同本地季号的分段跨文件夹展示时,
    // 切到另一物理分段后刷新, 须扫该分段所在文件夹,
    // 否则该季不更新(原实现固定扫进入详情页时的 s.show_path, 切到别文件夹的季时刷不到)。
    suspend fun resolveRefreshTarget(s: ScrapedShow): ScrapedShow {
        val currentSeason = seasons.getOrNull(selectedSeasonIndex)
        val targetShowId = currentSeason?.show_id
        if (targetShowId == null || targetShowId == s.id) return s
        return runSuspendCatching { scrapedRepo.getShow(targetShowId) }.getOrNull() ?: s
    }

    suspend fun scanShow(target: ScrapedShow, reapplyOnlineMeta: Boolean = true): ScanResult? {
        var result = scanShowOnce(target, reapplyOnlineMeta)
        if (result != null && (result.errors > 0 || result.timedOut)) {
            result = scanShowOnce(target, reapplyOnlineMeta) ?: result
        }
        return result
    }

    // 刷新此番剧: 单番剧重扫(重新解析 tvshow.nfo + 所有季/剧集), 完成后重新加载详情数据。
    // 详情页复用页面级 source cache，不走 PosterWallScanCoordinator(单番剧快, 用户在场)。
    // 普通刷新不清图片缓存(海报不闪); PROPFIND 抖动时轻量重试 1 次; 接住 ScanResult 给 toast 反馈。
    fun refreshShow() {
        val s = activeShow ?: return
        if (refreshing) return
        scope.launch {
            refreshing = true
            try {
                val target = resolveRefreshTarget(s)
                val result = scanShow(target)
                reloadAfterRefresh(target)
                when {
                    result == null -> AppNotif.toast("刷新失败: 网络错误")
                    result.errors > 0 -> AppNotif.toast("刷新失败: ${result.firstErrorMessage ?: "未知错误"}")
                    result.timedOut -> AppNotif.toast("刷新超时")
                    else -> AppNotif.toast("已刷新, 共 ${result.foundEpisodes} 集")
                }
            } finally {
                refreshing = false
            }
        }
    }

    // 刷新(清除缓存): 清刮削数据 + 隐藏状态 + 图片缓存, 保留标记与播放记录, 重新扫描入库。
    // 适用于普通刷新无效或元数据异常时。开头清图片缓存(海报会闪); 扫描成功后重置隐藏状态。
    fun refreshShowClearCache() {
        val s = activeShow ?: return
        if (refreshing) return
        scope.launch {
            refreshing = true
            try {
                val target = resolveRefreshTarget(s)
                val previousDecision = episodeThumbFallbackDecision
                val previousAutoScrapeTriggered = autoScrapeTriggered.value
                val previousForceAutoScrape = forceAutoScrape.value
                episodeThumbFallbackDecision = EpisodeThumbFallbackDecision.WAIT_FOR_ONLINE_MATCH
                autoScrapeTriggered.value = false
                forceAutoScrape.value = true
                val clearResult = runSuspendCatching { scrapedRepo.clearShowCache(target.id) }
                if (clearResult.isFailure) {
                    episodeThumbFallbackDecision = previousDecision
                    autoScrapeTriggered.value = previousAutoScrapeTriggered
                    forceAutoScrape.value = previousForceAutoScrape
                    AppNotif.toast("清除缓存失败，请稍后重试")
                    return@launch
                }
                val result = scanShow(target)
                if (result != null && result.errors == 0 && !result.timedOut) {
                    runSuspendCatching { scrapedRepo.setHidden(target.id, false) }
                }
                reloadAfterRefresh(target)
                autoScrapeGeneration++
                when {
                    result == null -> AppNotif.toast("刷新失败: 网络错误")
                    result.errors > 0 -> AppNotif.toast("刷新失败: ${result.firstErrorMessage ?: "未知错误"}")
                    result.timedOut -> AppNotif.toast("刷新超时")
                    else -> AppNotif.toast("已清除缓存并刷新, 共 ${result.foundEpisodes} 集")
                }
            } finally {
                refreshing = false
            }
        }
    }

    fun restoreNfoDisplay() {
        val s = activeShow ?: return
        if (refreshing || scrapeInProgress) return
        scope.launch {
            refreshing = true
            try {
                val target = resolveRefreshTarget(s)
                val result = scanShow(target, reapplyOnlineMeta = false)
                if (result == null || result.errors > 0 || result.timedOut) {
                    runSuspendCatching { scrapedRepo.reapplyOnlineMeta(target.library_id, target.show_path) }
                    reloadAfterRefresh(target)
                    when {
                        result == null -> AppNotif.toast("恢复 NFO 失败: 网络错误")
                        result.errors > 0 -> AppNotif.toast("恢复 NFO 失败: ${result.firstErrorMessage ?: "未知错误"}")
                        else -> AppNotif.toast("恢复 NFO 超时")
                    }
                    return@launch
                }
                val clearResult = runSuspendCatching { scrapedRepo.restoreNfoState(target.id) }
                if (clearResult.isFailure) {
                    runSuspendCatching { scrapedRepo.reapplyOnlineMeta(target.library_id, target.show_path) }
                    reloadAfterRefresh(target)
                    AppNotif.toast("清理在线刮削失败，请稍后重试")
                    return@launch
                }
                autoScrapeTriggered.value = true
                forceAutoScrape.value = false
                episodeThumbFallbackDecision = EpisodeThumbFallbackDecision.SKIP_AFTER_ONLINE_MATCH
                runSuspendCatching {
                    scrapedRepo.recordAutoScrapeAttempt(
                        libraryId = target.library_id,
                        showPath = target.show_path,
                        attemptedAt = platformTimeMillis(),
                    )
                }
                reloadAfterRefresh(target)
                AppNotif.toast("已恢复 NFO 刮削内容")
            } finally {
                refreshing = false
            }
        }
    }

    fun saveScheduleStatus(status: ScheduleStatus) {
        val subjectId = commentSubjectId
        val currentShow = activeShow
        if (subjectId == null || currentShow == null) {
            AppNotif.toast("标记番剧需要先关联 Bangumi")
            return
        }
        if (scheduleStatusSaving) return
        val currentWatch = selectedScheduleWatch
        val entry = ScheduleEntry(
            subjectId = subjectId,
            title = currentWatch?.title
                ?: selectedSeasonOnlineMeta?.title?.takeIf { it.isNotBlank() }
                ?: currentShow.title,
            originalTitle = currentShow.original_title,
            weekday = currentWatch?.airWeekday ?: 0,
            broadcastTime = null,
            airDate = selectedSeason?.release_date,
            posterUrl = null,
            rating = currentShow.rating,
            rank = null,
            watchingCount = null,
            animeId = selectedSeasonOnlineMeta?.dandanplay_id ?: currentWatch?.animeId,
            tmdbId = currentShow.tmdb_id ?: currentWatch?.tmdbId,
            libraryMatch = null,
            watched = status != ScheduleStatus.NONE,
            status = status,
        )
        scope.launch {
            scheduleStatusSaving = true
            runSuspendCatching {
                if (scheduleRepo != null) {
                    scheduleRepo.setStatus(entry, status)
                } else if (status == ScheduleStatus.NONE) {
                    scrapedRepo.deleteScheduleWatch(subjectId)
                } else {
                    scrapedRepo.upsertScheduleWatch(
                        ScheduleWatch(
                            subjectId = subjectId,
                            title = entry.title,
                            airWeekday = entry.weekday,
                            animeId = entry.animeId,
                            tmdbId = entry.tmdbId,
                            watchedAt = platformTimeMillis(),
                            status = status,
                        ),
                    )
                }
            }.onSuccess {
                onScheduleWatchChanged()
            }.onFailure {
                AppNotif.toast("保存标记失败，请稍后重试")
            }
            scheduleStatusSaving = false
        }
    }

    // 系统返回
    AppBackHandler(enabled = handleSystemBack) { onBack() }

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
                        text = activeShow?.title ?: "番剧",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    // 刷新此番剧: 单番剧重扫, 重新解析 nfo + 剧集
                    IconButton(onClick = { refreshShow() }, enabled = !refreshing && !detailOperationInProgress && activeShow != null) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新此番剧")
                        }
                    }
                    Box {
                        TextButton(
                            onClick = {
                                if (commentSubjectId == null) AppNotif.toast("标记番剧需要先关联 Bangumi")
                                else scheduleStatusMenuExpanded = true
                            },
                            enabled = activeShow != null && !scheduleStatusSaving,
                        ) {
                            Text(if (selectedScheduleStatus == ScheduleStatus.NONE) "标记" else selectedScheduleStatus.label)
                        }
                        DropdownMenu(
                            expanded = scheduleStatusMenuExpanded,
                            onDismissRequest = { scheduleStatusMenuExpanded = false },
                        ) {
                            ScheduleStatus.entries.forEach { status ->
                                DropdownMenuItem(
                                    text = { Text(status.label) },
                                    onClick = {
                                        scheduleStatusMenuExpanded = false
                                        saveScheduleStatus(status)
                                    },
                                )
                            }
                        }
                    }
                    // 隐藏/取消隐藏(临时归档, 列表默认不显示; 顶部「显示已隐藏」可找回)
                    IconButton(onClick = {
                        scope.launch {
                            val s = activeShow ?: return@launch
                            val newHidden = !(s.is_hidden == 1L)
                            scrapedRepo.setHidden(s.id, newHidden)
                            onShowChanged()
                            val updated = scrapedRepo.getShow(s.id) ?: return@launch
                            ownerShowsById = ownerShowsById + (updated.id to updated)
                            if (show?.id == updated.id) show = updated
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
                            if (activeShow?.is_hidden == 1L) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (activeShow?.is_hidden == 1L) "取消隐藏" else "隐藏",
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
                                text = { Text("在线刮削/纠正") },
                                enabled = scraper != null && activeShow != null && !refreshing && !detailOperationInProgress,
                                onClick = {
                                    moreMenuExpanded = false
                                    openScrapeDialog(defaultScrapeDialogSource, autoSearch = true)
                                },
                            )
                            if (autoScrapeSuppressed) {
                                DropdownMenuItem(
                                    text = { Text("重新开启自动刮削") },
                                    enabled = scraper != null && activeShow != null && !refreshing && !detailOperationInProgress,
                                    onClick = {
                                        moreMenuExpanded = false
                                        val target = activeShow
                                        if (target != null) {
                                            scope.launch {
                                                runSuspendCatching {
                                                    scrapedRepo.unsuppressAutoScrape(library.id, target.show_path)
                                                }
                                                autoScrapeSuppressed = false
                                                autoScrapeTriggered.value = false
                                                autoScrapeGeneration++
                                            }
                                        }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("TMDB 补全") },
                                enabled = scraper != null && activeShow != null && !refreshing && !detailOperationInProgress,
                                onClick = {
                                    moreMenuExpanded = false
                                    val target = activeShow
                                    if (scraper?.hasTmdb != true) {
                                        AppNotif.toast("TMDB Gateway 当前不可用")
                                    } else if (target != null) {
                                        directTmdbScrapeInProgress = true
                                        scrapeProgressMessage = "正在匹配 TMDB 图片与身份..."
                                        scope.launch {
                                            try {
                                                val result = runSuspendCatching {
                                                    scraper.enrichTmdb(
                                                        library = library,
                                                        showPath = target.show_path,
                                                        onProgress = { message -> scrapeProgressMessage = message },
                                                    )
                                                }
                                                if (result.isFailure) {
                                                    openScrapeDialog(ScrapeSource.TMDB, autoSearch = true)
                                                    scrapeMessage = "TMDB 自动补全失败，请手动选择"
                                                } else {
                                                    when (val outcome = result.getOrThrow()) {
                                                        is AnimeScraper.AutoScrapeOutcome.Done -> {
                                                            autoScrapeTriggered.value = true
                                                            forceAutoScrape.value = false
                                                            reloadAfterRefresh(target)
                                                            val missingThumb = currentSeasonNeedsEpisodeThumb()
                                                            episodeThumbFallbackDecision = episodeThumbFallbackDecisionAfter(
                                                                outcome,
                                                                missingThumb,
                                                            )
                                                            scrapeMessage = if (missingThumb) {
                                                                "TMDB 已匹配，但仍有集照缺失"
                                                            } else {
                                                                "已完成 TMDB 补全"
                                                            }
                                                        }
                                                        is AnimeScraper.AutoScrapeOutcome.Partial -> {
                                                            reloadAfterRefresh(target)
                                                            episodeThumbFallbackDecision = episodeThumbFallbackDecisionAfter(outcome)
                                                            scrapeMessage = "TMDB 已补全部分图片，稍后可继续重试"
                                                        }
                                                        AnimeScraper.AutoScrapeOutcome.NoMatch -> {
                                                            openScrapeDialog(ScrapeSource.TMDB, autoSearch = true)
                                                            scrapeMessage = "TMDB 未找到唯一匹配，请手动选择"
                                                        }
                                                        AnimeScraper.AutoScrapeOutcome.Skipped -> {
                                                            episodeThumbFallbackDecision = episodeThumbFallbackDecisionAfter(outcome)
                                                            scrapeMessage = "TMDB 补全正在其他任务中进行"
                                                        }
                                                        is AnimeScraper.AutoScrapeOutcome.NeedsConfirmation -> {
                                                            openScrapeDialog(ScrapeSource.TMDB, autoSearch = true)
                                                            scrapeMessage = "TMDB 候选不唯一，请手动选择"
                                                        }
                                                        AnimeScraper.AutoScrapeOutcome.RetryableFailure -> {
                                                            episodeThumbFallbackDecision = episodeThumbFallbackDecisionAfter(outcome)
                                                            scrapeMessage = "TMDB 服务暂时不可用，请稍后重试"
                                                        }
                                                    }
                                                }
                                            } finally {
                                                scrapeProgressMessage = null
                                                directTmdbScrapeInProgress = false
                                            }
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (generatingEpisodeThumbs) "正在生成本部集照..." else "生成本部集照")
                                },
                                enabled = episodeThumbGenerator != null && seasons.isNotEmpty() &&
                                    !refreshing && !detailOperationInProgress,
                                onClick = {
                                    moreMenuExpanded = false
                                    generateAllEpisodeThumbs()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("恢复 NFO 刮削") },
                                enabled = library.scanMode == ScanMode.NFO &&
                                    activeShow != null && !refreshing && !detailOperationInProgress,
                                onClick = {
                                    moreMenuExpanded = false
                                    showRestoreNfoDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Bangumi 关联") },
                                enabled = globalSettings.recognizeAnime && seasons.getOrNull(selectedSeasonIndex) != null,
                                onClick = {
                                    moreMenuExpanded = false
                                    showBangumiLinkDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("本部专属设置") },
                                onClick = {
                                    moreMenuExpanded = false
                                    showOverrideDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("刷新(清除缓存)") },
                                enabled = !refreshing && !detailOperationInProgress,
                                onClick = {
                                    moreMenuExpanded = false
                                    showClearCacheDialog = true
                                },
                            )
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
        floatingActionButton = {
            if (showBackToTop) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            // 嵌套 Pager 的列表与外层头部是两个独立 LazyListState, 必须同时复位。
                            detailListState.scrollToItem(0)
                            backToTopListState.scrollToItem(0)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "回到顶部")
                }
            }
        },
    ) { padding ->
        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (detailOperationInProgress) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = if (generatingEpisodeThumbs) {
                                episodeThumbGenerationProgress?.let {
                                    "正在生成集照 ${it.completed}/${it.total}"
                                } ?: "正在准备本部集照..."
                            } else {
                                scrapeProgressMessage ?: if (automaticScrapeInProgress) {
                                    "正在匹配在线信息..."
                                } else {
                                    "正在在线刮削..."
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (automaticScrapeInProgress) {
                            Spacer(modifier = Modifier.size(8.dp))
                            TextButton(onClick = { stopAutoScrape() }) {
                                Text("停止", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    val generationProgress = episodeThumbGenerationProgress
                    if (generatingEpisodeThumbs && generationProgress != null && generationProgress.total > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (generationProgress.completed.toFloat() / generationProgress.total).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                // 封面下载失败重试条(批次C): 有远程 URL 但本地文件缺失时低调提示(surfaceVariant 背景),
                // 手动「重试」走恢复通道; 成功后 needs 复查为 false 提示条消失, 失败不自动循环。
                if (posterRestoreNeeded && !detailOperationInProgress) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.shapes.small,
                            )
                            .padding(start = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "封面下载失败",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (posterRestoreInProgress) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = { retryPosterRestore() }) {
                                Text("重试", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                // 自动刮削结果横幅: 未命中/需确认/失败时显示, 最右 X 弹出「单次关闭/永久关闭自动刮削」。
                val autoBannerMessage = autoScrapeBannerMessage
                if (autoBannerMessage != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = autoBannerMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            IconButton(onClick = { autoScrapeBannerMenuExpanded = true }) {
                                Icon(Icons.Filled.Close, contentDescription = "关闭")
                            }
                            DropdownMenu(
                                expanded = autoScrapeBannerMenuExpanded,
                                onDismissRequest = { autoScrapeBannerMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("单次关闭") },
                                    onClick = {
                                        autoScrapeBannerMenuExpanded = false
                                        autoScrapeStoppedThisVisit = true
                                        autoScrapeBannerMessage = null
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("永久关闭自动刮削") },
                                    onClick = {
                                        autoScrapeBannerMenuExpanded = false
                                        autoScrapeBannerMessage = null
                                        suppressAutoScrapeForever()
                                    },
                                )
                            }
                        }
                    }
                }
                // 「停止」后的单次/永久确认: 点击停止即弹出, 单次关闭=本次进入不再自动刮削(默认),
                // 永久关闭=写 AutoScrapeSuppression(该番剧不再自动刮削, 菜单可重新开启)。
                if (showStopAutoScrapeDialog) {
                    AlertDialog(
                        onDismissRequest = { showStopAutoScrapeDialog = false },
                        title = { Text("停止自动刮削") },
                        text = { Text("本次进入不再自动刮削；永久关闭后可在菜单重新开启") },
                        confirmButton = {
                            TextButton(onClick = { showStopAutoScrapeDialog = false }) {
                                Text("单次关闭")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showStopAutoScrapeDialog = false
                                suppressAutoScrapeForever()
                            }) {
                                Text("永久关闭自动刮削")
                            }
                        },
                    )
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    var tabRowHeightPx by remember { mutableIntStateOf(0) }
                    val density = LocalDensity.current
                    val measuredTabRowHeight = with(density) { tabRowHeightPx.toDp() }
                    // 首帧尚未测量时使用 Material3 默认值; 后续以实际高度计算, 小视口不再溢出。
                    val tabRowHeight = measuredTabRowHeight.takeIf { it > 0.dp } ?: 48.dp
                    val pagerHeight = (maxHeight - tabRowHeight).coerceIn(0.dp, maxHeight)
                    LazyColumn(state = detailListState, modifier = Modifier.fillMaxSize()) {
                        // === 顶部头部区: fanart 背景 + 半透明遮罩 + poster + 标题/元信息 ===
                        item {
                            val selectedSeason = seasons.getOrNull(selectedSeasonIndex)
                            val seasonMeta = selectedSeason?.let { onlineMetaBySeasonId[it.id] }
                            val nfoSeasonPoster = mediaSourceImage(selectedSeason?.season_poster_path)
                            val nfoShowPoster = mediaSourceImage(activeShow?.poster_path)
                            val onlineSeasonPoster = localFileImage(seasonMeta?.local_poster_path)
                            val headerPosterCandidates = if (useSeasonPoster) {
                                imageCandidates(nfoSeasonPoster, onlineSeasonPoster, nfoShowPoster)
                            } else {
                                imageCandidates(nfoShowPoster, nfoSeasonPoster, onlineSeasonPoster)
                            }
                            val headerFanartCandidates = imageCandidates(
                                mediaSourceImage(activeShow?.fanart_path),
                                localFileImage(activeShowOnlineMeta?.local_fanart_path),
                            )
                            val headerBackgroundCandidates = (headerFanartCandidates + headerPosterCandidates).distinct()
                            val headerPoster = headerPosterCandidates.firstOrNull()
                            val headerBackground = headerBackgroundCandidates.firstOrNull()
                            var activeBackgroundIndex by remember(headerBackgroundCandidates) { mutableIntStateOf(0) }
                            val isBlurredFallback = headerPoster != null && activeBackgroundIndex >= headerFanartCandidates.size
                            Box(modifier = Modifier.fillMaxWidth().height(AnimeDetailLayout.headerHeight)) {
                                ScrapedImage(
                                    sourceKind = library.sourceKind,
                                    libraryId = library.id,
                                    imagePath = headerBackground?.path,
                                    imagePathKind = headerBackground?.kind ?: ScrapedImagePathKind.MEDIA_SOURCE,
                                    fallbackImages = headerBackgroundCandidates.drop(1),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().then(
                                        if (isBlurredFallback) Modifier.blur(20.dp) else Modifier
                                    ),
                                    placeholderText = activeShow?.title ?: "",
                                    imageCacheSizeMb = imageCacheSizeMb,
                                    downloader = imageDownloader,
                                    cacheSubdir = showKey,
                                    onCandidateIndexChanged = { activeBackgroundIndex = it },
                                    previewEnabled = headerBackground != null,
                                    saveFileStem = "${activeShow?.title ?: "番剧"} 头图",
                                    clickOpensPreview = headerBackground != null,
                                )
                                // 半透明遮罩让前景文字清晰(海报兜底时加深, 配合模糊)
                                Box(
                                    modifier = Modifier.fillMaxSize().background(
                                        Color.Black.copy(alpha = if (isBlurredFallback) 0.55f else 0.4f)
                                    )
                                )
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(AnimeDetailLayout.headerPadding),
                                    verticalAlignment = Alignment.Bottom,
                                ) {
                                    Box {  // 海报 + 季徽章
                                        ScrapedImage(
                                            sourceKind = library.sourceKind,
                                            libraryId = library.id,
                                            imagePath = headerPoster?.path,
                                            imagePathKind = headerPoster?.kind ?: ScrapedImagePathKind.MEDIA_SOURCE,
                                            fallbackImages = headerPosterCandidates.drop(1),
                                            contentDescription = activeShow?.title,
                                            modifier = Modifier.size(
                                                AnimeDetailLayout.posterWidth,
                                                AnimeDetailLayout.posterHeight,
                                            ),
                                            placeholderText = activeShow?.title ?: "",
                                            imageCacheSizeMb = imageCacheSizeMb,
                                            downloader = imageDownloader,
                                            cacheSubdir = showKey,
                                            previewEnabled = headerPoster != null,
                                            saveFileStem = "${activeShow?.title ?: "番剧"} 海报",
                                            clickOpensPreview = headerPoster != null,
                                        )
                                        val badgeSeason = selectedSeason?.season_number
                                        val minBadgeSeason = if (badgeShowSeason1) 1L else 2L
                                        if (badgeSeason != null && badgeSeason >= minBadgeSeason) {
                                            Text(
                                                text = "第${badgeSeason}季",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), blurRadius = 2.5f, offset = Offset(0.5f, 0.5f)),
                                                ),
                                                color = Color.White.copy(alpha = 0.7f),
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(4.dp),
                                            )
                                        }
                                    }
                                    Column(
                                        modifier = Modifier.padding(start = AnimeDetailLayout.headerContentSpacing).fillMaxWidth(),
                                    ) {
                                        Text(
                                            text = activeShow?.title ?: "",
                                            style = MaterialTheme.typography.titleLarge,
                                            color = Color.White,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        activeShow?.original_title?.let {
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
                                            activeShow?.year?.let { add(it.toString()) }
                                            activeShow?.rating?.let { add("评分 %.1f".format(it)) }
                                            activeShow?.studios?.takeIf { it.isNotBlank() }?.let { add(it) }
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
                        activeShow?.plot?.let { plot ->
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
                                            .padding(
                                                horizontal = AnimeDetailLayout.summaryHorizontalPadding,
                                                vertical = AnimeDetailLayout.summaryVerticalPadding,
                                            ),
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
                                            text = { Text(seasonTabLabels.getValue(s.id)) },
                                        )
                                    }
                                }
                            }
                        }

                        // 内容四 Tab(剧集 | 评论 | 吐槽 | 讨论版): TabRow 与 Pager 双向联动,
                        // TabRow 用 stickyHeader 吸顶, Pager 横向滑动切换 + 视差/淡入过渡。
                        if (globalSettings.recognizeAnime) {
                            stickyHeader(key = "detail-content-tabs") {
                                DetailContentTabRow(
                                    selected = DetailTabPage.fromIndex(pagerState.currentPage),
                                    modifier = Modifier.onSizeChanged { tabRowHeightPx = it.height },
                                ) { page ->
                                    scope.launch { pagerState.animateScrollToPage(page.index) }
                                }
                            }
                            item(key = "detail-content-pager") {
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.height(pagerHeight),
                                    beyondViewportPageCount = 1,
                                    key = { it },
                                ) { page ->
                                    Box(
                                        Modifier.fillMaxSize().graphicsLayer {
                                            // 视差 + 淡入: 在 draw 阶段读取偏移, 避免每帧重组页内大列表
                                            val distance = pagerState.getOffsetDistanceInPages(page).coerceIn(-1f, 1f)
                                            translationX = distance * 24.dp.toPx()
                                            alpha = 1f - abs(distance) * 0.22f
                                        },
                                    ) {
                                        when (DetailTabPage.fromIndex(page)) {
                                            DetailTabPage.EPISODES -> LazyColumn(state = episodesListState, modifier = Modifier.fillMaxSize().nestedScroll(episodesCollapseConnection)) {
                                                animeEpisodeItems(
                                                    episodes = episodes,
                                                    seasons = seasons,
                                                    selectedSeasonIndex = selectedSeasonIndex,
                                                    showEpisodeThumb = showEpisodeThumb,
                                                    show = activeShow,
                                                    library = library,
                                                    onlineEpisodeByNumber = onlineEpisodeByNumber,
                                                    tmdbEpisodeMapping = selectedTmdbEpisodeMapping,
                                                    imageCacheSizeMb = imageCacheSizeMb,
                                                    imageDownloader = imageDownloader,
                                                    showKey = showKey,
                                                    progressMap = progressMap,
                                                    crossLibProgress = crossLibProgress,
                                                    mediaSourceCache = mediaSourceCache,
                                                    onPlayEpisode = ::playEpisode,
                                                    onPlayMediaEntry = ::playMediaEntry,
                                                )
                                            }
                                            DetailTabPage.COMMENTS -> LazyColumn(state = commentListState, modifier = Modifier.fillMaxSize().nestedScroll(commentCollapseConnection)) {
                                                bangumiCommentItems(
                                                    state = commentState,
                                                    onOpenBangumiLink = { showBangumiLinkDialog = true },
                                                    onOpenReview = { reviewDialogTarget = it },
                                                    resolving = commentSubjectResolving,
                                                    sourceLabel = bangumiEndpoints.sourceLabel,
                                                    emojiBaseUrl = bangumiEndpoints.imageBaseUrl,
                                                    allowedImageHosts = bangumiEndpoints.allowedAvatarHosts,
                                                )
                                            }
                                            DetailTabPage.COMMENT_BOX -> LazyColumn(state = commentBoxListState, modifier = Modifier.fillMaxSize().nestedScroll(commentBoxCollapseConnection)) {
                                                bangumiCommentBoxItems(
                                                    state = commentBoxState,
                                                    onOpenBangumiLink = { showBangumiLinkDialog = true },
                                                    resolving = commentSubjectResolving,
                                                    sourceLabel = bangumiEndpoints.sourceLabel,
                                                    emojiBaseUrl = bangumiEndpoints.imageBaseUrl,
                                                    allowedImageHosts = bangumiEndpoints.allowedAvatarHosts,
                                                )
                                            }
                                            DetailTabPage.TOPICS -> LazyColumn(state = topicListState, modifier = Modifier.fillMaxSize().nestedScroll(topicCollapseConnection)) {
                                                bangumiTopicItems(
                                                    state = topicState,
                                                    onOpenBangumiLink = { showBangumiLinkDialog = true },
                                                    onOpenTopic = { topicDialogTarget = it },
                                                    resolving = commentSubjectResolving,
                                                    sourceLabel = bangumiEndpoints.sourceLabel,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // 番剧识别关闭时完全隐藏评论/吐槽/讨论入口，剧集直出(旧行为)。
                            animeEpisodeItems(
                                episodes = episodes,
                                seasons = seasons,
                                selectedSeasonIndex = selectedSeasonIndex,
                                showEpisodeThumb = showEpisodeThumb,
                                show = activeShow,
                                library = library,
                                onlineEpisodeByNumber = onlineEpisodeByNumber,
                                tmdbEpisodeMapping = selectedTmdbEpisodeMapping,
                                imageCacheSizeMb = imageCacheSizeMb,
                                imageDownloader = imageDownloader,
                                showKey = showKey,
                                progressMap = progressMap,
                                crossLibProgress = crossLibProgress,
                                mediaSourceCache = mediaSourceCache,
                                onPlayEpisode = ::playEpisode,
                                onPlayMediaEntry = ::playMediaEntry,
                            )
                        }
                    }
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
                    "「${activeShow?.title}」\n\n选择删除方式：\n" +
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
                                val s = activeShow ?: return@launch
                                val target = resolveRefreshTarget(s)
                                val fileDeleted = runSuspendCatching {
                                    mediaSourceCache.withSource(library) { source ->
                                        source.deleteFile(target.show_path)
                                    } ?: false
                                }.getOrDefault(false)
                                if (!fileDeleted) {
                                    AppNotif.toast("文件删除失败，已屏蔽")
                                }
                                // deleteShowAndBlock 内部已清该番剧图片缓存(Impl 在 androidMain 可见 PosterCache)
                                scrapedRepo.deleteShowAndBlock(target.id)
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
                                val s = activeShow ?: return@launch
                                val target = resolveRefreshTarget(s)
                                // TODO: 清图片缓存需 PosterCache(androidMain), commonMain 不可见
                                scrapedRepo.deleteShowAndBlock(target.id)
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
            text = { Text("「${activeShow?.title}」将从列表移除（记录保留），可在设置-屏蔽管理恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val s = activeShow ?: return@launch
                            val target = resolveRefreshTarget(s)
                            scrapedRepo.blockShow(target.id)
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

    // 刷新(清除缓存)确认框: 清图片缓存与隐藏状态, 保留标记、在线身份、文本和播放记录, 重新扫描入库
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("刷新(清除缓存)") },
            text = { Text("「${activeShow?.title}」\n\n将清除海报、头图和集照缓存，重置隐藏状态并重新扫描。\n标记、在线身份、刮削文本和播放进度保留，图片会重新下载。\n\n适用于图片缓存失效或元数据异常时。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheDialog = false
                        refreshShowClearCache()
                    },
                ) { Text("清除并刷新") }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) { Text("取消") }
            },
        )
    }

    if (showRestoreNfoDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreNfoDialog = false },
            title = { Text("恢复 NFO 刮削") },
            text = {
                Text(
                    "将删除本部的在线刮削展示数据和缓存，并重新读取 tvshow.nfo、season.nfo 和剧集 nfo。\n\n" +
                        "媒体文件和播放进度不会被删除。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreNfoDialog = false
                        restoreNfoDisplay()
                    },
                    enabled = !refreshing && !scrapeInProgress,
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreNfoDialog = false }) { Text("取消") }
            },
        )
    }

    // 本部专属设置弹窗: 身份键有 tmdb 跨库共用("tmdb:<id>"), ANCHOR 回落单库("show:<lib>:<path>")
    val overrideShow = activeShow
    val overrideKey = overrideShow?.let { ShowOverrideIdentity.keyFor(it.tmdb_id, it.library_id, it.show_path) }
    if (showOverrideDialog && overrideShow != null && overrideKey != null) {
        ShowOverrideDialog(
            showTitle = overrideShow.title,
            identityKey = overrideKey,
            globalSettings = globalSettings,
            scrapedRepo = scrapedRepo,
            // 无 tmdb 的 ANCHOR 节目: 播放端覆盖只认 tmdbId 不生效, 弹窗顶部加提示(仍可保存)
            appliesDuringPlayback = overrideShow.tmdb_id != null,
            onDismiss = { showOverrideDialog = false },
        )
    }

    val bangumiShow = activeShow
    val bangumiSeason = seasons.getOrNull(selectedSeasonIndex)
    if (showBangumiLinkDialog && bangumiShow != null && bangumiSeason != null) {
            BangumiLinkDialog(
            show = bangumiShow,
            season = bangumiSeason,
                repository = scrapedRepo,
                endpoints = bangumiEndpoints,
            onDismiss = { showBangumiLinkDialog = false },
            onChanged = { bangumiLinkVersion++ },
        )
    }

    topicDialogTarget?.let { topic ->
        BangumiTopicDialog(
            topic = topic,
            provider = commentProvider,
            emojiBaseUrl = bangumiEndpoints.imageBaseUrl,
            allowedImageHosts = bangumiEndpoints.allowedAvatarHosts,
            sourceLabel = bangumiEndpoints.sourceLabel,
            onDismiss = { topicDialogTarget = null },
        )
    }

    reviewDialogTarget?.let { review ->
        BangumiReviewDialog(
            review = review,
            provider = commentProvider,
            emojiBaseUrl = bangumiEndpoints.imageBaseUrl,
            allowedImageHosts = bangumiEndpoints.allowedAvatarHosts,
            sourceLabel = bangumiEndpoints.sourceLabel,
            onDismiss = { reviewDialogTarget = null },
        )
    }

    // 在线刮削手动纠正弹窗
    val scrapeShow = activeShow
    if (showScrapeDialog && scrapeShow != null && scraper != null) {
        ScrapeDialog(
            showTitle = scrapeShow.title,
            showPath = scrapeShow.show_path,
            library = library,
            scraper = scraper,
            seasonTargets = scrapeSeasonTargets,
            initialSeasonId = seasons.getOrNull(selectedSeasonIndex)?.id,
            initialSource = scrapeDialogInitialSource,
            autoSearchOnOpen = scrapeDialogAutoSearch,
            isAutoTmdbFailurePrompt = scrapeDialogIsAutoTmdbPrompt,
            applicationScope = scope,
            onPermanentlyDismissAutoTmdbPrompt = if (scrapeDialogIsAutoTmdbPrompt) {
                {
                    scrapedRepo.suppressTmdbAutoMatchPrompt(library.id, scrapeShow.show_path)
                    autoTmdbPromptHandled.value = true
                }
            } else {
                null
            },
            onDismiss = {
                val releaseThumbFallback = scrapeDialogBlocksThumbFallback
                showScrapeDialog = false
                scrapeDialogAutoSearch = false
                scrapeDialogBlocksThumbFallback = false
                scrapeDialogIsAutoTmdbPrompt = false
                if (releaseThumbFallback) {
                    scope.launch {
                        episodeThumbFallbackDecision = if (currentSeasonNeedsEpisodeThumb()) {
                            EpisodeThumbFallbackDecision.GENERATE_IF_ENABLED
                        } else {
                            EpisodeThumbFallbackDecision.SKIP_AFTER_ONLINE_MATCH
                        }
                    }
                }
            },
            onSearchBusyChange = { busy ->
                scrapeDialogSearchInProgress = busy
                if (busy) {
                    if (!scrapeDialogApplyInProgress) scrapeProgressMessage = "正在搜索在线候选..."
                } else if (!scrapeDialogApplyInProgress) {
                    scrapeProgressMessage = null
                }
            },
            onApplyBusyChange = { busy ->
                scrapeDialogApplyInProgress = busy
                scrapeProgressMessage = when {
                    busy -> scrapeProgressMessage ?: "正在应用在线刮削..."
                    scrapeDialogSearchInProgress -> "正在搜索在线候选..."
                    else -> null
                }
            },
            onApplicationProgress = { progress ->
                if (progress != null || scrapeDialogApplyInProgress) {
                    scrapeProgressMessage = progress
                }
            },
            onApplied = { appliedShowPath ->
                autoScrapeTriggered.value = true
                forceAutoScrape.value = false
                scrapeDialogAutoSearch = false
                scrapeDialogBlocksThumbFallback = false
                scrapeDialogIsAutoTmdbPrompt = false
                val appliedShow = scrapedRepo.getShowByPath(library.id, appliedShowPath) ?: scrapeShow
                reloadAfterRefresh(appliedShow)
                episodeThumbFallbackDecision = episodeThumbFallbackDecisionAfter(
                    AnimeScraper.AutoScrapeOutcome.Done(scrapeShow.id, seasons.size),
                    currentSeasonNeedsEpisodeThumb(),
                )
            },
        )
    }

    // 在线刮削状态提示(懒触发结果; 用 toast, 不阻塞页面)
    LaunchedEffect(scrapeMessage) {
        scrapeMessage?.let { AppNotif.toast(it) }
    }
}

/**
 * 剧集列表 + 原始目录浏览器(Pager 第 0 页与番剧识别关闭分支复用)。
 * 抽自 AnimeDetailScreen 原 if 分支, 内容与原实现一致; 播放回调由调用方注入。
 */
private fun LazyListScope.animeEpisodeItems(
    episodes: List<ScrapedEpisode>,
    seasons: List<ScrapedSeason>,
    selectedSeasonIndex: Int,
    showEpisodeThumb: Boolean,
    show: ScrapedShow?,
    library: LibraryConfig,
    onlineEpisodeByNumber: Map<Long, ScrapedOnlineEpisode>,
    tmdbEpisodeMapping: TmdbEpisodeMapping?,
    imageCacheSizeMb: Int,
    imageDownloader: suspend (String, PlatformFile) -> Boolean,
    showKey: String,
    progressMap: Map<String, PlaybackRecord>,
    crossLibProgress: Map<String, Double>,
    mediaSourceCache: MediaSourceCache,
    onPlayEpisode: (ScrapedEpisode) -> Unit,
    onPlayMediaEntry: (MediaEntry) -> Unit,
) {
    val selectedSeason = seasons.getOrNull(selectedSeasonIndex)
    val tmdbCoordinatesRequired = selectedSeason?.bangumi_id != null && selectedSeason.bangumi_offset != 0L
    // === 剧集列表 ===
    // key = 剧集主键: 集照生成成功逐集回写触发 episodes 整表替换(episodes.map 全量),
    // 无 key 时按位置对账导致全列表重组; 稳定 key 让 LazyColumn 只重组 local_thumb_path 变化的项。
    items(episodes, key = { it.id }, contentType = { "anime-episode-row" }) { ep ->
        // 正漂移(先行篇)季: 前 offset 集为被忽略集——标题显示原始文件名, 集照只认 NFO,
        // 显示号按 本地集号-offset 落位(E1→E0, E2 起当 E1), 与播放页"第x集"同一坐标系。
        val seasonBangumiOffset = seasons.getOrNull(selectedSeasonIndex)?.bangumi_offset ?: 0L
        val ignoredEpisode = isOffsetIgnoredEpisode(seasonBangumiOffset, ep.episode_number)
        val displayEpisodeNumber = if (seasonBangumiOffset > 0L) {
            (ep.episode_number - seasonBangumiOffset).coerceAtLeast(0L)
        } else {
            ep.episode_number
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPlayEpisode(ep) }
                .padding(
                    horizontal = AnimeDetailLayout.episodeRowHorizontalPadding,
                    vertical = AnimeDetailLayout.episodeRowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左: 缩略图(可选)
            if (showEpisodeThumb) {
                // 缓存名: S01E05 标题.jpg (季号取当前选中季, 集号+标题; 集号用本地原始号保缓存稳定)
                val seasonNum = seasons.getOrNull(selectedSeasonIndex)?.season_number ?: 0
                val epLabel = "S${seasonNum.toString().padStart(2, '0')}E${ep.episode_number.toString().padStart(2, '0')}"
                val epTitle = ep.title?.takeIf { it.isNotBlank() }?.let { " ${sanitizeFileName(it)}" } ?: ""
                val episodeImages = episodeImageCandidates(
                    nfoThumbPath = ep.thumb_path,
                    onlineEpisode = onlineEpisodeByNumber[ep.episode_number],
                    localThumbPath = ep.local_thumb_path,
                    tmdbEpisodeMapping = tmdbEpisodeMapping,
                    tmdbCoordinatesRequired = tmdbCoordinatesRequired,
                    ignoredByOffset = ignoredEpisode,
                )
                val episodeImage = episodeImages.firstOrNull()
                ScrapedImage(
                    sourceKind = library.sourceKind,
                    libraryId = library.id,
                    imagePath = episodeImage?.path,
                    imagePathKind = episodeImage?.kind ?: ScrapedImagePathKind.MEDIA_SOURCE,
                    fallbackImages = episodeImages.drop(1),
                    contentDescription = "E$displayEpisodeNumber",
                    modifier = Modifier.size(
                        AnimeDetailLayout.episodeThumbWidth,
                        AnimeDetailLayout.episodeThumbHeight,
                    ),
                    placeholderText = "E$displayEpisodeNumber",
                    imageCacheSizeMb = imageCacheSizeMb,
                    downloader = imageDownloader,
                    cacheSubdir = showKey,
                    cacheName = "$epLabel$epTitle.jpg",
                    previewEnabled = episodeImage != null,
                    saveFileStem = "${show?.title ?: "番剧"} $epLabel 剧照",
                    onPreviewTap = { onPlayEpisode(ep) },
                )
                Spacer(modifier = Modifier.size(12.dp))
            }
            // 中: 集号+标题 + aired + 进度
            Column(modifier = Modifier.weight(1f)) {
                // 被忽略集(先行篇)显示原始文件名: NFO 文本按 TMDB 坐标刮削整体错位, 在线文本同样不可信;
                // 文件名通常很长(含发布组前缀), 单行跑马灯滚动展示完整内容
                val displayTitle = if (ignoredEpisode) ep.video_name else ep.title ?: ""
                Text(
                    text = "E$displayEpisodeNumber $displayTitle",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (ignoredEpisode) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (ignoredEpisode) Modifier.basicMarquee() else Modifier,
                )
                // 被忽略集的 NFO 放送日/简介同样按错误坐标生成, 一并不显示
                if (!ignoredEpisode) {
                    ep.aired?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    // 剧集简介(在线刮削回填; nfo 逐集 plot 已有则同列展示)
                    ep.plot?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                // 播放进度: 三元组集用 loadEpisodes 已解析的"较新者"进度; 无三元组的集回落本文件进度
                val crossProgress = ep.media_key?.let { crossLibProgress[it] }
                val ownProgress = ep.media_key?.let { progressMap[it]?.watch_progress }
                val progress = crossProgress ?: ownProgress
                if (progress != null && progress > 0.0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(
                            progress = { progress.toFloat() },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            onPlay = onPlayMediaEntry,
        )
    }
}

/** 内容四 Tab 行(剧集 | 评论 | 吐槽 | 讨论版), 与 HorizontalPager 双向联动。 */
@Composable
private fun DetailContentTabRow(
    selected: DetailTabPage,
    modifier: Modifier = Modifier,
    onSelect: (DetailTabPage) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = selected.index, modifier = modifier.fillMaxWidth()) {
        DetailTabPage.values().forEach { page ->
            Tab(selected = selected == page, onClick = { onSelect(page) }, text = { Text(page.label) })
        }
    }
}

private enum class DetailTabPage(val index: Int, val label: String) {
    EPISODES(0, "剧集"),
    COMMENTS(1, "评论"),
    COMMENT_BOX(2, "吐槽"),
    TOPICS(3, "讨论版"),
    ;

    companion object {
        fun fromIndex(index: Int): DetailTabPage = values().getOrElse(index) { EPISODES }
    }
}

private fun detailTabListState(
    page: DetailTabPage,
    episodes: LazyListState,
    comments: LazyListState,
    commentBox: LazyListState,
    topics: LazyListState,
): LazyListState = when (page) {
    DetailTabPage.EPISODES -> episodes
    DetailTabPage.COMMENTS -> comments
    DetailTabPage.COMMENT_BOX -> commentBox
    DetailTabPage.TOPICS -> topics
}

/**
 * 内层列表与外层头部列表的嵌套滚动联动:
 * - 上滑: 先把滚动量喂给外层(头部跟随手势收起), 外层滚到底后剩余量自然由内层消费;
 * - 下滑: 仅当内层已在顶部时喂给外层(头部展开)——必须放在 onPreScroll(外→内分发)消费,
 *   抢在列表内部 overscroll 效果之前, 否则 Android 顶部下拉的发光/弹性会吞掉滚动量
 *   (表现为下拉颤抖、头部保持收起);
 * - 内层不在顶部时下滑返回 Zero, 由内层正常滚动。
 * ⚠️ 方向坑(已踩过两次): dispatchRawDelta 输入是滚动增量(正=内容上移=收起), 与
 *   available.y(正=内容下移)反号, 两个方向都要传 -available.y——下滑传正值会变成
 *   "强制收起"(用户报告"下滑被拉回收起状态")。
 */
@Composable
private fun rememberHeaderCollapseConnection(
    innerListState: LazyListState,
    headerListState: LazyListState,
): NestedScrollConnection = remember(innerListState, headerListState) {
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // dispatchRawDelta 的输入是滚动增量(正 = 滚动位置增大 = 内容上移 = 头部收起),
            // 与 available.y(正 = 内容下移)方向相反, 因此两个方向统一取 -available.y;
            // 返回值须与 available 同坐标系: dispatchRawDelta 返回实际消耗的滚动增量(正 = 内容上移),
            // 故返回 -consumed。下滑仅在列表已在顶部时消费(展开), 否则让内层正常滚动。
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

/** 同季分段按连续集起点排序：offset=0 在前，offset=-11 对应从第 12 集开始。 */
internal fun sortLogicalSeasons(seasons: List<ScrapedSeason>): List<ScrapedSeason> = seasons.sortedWith(
    compareBy<ScrapedSeason> { it.season_number }
        .thenBy { season ->
            1L - season.bangumi_offset.coerceIn(-Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong())
        }
        .thenBy { it.release_date == null }
        .thenBy { it.release_date }
        .thenBy { it.show_id }
        .thenBy { it.id },
)

/** 重复季号明确标成上下部分；唯一季保留 NFO 自定义标题。 */
internal fun buildSeasonTabLabels(seasons: List<ScrapedSeason>): Map<Long, String> = buildMap {
    seasons.groupBy { it.season_number }.forEach { (seasonNumber, sameSeason) ->
        sameSeason.forEachIndexed { index, season ->
            put(
                season.id,
                if (sameSeason.size == 1) {
                    season.title?.takeIf { it.isNotBlank() } ?: "第${seasonNumber}季"
                } else {
                    "第${seasonNumber}季 · 第${index + 1}部分"
                },
            )
        }
    }
}

private suspend fun buildEpisodeThumbTargets(
    seasons: List<ScrapedSeason>,
    currentShow: ScrapedShow?,
    onlineMetaBySeasonId: Map<Long, ScrapedOnlineMeta>,
    scrapedRepo: ScrapedLibraryRepository,
): List<EpisodeThumbCoordinator.Target> {
    val ownerShows = seasons.map { it.show_id }.distinct().associateWith { ownerId ->
        if (ownerId == currentShow?.id) currentShow
        else runSuspendCatching { scrapedRepo.getShow(ownerId) }.getOrNull()
    }
    return seasons.flatMap { season ->
        val owner = ownerShows[season.show_id] ?: return@flatMap emptyList()
        val onlineEpisodeNumbers = onlineMetaBySeasonId[season.id]
            ?.decodedEpisodes
            .orEmpty()
            .filter { !it.thumbPath.isNullOrBlank() && !isMissingLocalFilePath(it.thumbPath) }
            .mapTo(hashSetOf()) { it.episodeNumber.toLong() }
        scrapedRepo.listEpisodes(season.id)
            // 被忽略集(先行篇)只认 NFO 集照, 不为其抽帧(结果不会进入显示候选)
            .filterNot { episode -> isOffsetIgnoredEpisode(season.bangumi_offset, episode.episode_number) }
            .map { episode ->
                EpisodeThumbCoordinator.Target(
                    episode = episode,
                    showKey = owner.cacheKey,
                    hasOnlineThumb = episode.episode_number in onlineEpisodeNumbers,
                )
            }
    }
}

internal enum class EpisodeThumbFallbackDecision {
    WAIT_FOR_ONLINE_MATCH,
    GENERATE_IF_ENABLED,
    SKIP_AFTER_ONLINE_MATCH,
}

internal fun isOnlineScrapeBusy(
    automaticScrapeInProgress: Boolean,
    manualScrapeInProgress: Boolean,
): Boolean = automaticScrapeInProgress || manualScrapeInProgress

private const val AUTO_SHOW_RESCAN_INTERVAL_MS = 2L * 24L * 60L * 60L * 1000L

/** TMDB 自动匹配失败提示冷却: 失败后 24h 内不重复弹窗/自动搜索。 */
private const val TMDB_FAILURE_PROMPT_MIN_INTERVAL_MS = 24L * 60L * 60L * 1000L

/** 刚失败判定窗口: 失败写入后 5 分钟内视为"本次刚失败", 当场提示一次(不受 24h 冷却)。 */
private const val TMDB_FAILURE_PROMPT_JUST_FAILED_MS = 5L * 60L * 1000L

/**
 * 进程级"已提示时间"(libraryId, showPath) -> 最近一次 TMDB 失败弹窗时间。
 * autoTmdbPromptHandled 是每次进详情页的组合态, 离开再进入会丢失; 用进程级记录保证
 * "刚失败窗口内只提示一次"跨导航成立。仅主线程访问。有界防长会话内存线性累积。
 */
private val tmdbFailurePromptShownAt = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
        size > MAX_TMDB_PROMPT_KEYS
}
private const val MAX_TMDB_PROMPT_KEYS = 512

/** 刚失败窗口内是否已提示过(不再重复): 距上次提示已过 [windowMs] 才允许再次提示。 */
internal fun shouldRepeatTmdbFailurePrompt(lastShownAt: Long, now: Long, windowMs: Long): Boolean =
    now - lastShownAt >= windowMs

internal fun shouldAutoRescanShow(scannedAt: Long, now: Long): Boolean =
    scannedAt <= 0L || (now >= scannedAt && now - scannedAt >= AUTO_SHOW_RESCAN_INTERVAL_MS)

internal fun initialEpisodeThumbFallbackDecision(
    needsOnlineScrape: Boolean,
    canRunAutoScrape: Boolean,
    hasMissingEpisodeThumb: Boolean = false,
): EpisodeThumbFallbackDecision = when {
    needsOnlineScrape && canRunAutoScrape -> EpisodeThumbFallbackDecision.WAIT_FOR_ONLINE_MATCH
    hasMissingEpisodeThumb -> EpisodeThumbFallbackDecision.GENERATE_IF_ENABLED
    else -> EpisodeThumbFallbackDecision.SKIP_AFTER_ONLINE_MATCH
}

internal fun episodeThumbFallbackDecisionAfter(
    outcome: AnimeScraper.AutoScrapeOutcome,
    hasMissingEpisodeThumb: Boolean = false,
): EpisodeThumbFallbackDecision = when (outcome) {
    is AnimeScraper.AutoScrapeOutcome.Done -> if (hasMissingEpisodeThumb) {
        EpisodeThumbFallbackDecision.GENERATE_IF_ENABLED
    } else {
        EpisodeThumbFallbackDecision.SKIP_AFTER_ONLINE_MATCH
    }
    is AnimeScraper.AutoScrapeOutcome.Partial,
    is AnimeScraper.AutoScrapeOutcome.NoMatch,
    is AnimeScraper.AutoScrapeOutcome.NeedsConfirmation,
    AnimeScraper.AutoScrapeOutcome.RetryableFailure -> EpisodeThumbFallbackDecision.GENERATE_IF_ENABLED
    AnimeScraper.AutoScrapeOutcome.Skipped -> EpisodeThumbFallbackDecision.WAIT_FOR_ONLINE_MATCH
}

internal fun candidateDialogSourceAfter(
    outcome: AnimeScraper.AutoScrapeOutcome,
): ScrapeSource? = when (outcome) {
    is AnimeScraper.AutoScrapeOutcome.NeedsConfirmation -> outcome.candidates.firstOrNull()?.source
        ?.takeIf { it == ScrapeSource.DANDANPLAY || it == ScrapeSource.BANGUMI }
    else -> null
}

internal fun shouldOpenTmdbFailurePrompt(
    failure: TmdbAutoMatchFailureState?,
    tmdbId: Long?,
    hasTmdb: Boolean,
    handledInThisDetailSession: Boolean,
    now: Long = platformTimeMillis(),
): Boolean = failure != null &&
    !failure.promptSuppressed &&
    hasTmdb &&
    tmdbId == null &&
    !handledInThisDetailSession &&
    // 刚失败的当场提示一次(用户知道本次自动刮削未命中, 可直接手动选择);
    // 之后进入详情页受 24h 冷却, 避免每次进入重复弹窗/重复自动搜索; 永久关闭仍立即生效。
    (now - failure.failedAt < TMDB_FAILURE_PROMPT_JUST_FAILED_MS ||
        now - failure.failedAt >= TMDB_FAILURE_PROMPT_MIN_INTERVAL_MS)

internal suspend fun hasMissingEpisodeThumbCandidate(
    nfoThumbsByEpisode: Map<Long, String?>,
    onlineThumbsByEpisode: Map<Long, String?>,
    nfoThumbsTrustworthy: Boolean = true,
): Boolean = nfoThumbsByEpisode.any { (episodeNumber, nfoThumb) ->
    (!nfoThumbsTrustworthy || nfoThumb.isNullOrBlank()) && onlineThumbsByEpisode[episodeNumber].let { onlineThumb ->
        onlineThumb.isNullOrBlank() || isMissingLocalFilePath(onlineThumb)
    }
}

/** 分段映射成立后，旧 NFO/旧在线缓存都可能来自本地同号集，必须只显示带当前 TMDB 坐标的缓存。 */
internal fun episodeImageCandidates(
    nfoThumbPath: String?,
    onlineEpisode: ScrapedOnlineEpisode?,
    localThumbPath: String?,
    tmdbEpisodeMapping: TmdbEpisodeMapping?,
    tmdbCoordinatesRequired: Boolean = false,
    ignoredByOffset: Boolean = false,
): List<ScrapedImageCandidate> {
    // 被忽略集(正漂移前 offset 集 = 先行篇): 只认同文件名 NFO 集照。TMDB 在线图按错误
    // 坐标生成, 本地抽帧也无法证明内容, 一律不进候选。
    if (ignoredByOffset) {
        return imageCandidates(mediaSourceImage(nfoThumbPath))
    }
    val shiftedMapping = tmdbEpisodeMapping?.episodeOffset != null && tmdbEpisodeMapping.episodeOffset != 0
    val suppressUnverifiedRemoteImages = shiftedMapping || (tmdbCoordinatesRequired && tmdbEpisodeMapping == null)
    val verifiedOnlinePath = onlineEpisode?.thumbPath?.takeIf {
        when {
            tmdbEpisodeMapping != null -> onlineEpisode.matchesTmdbStillCoordinates(tmdbEpisodeMapping)
            tmdbCoordinatesRequired -> false
            else -> true
        }
    }
    return if (suppressUnverifiedRemoteImages) {
        imageCandidates(
            localFileImage(verifiedOnlinePath),
            localFileImage(localThumbPath),
        )
    } else {
        imageCandidates(
            mediaSourceImage(nfoThumbPath),
            localFileImage(verifiedOnlinePath),
            localFileImage(localThumbPath),
        )
    }
}

private fun mediaSourceImage(path: String?): ScrapedImageCandidate? =
    path?.takeIf { it.isNotBlank() }?.let { ScrapedImageCandidate(it, ScrapedImagePathKind.MEDIA_SOURCE) }

private fun localFileImage(path: String?): ScrapedImageCandidate? =
    path?.takeIf { it.isNotBlank() }?.let { ScrapedImageCandidate(it, ScrapedImagePathKind.LOCAL_FILE) }

private fun imageCandidates(vararg images: ScrapedImageCandidate?): List<ScrapedImageCandidate> =
    images.filterNotNull().distinct()
