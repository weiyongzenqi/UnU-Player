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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentProviderContract
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentAuthor
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiAvatarRepository
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiEpisodeCommentThread
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiEpisodeMapping
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiEpisodeRef
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiRichText
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiRichTextNode
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiTextStyle
import io.github.weiyongzenqi.unuplayer.bangumi.comment.COMMENT_SEASON_PAGE_SIZE
import io.github.weiyongzenqi.unuplayer.bangumi.comment.mapBangumiEpisode
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class LocalCommentEpisode(val id: Long, val number: Long, val title: String?)

enum class BangumiCommentMode { SEASON, EPISODE }

@Stable
class BangumiCommentUiState internal constructor(
    private val provider: BangumiCommentProviderContract,
    private val scope: CoroutineScope,
) {
    var mode by mutableStateOf(BangumiCommentMode.SEASON)
        private set
    var subjectId by mutableStateOf<Long?>(null)
        private set
    var localEpisodes by mutableStateOf<List<LocalCommentEpisode>>(emptyList())
        private set
    var bangumiOffset by mutableStateOf(0L)
        private set
    var seasonComments by mutableStateOf<List<io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiSeasonComment>>(emptyList())
        private set
    var seasonTotal by mutableStateOf(0)
        private set
    var seasonLoading by mutableStateOf(false)
        private set
    var seasonError by mutableStateOf<String?>(null)
        private set
    var seasonHasMore by mutableStateOf(false)
        private set
    private var seasonNextOffset = 0
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
    private val seasonSnapshots = mutableMapOf<Long, SeasonCommentSnapshot>()
    private val selectedEpisodes = mutableMapOf<Long, Long>()

    fun configure(
        key: Long,
        subject: Long?,
        episodes: List<LocalCommentEpisode>,
        offset: Long,
        active: Boolean,
        preloadSeasonFirstPage: Boolean = false,
        initialMode: BangumiCommentMode? = null,
        preferredEpisodeId: Long? = null,
    ) {
        val nextConfiguration = CommentConfiguration(
            key = key.takeIf { it > 0 },
            subject = subject,
            episodes = episodes,
            offset = offset,
            initialMode = initialMode,
            preferredEpisodeId = preferredEpisodeId,
        )
        if (configuration == nextConfiguration) {
            when {
                active -> activate()
                preloadSeasonFirstPage -> preloadSeasonPage()
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
        bangumiOffset = offset
        selectedLocalEpisodeId = preferredEpisodeId ?: activeKey?.let(selectedEpisodes::get)
        val snapshot = subject?.let(seasonSnapshots::get)
        seasonComments = snapshot?.comments.orEmpty()
        seasonTotal = snapshot?.total ?: 0
        seasonHasMore = snapshot?.hasMore == true
        seasonNextOffset = snapshot?.nextOffset ?: 0
        seasonError = null
        seasonLoading = false
        episodeRefs = emptyList()
        episodeComments = emptyList()
        loadedEpisodeCommentId = null
        episodeVisibleCount = 0
        episodeError = null
        episodeIndexLoading = false
        episodeLoading = false
        if (active) activate() else if (preloadSeasonFirstPage) preloadSeasonPage()
    }

    fun deactivate() {
        cancelActiveLoad()
    }

    private fun cancelActiveLoad() {
        activeRequestToken++
        activeJob?.cancel()
        activeJob = null
        seasonLoading = false
        episodeIndexLoading = false
        episodeLoading = false
    }

    fun selectMode(next: BangumiCommentMode) {
        if (mode == next) return
        cancelActiveLoad()
        mode = next
        if (next == BangumiCommentMode.SEASON) loadSeasonPage(refresh = false, reset = seasonComments.isEmpty())
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
            BangumiCommentMode.SEASON -> loadSeasonPage(refresh = true, reset = true)
            BangumiCommentMode.EPISODE -> {
                if (episodeRefs.isEmpty() || selectedLocalEpisodeId == null) loadEpisodeIndex(refresh = true)
                else loadEpisodeComments(refresh = true)
            }
        }
    }

    fun loadMoreSeason() {
        if (!seasonLoading && seasonHasMore) loadSeasonPage(refresh = false, reset = false)
    }

    fun showMoreEpisodeComments() {
        episodeVisibleCount = (episodeVisibleCount + EPISODE_COMMENT_BATCH_SIZE)
            .coerceAtMost(episodeComments.size)
    }

    private fun activate() {
        if (subjectId == null) return
        if (mode == BangumiCommentMode.SEASON && seasonComments.isEmpty() && seasonError == null) {
            loadSeasonPage(refresh = false, reset = true)
        } else if (mode == BangumiCommentMode.EPISODE && episodeError == null) {
            if (episodeRefs.isEmpty()) {
                loadEpisodeIndex()
            } else {
                val remoteId = selectedRemoteEpisodeId()
                if (remoteId != null && loadedEpisodeCommentId != remoteId) loadEpisodeComments()
            }
        }
    }

    private fun preloadSeasonPage() {
        if (
            mode == BangumiCommentMode.SEASON &&
            subjectId != null &&
            seasonComments.isEmpty() &&
            seasonError == null
        ) {
            loadSeasonPage(refresh = false, reset = true)
        }
    }

    private fun loadSeasonPage(refresh: Boolean, reset: Boolean) {
        val subject = subjectId ?: return
        if (seasonLoading) return
        val offset = if (reset) 0 else seasonNextOffset
        seasonLoading = true
        seasonError = null
        val requestToken = ++activeRequestToken
        activeJob = scope.launch {
            try {
                val result = runSuspendCatching {
                    provider.getSeasonComments(subject, COMMENT_SEASON_PAGE_SIZE, offset, refresh)
                }
                if (activeRequestToken != requestToken) return@launch
                result.onSuccess { page ->
                    val merged = if (reset) page.comments else mergeComments(seasonComments, page.comments)
                    seasonComments = merged
                    seasonTotal = page.total
                    seasonHasMore = page.hasMore
                    seasonNextOffset = page.nextOffset
                    seasonSnapshots[subject] = SeasonCommentSnapshot(
                        comments = merged,
                        total = page.total,
                        nextOffset = page.nextOffset,
                        hasMore = page.hasMore,
                    )
                }.onFailure { throwable ->
                    seasonError = throwable.message?.take(120) ?: "加载评论失败"
                }
            } finally {
                if (activeRequestToken == requestToken) {
                    seasonLoading = false
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
        val mapping = mapBangumiEpisode(local.number, bangumiOffset, episodeRefs)
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

    private fun mergeComments(
        current: List<io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiSeasonComment>,
        next: List<io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiSeasonComment>,
    ) = buildList {
        val seen = mutableSetOf<Long>()
        (current + next).forEach { if (seen.add(it.id)) add(it) }
    }

    fun mappingFor(local: LocalCommentEpisode): BangumiEpisodeMapping =
        mapBangumiEpisode(local.number, bangumiOffset, episodeRefs)

    private data class SeasonCommentSnapshot(
        val comments: List<io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiSeasonComment>,
        val total: Int,
        val nextOffset: Int,
        val hasMore: Boolean,
    )

    private data class CommentConfiguration(
        val key: Long?,
        val subject: Long?,
        val episodes: List<LocalCommentEpisode>,
        val offset: Long,
        val initialMode: BangumiCommentMode?,
        val preferredEpisodeId: Long?,
    )
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
    LaunchedEffect(state, listState, enabled, state.mode, state.subjectId, state.selectedLocalEpisodeId) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearEnd = shouldAutoLoadComments(lastVisibleIndex, layoutInfo.totalItemsCount)
            nearEnd && when (state.mode) {
                BangumiCommentMode.SEASON -> state.seasonHasMore && state.seasonError == null
                BangumiCommentMode.EPISODE -> state.episodeHasMore
            }
        }.distinctUntilChanged().collect { shouldLoad ->
            if (!shouldLoad) return@collect
            when (state.mode) {
                BangumiCommentMode.SEASON -> state.loadMoreSeason()
                BangumiCommentMode.EPISODE -> state.showMoreEpisodeComments()
            }
        }
    }
}

fun LazyListScope.bangumiCommentItems(
    state: BangumiCommentUiState,
    onOpenBangumiLink: () -> Unit,
    showEpisodeMode: Boolean = true,
    sourceLabel: String = "Bangumi 官方",
) {
    item(key = "bangumi-comment-toolbar") {
        BangumiCommentToolbar(state, showEpisodeMode, sourceLabel)
    }
    if (state.subjectId == null) {
        item(key = "bangumi-comment-no-link") {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "尚未建立 Bangumi 关联",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text("建立季度关联后即可读取本季和单集评论。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpenBangumiLink) { Text("去建立关联") }
            }
        }
        return
    }
    if (state.mode == BangumiCommentMode.SEASON) {
        if (state.seasonLoading && state.seasonComments.isEmpty()) {
            item(key = "bangumi-season-loading") { CommentLoadingRow() }
        } else if (state.seasonError != null && state.seasonComments.isEmpty()) {
            item(key = "bangumi-season-error") { CommentErrorRow(state.seasonError!!, state::refresh) }
        } else if (state.seasonComments.isEmpty() && !state.seasonLoading) {
            item(key = "bangumi-season-empty") { CommentEmptyRow("本季暂时没有评论") }
        } else {
            items(state.seasonComments, key = { "season-comment-${it.id}" }) { comment ->
                BangumiSeasonCommentRow(comment)
            }
            item(key = "bangumi-season-more") {
                when {
                    state.seasonError != null -> CommentErrorRow(state.seasonError!!, state::loadMoreSeason)
                    state.seasonLoading -> CommentLoadingRow()
                    !state.seasonHasMore -> {
                        Text("已显示全部 ${state.seasonTotal} 条评论", modifier = Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    } else {
        bangumiEpisodeCommentItems(state, showPicker = showEpisodeMode)
    }
}

@Composable
private fun BangumiCommentToolbar(
    state: BangumiCommentUiState,
    showEpisodeMode: Boolean,
    sourceLabel: String,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    if (showEpisodeMode) "Bangumi 评论" else "本季评论",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text("数据源：$sourceLabel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = state::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "刷新评论") }
        }
        if (showEpisodeMode) PrimaryTabRow(selectedTabIndex = state.mode.ordinal) {
            Tab(
                selected = state.mode == BangumiCommentMode.SEASON,
                onClick = { state.selectMode(BangumiCommentMode.SEASON) },
                text = { Text(if (state.seasonTotal > 0) "本季 · ${state.seasonTotal}" else "本季") },
            )
            Tab(
                selected = state.mode == BangumiCommentMode.EPISODE,
                onClick = { state.selectMode(BangumiCommentMode.EPISODE) },
                text = { Text("单集") },
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

@Composable
private fun BangumiSeasonCommentRow(comment: io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiSeasonComment) {
    CommentRowShell(author = comment.author, time = relativeBangumiTime(comment.updatedAtSeconds), trailing = comment.rating?.let { "评分 $it" }) {
        BangumiRichTextText(comment.content, "season-${comment.id}")
    }
}

@Composable
private fun BangumiEpisodeCommentRow(thread: BangumiEpisodeCommentThread) {
    var repliesExpanded by remember(thread.id) { mutableStateOf(false) }
    val visibleReplies = if (repliesExpanded) thread.replies else thread.replies.take(COLLAPSED_REPLY_COUNT)
    CommentRowShell(author = thread.author, time = relativeBangumiTime(thread.createdAtSeconds), trailing = thread.reactionCount.takeIf { it > 0 }?.let { "赞 $it" }) {
        BangumiRichTextText(thread.content, "episode-${thread.id}")
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
                            BangumiRichTextText(reply.content, "reply-${reply.id}", small = true)
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
private fun CommentRowShell(author: BangumiCommentAuthor, time: String, trailing: String?, content: @Composable () -> Unit) {
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
private fun BangumiAvatar(author: BangumiCommentAuthor, size: androidx.compose.ui.unit.Dp) {
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
            items(state.visibleEpisodeComments, key = { "episode-comment-${it.id}" }) { thread ->
                BangumiEpisodeCommentRow(thread)
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
                                bangumiEpisodeCommentItems(state, showPicker = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BangumiRichTextText(richText: BangumiRichText, key: String, small: Boolean = false) {
    var revealSpoiler by remember(key) { mutableStateOf(false) }
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val text = remember(richText, revealSpoiler, onSurfaceVariant, surfaceVariant) {
        richText.toAnnotatedString(revealSpoiler, onSurfaceVariant, surfaceVariant)
    }
    Text(
        text = text,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = if (richText.hasSpoiler) Modifier.clickable { revealSpoiler = !revealSpoiler } else Modifier,
    )
}

private fun BangumiRichText.toAnnotatedString(
    revealSpoiler: Boolean,
    onSurfaceVariant: Color,
    surfaceVariant: Color,
): AnnotatedString = buildAnnotatedString {
    fun appendNodes(nodes: List<BangumiRichTextNode>) {
        nodes.forEach { node ->
            when (node) {
                is BangumiRichTextNode.Text -> append(node.value)
                is BangumiRichTextNode.Emoji -> append("(${node.code})")
                is BangumiRichTextNode.ImagePlaceholder -> append(
                    if (node.url != null) "[远程图片，未自动加载]" else "[无效图片]",
                )
                is BangumiRichTextNode.Link -> withStyle(SpanStyle(color = Color(0xFF1976D2), textDecoration = TextDecoration.Underline)) { appendNodes(node.children) }
                is BangumiRichTextNode.Styled -> {
                    if (node.style == BangumiTextStyle.SPOILER && !revealSpoiler) append("[点击显示剧透]")
                    else {
                        val style = when (node.style) {
                            BangumiTextStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                            BangumiTextStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                            BangumiTextStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                            BangumiTextStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                            BangumiTextStyle.QUOTE -> SpanStyle(color = onSurfaceVariant)
                            BangumiTextStyle.COLOR -> node.value?.let(::safeTextColor)?.let { SpanStyle(color = it) } ?: SpanStyle()
                            BangumiTextStyle.SIZE -> node.value?.toIntOrNull()?.takeIf { it in 8..48 }?.let { SpanStyle(fontSize = it.sp) } ?: SpanStyle()
                            BangumiTextStyle.SPOILER -> SpanStyle(background = surfaceVariant)
                        }
                        if (node.style == BangumiTextStyle.QUOTE) append("> ")
                        withStyle(style) { appendNodes(node.children) }
                    }
                }
            }
        }
    }
    appendNodes(nodes)
}

private fun safeTextColor(value: String): Color? = when (value.lowercase()) {
    "black" -> Color.Black
    "white" -> Color.White
    "red" -> Color.Red
    "green" -> Color.Green
    "blue" -> Color.Blue
    "gray" -> Color.Gray
    "yellow" -> Color.Yellow
    else -> value.takeIf {
        it.length in 7..9 && it.first() == '#' && it.drop(1).all { char -> char.isDigit() || char.lowercaseChar() in 'a'..'f' }
    }?.let { Color(it.removePrefix("#").toLong(16) or if (it.length == 7) 0xFF000000 else 0L) }
}

private fun relativeBangumiTime(seconds: Long): String {
    if (seconds <= 0) return "时间未知"
    val delta = (platformTimeMillis() / 1000 - seconds).coerceAtLeast(0)
    return when {
        delta < 60 -> "刚刚"
        delta < 3600 -> "${delta / 60} 分钟前"
        delta < 86_400 -> "${delta / 3600} 小时前"
        delta < 2_592_000 -> "${delta / 86_400} 天前"
        else -> "较早前"
    }
}

@Composable
private fun CommentLoadingRow() {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
}

@Composable
private fun CommentErrorRow(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = retry) { Text("重试") }
    }
}

@Composable
private fun CommentEmptyRow(message: String) {
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
