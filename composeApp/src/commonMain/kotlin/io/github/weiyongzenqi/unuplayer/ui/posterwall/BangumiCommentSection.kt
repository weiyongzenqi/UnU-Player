package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentProviderContract
import io.github.weiyongzenqi.unuplayer.bangumi.OFFICIAL_BANGUMI_ENDPOINTS
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentAuthor
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiAvatarRepository
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiEpisodeCommentThread
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiEpisodeMapping
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiEpisodeRef
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BANGUMI_REVIEW_PAGE_SIZE
import io.github.weiyongzenqi.unuplayer.bangumi.comment.mapBangumiEpisode
import io.github.weiyongzenqi.unuplayer.bangumi.comment.mergeReviewPages
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.util.formatLogDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class LocalCommentEpisode(val id: Long, val number: Long, val title: String?)

/** 评论 Tab 模式: REVIEWS=条目长评(原 SEASON 与吐槽 Tab 同源 /c, 已移除), EPISODE=单集评论。 */
enum class BangumiCommentMode { REVIEWS, EPISODE }

@Stable
class BangumiCommentUiState internal constructor(
    private val provider: BangumiCommentProviderContract,
    private val scope: CoroutineScope,
) {
    var mode by mutableStateOf(BangumiCommentMode.REVIEWS)
        private set
    var subjectId by mutableStateOf<Long?>(null)
        private set
    var localEpisodes by mutableStateOf<List<LocalCommentEpisode>>(emptyList())
        private set
    /** 本季 bangumi.ini 漂移; 本地集号为 TMDB 全系列连续号时用它减回条目内集号。 */
    var bangumiEpisodeOffset by mutableStateOf(0L)
        private set
    var reviews by mutableStateOf<List<io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiReview>>(emptyList())
        private set
    var reviewTotal by mutableStateOf(0)
        private set
    var reviewLoading by mutableStateOf(false)
        private set
    var reviewError by mutableStateOf<String?>(null)
        private set
    var reviewHasMore by mutableStateOf(false)
        private set
    private var reviewNextOffset = 0
    var episodeRefs by mutableStateOf<List<BangumiEpisodeRef>>(emptyList())
        private set
    var episodeIndexLoading by mutableStateOf(false)
        private set
    var episodeError by mutableStateOf<String?>(null)
        private set
    var selectedLocalEpisodeId by mutableStateOf<Long?>(null)
        private set
    var episodeComments by mutableStateOf<List<BangumiEpisodeCommentThread>>(emptyList())
        private set
    var episodeVisibleCount by mutableIntStateOf(0)
        private set
    val visibleEpisodeComments: List<BangumiEpisodeCommentThread>
        get() = episodeComments.take(episodeVisibleCount)
    val episodeHasMore: Boolean
        get() = episodeVisibleCount < episodeComments.size
    var episodeLoading by mutableStateOf(false)
        private set
    private var activeJob: Job? = null
    private var activeRequestToken = 0L
    private var activeKey: Long? = null
    private var configuration: CommentConfiguration? = null
    private var loadedEpisodeCommentId: Long? = null
    // 长评快照按 subject 缓存, 切走再切回可恢复分页位置; 有界防长会话内存线性累积
    // (浏览 N 部番剧即常驻 N 份完整长评列表, 热门条目累计数 MB)
    private val reviewSnapshots = object : LinkedHashMap<Long, ReviewSnapshot>(INITIAL_SNAPSHOT_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ReviewSnapshot>?): Boolean =
            size > MAX_REVIEW_SNAPSHOT_SUBJECTS
    }
    private val selectedEpisodes = mutableMapOf<Long, Long>()

    fun configure(
        key: Long,
        subject: Long?,
        episodes: List<LocalCommentEpisode>,
        active: Boolean,
        preloadFirstPage: Boolean = false,
        initialMode: BangumiCommentMode? = null,
        preferredEpisodeId: Long? = null,
        bangumiEpisodeOffset: Long = 0L,
    ) {
        val nextConfiguration = CommentConfiguration(
            key = key.takeIf { it > 0 },
            subject = subject,
            episodes = episodes,
            initialMode = initialMode,
            preferredEpisodeId = preferredEpisodeId,
            bangumiEpisodeOffset = bangumiEpisodeOffset,
        )
        if (configuration == nextConfiguration) {
            when {
                active -> activate()
                preloadFirstPage -> preloadFirstPageIfNeeded()
                else -> deactivate()
            }
            return
        }
        cancelActiveLoad()
        configuration = nextConfiguration
        initialMode?.let { mode = it }
        activeKey = nextConfiguration.key
        subjectId = subject
        localEpisodes = episodes
        this.bangumiEpisodeOffset = nextConfiguration.bangumiEpisodeOffset
        selectedLocalEpisodeId = preferredEpisodeId ?: activeKey?.let(selectedEpisodes::get)
        val snapshot = subject?.let(reviewSnapshots::get)
        reviews = snapshot?.reviews.orEmpty()
        reviewTotal = snapshot?.total ?: 0
        reviewHasMore = snapshot?.hasMore == true
        reviewNextOffset = snapshot?.nextOffset ?: 0
        reviewError = null
        reviewLoading = false
        episodeRefs = emptyList()
        episodeComments = emptyList()
        loadedEpisodeCommentId = null
        episodeVisibleCount = 0
        episodeError = null
        episodeIndexLoading = false
        episodeLoading = false
        if (active) activate() else if (preloadFirstPage) preloadFirstPageIfNeeded()
    }

    fun deactivate() {
        cancelActiveLoad()
    }

    private fun cancelActiveLoad() {
        activeRequestToken++
        activeJob?.cancel()
        activeJob = null
        reviewLoading = false
        episodeIndexLoading = false
        episodeLoading = false
    }

    fun selectMode(next: BangumiCommentMode) {
        if (mode == next) return
        cancelActiveLoad()
        mode = next
        if (next == BangumiCommentMode.REVIEWS) loadReviewPage(refresh = false, reset = reviews.isEmpty())
        else loadEpisodeIndex()
    }

    fun selectEpisode(localId: Long) {
        cancelActiveLoad()
        selectedLocalEpisodeId = localId
        episodeComments = emptyList()
        loadedEpisodeCommentId = null
        episodeVisibleCount = 0
        episodeError = null
        activeKey?.let { selectedEpisodes[it] = localId }
        loadEpisodeComments()
    }

    fun refresh() {
        cancelActiveLoad()
        when (mode) {
            BangumiCommentMode.REVIEWS -> loadReviewPage(refresh = true, reset = true)
            BangumiCommentMode.EPISODE -> {
                if (episodeRefs.isEmpty() || selectedLocalEpisodeId == null) loadEpisodeIndex(refresh = true)
                else loadEpisodeComments(refresh = true)
            }
        }
    }

    fun loadMoreReviews() {
        if (!reviewLoading && reviewHasMore) loadReviewPage(refresh = false, reset = false)
    }

    fun showMoreEpisodeComments() {
        episodeVisibleCount = (episodeVisibleCount + EPISODE_COMMENT_BATCH_SIZE)
            .coerceAtMost(episodeComments.size)
    }

    private fun activate() {
        if (subjectId == null) return
        if (mode == BangumiCommentMode.REVIEWS && reviews.isEmpty() && reviewError == null) {
            loadReviewPage(refresh = false, reset = true)
        } else if (mode == BangumiCommentMode.EPISODE && episodeError == null) {
            if (episodeRefs.isEmpty()) {
                loadEpisodeIndex()
            } else {
                val remoteId = selectedRemoteEpisodeId()
                if (remoteId != null && loadedEpisodeCommentId != remoteId) loadEpisodeComments()
            }
        }
    }

    private fun preloadFirstPageIfNeeded() {
        if (
            mode == BangumiCommentMode.REVIEWS &&
            subjectId != null &&
            reviews.isEmpty() &&
            reviewError == null
        ) {
            loadReviewPage(refresh = false, reset = true)
        }
    }

    private fun loadReviewPage(refresh: Boolean, reset: Boolean) {
        val subject = subjectId ?: return
        if (reviewLoading) return
        val offset = if (reset) 0 else reviewNextOffset
        reviewLoading = true
        reviewError = null
        val requestToken = ++activeRequestToken
        activeJob = scope.launch {
            try {
                val result = runSuspendCatching {
                    provider.getSubjectReviews(subject, BANGUMI_REVIEW_PAGE_SIZE, offset, refresh)
                }
                if (activeRequestToken != requestToken) return@launch
                result.onSuccess { page ->
                    val merged = if (reset) page.reviews else mergeReviewPages(reviews, page)
                    reviews = merged
                    reviewTotal = page.total
                    reviewHasMore = page.hasMore
                    reviewNextOffset = page.nextOffset
                    reviewSnapshots[subject] = ReviewSnapshot(
                        reviews = merged,
                        total = page.total,
                        nextOffset = page.nextOffset,
                        hasMore = page.hasMore,
                    )
                }.onFailure { throwable ->
                    reviewError = throwable.message?.take(120) ?: "加载长评失败"
                }
            } finally {
                if (activeRequestToken == requestToken) {
                    reviewLoading = false
                    activeJob = null
                }
            }
        }
    }

    private fun loadEpisodeIndex(refresh: Boolean = false) {
        val subject = subjectId ?: return
        if (episodeIndexLoading) return
        episodeIndexLoading = true
        episodeError = null
        val requestToken = ++activeRequestToken
        activeJob = scope.launch {
            var loadSelectedEpisode = false
            try {
                val result = runSuspendCatching { provider.resolveEpisodes(subject, refresh) }
                if (activeRequestToken != requestToken) return@launch
                result.onSuccess { refs ->
                    episodeRefs = refs
                    val currentSelection = localEpisodes.firstOrNull { it.id == selectedLocalEpisodeId }
                    if (currentSelection == null || mappingFor(currentSelection) !is BangumiEpisodeMapping.Mapped) {
                        selectedLocalEpisodeId = localEpisodes.firstOrNull {
                            mappingFor(it) is BangumiEpisodeMapping.Mapped
                        }?.id
                    }
                    selectedLocalEpisodeId?.let { localId -> activeKey?.let { selectedEpisodes[it] = localId } }
                    loadSelectedEpisode = selectedLocalEpisodeId != null
                }
                .onFailure { throwable -> episodeError = throwable.message?.take(120) ?: "加载集数索引失败" }
            } finally {
                if (activeRequestToken == requestToken) {
                    episodeIndexLoading = false
                    activeJob = null
                }
            }
            if (activeRequestToken == requestToken && loadSelectedEpisode) loadEpisodeComments()
        }
    }

    private fun loadEpisodeComments(refresh: Boolean = false) {
        val local = localEpisodes.firstOrNull { it.id == selectedLocalEpisodeId } ?: return
        val mapping = mapBangumiEpisode(local.number, episodeRefs, bangumiEpisodeOffset)
        val remoteId = (mapping as? BangumiEpisodeMapping.Mapped)?.episode?.id ?: run {
            episodeComments = emptyList()
            loadedEpisodeCommentId = null
            episodeVisibleCount = 0
            episodeError = null
            episodeLoading = false
            return
        }
        if (episodeLoading) return
        episodeLoading = true
        episodeError = null
        val requestToken = ++activeRequestToken
        activeJob = scope.launch {
            try {
                val result = runSuspendCatching { provider.getEpisodeComments(remoteId, refresh) }
                if (activeRequestToken != requestToken) return@launch
                result.onSuccess {
                    episodeComments = it
                    loadedEpisodeCommentId = remoteId
                    episodeVisibleCount = it.size.coerceAtMost(EPISODE_COMMENT_BATCH_SIZE)
                }
                .onFailure { throwable -> episodeError = throwable.message?.take(120) ?: "加载单集评论失败" }
            } finally {
                if (activeRequestToken == requestToken) {
                    episodeLoading = false
                    activeJob = null
                }
            }
        }
    }

    private fun selectedRemoteEpisodeId(): Long? {
        val local = localEpisodes.firstOrNull { it.id == selectedLocalEpisodeId } ?: return null
        return (mappingFor(local) as? BangumiEpisodeMapping.Mapped)?.episode?.id
    }

    fun mappingFor(local: LocalCommentEpisode): BangumiEpisodeMapping =
        mapBangumiEpisode(local.number, episodeRefs, bangumiEpisodeOffset)

    private data class ReviewSnapshot(
        val reviews: List<io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiReview>,
        val total: Int,
        val nextOffset: Int,
        val hasMore: Boolean,
    )

    private data class CommentConfiguration(
        val key: Long?,
        val subject: Long?,
        val episodes: List<LocalCommentEpisode>,
        val initialMode: BangumiCommentMode?,
        val preferredEpisodeId: Long?,
        val bangumiEpisodeOffset: Long = 0L,
    )

    private companion object {
        const val INITIAL_SNAPSHOT_CAPACITY = 16
        const val MAX_REVIEW_SNAPSHOT_SUBJECTS = 32
    }
}

