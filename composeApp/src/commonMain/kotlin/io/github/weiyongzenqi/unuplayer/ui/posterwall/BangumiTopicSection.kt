package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BANGUMI_TOPIC_PAGE_SIZE
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentProviderContract
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiTopic
import io.github.weiyongzenqi.unuplayer.bangumi.comment.mergeTopicPages
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 讨论版列表状态(按 subjectId 快照 + 竞态 token, 骨架照 BangumiCommentUiState)。
 * 配置项只有 subject: subject 变化即"配置变"(取消在飞请求 + 回填快照/清空 + active 时加载),
 * 配置不变则按 active 激活/停用。
 */
@Stable
class BangumiTopicUiState internal constructor(
    private val provider: BangumiCommentProviderContract,
    private val scope: CoroutineScope,
) {
    var subjectId by mutableStateOf<Long?>(null)
        private set
    var topics by mutableStateOf<List<BangumiTopic>>(emptyList())
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
    // 有界快照: 切走再切回恢复分页, 超上限淘汰最久未访问(与评论/吐槽快照同款防长会话内存累积)
    private val snapshots = object : LinkedHashMap<Long, TopicSnapshot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TopicSnapshot>?): Boolean =
            size > MAX_SNAPSHOT_SUBJECTS
    }

    private data class TopicSnapshot(
        val topics: List<BangumiTopic>,
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
            topics = snapshot?.topics.orEmpty()
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
                    provider.getSubjectTopics(subject, BANGUMI_TOPIC_PAGE_SIZE, offset, refresh)
                }
                if (activeRequestToken != requestToken) return@launch
                result.onSuccess { page ->
                    val merged = if (reset) page.topics else mergeTopicPages(topics, page)
                    topics = merged
                    total = page.total
                    hasMore = page.hasMore
                    nextOffset = page.nextOffset
                    snapshots[subject] = TopicSnapshot(
                        topics = merged,
                        total = page.total,
                        nextOffset = page.nextOffset,
                        hasMore = page.hasMore,
                    )
                    loaded = true
                }.onFailure { throwable ->
                    error = throwable.message?.take(120) ?: "加载讨论失败"
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
fun rememberBangumiTopicUiState(provider: BangumiCommentProviderContract): BangumiTopicUiState {
    val scope = rememberCoroutineScope()
    val state = remember(provider) { BangumiTopicUiState(provider, scope) }
    DisposableEffect(state) {
        onDispose { state.deactivate() }
    }
    return state
}

fun LazyListScope.bangumiTopicItems(
    state: BangumiTopicUiState,
    onOpenBangumiLink: () -> Unit,
    onOpenTopic: (BangumiTopic) -> Unit,
    resolving: Boolean = false,
    sourceLabel: String = "Bangumi 官方",
) {
    item(key = "bangumi-topic-toolbar") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Bangumi 讨论版",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "数据源：$sourceLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = state::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "刷新讨论") }
        }
    }
    if (resolving) {
        item(key = "bangumi-topic-resolving") { CommentLoadingRow() }
        return
    }
    if (state.subjectId == null) {
        item(key = "bangumi-topic-no-link") {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "尚未建立 Bangumi 关联",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text("建立季度关联后即可查看讨论版。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpenBangumiLink) { Text("去建立关联") }
            }
        }
        return
    }
    if (state.loading && state.topics.isEmpty()) {
        item(key = "bangumi-topic-loading") { CommentLoadingRow() }
    } else if (state.error != null && state.topics.isEmpty()) {
        item(key = "bangumi-topic-error") { CommentErrorRow(state.error!!, state::refresh) }
    } else if (state.topics.isEmpty() && !state.loading) {
        item(key = "bangumi-topic-empty") { CommentEmptyRow("本条目暂无讨论") }
    } else {
        items(state.topics, key = { "topic-${it.id}" }, contentType = { "bangumi-topic-row" }) { topic ->
            BangumiTopicRow(topic) { onOpenTopic(topic) }
        }
        item(key = "bangumi-topic-more") {
            when {
                state.error != null -> CommentErrorRow(state.error!!, state::loadMore)
                state.loading -> CommentLoadingRow()
                !state.hasMore -> {
                    Text(
                        "已显示全部 ${state.total} 条讨论",
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BangumiTopicRow(topic: BangumiTopic, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                topic.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    topic.author.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    relativeBangumiTime(topic.updatedAtSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${topic.replyCount} 回复",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    HorizontalDivider()
}
