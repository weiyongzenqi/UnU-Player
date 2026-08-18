package io.github.weiyongzenqi.unuplayer.bangumi.comment

data class BangumiCommentAuthor(
    val id: Long,
    val username: String,
    val nickname: String,
    val signature: String? = null,
    val avatarUrl: String? = null,
) {
    val displayName: String get() = nickname.ifBlank { username.ifBlank { "Bangumi 用户" } }
}

data class BangumiSeasonComment(
    val id: Long,
    val author: BangumiCommentAuthor,
    val rating: Int?,
    val updatedAtSeconds: Long,
    val content: BangumiRichText,
    val collectType: Int? = null,
)

data class BangumiEpisodeCommentReply(
    val id: Long,
    val author: BangumiCommentAuthor,
    val createdAtSeconds: Long,
    val content: BangumiRichText,
    val relatedCommentId: Long = 0,
    val replyToAuthorName: String? = null,
)

data class BangumiEpisodeCommentThread(
    val id: Long,
    val author: BangumiCommentAuthor,
    val createdAtSeconds: Long,
    val content: BangumiRichText,
    val replies: List<BangumiEpisodeCommentReply>,
    val reactionCount: Int,
)

data class BangumiCommentPage(
    val comments: List<BangumiSeasonComment>,
    val total: Int,
    val offset: Int,
    val limit: Int,
) {
    val hasMore: Boolean get() = comments.isNotEmpty() && offset + limit < total
    val nextOffset: Int get() = (offset + limit).coerceAtMost(total)
}

data class BangumiEpisodeRef(
    val id: Long,
    val type: Int,
    val sort: Double,
    val episodeNumber: Double?,
    val title: String,
    val commentCount: Int,
)

sealed interface BangumiEpisodeMapping {
    data class Mapped(val episode: BangumiEpisodeRef) : BangumiEpisodeMapping
    data object InvalidLocalEpisode : BangumiEpisodeMapping
    data object NotFound : BangumiEpisodeMapping
    data object Conflict : BangumiEpisodeMapping
}

fun mapBangumiEpisode(
    localEpisodeNumber: Long,
    bangumiOffset: Long,
    remoteEpisodes: List<BangumiEpisodeRef>,
): BangumiEpisodeMapping {
    if (localEpisodeNumber <= 0) return BangumiEpisodeMapping.InvalidLocalEpisode
    if (bangumiOffset < 0 && localEpisodeNumber > Long.MAX_VALUE + bangumiOffset) {
        return BangumiEpisodeMapping.NotFound
    }
    val targetSort = localEpisodeNumber - bangumiOffset
    if (targetSort <= 0) return BangumiEpisodeMapping.NotFound
    val matches = remoteEpisodes.filter { episode ->
        episode.type == BANGUMI_REGULAR_EPISODE_TYPE &&
            episode.sort.isFinite() &&
            episode.sort > 0.0 &&
            episode.sort < Long.MAX_VALUE.toDouble() &&
            episode.sort % 1.0 == 0.0 &&
            episode.sort.toLong() == targetSort
    }
    return when (matches.size) {
        0 -> BangumiEpisodeMapping.NotFound
        1 -> BangumiEpisodeMapping.Mapped(matches.single())
        else -> BangumiEpisodeMapping.Conflict
    }
}

fun mergeSeasonCommentPages(
    current: List<BangumiSeasonComment>,
    next: BangumiCommentPage,
): List<BangumiSeasonComment> = buildList(current.size + next.comments.size) {
    val seen = mutableSetOf<Long>()
    (current + next.comments).forEach { comment ->
        if (seen.add(comment.id)) add(comment)
    }
}

data class BangumiTopic(
    val id: Long,
    val title: String,
    val author: BangumiCommentAuthor,
    val replyCount: Int,
    val createdAtSeconds: Long,
    val updatedAtSeconds: Long,
)

data class BangumiTopicPage(
    val topics: List<BangumiTopic>,
    val total: Int,
    val offset: Int,
    val limit: Int,
) {
    val hasMore: Boolean get() = topics.isNotEmpty() && offset + limit < total
    val nextOffset: Int get() = (offset + limit).coerceAtMost(total)
}

data class BangumiTopicDetail(
    val topicId: Long,
    val mainReply: BangumiEpisodeCommentReply, // 主楼 = replies[0]; 复用单集回复模型(字段吻合)
    val replies: List<BangumiEpisodeCommentReply>, // 除主楼外的全部回帖, 已拍平并带 relatedCommentId
)

data class BangumiTopicReplyNode(
    val reply: BangumiEpisodeCommentReply,
    val children: List<BangumiTopicReplyNode>,
)