@Composable
fun rememberBangumiCommentUiState(provider: BangumiCommentProviderContract): BangumiCommentUiState {
    val scope = rememberCoroutineScope()
    val state = remember(provider) { BangumiCommentUiState(provider, scope) }
    DisposableEffect(state) {
        onDispose { state.deactivate() }
    }
    return state
}

@Composable
fun BangumiCommentAutoLoadEffect(
    state: BangumiCommentUiState,
    listState: LazyListState,
    enabled: Boolean = true,
) {
    // 委托通用版, 按模式提供 hasMore/error/onLoadMore——保持滚动触发语义唯一实现。
    // EPISODE 模式的"加载更多"是本地分批展示(showMoreEpisodeComments), 无网络请求;
    // 其 episodeError 门与 REVIEWS 一致, 加载失败时 episodeComments 为空、episodeHasMore 恒 false, 行为等价。
    // restartKey 保留原实现的"身份切换重启监听"语义: 切季/切模式/切集且新旧 hasMore 布尔值相同时,
    // distinctUntilChanged 会吞掉重算, 需要按身份强制重启以重置触发状态。
    BangumiAutoLoadMoreEffect(
        listState = listState,
        enabled = enabled,
        hasMore = when (state.mode) {
            BangumiCommentMode.REVIEWS -> state.reviewHasMore
            BangumiCommentMode.EPISODE -> state.episodeHasMore
        },
        error = when (state.mode) {
            BangumiCommentMode.REVIEWS -> state.reviewError
            BangumiCommentMode.EPISODE -> state.episodeError
        },
        onLoadMore = {
            when (state.mode) {
                BangumiCommentMode.REVIEWS -> state.loadMoreReviews()
                BangumiCommentMode.EPISODE -> state.showMoreEpisodeComments()
            }
        },
        restartKey = "${state.mode.name}:${state.subjectId}:${state.selectedLocalEpisodeId}",
    )
}

