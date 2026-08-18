package io.github.weiyongzenqi.unuplayer.bangumi.comment

import io.github.weiyongzenqi.unuplayer.bangumi.OFFICIAL_BANGUMI_ENDPOINTS
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface BangumiCommentProviderContract {
    suspend fun getSeasonComments(
        subjectId: Long,
        limit: Int = COMMENT_SEASON_PAGE_SIZE,
        offset: Int = 0,
        refresh: Boolean = false,
    ): BangumiCommentPage

    suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean = false): List<BangumiEpisodeRef>
    suspend fun getEpisodeComments(episodeId: Long, refresh: Boolean = false): List<BangumiEpisodeCommentThread>

    suspend fun getSubjectTopics(
        subjectId: Long,
        limit: Int = BANGUMI_TOPIC_PAGE_SIZE,
        offset: Int = 0,
        refresh: Boolean = false,
    ): BangumiTopicPage

    suspend fun getTopicDetail(topicId: Long, refresh: Boolean = false): BangumiTopicDetail

    suspend fun getSubjectReviews(
        subjectId: Long,
        limit: Int = BANGUMI_REVIEW_PAGE_SIZE,
        offset: Int = 0,
        refresh: Boolean = false,
    ): BangumiReviewPage

    suspend fun getReviewDetail(blogId: Long, refresh: Boolean = false): BangumiReviewDetail
    suspend fun clear()
}

