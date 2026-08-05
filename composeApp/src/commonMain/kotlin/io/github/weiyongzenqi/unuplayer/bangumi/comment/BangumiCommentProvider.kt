package io.github.weiyongzenqi.unuplayer.bangumi.comment

import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import kotlinx.coroutines.CancellationException

interface BangumiCommentProviderContract {
    suspend fun getSeasonComments(
        subjectId: Long,
        limit: Int = COMMENT_SEASON_PAGE_SIZE,
        offset: Int = 0,
        refresh: Boolean = false,
    ): BangumiCommentPage

    suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean = false): List<BangumiEpisodeRef>
    suspend fun getEpisodeComments(episodeId: Long, refresh: Boolean = false): List<BangumiEpisodeCommentThread>
    suspend fun clear()
}

class BangumiCommentProvider(
    private val api: BangumiCommentTransport,
    private val isEnabled: () -> Boolean = { true },
    private val allowedAvatarHosts: Set<String> = setOf("lain.bgm.tv"),
    cacheTtlMillis: Long = DEFAULT_COMMENT_CACHE_TTL_MILLIS,
    cacheMaxEntries: Int = DEFAULT_COMMENT_CACHE_MAX_ENTRIES,
    nowMillis: () -> Long = ::platformTimeMillis,
) : BangumiCommentProviderContract {
    private val seasonCacheEntries = (cacheMaxEntries / 2).coerceAtLeast(1)
    private val episodeIndexCacheEntries = (cacheMaxEntries / 4).coerceAtLeast(1)
    private val episodeCommentCacheEntries =
        (cacheMaxEntries - seasonCacheEntries - episodeIndexCacheEntries).coerceAtLeast(1)
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

    init {
        require(cacheMaxEntries >= 3)
    }

    override suspend fun getSeasonComments(subjectId: Long, limit: Int, offset: Int, refresh: Boolean): BangumiCommentPage {
        ensureEnabled()
        val safeLimit = limit.coerceIn(1, COMMENT_SEASON_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        return seasonCache.getOrLoad(SeasonPageKey(subjectId, safeOffset, safeLimit), refresh) {
            ensureEnabled()
            val response = api.getSeasonComments(subjectId, safeLimit, safeOffset)
            BangumiCommentPage(
                comments = response.data.mapNotNull { dto ->
                    if (dto.id <= 0) null else BangumiSeasonComment(
                        id = dto.id,
                        author = (dto.user ?: BangumiUserDto()).toAuthor(dto.user?.id ?: 0, allowedAvatarHosts),
                        rating = dto.rate?.takeIf { it in 1..10 },
                        updatedAtSeconds = dto.updatedAt,
                        content = BangumiBbCodeParser.parse(dto.comment),
                    )
                }.distinctBy { it.id },
                total = response.total.coerceAtLeast(0),
                offset = safeOffset,
                limit = safeLimit,
            )
        }
    }

    override suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean): List<BangumiEpisodeRef> {
        ensureEnabled()
        return episodeIndexCache.getOrLoad(subjectId, refresh) {
            ensureEnabled()
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

    override suspend fun getEpisodeComments(episodeId: Long, refresh: Boolean): List<BangumiEpisodeCommentThread> {
        ensureEnabled()
        return episodeCommentsCache.getOrLoad(episodeId, refresh) {
            ensureEnabled()
            val response = api.getEpisodeComments(episodeId)
            if (response.any { thread ->
                    thread.mainID != episodeId || thread.replies.any { it.mainID != episodeId }
                }
            ) {
                throw BangumiCommentContractException("Bangumi 单集评论或回复 mainID 不一致")
            }
            response.mapNotNull { dto ->
                if (dto.id <= 0) null else {
                    val threadAuthor = (dto.user ?: BangumiUserDto(id = dto.creatorID))
                        .toAuthor(dto.creatorID, allowedAvatarHosts)
                    val replyDtos = dto.replies.filter { it.id > 0 }.distinctBy { it.id }
                    val replyAuthors = replyDtos.associate { reply ->
                        reply.id to (reply.user ?: BangumiUserDto(id = reply.creatorID))
                            .toAuthor(reply.creatorID, allowedAvatarHosts)
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
                            content = BangumiBbCodeParser.parse(reply.content),
                            relatedCommentId = reply.relatedID,
                            replyToAuthorName = authorsByCommentId[reply.relatedID]
                                ?.takeIf { reply.relatedID != dto.id },
                        )
                    }
                    BangumiEpisodeCommentThread(
                        id = dto.id,
                        author = threadAuthor,
                        createdAtSeconds = dto.createdAt,
                        content = BangumiBbCodeParser.parse(dto.content),
                        replies = replies,
                        reactionCount = dto.reactions.sumOf { it.users.size.toLong() }
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt(),
                    )
                }
            }.distinctBy { it.id }
        }
    }

    override suspend fun clear() {
        seasonCache.clear()
        episodeIndexCache.clear()
        episodeCommentsCache.clear()
    }

    private fun ensureEnabled() {
        if (!isEnabled()) throw BangumiCommentsDisabledException()
    }

    private companion object {
        const val MAX_EPISODE_PAGE_SIZE = 200
        const val MAX_EPISODE_PAGES = 32
    }
}

data class SeasonPageKey(val subjectId: Long, val offset: Int, val limit: Int)

class BangumiCommentsDisabledException : CancellationException("Bangumi 评论区已被番剧识别开关禁用")

class BangumiCommentContractException(message: String) : Exception(message)

const val COMMENT_SEASON_PAGE_SIZE = 20
