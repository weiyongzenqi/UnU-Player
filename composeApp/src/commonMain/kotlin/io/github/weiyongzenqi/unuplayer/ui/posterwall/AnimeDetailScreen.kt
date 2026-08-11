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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import io.github.weiyongzenqi.unuplayer.core.platform.AppNotif
import io.github.weiyongzenqi.unuplayer.ui.AppBackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.core.media.AnimePlaybackContext
import io.github.weiyongzenqi.unuplayer.domain.SettingsState
import io.github.weiyongzenqi.unuplayer.library.AnimeScraper
import io.github.weiyongzenqi.unuplayer.library.EpisodeThumbCoordinator
import io.github.weiyongzenqi.unuplayer.library.EpisodeThumbGenerator
import io.github.weiyongzenqi.unuplayer.library.EpisodeThumbPosition
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
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
import io.github.weiyongzenqi.unuplayer.library.ScrapedOnlineMeta
import io.github.weiyongzenqi.unuplayer.library.ScrapedSeason
import io.github.weiyongzenqi.unuplayer.library.ScrapedShow
import io.github.weiyongzenqi.unuplayer.library.TmdbAutoMatchFailureState
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideIdentity
import io.github.weiyongzenqi.unuplayer.library.cacheKey
import io.github.weiyongzenqi.unuplayer.library.decodedEpisodes
import io.github.weiyongzenqi.unuplayer.library.isMissingLocalFilePath
import io.github.weiyongzenqi.unuplayer.library.sanitizeFileName
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.playback.episodeProgressKey
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonIdentity
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentApi
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentProvider
import io.github.weiyongzenqi.unuplayer.bangumi.resolveEffectiveBangumiLink
import io.github.weiyongzenqi.unuplayer.domain.bangumiEndpoints

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
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val recognizeAnimeState = rememberUpdatedState(globalSettings.recognizeAnime)
    val bangumiEndpoints = globalSettings.bangumiEndpoints()
    val commentProvider = remember(bangumiEndpoints.identity) {
        BangumiCommentProvider(
            api = BangumiCommentApi(
                officialBaseUrl = bangumiEndpoints.apiBaseUrl,
                nextBaseUrl = bangumiEndpoints.nextApiBaseUrl,
            ),
            isEnabled = { recognizeAnimeState.value },
            allowedAvatarHosts = bangumiEndpoints.allowedAvatarHosts,
        )
    }
    val commentState = rememberBangumiCommentUiState(commentProvider)
    val detailListState = rememberLazyListState()
    var show by remember { mutableStateOf<ScrapedShow?>(null) }
    var seasons by remember { mutableStateOf<List<ScrapedSeason>>(emptyList()) }
    var selectedSeasonIndex by remember { mutableStateOf(0) }
    var episodes by remember { mutableStateOf<List<ScrapedEpisode>>(emptyList()) }
    var onlineMetaBySeason by remember { mutableStateOf<Map<Long, ScrapedOnlineMeta>>(emptyMap()) }
    var seasonShowPathByNumber by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var progressMap by remember { mutableStateOf<Map<String, PlaybackRecord>>(emptyMap()) }
    // 剧集显示进度(跨库双向跟随): 有三元组的集已解析为"本文件/跨库 last_played_at 较新者"的 watch_progress;
    // 无三元组的集不在其中, UI 回落本文件 progressMap。
    var crossLibProgress by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    // 集照懒加载触发 token: loadEpisodes 后自增, LaunchedEffect(thumbTrigger) 据此触发 coordinator(切季自动取消上一个)
    var thumbTrigger by remember { mutableLongStateOf(0L) }
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
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var detailContentTab by remember { mutableStateOf(0) }
    var bangumiLinkVersion by remember { mutableLongStateOf(0L) }
    var commentSubjectId by remember { mutableStateOf<Long?>(null) }

    // 缓存子目录(番剧名-tmdbid), show 加载后算; WebDAV 图片下载到此目录
    val showKey = show?.cacheKey ?: "unknown"

    // 每张媒体源图片只在实际下载期间租用 source，离页清理不会中途关闭活跃下载。
    val imageDownloader: suspend (String, PlatformFile) -> Boolean = { imagePath, dest ->
        mediaSourceCache.withSource(library) { source ->
            source.downloadToFile(imagePath, dest)
        } ?: false
    }

    suspend fun loadPlaybackProgress(
        eps: List<ScrapedEpisode>,
        showSnapshot: ScrapedShow?,
        seasonNumber: Long?,
    ) {
        progressMap = playbackRepo?.let { repo ->
            val keys = eps.mapNotNull { it.media_key }
            if (keys.isNotEmpty()) repo.getByMediaKeys(keys) else emptyMap()
        } ?: emptyMap()
        // 跨库进度与本文件记录取较新者，保证跨库/跨设备续播能反映到详情页。
        crossLibProgress = if (playbackRepo != null && showSnapshot?.tmdb_id != null && seasonNumber != null) {
            val tmdbId = showSnapshot.tmdb_id
            runSuspendCatching {
                val withTriple = eps.filter { it.media_key != null && it.episode_number > 0 }
                if (withTriple.isNotEmpty()) {
                    val tripleKeys = withTriple.map { ep ->
                        episodeProgressKey(tmdbId, seasonNumber, ep.episode_number)
                    }
                    val episodeProgress = playbackRepo.getEpisodeProgressByTriples(tripleKeys)
                    withTriple.mapNotNull { ep ->
                        val mk = ep.media_key!!
                        val key = episodeProgressKey(tmdbId, seasonNumber, ep.episode_number)
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
        val eps = scrapedRepo.listEpisodes(seasonId)
        episodes = eps
        loadPlaybackProgress(
            eps = eps,
            showSnapshot = show,
            seasonNumber = seasons.getOrNull(selectedSeasonIndex)?.season_number,
        )
        thumbTrigger++  // 触发集照懒加载(切季/首次加载后)
    }

    suspend fun loadOnlineMeta(s: ScrapedShow?, seasonSnapshot: List<ScrapedSeason>) {
        if (s == null) {
            onlineMetaBySeason = emptyMap()
            seasonShowPathByNumber = emptyMap()
            return
        }
        val ownerShows = seasonSnapshot.map { it.show_id }.toSet().associateWith { showId ->
            if (showId == s.id) s else runSuspendCatching { scrapedRepo.getShow(showId) }.getOrNull()
        }
        val metaByShowId = ownerShows.mapValues { (_, owner) ->
            if (owner == null) emptyMap() else {
                runSuspendCatching { scrapedRepo.listOnlineMeta(owner.library_id, owner.show_path) }
                    .getOrDefault(emptyList())
                    .associateBy { it.season_number }
            }
        }
        onlineMetaBySeason = buildMap {
            metaByShowId[s.id]?.get(0L)?.let { put(0L, it) }
            seasonSnapshot.forEach { season ->
                metaByShowId[season.show_id]?.get(season.season_number)?.let { put(season.season_number, it) }
            }
        }
        seasonShowPathByNumber = buildMap {
            seasonSnapshot.forEach { season ->
                ownerShows[season.show_id]?.show_path?.let { path ->
                    put(season.season_number.toInt(), path)
                }
            }
        }
    }

    /** 按 tmdbid 跨文件夹检索同库所有季(同 tmdbid 的其他文件夹季也纳入, 详情页横向季切换用);
     *  无 tmdbid(ANCHOR) 回落本 show 的季。按 season_number 去重(同 tmdbid 多文件夹可能同季号,
     *  优先当前 show 的)再按 season_number 升序。 */
    suspend fun loadMergedSeasons(s: ScrapedShow?): List<ScrapedSeason> {
        if (s == null) return emptyList()
        val raw = if (s.tmdb_id != null) {
            runSuspendCatching { scrapedRepo.listSeasonsByTmdb(library.id, s.tmdb_id) }.getOrDefault(emptyList())
        } else {
            runSuspendCatching { scrapedRepo.listSeasons(s.id) }.getOrDefault(emptyList())
        }
        return raw.groupBy { it.season_number }.toSortedMap().values
            .map { group -> group.firstOrNull { it.show_id == s.id } ?: group.first() }
    }

    // 首次加载: show -> seasons -> 首季 episodes
    LaunchedEffect(showId) {
        loading = true
        detailsReady = false
        try {
            val s = scrapedRepo.getShow(showId)
            show = s
            val merged = loadMergedSeasons(s)
            seasons = merged
            loadOnlineMeta(s, merged)
            if (merged.isNotEmpty()) {
                // 默认选当前 show 的最低季号(merged 已按 season_number 升序, firstOrNull{show_id==s.id} 即该 show 最低季); 取不到则首个
                val defaultSeasonNumber = merged.firstOrNull { it.show_id == s?.id }?.season_number
                    ?: merged.first().season_number
                val idx = merged.indexOfFirst { it.season_number == defaultSeasonNumber }.coerceAtLeast(0)
                selectedSeasonIndex = idx
                loadEpisodes(merged[idx].id)
            }
        } finally {
            detailsReady = true
            loading = false
        }
    }

    val selectedSeason = seasons.getOrNull(selectedSeasonIndex)
    val selectedSeasonOnlineMeta = selectedSeason?.let { onlineMetaBySeason[it.season_number] }
    val onlineEpisodeByNumber = remember(selectedSeasonOnlineMeta?.episode_json) {
        selectedSeasonOnlineMeta?.decodedEpisodes.orEmpty().associateBy { it.episodeNumber.toLong() }
    }

    val autoScrapeTriggered = remember(showId) { mutableStateOf(false) }
    val forceAutoScrape = remember(showId) { mutableStateOf(false) }
    var libraryRefreshReady by remember(showId) { mutableStateOf(false) }
    val autoTmdbPromptHandled = remember(showId) { mutableStateOf(false) }
    var autoScrapeGeneration by remember(showId) { mutableLongStateOf(0L) }
    val localCommentEpisodes = remember(episodes) {
        episodes.map { LocalCommentEpisode(it.id, it.episode_number, it.title) }
    }

    // 懒触发在线刮削(定义在 reloadAfterRefresh 之后; 见其下方, 因局部函数不支持前向引用)

    // 评论只接受数据库/扫描器已经确认的季度关联；切季或关联变更后立即重读，不猜测 subject ID。
    LaunchedEffect(show, selectedSeason, bangumiLinkVersion, globalSettings.recognizeAnime) {
        commentSubjectId = null
        val currentShow = show
        val currentSeason = selectedSeason
        if (!globalSettings.recognizeAnime || currentShow == null || currentSeason == null) return@LaunchedEffect
        val identityKey = BangumiSeasonIdentity.keyFor(currentShow, currentSeason)
        val persisted = runSuspendCatching { scrapedRepo.getBangumiSeasonLink(identityKey) }.getOrNull()
        commentSubjectId = resolveEffectiveBangumiLink(persisted, currentSeason.bangumi_id)?.subjectId
    }

    LaunchedEffect(
        selectedSeason?.id,
        commentSubjectId,
        localCommentEpisodes,
        detailContentTab,
        globalSettings.recognizeAnime,
    ) {
        val currentSeason = selectedSeason
        if (!globalSettings.recognizeAnime) {
            detailContentTab = 0
            commentState.deactivate()
            commentProvider.clear()
            return@LaunchedEffect
        }
        if (currentSeason == null) {
            commentState.deactivate()
            return@LaunchedEffect
        }
        commentState.configure(
            key = currentSeason.id,
            subject = commentSubjectId,
            episodes = localCommentEpisodes,
            offset = currentSeason.bangumi_offset,
            active = detailContentTab == 1,
            preloadSeasonFirstPage = true,
            initialMode = BangumiCommentMode.SEASON,
        )
    }

    BangumiCommentAutoLoadEffect(
        state = commentState,
        listState = detailListState,
        enabled = globalSettings.recognizeAnime && detailContentTab == 1,
    )

    // 两端播放器都通过仓库版本通知写入完成；只重读当前季进度，不重复加载剧集或生成集照。
    LaunchedEffect(playbackRepo) {
        playbackRepo?.changeVersion?.collect { version ->
            if (version == 0L) return@collect
            val currentSeason = seasons.getOrNull(selectedSeasonIndex)
            val currentShow = show
            val currentEpisodes = episodes
            if (currentShow != null && currentSeason != null && currentEpisodes.isNotEmpty()) {
                loadPlaybackProgress(currentEpisodes, currentShow, currentSeason.season_number)
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
        val s = show ?: return@LaunchedEffect
        val eps = episodes
        // 生成层闸门用 autoGenerateEpisodeThumb(展示层 showEpisodeThumb 仅控制剧集列表是否渲染缩略图)
        if (eps.isEmpty() || episodeThumbGenerator == null || !autoGenerateEpisodeThumb) return@LaunchedEffect
        val seasonNumber = seasons.getOrNull(selectedSeasonIndex)?.season_number
        val onlineThumbEpisodeNumbers = buildSet {
            seasonNumber?.let { onlineMetaBySeason[it] }
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

    // 播放剧集: 重建 MediaEntry -> playMediaEntry
    fun playEpisode(ep: ScrapedEpisode) {
        val s = show
        val selected = seasons.getOrNull(selectedSeasonIndex)
        val sn = selected?.season_number
        val onlineEpisode = onlineEpisodeByNumber[ep.episode_number]
        playMediaEntry(MediaEntry(
            name = ep.video_name,
            path = ep.video_path,
            isDirectory = false,
            tmdbId = s?.tmdb_id,
            seasonNumber = sn,
            episodeNumber = ep.episode_number,
        ), AnimePlaybackContext(
            seriesTitle = s?.title.orEmpty(),
            episodeTitle = onlineEpisode?.title?.takeIf { it.isNotBlank() } ?: ep.title,
            episodeDescription = onlineEpisode?.plot?.takeIf { it.isNotBlank() } ?: ep.plot,
            bangumiSubjectId = commentSubjectId,
            bangumiEpisodeOffset = selected?.bangumi_offset ?: 0L,
        ))
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
    suspend fun reloadAfterRefresh(s: ScrapedShow) {
        val updated = scrapedRepo.getShow(s.id)
        show = updated
        val currentSeasonNumber = seasons.getOrNull(selectedSeasonIndex)?.season_number
        val merged = loadMergedSeasons(updated)
        seasons = merged
        loadOnlineMeta(updated, merged)
        if (merged.isNotEmpty()) {
            val idx = if (currentSeasonNumber != null)
                merged.indexOfFirst { it.season_number == currentSeasonNumber } else -1
            selectedSeasonIndex = if (idx >= 0) idx else 0
            loadEpisodes(merged[selectedSeasonIndex].id)
        }
        onShowChanged()
    }

    // 每次进入该番剧详情页后按番剧自身的扫描时间做低频深探测；海报墙顶部增量扫描不进入已记录番剧目录。
    LaunchedEffect(show?.id, detailsReady) {
        if (!detailsReady || libraryRefreshReady) return@LaunchedEffect
        val current = show
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
                }
            } finally {
                refreshing = false
                libraryRefreshReady = true
            }
        } else {
            libraryRefreshReady = true
        }
    }

    suspend fun currentSeasonNeedsEpisodeThumb(): Boolean {
        val season = seasons.getOrNull(selectedSeasonIndex) ?: return false
        return hasMissingEpisodeThumbCandidate(
            nfoThumbsByEpisode = episodes.associate { it.episode_number to it.thumb_path },
            onlineThumbsByEpisode = onlineMetaBySeason[season.season_number]
                ?.decodedEpisodes
                .orEmpty()
                .associate { it.episodeNumber.toLong() to it.thumbPath },
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
                    currentShow = show,
                    onlineMetaBySeason = onlineMetaBySeason,
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
    LaunchedEffect(show?.id, scraper, detailsReady, autoScrapeGeneration, libraryRefreshReady) {
        if (!detailsReady || !libraryRefreshReady) return@LaunchedEffect
        val s = show ?: return@LaunchedEffect
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
        val shouldAutoScrape = if (forceAutoScrape.value) {
            true
        } else {
            runSuspendCatching { scr.shouldAutoScrape(library.id, s.show_path) }.getOrElse {
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
        if (!shouldAutoScrape) {
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
        episodeThumbFallbackDecision = EpisodeThumbFallbackDecision.WAIT_FOR_ONLINE_MATCH
        automaticScrapeInProgress = true
        scrapeMessage = null
        scrapeProgressMessage = "正在匹配在线信息..."
        try {
            val outcome = runSuspendCatching {
                scr.scrapeAuto(
                    library = library,
                    showPath = s.show_path,
                    hashProvider = scrapeHashProvider,
                    onProgress = { message -> scrapeProgressMessage = message },
                )
            }.getOrElse {
                scrapeMessage = "自动刮削失败, 菜单里可手动重试"
                episodeThumbFallbackDecision = initialEpisodeThumbFallbackDecision(
                    needsOnlineScrape = true,
                    canRunAutoScrape = false,
                    hasMissingEpisodeThumb = selectedSeasonNeedsEpisodeThumb,
                )
                return@LaunchedEffect
            }
            var pendingCandidateSource: ScrapeSource? = null
            when (outcome) {
                is AnimeScraper.AutoScrapeOutcome.Done -> {
                    scrapeMessage = "已在线补全元数据"
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
                }
                is AnimeScraper.AutoScrapeOutcome.NeedsConfirmation -> {
                    episodeThumbFallbackDecision = episodeThumbFallbackDecisionAfter(outcome)
                    pendingCandidateSource = candidateDialogSourceAfter(outcome)
                    scrapeMessage = "候选不唯一，可从菜单手动选择正确作品"
                }
                AnimeScraper.AutoScrapeOutcome.RetryableFailure -> {
                    episodeThumbFallbackDecision = episodeThumbFallbackDecisionAfter(outcome)
                    scrapeMessage = "在线服务暂时不可用，稍后进入详情页会自动重试"
                }
                AnimeScraper.AutoScrapeOutcome.Skipped -> {
                    episodeThumbFallbackDecision = episodeThumbFallbackDecisionAfter(outcome)
                    scrapeMessage = "在线刮削正在其他任务中进行"
                }
            }
            if (openPendingTmdbPrompt(s, scr)) {
                scrapeMessage = "TMDB 未能自动确定作品，请手动选择"
            } else if (pendingCandidateSource != null) {
                openScrapeDialog(pendingCandidateSource, autoSearch = true)
                scrapeMessage = "候选不唯一，请手动选择正确作品"
            }
        } finally {
            scrapeProgressMessage = null
            automaticScrapeInProgress = false
        }
    }

    // 确定刷新目标 show: 跟随当前选中季所在文件夹。跨文件夹番剧(同 tmdbid 多文件夹)时,
    // 详情页季列表按 tmdbid 跨文件夹合并; 切到其他文件夹的季后刷新, 须扫该季所在文件夹,
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
        val s = show ?: return
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

    // 刷新(清除缓存): 清刮削数据 + 收藏/隐藏用户状态 + 图片缓存, 保留播放记录, 重新扫描入库。
    // 适用于普通刷新无效或元数据异常时。开头清图片缓存(海报会闪); 扫描成功后重置收藏/隐藏状态。
    fun refreshShowClearCache() {
        val s = show ?: return
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
                    runSuspendCatching { scrapedRepo.setFavorite(target.id, false) }
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
        val s = show ?: return
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
                    IconButton(onClick = { refreshShow() }, enabled = !refreshing && !detailOperationInProgress && show != null) {
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
                                text = { Text("在线刮削/纠正") },
                                enabled = scraper != null && show != null && !refreshing && !detailOperationInProgress,
                                onClick = {
                                    moreMenuExpanded = false
                                    openScrapeDialog(defaultScrapeDialogSource, autoSearch = true)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("TMDB 补全") },
                                enabled = scraper != null && show != null && !refreshing && !detailOperationInProgress,
                                onClick = {
                                    moreMenuExpanded = false
                                    val target = show
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
                                    show != null && !refreshing && !detailOperationInProgress,
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
                LazyColumn(
                    state = detailListState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                // === 顶部头部区: fanart 背景 + 半透明遮罩 + poster + 标题/元信息 ===
                item {
                    val selectedSeason = seasons.getOrNull(selectedSeasonIndex)
                    val seasonMeta = selectedSeason?.let { onlineMetaBySeason[it.season_number] }
                    val nfoSeasonPoster = mediaSourceImage(selectedSeason?.season_poster_path)
                    val nfoShowPoster = mediaSourceImage(show?.poster_path)
                    val onlineSeasonPoster = localFileImage(seasonMeta?.local_poster_path)
                    val headerPosterCandidates = if (useSeasonPoster) {
                        imageCandidates(nfoSeasonPoster, nfoShowPoster, onlineSeasonPoster)
                    } else {
                        imageCandidates(nfoShowPoster, nfoSeasonPoster, onlineSeasonPoster)
                    }
                    val headerFanartCandidates = imageCandidates(
                        mediaSourceImage(show?.fanart_path),
                        localFileImage(onlineMetaBySeason[0L]?.local_fanart_path),
                    )
                    val headerBackgroundCandidates = (headerFanartCandidates + headerPosterCandidates).distinct()
                    val headerPoster = headerPosterCandidates.firstOrNull()
                    val headerBackground = headerBackgroundCandidates.firstOrNull()
                    var activeBackgroundIndex by remember(headerBackgroundCandidates) { mutableIntStateOf(0) }
                    val isBlurredFallback = headerPoster != null && activeBackgroundIndex >= headerFanartCandidates.size
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
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
                            placeholderText = show?.title ?: "",
                            imageCacheSizeMb = imageCacheSizeMb,
                            downloader = imageDownloader,
                            cacheSubdir = showKey,
                            onCandidateIndexChanged = { activeBackgroundIndex = it },
                            previewEnabled = headerBackground != null,
                            saveFileStem = "${show?.title ?: "番剧"} 头图",
                        )
                        // 半透明遮罩让前景文字清晰(海报兜底时加深, 配合模糊)
                        Box(
                            modifier = Modifier.fillMaxSize().background(
                                Color.Black.copy(alpha = if (isBlurredFallback) 0.55f else 0.4f)
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Box {  // 海报 + 季徽章
                                ScrapedImage(
                                    sourceKind = library.sourceKind,
                                    libraryId = library.id,
                                    imagePath = headerPoster?.path,
                                    imagePathKind = headerPoster?.kind ?: ScrapedImagePathKind.MEDIA_SOURCE,
                                    fallbackImages = headerPosterCandidates.drop(1),
                                    contentDescription = show?.title,
                                    modifier = Modifier.size(100.dp, 150.dp),
                                    placeholderText = show?.title ?: "",
                                    imageCacheSizeMb = imageCacheSizeMb,
                                    downloader = imageDownloader,
                                    cacheSubdir = showKey,
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

                // 番剧识别关闭时完全隐藏评论入口，并固定回到剧集视图。
                if (globalSettings.recognizeAnime) {
                    item {
                        PrimaryTabRow(
                            selectedTabIndex = detailContentTab,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Tab(
                                selected = detailContentTab == 0,
                                onClick = { detailContentTab = 0 },
                                text = { Text("剧集") },
                            )
                            Tab(
                                selected = detailContentTab == 1,
                                onClick = { detailContentTab = 1 },
                                text = { Text("评论") },
                            )
                        }
                    }
                }

                if (detailContentTab == 0 || !globalSettings.recognizeAnime) {
                // === 剧集列表 ===
                // key = 剧集主键: 集照生成成功逐集回写触发 episodes 整表替换(episodes.map 全量),
                // 无 key 时按位置对账导致全列表重组; 稳定 key 让 LazyColumn 只重组 local_thumb_path 变化的项。
                items(episodes, key = { it.id }) { ep ->
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
                            val episodeImages = imageCandidates(
                                mediaSourceImage(ep.thumb_path),
                                localFileImage(onlineEpisodeByNumber[ep.episode_number]?.thumbPath),
                                localFileImage(ep.local_thumb_path),
                            )
                            val episodeImage = episodeImages.firstOrNull()
                            ScrapedImage(
                                sourceKind = library.sourceKind,
                                libraryId = library.id,
                                imagePath = episodeImage?.path,
                                imagePathKind = episodeImage?.kind ?: ScrapedImagePathKind.MEDIA_SOURCE,
                                fallbackImages = episodeImages.drop(1),
                                contentDescription = "E${ep.episode_number}",
                                modifier = Modifier.size(120.dp, 68.dp),
                                placeholderText = "E${ep.episode_number}",
                                imageCacheSizeMb = imageCacheSizeMb,
                                downloader = imageDownloader,
                                cacheSubdir = showKey,
                                cacheName = "$epLabel$epTitle.jpg",
                                previewEnabled = episodeImage != null,
                                saveFileStem = "${show?.title ?: "番剧"} $epLabel 剧照",
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
                        onPlay = ::playMediaEntry,
                    )
                }
                } else {
                    bangumiCommentItems(
                        state = commentState,
                        onOpenBangumiLink = { showBangumiLinkDialog = true },
                        showEpisodeMode = false,
                        sourceLabel = bangumiEndpoints.sourceLabel,
                    )
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

    // 刷新(清除缓存)确认框: 清图片缓存与收藏/隐藏状态, 保留在线身份、文本和播放记录, 重新扫描入库
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("刷新(清除缓存)") },
            text = { Text("「${show?.title}」\n\n将清除海报、头图和集照缓存，重置收藏/隐藏状态并重新扫描。\n在线身份、刮削文本和播放进度保留，图片会重新下载。\n\n适用于图片缓存失效或元数据异常时。") },
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
    val overrideKey = show?.let { ShowOverrideIdentity.keyFor(it.tmdb_id, it.library_id, it.show_path) }
    if (showOverrideDialog && overrideKey != null) {
        ShowOverrideDialog(
            showTitle = show?.title ?: "",
            identityKey = overrideKey,
            globalSettings = globalSettings,
            scrapedRepo = scrapedRepo,
            // 无 tmdb 的 ANCHOR 节目: 播放端覆盖只认 tmdbId 不生效, 弹窗顶部加提示(仍可保存)
            appliesDuringPlayback = show?.tmdb_id != null,
            onDismiss = { showOverrideDialog = false },
        )
    }

    val bangumiShow = show
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

    // 在线刮削手动纠正弹窗
    val scrapeShow = show
    if (showScrapeDialog && scrapeShow != null && scraper != null) {
        ScrapeDialog(
            showTitle = scrapeShow.title,
            showPath = scrapeShow.show_path,
            library = library,
            scraper = scraper,
            seasonNumbers = seasons.map { it.season_number.toInt() }.distinct(),
            seasonShowPaths = seasonShowPathByNumber,
            initialSeasonNumber = seasons.getOrNull(selectedSeasonIndex)?.season_number?.toInt(),
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

private suspend fun buildEpisodeThumbTargets(
    seasons: List<ScrapedSeason>,
    currentShow: ScrapedShow?,
    onlineMetaBySeason: Map<Long, ScrapedOnlineMeta>,
    scrapedRepo: ScrapedLibraryRepository,
): List<EpisodeThumbCoordinator.Target> {
    val ownerShows = seasons.map { it.show_id }.distinct().associateWith { ownerId ->
        if (ownerId == currentShow?.id) currentShow
        else runSuspendCatching { scrapedRepo.getShow(ownerId) }.getOrNull()
    }
    return seasons.flatMap { season ->
        val owner = ownerShows[season.show_id] ?: return@flatMap emptyList()
        val onlineEpisodeNumbers = onlineMetaBySeason[season.season_number]
            ?.decodedEpisodes
            .orEmpty()
            .filter { !it.thumbPath.isNullOrBlank() && !isMissingLocalFilePath(it.thumbPath) }
            .mapTo(hashSetOf()) { it.episodeNumber.toLong() }
        scrapedRepo.listEpisodes(season.id).map { episode ->
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
): Boolean = hasTmdb &&
    tmdbId == null &&
    failure?.promptSuppressed == false &&
    !handledInThisDetailSession

internal suspend fun hasMissingEpisodeThumbCandidate(
    nfoThumbsByEpisode: Map<Long, String?>,
    onlineThumbsByEpisode: Map<Long, String?>,
): Boolean = nfoThumbsByEpisode.any { (episodeNumber, nfoThumb) ->
    nfoThumb.isNullOrBlank() && onlineThumbsByEpisode[episodeNumber].let { onlineThumb ->
        onlineThumb.isNullOrBlank() || isMissingLocalFilePath(onlineThumb)
    }
}

private fun mediaSourceImage(path: String?): ScrapedImageCandidate? =
    path?.takeIf { it.isNotBlank() }?.let { ScrapedImageCandidate(it, ScrapedImagePathKind.MEDIA_SOURCE) }

private fun localFileImage(path: String?): ScrapedImageCandidate? =
    path?.takeIf { it.isNotBlank() }?.let { ScrapedImageCandidate(it, ScrapedImagePathKind.LOCAL_FILE) }

private fun imageCandidates(vararg images: ScrapedImageCandidate?): List<ScrapedImageCandidate> =
    images.filterNotNull().distinct()
