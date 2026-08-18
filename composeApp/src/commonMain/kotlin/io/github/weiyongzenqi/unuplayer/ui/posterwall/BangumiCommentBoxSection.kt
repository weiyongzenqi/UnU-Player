package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.weiyongzenqi.unuplayer.bangumi.OFFICIAL_BANGUMI_ENDPOINTS
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentProviderContract
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiSeasonComment
import io.github.weiyongzenqi.unuplayer.bangumi.comment.COMMENT_SEASON_PAGE_SIZE
import io.github.weiyongzenqi.unuplayer.bangumi.comment.bangumiCollectTypeLabel
import io.github.weiyongzenqi.unuplayer.bangumi.comment.mergeSeasonCommentPages
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 吐槽箱状态(按 subjectId 快照 + 竞态 token, 骨架照 BangumiCommentUiState)。
 * 本类只有 subject 一个配置项: subject 变化即"配置变"(取消在飞请求 + 回填快照/清空 + active 时加载),
 * 配置不变则按 active 激活/停用。
 */
@Stable
class BangumiCommentBoxUiState internal constructor(
    private val provider: BangumiCommentProviderContract,
    private val scope: CoroutineScope,
) {
    var subjectId by mutableStateOf<Long?>(null)
        private set
    var comments by mutableStateOf<List<BangumiSeasonComment>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    private var nextOffset = 0
    private var activeJob: Job? = null
    private var activeRequestToken = 0L
    private var configuration: Long? = null
    // 有界快照: 切走再切回恢复分页, 超上限淘汰最久未访问(与评论/讨论版快照同款防内存累积)
    private val snapshots = object : LinkedHashMap<Long, BoxSnapshot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, BoxSnapshot>?): Boolean =
            size > MAX_SNAPSHOT_SUBJECTS
    }

    private data class BoxSnapshot(
        val comments: List<BangumiSeasonComment>,
        val total: Int,
        val nextOffset: Int,
        val hasMore: Boolean,
    )

    private companion object {
        const val MAX_SNAPSHOT_SUBJECTS = 32
    }

    fun configure(subject: Long?, active: Boolean) {
        if (configuration != subject) {
            cancelActiveLoad()
            configuration = subject
            subjectId = subject
            val snapshot = subject?.let(snapshots::get)
            comments = snapshot?.comments.orEmpty()
            total = snapshot?.total ?: 0
            hasMore = snapshot?.hasMore == true
            loaded = snapshot != null
            nextOffset = snapshot?.nextOffset ?: 0
            error = null
            loading = false
            if (active) activate()
        } else {
            if (active) activate() else deactivate()
        }
    }

    fun deactivate() {
        cancelActiveLoad()
    }

    fun refresh() {
        cancelActiveLoad()
        loadPage(refresh = true, reset = true)
    }

    fun loadMore() {
        if (!loading && hasMore) loadPage(refresh = false, reset = false)
    }

    private fun activate() {
        if (subjectId != null && !loaded && error == null) {
            loadPage(refresh = false, reset = true)
        }
    }

    private fun loadPage(refresh: Boolean, reset: Boolean) {
        val subject = subjectId ?: return
        if (loading) return
        val offset = if (reset) 0 else nextOffset
        loading = true
        error = null
        val requestToken = ++activeRequestToken
        activeJob = scope.launch {
            try {
                val result = runSuspendCatching {
                    provider.getSeasonComments(subject, COMMENT_SEASON_PAGE_SIZE, offset, refresh)
                }
                if (activeRequestToken != requestToken) return@launch
                result.onSuccess { page ->
                    val merged = if (reset) page.comments else mergeSeasonCommentPages(comments, page)
                    comments = merged
                    total = page.total
                    hasMore = page.hasMore
                    nextOffset = page.nextOffset
                    snapshots[subject] = BoxSnapshot(
                        comments = merged,
                        total = page.total,
                        nextOffset = page.nextOffset,
                        hasMore = page.hasMore,
                    )
                    loaded = true
                }.onFailure { throwable ->
                    error = throwable.message?.take(120) ?: "加载吐槽失败"
                }
            } finally {
                if (activeRequestToken == requestToken) {
                    loading = false
                    activeJob = null
                }
            }
        }
    }

    private fun cancelActiveLoad() {
        activeRequestToken++
        activeJob?.cancel()
        activeJob = null
        loading = false
    }
}

