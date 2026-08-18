package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentAuthor
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentProviderContract
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiEpisodeCommentReply
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiReview
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiTopic
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiTopicReplyNode
import io.github.weiyongzenqi.unuplayer.bangumi.comment.buildBangumiTopicReplyTree
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching

/** 帖类详情加载状态: 加载中 / 失败(可重试) / 就绪(主楼 + 已拍平回帖)。 */
private sealed interface BangumiPostDetailState {
    data object Loading : BangumiPostDetailState
    data class Error(val message: String) : BangumiPostDetailState
    data class Ready(val mainReply: BangumiEpisodeCommentReply, val replies: List<BangumiEpisodeCommentReply>) : BangumiPostDetailState
}

/** 讨论帖与长评详情的公共载荷: 主楼 + 已拍平回帖(模型同构, 树由弹窗统一构建)。 */
private data class BangumiPostDetail(
    val mainReply: BangumiEpisodeCommentReply,
    val replies: List<BangumiEpisodeCommentReply>,
)

/**
 * 讨论帖详情弹窗: 顶部标题/元信息 + 主楼 + 回帖树(按楼中楼关系嵌套缩进, 每层可展开/折叠)。
 * 详情经 [provider.getTopicDetail] 按需加载, 弹窗关闭时 produceState 随 Dialog dispose 自动取消在途请求。
 */
@Composable
fun BangumiTopicDialog(
    topic: BangumiTopic,
    provider: BangumiCommentProviderContract,
    emojiBaseUrl: String,
    allowedImageHosts: Set<String>,
    sourceLabel: String,
    onDismiss: () -> Unit,
) {
    BangumiPostDetailDialogShell(
        postId = topic.id,
        title = topic.title,
        author = topic.author,
        timeSeconds = topic.updatedAtSeconds,
        replyCount = topic.replyCount,
        sourceLabel = sourceLabel,
        loadDetail = {
            val detail = provider.getTopicDetail(topic.id)
            BangumiPostDetail(detail.mainReply, detail.replies)
        },
        errorText = "加载讨论失败",
        emojiBaseUrl = emojiBaseUrl,
        allowedImageHosts = allowedImageHosts,
        onDismiss = onDismiss,
    )
}

/** 长评详情弹窗: 主楼为长评正文(blog), 回帖树与讨论帖同构; 详情按 blogId 按需加载。 */
@Composable
fun BangumiReviewDialog(
    review: BangumiReview,
    provider: BangumiCommentProviderContract,
    emojiBaseUrl: String,
    allowedImageHosts: Set<String>,
    sourceLabel: String,
    onDismiss: () -> Unit,
) {
    BangumiPostDetailDialogShell(
        postId = review.blogId,
        title = review.title,
        author = review.author,
        timeSeconds = review.createdAtSeconds,
        replyCount = review.replyCount,
        sourceLabel = sourceLabel,
        loadDetail = {
            val detail = provider.getReviewDetail(review.blogId)
            BangumiPostDetail(detail.mainReply, detail.replies)
        },
        errorText = "加载长评失败",
        emojiBaseUrl = emojiBaseUrl,
        allowedImageHosts = allowedImageHosts,
        onDismiss = onDismiss,
    )
}

/**
 * 帖类详情弹窗公共外壳: 头部(头像/标题/作者/时间/回复数/数据源) + 主楼 + 回帖树。
 * [loadDetail] 在 produceState 内按需执行。produceState 以 [postId](帖子身份) + [title]
 * 作 key: 标题是同名帖可复用的展示串, 真正强制重启加载的是身份键——同标题不同帖(长评
 * 标题雷同极常见)换目标时正文不会残留上一帖的缓存。
 */
@Composable
private fun BangumiPostDetailDialogShell(
    postId: Long,
    title: String,
    author: BangumiCommentAuthor,
    timeSeconds: Long,
    replyCount: Int,
    sourceLabel: String,
    loadDetail: suspend () -> BangumiPostDetail,
    errorText: String,
    emojiBaseUrl: String,
    allowedImageHosts: Set<String>,
    onDismiss: () -> Unit,
) {
    var retryToken by remember { mutableIntStateOf(0) }
    val detailState by produceState<BangumiPostDetailState>(
        initialValue = BangumiPostDetailState.Loading,
        postId,
        title,
        retryToken,
    ) {
        value = BangumiPostDetailState.Loading
        value = runSuspendCatching { loadDetail() }
            .fold(
                onSuccess = { BangumiPostDetailState.Ready(it.mainReply, it.replies) },
                onFailure = { BangumiPostDetailState.Error(it.message?.take(120) ?: errorText) },
            )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .widthIn(max = 720.dp)
                .heightIn(min = 360.dp, max = 640.dp),
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BangumiAvatar(author, 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                author.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                relativeBangumiTime(timeSeconds),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (replyCount > 0) {
                                Text(
                                    "$replyCount 回复",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                sourceLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
                when (val current = detailState) {
                    BangumiPostDetailState.Loading -> Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                    is BangumiPostDetailState.Error -> Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CommentErrorRow(current.message, retry = { retryToken++ })
                    }
                    is BangumiPostDetailState.Ready -> {
                        val mainReply = current.mainReply
                        val tree = remember(mainReply, current.replies) {
                            buildBangumiTopicReplyTree(current.replies, mainReply.id)
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            item(key = "post-main-${mainReply.id}") {
                                // CommentRowShell 自带 padding 与行尾分隔线, 与列表行样式一致
                                CommentRowShell(
                                    author = mainReply.author,
                                    time = relativeBangumiTime(mainReply.createdAtSeconds),
                                    trailing = "楼主",
                                ) {
                                    BangumiRichTextText(
                                        mainReply.content,
                                        "post-main-${mainReply.id}",
                                        emojiBaseUrl = emojiBaseUrl,
                                        allowedImageHosts = allowedImageHosts,
                                    )
                                }
                            }
                            items(tree, key = { "post-node-${it.reply.id}" }, contentType = { "post-reply-node" }) { node ->
                                TopicReplyTreeNode(
                                    node = node,
                                    depth = 0,
                                    emojiBaseUrl = emojiBaseUrl,
                                    allowedImageHosts = allowedImageHosts,
                                )
                            }
                            item(key = "post-end") {
                                Text(
                                    "共 ${current.replies.size} 条回帖",
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 回帖树节点: 顶层默认展开, 深层默认折叠; 每层缩进 16dp。
 * 递归深度已由 [buildBangumiTopicReplyTree](maxDepth=4) 在构建期封顶(第 4 层后代拍平为叶子), UI 递归天然无越界。
 */
@Composable
private fun TopicReplyTreeNode(
    node: BangumiTopicReplyNode,
    depth: Int,
    emojiBaseUrl: String,
    allowedImageHosts: Set<String>,
) {
    var expanded by remember(node.reply.id) { mutableStateOf(depth == 0) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BangumiAvatar(node.reply.author, 28.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        node.reply.author.displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        relativeBangumiTime(node.reply.createdAtSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                node.reply.replyToAuthorName?.let {
                    Text("回复 @$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                BangumiRichTextText(
                    node.reply.content,
                    "topic-reply-${node.reply.id}",
                    small = true,
                    emojiBaseUrl = emojiBaseUrl,
                    allowedImageHosts = allowedImageHosts,
                )
            }
        }
        if (node.children.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(start = 36.dp)) {
                Text(
                    if (expanded) "收起回复" else "展开 ${node.children.size} 条回复",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (expanded) {
            node.children.forEach { child ->
                TopicReplyTreeNode(child, depth + 1, emojiBaseUrl, allowedImageHosts)
            }
        }
    }
}
