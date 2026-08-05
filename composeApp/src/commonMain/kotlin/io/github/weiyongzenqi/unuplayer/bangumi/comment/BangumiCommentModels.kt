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

private const val BANGUMI_REGULAR_EPISODE_TYPE = 0