class BangumiCommentProvider(
    private val api: BangumiCommentTransport,
    private val isEnabled: () -> Boolean = { true },
    private val allowedAvatarHosts: Set<String> = setOf("lain.bgm.tv"),
    private val imageBaseUrl: String = OFFICIAL_BANGUMI_ENDPOINTS.imageBaseUrl,
    cacheTtlMillis: Long = DEFAULT_COMMENT_CACHE_TTL_MILLIS,
    cacheMaxEntries: Int = DEFAULT_COMMENT_CACHE_MAX_ENTRIES,
    nowMillis: () -> Long = ::platformTimeMillis,
) : BangumiCommentProviderContract {
    // 缓存预算再切分(总预算不变): 默认 64 槽 = 季评论 16 / 集数索引 8 / 单集评论 12 / 主题列表 6 / 主题详情 6 / 长评列表 8 / 长评详情 8
    private val seasonCacheEntries = (cacheMaxEntries / 4).coerceAtLeast(1)
    private val episodeIndexCacheEntries = (cacheMaxEntries / 8).coerceAtLeast(1)
    private val episodeCommentCacheEntries = (cacheMaxEntries * 3 / 16).coerceAtLeast(1)
    private val topicListCacheEntries = (cacheMaxEntries * 3 / 32).coerceAtLeast(1)
    private val topicDetailCacheEntries = (cacheMaxEntries * 3 / 32).coerceAtLeast(1)
    private val reviewListCacheEntries = (cacheMaxEntries / 8).coerceAtLeast(1)
    private val reviewDetailCacheEntries = (cacheMaxEntries / 8).coerceAtLeast(1)
    private val seasonCache = CommentMemoryCache<SeasonPageKey, BangumiCommentPage>(
        cacheTtlMillis,
        seasonCacheEntries,
        nowMillis,
    )
    private val episodeIndexCache = CommentMemoryCache<Long, List<BangumiEpisodeRef>>(
        cacheTtlMillis,
        episodeIndexCacheEntries,
        nowMillis,
    )
    private val episodeCommentsCache = CommentMemoryCache<Long, List<BangumiEpisodeCommentThread>>(
        cacheTtlMillis,
        episodeCommentCacheEntries,
        nowMillis,
    )
    private val topicListCache = CommentMemoryCache<TopicPageKey, BangumiTopicPage>(
        cacheTtlMillis,
        topicListCacheEntries,
        nowMillis,
    )
    private val topicDetailCache = CommentMemoryCache<Long, BangumiTopicDetail>(
        cacheTtlMillis,
        topicDetailCacheEntries,
        nowMillis,
    )
    private val reviewListCache = CommentMemoryCache<ReviewPageKey, BangumiReviewPage>(
        cacheTtlMillis,
        reviewListCacheEntries,
        nowMillis,
    )
    private val reviewDetailCache = CommentMemoryCache<Long, BangumiReviewDetail>(
        cacheTtlMillis,
        reviewDetailCacheEntries,
        nowMillis,
    )

    init {
        require(cacheMaxEntries >= 3)
    }

    override suspend fun getSeasonComments(subjectId: Long, limit: Int, offset: Int, refresh: Boolean): BangumiCommentPage {
        ensureEnabled()
        val safeLimit = limit.coerceIn(1, COMMENT_SEASON_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        return seasonCache.getOrLoad(SeasonPageKey(subjectId, safeOffset, safeLimit), refresh) {
            ensureEnabled()
            // D-P1-1: 网络读 body + JSON decode + BBcode 解析全部切出主线程(调用方为 rememberCoroutineScope 主线程协程)。
            withContext(Dispatchers.Default) {
                val response = api.getSeasonComments(subjectId, safeLimit, safeOffset)
                BangumiCommentPage(
                    comments = response.data.mapNotNull { dto ->
                        if (dto.id <= 0) null else BangumiSeasonComment(
                            id = dto.id,
                            author = (dto.user ?: BangumiUserDto()).toAuthor(dto.user?.id ?: 0, allowedAvatarHosts),
                            rating = dto.rate?.takeIf { it in 1..10 },
                            updatedAtSeconds = dto.updatedAt,
                            content = BangumiBbCodeParser.parse(dto.comment, imageBaseUrl),
                            collectType = dto.type?.takeIf { it in 1..5 },
                        )
                    }.distinctBy { it.id },
                    total = response.total.coerceAtLeast(0),
                    offset = safeOffset,
                    limit = safeLimit,
                )
            }
        }
    }

    override suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean): List<BangumiEpisodeRef> {
        ensureEnabled()
        return episodeIndexCache.getOrLoad(subjectId, refresh) {
            ensureEnabled()
            withContext(Dispatchers.Default) {
                val result = mutableListOf<BangumiEpisodeRef>()
                var offset = 0
                var total = Int.MAX_VALUE
                var pages = 0
                while (offset < total && pages++ < MAX_EPISODE_PAGES) {
                    val page = api.getEpisodes(subjectId, MAX_EPISODE_PAGE_SIZE, offset)
                    if (page.data.size > MAX_EPISODE_PAGE_SIZE) {
                        throw BangumiCommentContractException("Bangumi 集数索引单页超过上限")
                    }
                    if (page.data.any { it.subject_id != subjectId }) {
                        throw BangumiCommentContractException("Bangumi 集数索引 subject 不一致")
                    }
                    total = page.total.coerceAtLeast(0)
                    val mapped = page.data.mapNotNull { dto -> dto.takeIf { it.id > 0 }?.toRef() }
                    result += mapped
                    if (page.data.isEmpty() || offset + page.data.size >= total) break
                    offset += page.data.size
                }
                result.distinctBy { it.id }
            }
        }
    }

    override suspend fun getEpisodeComments(episodeId: Long, refresh: Boolean): List<BangumiEpisodeCommentThread> {
        ensureEnabled()
        return episodeCommentsCache.getOrLoad(episodeId, refresh) {
            ensureEnabled()
            withContext(Dispatchers.Default) {
                val response = api.getEpisodeComments(episodeId)
                if (response.any { thread ->
                        thread.mainID != episodeId || thread.replies.any { it.mainID != episodeId }
                    }
                ) {
                    throw BangumiCommentContractException("Bangumi 单集评论或回复 mainID 不一致")
                }
                response.mapNotNull { dto ->
                    if (dto.id <= 0) null else {
                        val threadAuthor = mapReplyAuthor(dto.user, dto.creatorID)
                        val replyDtos = dto.replies.filter { it.id > 0 }.distinctBy { it.id }
                        val replyAuthors = replyDtos.associate { reply ->
                            reply.id to mapReplyAuthor(reply.user, reply.creatorID)
                        }
                        val authorsByCommentId = buildMap {
                            put(dto.id, threadAuthor.displayName)
                            replyAuthors.forEach { (commentId, author) -> put(commentId, author.displayName) }
                        }
                        val replies = replyDtos.map { reply ->
                            val author = replyAuthors.getValue(reply.id)
                            BangumiEpisodeCommentReply(
                                id = reply.id,
                                author = author,
                                createdAtSeconds = reply.createdAt,
                                content = BangumiBbCodeParser.parse(reply.content, imageBaseUrl),
                                relatedCommentId = reply.relatedID,
                                replyToAuthorName = authorsByCommentId[reply.relatedID]
                                    ?.takeIf { reply.relatedID != dto.id },
                            )
                        }
                        BangumiEpisodeCommentThread(
                            id = dto.id,
                            author = threadAuthor,
                            createdAtSeconds = dto.createdAt,
                            content = BangumiBbCodeParser.parse(dto.content, imageBaseUrl),
                            replies = replies,
                            reactionCount = dto.reactions.sumOf { it.users.size.toLong() }
                                .coerceAtMost(Int.MAX_VALUE.toLong())
                                .toInt(),
                        )
                    }
                }.distinctBy { it.id }
            }
        }
    }

    override suspend fun getSubjectTopics(subjectId: Long, limit: Int, offset: Int, refresh: Boolean): BangumiTopicPage {
        ensureEnabled()
        val safeLimit = limit.coerceIn(1, BANGUMI_TOPIC_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        return topicListCache.getOrLoad(TopicPageKey(subjectId, safeOffset, safeLimit), refresh) {
            ensureEnabled()
            withContext(Dispatchers.Default) {
                val response = api.getSubjectTopics(subjectId, safeLimit, safeOffset)
                BangumiTopicPage(
                    topics = response.data.filter { dto ->
                        dto.id > 0 && dto.title.isNotBlank() && dto.state == 0 && dto.display == 1
                    }.distinctBy { it.id }.map { dto ->
                        BangumiTopic(
                            id = dto.id,
                            title = dto.title.take(MAX_TOPIC_TITLE_LENGTH),
                            author = (dto.creator ?: BangumiUserDto(id = dto.creatorID))
                                .toAuthor(dto.creatorID, allowedAvatarHosts),
                            replyCount = dto.replyCount.coerceAtLeast(0),
                            createdAtSeconds = dto.createdAt,
                            updatedAtSeconds = dto.updatedAt,
                        )
                    },
                    total = response.total.coerceAtLeast(0),
                    offset = safeOffset,
                    limit = safeLimit,
                )
            }
        }
    }

    override suspend fun getTopicDetail(topicId: Long, refresh: Boolean): BangumiTopicDetail {
        ensureEnabled()
        return topicDetailCache.getOrLoad(topicId, refresh) {
            ensureEnabled()
            withContext(Dispatchers.Default) {
                val detail = api.getTopicDetail(topicId)
                if (detail.replies.isEmpty()) {
                    throw BangumiCommentContractException("Bangumi 讨论主题缺少回帖")
                }
                if (detail.id > 0 && detail.id != topicId) {
                    throw BangumiCommentContractException("Bangumi 讨论主题 ID 不一致")
                }
                val mainId = detail.replies.first().id
                // 显式栈 DFS 拍平嵌套回帖树: 保序、visited 防环, 每层记录父楼 id(根层父 = 主楼 id)
                val flattened = mutableListOf<Pair<BangumiTopicReplyDto, Long>>()
                val visited = mutableSetOf<Long>()
                val stack = ArrayDeque<Pair<BangumiTopicReplyDto, Long>>()
                detail.replies.asReversed().forEach { stack.addLast(it to mainId) }
                while (stack.isNotEmpty()) {
                    val (node, parentId) = stack.removeLast()
                    if (!visited.add(node.id)) continue
                    // 身份校验放宽: mainID 缺省 0 不校验, 只在确有值且与请求不符时才拒绝
                    if (node.mainID > 0 && node.mainID != topicId) {
                        throw BangumiCommentContractException("Bangumi 讨论主题回帖 mainID 不一致")
                    }
                    flattened += node to parentId
                    node.replies.asReversed().forEach { stack.addLast(it to node.id) }
                }
                val authorsByCommentId = flattened.map { (dto, _) ->
                    // 实测回帖作者字段名是 creator(user 为防御性兼容); 主楼自身两者都缺时回落详情级 creator。
                    val user = dto.user ?: dto.creator ?: if (dto.id == mainId) detail.creator else null
                    dto.id to mapReplyAuthor(user, dto.creatorID)
                }.toMap()
                val mapped = flattened.map { (dto, parentId) ->
                    // relatedID 指向缺失楼时回落到响应嵌套关系, 防止孤儿回帖从树上消失。
                    val relatedId = dto.relatedID
                        .takeIf { it > 0 && authorsByCommentId.containsKey(it) }
                        ?: parentId
                    BangumiEpisodeCommentReply(
                        id = dto.id,
                        author = authorsByCommentId.getValue(dto.id),
                        createdAtSeconds = dto.createdAt,
                        content = BangumiBbCodeParser.parse(dto.content, imageBaseUrl),
                        relatedCommentId = relatedId,
                        replyToAuthorName = authorsByCommentId[relatedId]?.displayName
                            ?.takeIf { relatedId != mainId },
                    )
                }
                BangumiTopicDetail(
                    topicId = topicId,
                    mainReply = mapped.first(),
                    replies = mapped.drop(1),
                )
            }
        }
    }

    override suspend fun getSubjectReviews(subjectId: Long, limit: Int, offset: Int, refresh: Boolean): BangumiReviewPage {
        ensureEnabled()
        val safeLimit = limit.coerceIn(1, BANGUMI_REVIEW_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        return reviewListCache.getOrLoad(ReviewPageKey(subjectId, safeOffset, safeLimit), refresh) {
            ensureEnabled()
            withContext(Dispatchers.Default) {
                val response = api.getSubjectReviews(subjectId, safeLimit, safeOffset)
                BangumiReviewPage(
                    reviews = response.data.mapNotNull { dto ->
                        val entry = dto.entry ?: return@mapNotNull null
                        if (dto.id <= 0 || entry.id <= 0) return@mapNotNull null
                        BangumiReview(
                            id = dto.id,
                            blogId = entry.id,
                            title = entry.title.take(MAX_REVIEW_TITLE_LENGTH),
                            // 作者: user 缺失时用空 DTO(displayName 回落「Bangumi 用户」, id=0),
                            // 不用 review id 冒充 user id(否则与列表去重键混淆, @提及/头像去重语义错)
                            author = (dto.user ?: BangumiUserDto()).toAuthor(dto.user?.id ?: 0, allowedAvatarHosts),
                            summary = BangumiBbCodeParser.parse(entry.summary, imageBaseUrl),
                            replyCount = entry.replies.coerceAtLeast(0),
                            createdAtSeconds = entry.createdAt,
                        )
                    }.distinctBy { it.id },
                    total = response.total.coerceAtLeast(0),
                    offset = safeOffset,
                    limit = safeLimit,
                )
            }
        }
    }

    override suspend fun getReviewDetail(blogId: Long, refresh: Boolean): BangumiReviewDetail {
        ensureEnabled()
        return reviewDetailCache.getOrLoad(blogId, refresh) {
            ensureEnabled()
            withContext(Dispatchers.Default) {
                val blog = api.getReviewDetail(blogId)
                if (blog.id > 0 && blog.id != blogId) {
                    throw BangumiCommentContractException("Bangumi 长评 ID 不一致")
                }
                val comments = api.getReviewComments(blogId)
                // 回帖树拍平同讨论帖(嵌套 DFS + visited 防环); 长评的"主楼 id"是 blogId,
                // 顶层回帖 relatedID=0 → 回落到父楼 blogId, 树构建时据此挂顶层
                val flattened = mutableListOf<Pair<BangumiTopicReplyDto, Long>>()
                val visited = mutableSetOf<Long>()
                val stack = ArrayDeque<Pair<BangumiTopicReplyDto, Long>>()
                comments.asReversed().forEach { stack.addLast(it to blogId) }
                while (stack.isNotEmpty()) {
                    val (node, parentId) = stack.removeLast()
                    if (!visited.add(node.id)) continue
                    if (node.mainID > 0 && node.mainID != blogId) {
                        throw BangumiCommentContractException("Bangumi 长评回帖 mainID 不一致")
                    }
                    flattened += node to parentId
                    node.replies.asReversed().forEach { stack.addLast(it to node.id) }
                }
                val authorsByCommentId = flattened.map { (dto, _) ->
                    // 实测长评回帖作者字段名是 user(creator 为防御性兼容, 与讨论帖相反)
                    dto.id to mapReplyAuthor(dto.user ?: dto.creator, dto.creatorID)
                }.toMap()
                val mappedReplies = flattened.map { (dto, parentId) ->
                    val relatedId = dto.relatedID
                        .takeIf { it > 0 && authorsByCommentId.containsKey(it) }
                        ?: parentId
                    BangumiEpisodeCommentReply(
                        id = dto.id,
                        author = authorsByCommentId.getValue(dto.id),
                        createdAtSeconds = dto.createdAt,
                        content = BangumiBbCodeParser.parse(dto.content, imageBaseUrl),
                        relatedCommentId = relatedId,
                        replyToAuthorName = authorsByCommentId[relatedId]?.displayName
                            ?.takeIf { relatedId != blogId },
                    )
                }
                BangumiReviewDetail(
                    reviewId = blogId,
                    mainReply = BangumiEpisodeCommentReply(
                        id = blogId,
                        author = (blog.user ?: BangumiUserDto(id = blog.uid))
                            .toAuthor(blog.uid, allowedAvatarHosts),
                        createdAtSeconds = blog.createdAt,
                        content = BangumiBbCodeParser.parse(blog.content, imageBaseUrl),
                    ),
                    replies = mappedReplies,
                )
            }
        }
    }

    override suspend fun clear() {
        seasonCache.clear()
        episodeIndexCache.clear()
        episodeCommentsCache.clear()
        topicListCache.clear()
        topicDetailCache.clear()
        reviewListCache.clear()
        reviewDetailCache.clear()
    }

    private fun ensureEnabled() {
        if (!isEnabled()) throw BangumiCommentsDisabledException()
    }

    /** 作者映射共享逻辑: user 缺失时回落 creatorID 构造占位用户, 再走 [BangumiUserDto.toAuthor]。 */
    private fun mapReplyAuthor(user: BangumiUserDto?, creatorId: Long): BangumiCommentAuthor =
        (user ?: BangumiUserDto(id = creatorId)).toAuthor(creatorId, allowedAvatarHosts)

    private companion object {
        const val MAX_EPISODE_PAGE_SIZE = 200
        const val MAX_EPISODE_PAGES = 32
        const val MAX_TOPIC_TITLE_LENGTH = 200
        const val MAX_REVIEW_TITLE_LENGTH = 200
    }
}

data class SeasonPageKey(val subjectId: Long, val offset: Int, val limit: Int)

data class TopicPageKey(val subjectId: Long, val offset: Int, val limit: Int)

data class ReviewPageKey(val subjectId: Long, val offset: Int, val limit: Int)

class BangumiCommentsDisabledException : CancellationException("Bangumi 评论区已被番剧识别开关禁用")

class BangumiCommentContractException(message: String) : Exception(message)

const val COMMENT_SEASON_PAGE_SIZE = 20
const val BANGUMI_TOPIC_PAGE_SIZE = 20
const val BANGUMI_REVIEW_PAGE_SIZE = 20