/**
 * 通用分页自动加载 effect(评论/吐槽箱/讨论版共用): 滚动接近列表末尾、有更多数据且无错误时触发 [onLoadMore]。
 * 加载门控(loading/去重)由状态类内部保证; [restartKey] 变化时强制重启监听(重置 distinctUntilChanged 状态)。
 */
@Composable
fun BangumiAutoLoadMoreEffect(
    listState: LazyListState,
    enabled: Boolean = true,
    hasMore: Boolean,
    error: String? = null,
    onLoadMore: () -> Unit,
    restartKey: Any? = null,
) {
    LaunchedEffect(listState, enabled, hasMore, error, restartKey) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            shouldAutoLoadComments(lastVisibleIndex, layoutInfo.totalItemsCount) && hasMore && error == null
        }.distinctUntilChanged().collect { shouldLoad -> if (shouldLoad) onLoadMore() }
    }
}

/** 详情页评论 Tab 内容: 只承载长评列表(单集评价在播放器"本集评论"面板, 不在此处)。 */
fun LazyListScope.bangumiCommentItems(
    state: BangumiCommentUiState,
    onOpenBangumiLink: () -> Unit,
    onOpenReview: (io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiReview) -> Unit,
    resolving: Boolean = false,
    showEpisodePicker: Boolean = false,
    sourceLabel: String = "Bangumi 官方",
    emojiBaseUrl: String = OFFICIAL_BANGUMI_ENDPOINTS.imageBaseUrl,
    allowedImageHosts: Set<String> = OFFICIAL_BANGUMI_ENDPOINTS.allowedAvatarHosts,
) {
    item(key = "bangumi-comment-toolbar") {
        BangumiCommentToolbar(state, sourceLabel)
    }
    if (resolving) {
        item(key = "bangumi-comment-resolving") { CommentLoadingRow() }
        return
    }
    if (state.subjectId == null) {
        item(key = "bangumi-comment-no-link") {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "尚未建立 Bangumi 关联",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text("建立季度关联后即可读取长评和单集评论。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpenBangumiLink) { Text("去建立关联") }
            }
        }
        return
    }
    if (showEpisodePicker) {
        item(key = "bangumi-comment-mode") {
            BangumiCommentModeRow(state)
        }
    }
    if (state.mode == BangumiCommentMode.REVIEWS) {
        if (state.reviewLoading && state.reviews.isEmpty()) {
            item(key = "bangumi-review-loading") { CommentLoadingRow() }
        } else if (state.reviewError != null && state.reviews.isEmpty()) {
            item(key = "bangumi-review-error") { CommentErrorRow(state.reviewError!!, state::refresh) }
        } else if (state.reviews.isEmpty() && !state.reviewLoading) {
            item(key = "bangumi-review-empty") { CommentEmptyRow("本条目暂无长评") }
        } else {
            items(state.reviews, key = { "review-${it.id}" }, contentType = { "bangumi-review-row" }) { review ->
                BangumiReviewRow(review, emojiBaseUrl, allowedImageHosts) { onOpenReview(review) }
            }
            item(key = "bangumi-review-more") {
                when {
                    state.reviewError != null -> CommentErrorRow(state.reviewError!!, state::loadMoreReviews)
                    state.reviewLoading -> CommentLoadingRow()
                    !state.reviewHasMore -> {
                        Text("已显示全部 ${state.reviewTotal} 条长评", modifier = Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    } else {
        bangumiEpisodeCommentItems(state, showPicker = showEpisodePicker, emojiBaseUrl = emojiBaseUrl, allowedImageHosts = allowedImageHosts)
    }
}

@Composable
private fun BangumiCommentModeRow(state: BangumiCommentUiState) {
    if (state.localEpisodes.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.mode == BangumiCommentMode.REVIEWS,
            onClick = { state.selectMode(BangumiCommentMode.REVIEWS) },
            label = { Text("条目长评") },
        )
        FilterChip(
            selected = state.mode == BangumiCommentMode.EPISODE,
            onClick = { state.selectMode(BangumiCommentMode.EPISODE) },
            label = { Text("单集评论") },
        )
    }
}

@Composable
private fun BangumiCommentToolbar(
    state: BangumiCommentUiState,
    sourceLabel: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                if (state.mode == BangumiCommentMode.EPISODE) "Bangumi 单集评论" else "Bangumi 长评",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text("数据源：$sourceLabel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = state::refresh) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = if (state.mode == BangumiCommentMode.EPISODE) "刷新单集评论" else "刷新长评",
            )
        }
    }
}

@Composable
private fun BangumiEpisodePicker(state: BangumiCommentUiState) {
    var expanded by remember(state.activeEpisodeKey()) { mutableStateOf(false) }
    val selected = state.localEpisodes.firstOrNull { it.id == state.selectedLocalEpisodeId }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = true }, enabled = state.episodeRefs.isNotEmpty()) {
            Text(selected?.let { "E${it.number} ${it.title.orEmpty()}" } ?: "选择集数")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.localEpisodes.forEach { local ->
                val mapping = state.mappingFor(local)
                val mapped = mapping is BangumiEpisodeMapping.Mapped
                DropdownMenuItem(
                    text = { Text("E${local.number} ${local.title.orEmpty()}".trim()) },
                    enabled = mapped,
                    onClick = { expanded = false; state.selectEpisode(local.id) },
                )
            }
        }
        val mapping = selected?.let(state::mappingFor)
        Text(
            text = when (mapping) {
                is BangumiEpisodeMapping.Mapped -> "已映射 Bangumi E${mapping.episode.sort.toLong()}"
                BangumiEpisodeMapping.Conflict -> "映射冲突，请检查季度 offset"
                BangumiEpisodeMapping.InvalidLocalEpisode -> "本地集号不可用"
                BangumiEpisodeMapping.NotFound -> "暂无对应 Bangumi 集"
                null -> "请选择剧集"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(top = 12.dp),
        )
    }
    HorizontalDivider()
}