/** 条目长评(列表项): id 为评论 id(去重键), blogId 为日志 id(详情/回帖键)。 */
data class BangumiReview(
    val id: Long,
    val blogId: Long,
    val title: String,
    val author: BangumiCommentAuthor,
    val summary: BangumiRichText,
    val replyCount: Int,
    val createdAtSeconds: Long,
)

data class BangumiReviewPage(
    val reviews: List<BangumiReview>,
    val total: Int,
    val offset: Int,
    val limit: Int,
) {
    val hasMore: Boolean get() = reviews.isNotEmpty() && offset + limit < total
    val nextOffset: Int get() = (offset + limit).coerceAtMost(total)
}

/** 长评详情: 主楼 = blog 正文本身(回帖树不含主楼), 模型与讨论帖详情同构以复用详情弹窗。 */
data class BangumiReviewDetail(
    val reviewId: Long,
    val mainReply: BangumiEpisodeCommentReply,
    val replies: List<BangumiEpisodeCommentReply>,
)

/** 合并长评分页: 按 id 去重, 先 current 后 next 保序(与 [mergeTopicPages] 同规则)。 */
fun mergeReviewPages(
    current: List<BangumiReview>,
    next: BangumiReviewPage,
): List<BangumiReview> = buildList(current.size + next.reviews.size) {
    val seen = mutableSetOf<Long>()
    (current + next.reviews).forEach { review ->
        if (seen.add(review.id)) add(review)
    }
}

/** Bangumi 收藏类型标签; 1=想看 2=看过 3=在看 4=搁置 5=抛弃, 其余(0/6/缺省)无标签。 */
fun bangumiCollectTypeLabel(type: Int?): String? = when (type) {
    1 -> "想看"
    2 -> "看过"
    3 -> "在看"
    4 -> "搁置"
    5 -> "抛弃"
    else -> null
}

/** 合并主题分页: 按 id 去重, 先 current 后 next 保序(与 [mergeSeasonCommentPages] 同规则)。 */
fun mergeTopicPages(
    current: List<BangumiTopic>,
    next: BangumiTopicPage,
): List<BangumiTopic> = buildList(current.size + next.topics.size) {
    val seen = mutableSetOf<Long>()
    (current + next.topics).forEach { topic ->
        if (seen.add(topic.id)) add(topic)
    }
}

/**
 * 把已拍平的讨论版回帖组装成树:
 * - 顶层 = [relatedCommentId] == [mainId] 的直接回帖(保持输入顺序);
 * - 每层的 children 按 [relatedCommentId] 分组挂载(同样保序);
 * - [visited] 保证每个楼 id 只挂载一次, 天然切断环引用, 递归深度受 [maxDepth] 封顶不会爆栈;
 * - 深度达到 [maxDepth] 的节点不再递归建树, 其后代用显式栈全部拍平收纳到该节点的 children(叶子化, 不丢节点)。
 */
fun buildBangumiTopicReplyTree(
    replies: List<BangumiEpisodeCommentReply>,
    mainId: Long,
    maxDepth: Int = 4,
): List<BangumiTopicReplyNode> {
    // 按父楼 id 分组(保序) + id 索引供拍平时回查
    val byParent: Map<Long, List<BangumiEpisodeCommentReply>> =
        buildMap<Long, MutableList<BangumiEpisodeCommentReply>> {
            replies.forEach { reply ->
                getOrPut(reply.relatedCommentId) { mutableListOf() }.add(reply)
            }
        }
    val byId = replies.associateBy { it.id }
    val visited = mutableSetOf<Long>()

    // 显式栈收集某楼的全部后代(保序), 用于深度封顶时的拍平收纳
    fun collectDescendants(rootId: Long): List<BangumiEpisodeCommentReply> = buildList {
        val stack = ArrayDeque<Long>().apply {
            byParent[rootId].orEmpty().asReversed().forEach { addLast(it.id) }
        }
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!visited.add(id)) continue
            val reply = byId[id] ?: continue
            add(reply)
            byParent[id].orEmpty().asReversed().forEach { stack.addLast(it.id) }
        }
    }

    fun buildNodes(parentId: Long, depth: Int): List<BangumiTopicReplyNode> = buildList {
        byParent[parentId].orEmpty().forEach { reply ->
            if (!visited.add(reply.id)) return@forEach // 防环: 已挂载过的楼不再重复挂载
            val children = if (depth >= maxDepth) {
                // 深度封顶: 第 maxDepth 层不再递归, 后代全部拍平收纳为叶子节点
                collectDescendants(reply.id).map { descendant -> BangumiTopicReplyNode(descendant, emptyList()) }
            } else {
                buildNodes(reply.id, depth + 1)
            }
            add(BangumiTopicReplyNode(reply, children))
        }
    }

    return buildNodes(mainId, 1)
}

private const val BANGUMI_REGULAR_EPISODE_TYPE = 0