@Composable
fun rememberBangumiCommentBoxUiState(provider: BangumiCommentProviderContract): BangumiCommentBoxUiState {
    val scope = rememberCoroutineScope()
    val state = remember(provider) { BangumiCommentBoxUiState(provider, scope) }
    DisposableEffect(state) {
        onDispose { state.deactivate() }
    }
    return state
}

fun LazyListScope.bangumiCommentBoxItems(
    state: BangumiCommentBoxUiState,
    onOpenBangumiLink: () -> Unit,
    resolving: Boolean = false,
    sourceLabel: String = "Bangumi 官方",
    emojiBaseUrl: String = OFFICIAL_BANGUMI_ENDPOINTS.imageBaseUrl,
    allowedImageHosts: Set<String> = OFFICIAL_BANGUMI_ENDPOINTS.allowedAvatarHosts,
) {
    item(key = "bangumi-comment-box-toolbar") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Bangumi 吐槽箱",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "数据源：$sourceLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = state::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "刷新吐槽") }
        }
    }
    if (resolving) {
        item(key = "bangumi-comment-box-resolving") { CommentLoadingRow() }
        return
    }
    if (state.subjectId == null) {
        item(key = "bangumi-comment-box-no-link") {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "尚未建立 Bangumi 关联",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text("建立季度关联后即可读取本季吐槽。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpenBangumiLink) { Text("去建立关联") }
            }
        }
        return
    }
    if (state.loading && state.comments.isEmpty()) {
        item(key = "bangumi-comment-box-loading") { CommentLoadingRow() }
    } else if (state.error != null && state.comments.isEmpty()) {
        item(key = "bangumi-comment-box-error") { CommentErrorRow(state.error!!, state::refresh) }
    } else if (state.comments.isEmpty() && !state.loading) {
        item(key = "bangumi-comment-box-empty") { CommentEmptyRow("本季暂时没有吐槽") }
    } else {
        items(state.comments, key = { "comment-box-${it.id}" }, contentType = { "bangumi-comment-box" }) { comment ->
            BangumiCommentBoxRow(comment, emojiBaseUrl, allowedImageHosts)
        }
        item(key = "bangumi-comment-box-more") {
            when {
                state.error != null -> CommentErrorRow(state.error!!, state::loadMore)
                state.loading -> CommentLoadingRow()
                !state.hasMore -> {
                    Text(
                        "已显示全部 ${state.total} 条吐槽",
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BangumiCommentBoxRow(
    comment: BangumiSeasonComment,
    emojiBaseUrl: String,
    allowedImageHosts: Set<String>,
) {
    CommentRowShell(author = comment.author, time = relativeBangumiTime(comment.updatedAtSeconds), trailing = null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (comment.rating != null) {
                BangumiStarRating(comment.rating)
                Text("${comment.rating}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("未评分", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            bangumiCollectTypeLabel(comment.collectType)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bangumiCollectTypeColor(comment.collectType),
                )
            }
        }
        BangumiRichTextText(
            comment.content,
            "comment-box-${comment.id}",
            emojiBaseUrl = emojiBaseUrl,
            allowedImageHosts = allowedImageHosts,
        )
    }
}

/** 五角星评分(1..10 分映到 0.5 星粒度): 满星/半星/空星。 */
@Composable
internal fun BangumiStarRating(rate: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        for (i in 0 until 5) {
            val icon = when {
                rate >= (i + 1) * 2 -> Icons.Filled.Star
                rate >= i * 2 + 1 -> Icons.AutoMirrored.Filled.StarHalf
                else -> Icons.Outlined.StarBorder
            }
            Icon(icon, contentDescription = null, tint = Color(0xFFFFA000), modifier = Modifier.size(14.dp))
        }
    }
}

/** 收藏类型配色(纯函数、固定色, 便于测试; 不依赖 MaterialTheme)。 */
internal fun bangumiCollectTypeColor(type: Int?): Color = when (type) {
    1 -> Color(0xFF2196F3)  // 想看-蓝
    2 -> Color(0xFF4CAF50)  // 看过-绿
    3 -> Color(0xFFFF9800)  // 在看-橙
    4 -> Color(0xFF9E9E9E)  // 搁置-灰
    5 -> Color(0xFFF44336)  // 抛弃-红
    else -> Color(0xFF9E9E9E)
}