/** 长评列表行: 标题 + 作者/时间/回复数 + 摘要两行, 点击进入详情弹窗。 */
@Composable
private fun BangumiReviewRow(
    review: io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiReview,
    emojiBaseUrl: String,
    allowedImageHosts: Set<String>,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BangumiAvatar(review.author, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                review.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    review.author.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    relativeBangumiTime(review.createdAtSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (review.replyCount > 0) {
                    Text(
                        "${review.replyCount} 回复",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            BangumiRichTextText(
                review.summary,
                "review-${review.id}",
                small = true,
                emojiBaseUrl = emojiBaseUrl,
                allowedImageHosts = allowedImageHosts,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun BangumiEpisodeCommentRow(
    thread: BangumiEpisodeCommentThread,
    emojiBaseUrl: String,
    allowedImageHosts: Set<String>,
) {
    var repliesExpanded by remember(thread.id) { mutableStateOf(false) }
    val visibleReplies = if (repliesExpanded) thread.replies else thread.replies.take(COLLAPSED_REPLY_COUNT)
    CommentRowShell(author = thread.author, time = relativeBangumiTime(thread.createdAtSeconds), trailing = thread.reactionCount.takeIf { it > 0 }?.let { "赞 $it" }) {
        BangumiRichTextText(
            thread.content,
            "episode-${thread.id}",
            emojiBaseUrl = emojiBaseUrl,
            allowedImageHosts = allowedImageHosts,
        )
        if (visibleReplies.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visibleReplies.forEach { reply ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BangumiAvatar(reply.author, 28.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    reply.author.displayName,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(relativeBangumiTime(reply.createdAtSeconds), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            reply.replyToAuthorName?.let {
                                Text("回复 @$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            BangumiRichTextText(
                                reply.content,
                                "reply-${reply.id}",
                                small = true,
                                emojiBaseUrl = emojiBaseUrl,
                                allowedImageHosts = allowedImageHosts,
                            )
                        }
                    }
                }
                if (thread.replies.size > COLLAPSED_REPLY_COUNT) {
                    TextButton(onClick = { repliesExpanded = !repliesExpanded }) {
                        Text(if (repliesExpanded) "收起回复" else "展开 ${thread.replies.size - COLLAPSED_REPLY_COUNT} 条回复")
                    }
                }
            }
        }
    }
}

@Composable
internal fun CommentRowShell(author: BangumiCommentAuthor, time: String, trailing: String?, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BangumiAvatar(author, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    author.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                trailing?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            }
            content()
        }
    }
    HorizontalDivider()
}

@Composable
internal fun BangumiAvatar(author: BangumiCommentAuthor, size: androidx.compose.ui.unit.Dp) {
    val avatarUrl = author.avatarUrl
    val bytes by androidx.compose.runtime.produceState<ByteArray?>(initialValue = null, avatarUrl) {
        value = avatarUrl?.let { runSuspendCatching { BangumiAvatarRepository.load(it) }.getOrNull() }
    }
    val platformContext = LocalPlatformContext.current
    val imageRequest = remember(bytes, platformContext) {
        bytes?.let {
            ImageRequest.Builder(platformContext)
                .data(it)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
        }
    }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            author.displayName.firstOrNull()?.toString() ?: "?",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = "${author.displayName}的头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun LazyListScope.bangumiEpisodeCommentItems(
    state: BangumiCommentUiState,
    showPicker: Boolean,
    emojiBaseUrl: String,
    allowedImageHosts: Set<String>,
) {
    if (showPicker) item(key = "bangumi-episode-picker") { BangumiEpisodePicker(state) }
    when {
        state.episodeIndexLoading -> item(key = "bangumi-episode-index-loading") { CommentLoadingRow() }
        state.episodeError != null && state.episodeRefs.isEmpty() -> item(key = "bangumi-episode-index-error") { CommentErrorRow(state.episodeError!!, state::refresh) }
        state.episodeRefs.isEmpty() -> item(key = "bangumi-episode-index-empty") { CommentEmptyRow("Bangumi 没有可用的集数索引") }
        state.episodeComments.isEmpty() && state.episodeLoading -> item(key = "bangumi-episode-loading") { CommentLoadingRow() }
        state.episodeError != null -> item(key = "bangumi-episode-error") { CommentErrorRow(state.episodeError!!, state::refresh) }
        state.episodeComments.isEmpty() -> item(key = "bangumi-episode-empty") { CommentEmptyRow("这一集暂时没有评论") }
        else -> {
            items(state.visibleEpisodeComments, key = { "episode-comment-${it.id}" }, contentType = { "bangumi-episode-comment" }) { thread ->
                BangumiEpisodeCommentRow(thread, emojiBaseUrl, allowedImageHosts)
            }
            item(key = "bangumi-episode-more") {
                if (!state.episodeHasMore) {
                    Text(
                        "已显示全部 ${state.episodeComments.size} 条评论",
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BangumiEpisodeCommentPanel(
    state: BangumiCommentUiState,
    configured: Boolean,
    listState: LazyListState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    emojiBaseUrl: String = "https://lain.bgm.tv",
    allowedImageHosts: Set<String> = setOf("lain.bgm.tv"),
) {
    BangumiCommentAutoLoadEffect(state, listState, enabled = configured && expanded)
    var pullRefreshRequested by remember { mutableStateOf(false) }
    val pullRefreshLoading = state.episodeIndexLoading || state.episodeLoading
    LaunchedEffect(pullRefreshRequested, pullRefreshLoading) {
        if (pullRefreshRequested && !pullRefreshLoading) pullRefreshRequested = false
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "本集评论",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (configured && state.episodeComments.isNotEmpty()) {
                    Text(
                        "${state.episodeComments.size} 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起本集评论" else "展开本集评论",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            if (expanded) {
                if (!configured) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                } else {
                    PullToRefreshBox(
                        isRefreshing = pullRefreshRequested && pullRefreshLoading,
                        onRefresh = {
                            if (state.subjectId != null && !pullRefreshLoading) {
                                pullRefreshRequested = true
                                state.refresh()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            if (state.subjectId == null) {
                                item(key = "episode-comments-no-link") {
                                    CommentEmptyRow("当前季度尚未建立 Bangumi 关联，请返回番剧详情页完成关联。")
                                }
                            } else {
                                bangumiEpisodeCommentItems(
                                    state,
                                    showPicker = false,
                                    emojiBaseUrl = emojiBaseUrl,
                                    allowedImageHosts = allowedImageHosts,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun relativeBangumiTime(seconds: Long): String {
    if (seconds <= 0) return "时间未知"
    val delta = (platformTimeMillis() / 1000 - seconds).coerceAtLeast(0)
    return when {
        delta < 60 -> "刚刚"
        delta < 3600 -> "${delta / 60} 分钟前"
        delta < 86_400 -> "${delta / 3600} 小时前"
        delta < 259_200 -> "${delta / 86_400} 天前"
        else -> formatLogDate(seconds * 1000)  // 超过 3 天显示实际日期(本地时区 yyyy-MM-dd)
    }
}

@Composable
internal fun CommentLoadingRow() {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
}

@Composable
internal fun CommentErrorRow(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = retry) { Text("重试") }
    }
}

@Composable
internal fun CommentEmptyRow(message: String) {
    Text(message, Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun BangumiCommentUiState.activeEpisodeKey(): Long? = selectedLocalEpisodeId

internal fun shouldAutoLoadComments(lastVisibleIndex: Int, totalItemsCount: Int): Boolean =
    lastVisibleIndex >= 0 &&
        totalItemsCount > 0 &&
        lastVisibleIndex >= totalItemsCount - COMMENT_AUTO_LOAD_THRESHOLD

private const val COLLAPSED_REPLY_COUNT = 3
private const val COMMENT_AUTO_LOAD_THRESHOLD = 4
internal const val EPISODE_COMMENT_BATCH_SIZE = 20
