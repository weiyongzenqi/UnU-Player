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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.weiyongzenqi.unuplayer.library.EpisodeThumbCoordinator
import io.github.weiyongzenqi.unuplayer.library.EpisodeThumbGenerator
import io.github.weiyongzenqi.unuplayer.library.EpisodeThumbPosition
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.MediaSourceCache
import io.github.weiyongzenqi.unuplayer.library.ScanConfig
import io.github.weiyongzenqi.unuplayer.library.ScanResult
import io.github.weiyongzenqi.unuplayer.library.ScrapedEpisode
import io.github.weiyongzenqi.unuplayer.library.ScrapedImage
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryScanner
import io.github.weiyongzenqi.unuplayer.library.ScrapedSeason
import io.github.weiyongzenqi.unuplayer.library.ScrapedShow
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideIdentity
import io.github.weiyongzenqi.unuplayer.library.cacheKey
import io.github.weiyongzenqi.unuplayer.library.sanitizeFileName
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.playback.episodeProgressKey
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile
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
    var progressMap by remember { mutableStateOf<Map<String, PlaybackRecord>>(emptyMap()) }
    // 剧集显示进度(跨库双向跟随): 有三元组的集已解析为"本文件/跨库 last_played_at 较新者"的 watch_progress;
    // 无三元组的集不在其中, UI 回落本文件 progressMap。
    var crossLibProgress by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    // 集照懒加载触发 token: loadEpisodes 后自增, LaunchedEffect(thumbTrigger) 据此触发 coordinator(切季自动取消上一个)
    var thumbTrigger by remember { mutableLongStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showOverrideDialog by remember { mutableStateOf(false) }
    var showBangumiLinkDialog by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var detailContentTab by remember { mutableStateOf(0) }
    var bangumiLinkVersion by remember { mutableLongStateOf(0L) }
    var commentSubjectId by remember { mutableStateOf<Long?>(null) }

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
        val s = scrapedRepo.getShow(showId)
        show = s
        val merged = loadMergedSeasons(s)
        seasons = merged
        if (merged.isNotEmpty()) {
            // 默认选当前 show 的最低季号(merged 已按 season_number 升序, firstOrNull{show_id==s.id} 即该 show 最低季); 取不到则首个
            val defaultSeasonNumber = merged.firstOrNull { it.show_id == s?.id }?.season_number
                ?: merged.first().season_number
            val idx = merged.indexOfFirst { it.season_number == defaultSeasonNumber }.coerceAtLeast(0)
            selectedSeasonIndex = idx
            loadEpisodes(merged[idx].id)
        }
        loading = false
    }

    val selectedSeason = seasons.getOrNull(selectedSeasonIndex)
    val localCommentEpisodes = remember(episodes) {
        episodes.map { LocalCommentEpisode(it.id, it.episode_number, it.title) }
    }

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

    // 集照懒加载: loadEpisodes 后(thumbTrigger 变化)对无刮削集照的集本地抽帧生成; 切季自动取消上一个
    LaunchedEffect(thumbTrigger) {
        if (thumbTrigger == 0L) return@LaunchedEffect
        val s = show ?: return@LaunchedEffect
        val eps = episodes
        // 生成层闸门用 autoGenerateEpisodeThumb(展示层 showEpisodeThumb 仅控制剧集列表是否渲染缩略图)
        if (eps.isEmpty() || episodeThumbGenerator == null || !autoGenerateEpisodeThumb) return@LaunchedEffect
        runSuspendCatching {
            EpisodeThumbCoordinator.ensureThumbs(
                episodes = eps,
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
        playMediaEntry(MediaEntry(
            name = ep.video_name,
            path = ep.video_path,
            isDirectory = false,
            tmdbId = s?.tmdb_id,
            seasonNumber = sn,
            episodeNumber = ep.episode_number,
        ), AnimePlaybackContext(
            seriesTitle = s?.title.orEmpty(),
            episodeTitle = ep.title,
            bangumiSubjectId = commentSubjectId,
            bangumiEpisodeOffset = selected?.bangumi_offset ?: 0L,
        ))
    }

    // 刷新后重载 show 元数据 + seasons + 当前季 episodes(普通刷新与清缓存刷新复用)。
    // 注: 局部函数不能用 private 修饰符, 且须定义在调用方之前(Kotlin 局部函数不支持前向引用)。
    suspend fun reloadAfterRefresh(s: ScrapedShow) {
        val updated = scrapedRepo.getShow(s.id)
        show = updated
        val currentSeasonNumber = seasons.getOrNull(selectedSeasonIndex)?.season_number
        val merged = loadMergedSeasons(updated)
        seasons = merged
        if (merged.isNotEmpty()) {
            val idx = if (currentSeasonNumber != null)
                merged.indexOfFirst { it.season_number == currentSeasonNumber } else -1
            selectedSeasonIndex = if (idx >= 0) idx else 0
            loadEpisodes(merged[selectedSeasonIndex].id)
        }
        onShowChanged()
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

    // 刷新此番剧: 单番剧重扫(重新解析 tvshow.nfo + 所有季/剧集), 完成后重新加载详情数据。
    // 详情页复用页面级 source cache，不走 PosterWallScanCoordinator(单番剧快, 用户在场)。
    // 普通刷新不清图片缓存(海报不闪); PROPFIND 抖动时轻量重试 1 次; 接住 ScanResult 给 toast 反馈。
    fun refreshShow() {
        val s = show ?: return
        if (refreshing) return
        scope.launch {
            refreshing = true
            // 跟随当前选中季所在文件夹(跨文件夹番剧时刷新切到的季所在文件夹, 而非进入详情页的原始文件夹)
            val target = resolveRefreshTarget(s)
            var result = runSuspendCatching {
                mediaSourceCache.withSource(library) { source ->
                    val scanner = ScrapedLibraryScanner(source, library, scrapedRepo, scanConfig)
                    scanner.scanOneShow(target.show_path)
                }
            }.getOrNull()
            // PROPFIND 抖动等偶发错误时轻量重试 1 次(errors>0 或 timedOut 才重试, 不无限重试)
            if (result != null && (result.errors > 0 || result.timedOut)) {
                result = runSuspendCatching {
                    mediaSourceCache.withSource(library) { source ->
                        val scanner = ScrapedLibraryScanner(source, library, scrapedRepo, scanConfig)
                        scanner.scanOneShow(target.show_path)
                    }
                }.getOrNull() ?: result
            }
            reloadAfterRefresh(s)
            // toast 反馈(成功/失败/超时)
            when {
                result == null -> AppNotif.toast("刷新失败: 网络错误")
                result.errors > 0 -> AppNotif.toast("刷新失败: ${result.firstErrorMessage ?: "未知错误"}")
                result.timedOut -> AppNotif.toast("刷新超时")
                else -> AppNotif.toast("已刷新, 共 ${result.foundEpisodes} 集")
            }
            refreshing = false
        }
    }

    // 刷新(清除缓存): 清刮削数据 + 收藏/隐藏用户状态 + 图片缓存, 保留播放记录, 重新扫描入库。
    // 适用于普通刷新无效或元数据异常时。开头清图片缓存(海报会闪); 扫描成功后重置收藏/隐藏状态。
    fun refreshShowClearCache() {
        val s = show ?: return
        if (refreshing) return
        scope.launch {
            refreshing = true
            // 跟随当前选中季所在文件夹(跨文件夹番剧时清缓存+刷新切到的季所在文件夹)
            val target = resolveRefreshTarget(s)
            // 清图片缓存(海报/缩略图重新下载)
            runSuspendCatching { scrapedRepo.clearShowCache(target.id) }
            var result = runSuspendCatching {
                mediaSourceCache.withSource(library) { source ->
                    val scanner = ScrapedLibraryScanner(source, library, scrapedRepo, scanConfig)
                    scanner.scanOneShow(target.show_path)
                }
            }.getOrNull()
            if (result != null && (result.errors > 0 || result.timedOut)) {
                result = runSuspendCatching {
                    mediaSourceCache.withSource(library) { source ->
                        val scanner = ScrapedLibraryScanner(source, library, scrapedRepo, scanConfig)
                        scanner.scanOneShow(target.show_path)
                    }
                }.getOrNull() ?: result
            }
            // 扫描成功后重置目标 show 收藏/隐藏状态(保留播放记录, 仅清刮削元数据 + 用户状态)
            if (result != null && result.errors == 0 && !result.timedOut) {
                runSuspendCatching { scrapedRepo.setFavorite(target.id, false) }
                runSuspendCatching { scrapedRepo.setHidden(target.id, false) }
            }
            reloadAfterRefresh(s)
            when {
                result == null -> AppNotif.toast("刷新失败: 网络错误")
                result.errors > 0 -> AppNotif.toast("刷新失败: ${result.firstErrorMessage ?: "未知错误"}")
                result.timedOut -> AppNotif.toast("刷新超时")
                else -> AppNotif.toast("已清除缓存并刷新, 共 ${result.foundEpisodes} 集")
            }
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
            LazyColumn(
                state = detailListState,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                // === 顶部头部区: fanart 背景 + 半透明遮罩 + poster + 标题/元信息 ===
                item {
                    val selectedSeason = seasons.getOrNull(selectedSeasonIndex)
                    val headerPosterPath = if (useSeasonPoster)
                        (selectedSeason?.season_poster_path ?: show?.poster_path) else show?.poster_path
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
                            Box {  // 海报 + 季徽章
                                ScrapedImage(
                                    sourceKind = library.sourceKind,
                                    libraryId = library.id,
                                    imagePath = headerPosterPath,
                                    contentDescription = show?.title,
                                    modifier = Modifier.size(100.dp, 150.dp),
                                    placeholderText = show?.title ?: "",
                                    imageCacheSizeMb = imageCacheSizeMb,
                                    downloader = downloader(headerPosterPath),
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
                            ScrapedImage(
                                sourceKind = library.sourceKind,
                                libraryId = library.id,
                                imagePath = ep.thumb_path ?: ep.local_thumb_path,
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

    // 刷新(清除缓存)确认框: 清刮削元数据 + 收藏/隐藏状态 + 图片缓存, 保留播放记录, 重新扫描入库
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("刷新(清除缓存)") },
            text = { Text("「${show?.title}」\n\n将清除刮削元数据、收藏/隐藏状态并重新扫描，图片重新下载。\n播放进度保留。\n\n适用于刷新无效或元数据异常时。") },
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
}
