package io.github.weiyongzenqi.unuplayer.bangumi.comment

import io.github.weiyongzenqi.unuplayer.core.network.APP_USER_AGENT
import io.github.weiyongzenqi.unuplayer.bangumi.readLimitedJson
import io.github.weiyongzenqi.unuplayer.webdav.createStrictHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import io.github.weiyongzenqi.unuplayer.bangumi.isAllowedBangumiAvatarUrl

interface BangumiCommentTransport {
    suspend fun getSeasonComments(subjectId: Long, limit: Int, offset: Int): BangumiSeasonCommentsDto
    suspend fun getEpisodes(subjectId: Long, limit: Int, offset: Int): BangumiEpisodesDto
    suspend fun getEpisodeComments(episodeId: Long): List<BangumiEpisodeCommentDto>
    suspend fun getSubjectTopics(subjectId: Long, limit: Int, offset: Int): BangumiSubjectTopicsDto
    suspend fun getTopicDetail(topicId: Long): BangumiTopicDetailDto
    suspend fun getSubjectReviews(subjectId: Long, limit: Int, offset: Int): BangumiSubjectReviewsDto
    suspend fun getReviewDetail(blogId: Long): BangumiBlogDetailDto
    suspend fun getReviewComments(blogId: Long): List<BangumiTopicReplyDto>
}

class BangumiCommentApi(
    private val httpClient: HttpClient = createStrictHttpClient(),
    private val officialBaseUrl: String = "https://api.bgm.tv",
    private val nextBaseUrl: String = "https://next.bgm.tv/p1",
    private val limits: BangumiCommentResponseLimits = BangumiCommentResponseLimits(),
    /** GATEWAY 预设注入: 非 null 时五类请求全部走网关中性路由(/c /e /ec /t /d)。 */
    private val gateway: io.github.weiyongzenqi.unuplayer.bangumi.BangumiGatewayEndpoint? = null,
) : BangumiCommentTransport {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    override suspend fun getSeasonComments(subjectId: Long, limit: Int, offset: Int): BangumiSeasonCommentsDto {
        require(subjectId > 0)
        val lim = limit.coerceIn(1, MAX_SEASON_PAGE_SIZE)
        val body = gateway?.seasonComments(subjectId, lim, offset, limits.seasonCommentsBytes)
            ?: execute(
                baseUrl = nextBaseUrl,
                path = "/subjects/$subjectId/comments",
                parameters = mapOf(
                    "limit" to lim.toString(),
                    "offset" to offset.coerceAtLeast(0).toString(),
                ),
                maxBytes = limits.seasonCommentsBytes,
            )
        return json.decodeFromString(BangumiSeasonCommentsDto.serializer(), body)
    }

    override suspend fun getEpisodes(subjectId: Long, limit: Int, offset: Int): BangumiEpisodesDto {
        require(subjectId > 0)
        val lim = limit.coerceIn(1, MAX_EPISODE_PAGE_SIZE)
        val body = gateway?.episodes(subjectId, lim, offset, limits.episodeIndexBytes, type = -1)
            ?: execute(
                baseUrl = officialBaseUrl,
                path = "/v0/episodes",
                parameters = mapOf(
                    "subject_id" to subjectId.toString(),
                    "limit" to lim.toString(),
                    "offset" to offset.coerceAtLeast(0).toString(),
                ),
                maxBytes = limits.episodeIndexBytes,
            )
        return json.decodeFromString(BangumiEpisodesDto.serializer(), body)
    }

    override suspend fun getEpisodeComments(episodeId: Long): List<BangumiEpisodeCommentDto> {
        require(episodeId > 0)
        val body = gateway?.episodeComments(episodeId, limits.episodeCommentsBytes)
            ?: execute(
                baseUrl = nextBaseUrl,
                path = "/episodes/$episodeId/comments",
                maxBytes = limits.episodeCommentsBytes,
            )
        return json.decodeFromString(ListSerializer(BangumiEpisodeCommentDto.serializer()), body)
    }

    override suspend fun getSubjectTopics(subjectId: Long, limit: Int, offset: Int): BangumiSubjectTopicsDto {
        require(subjectId > 0)
        val lim = limit.coerceIn(1, MAX_TOPIC_PAGE_SIZE)
        val body = gateway?.subjectTopics(subjectId, lim, offset, limits.subjectTopicsBytes)
            ?: execute(
                baseUrl = nextBaseUrl,
                path = "/subjects/$subjectId/topics",
                parameters = mapOf(
                    "limit" to lim.toString(),
                    "offset" to offset.coerceAtLeast(0).toString(),
                ),
                maxBytes = limits.subjectTopicsBytes,
            )
        return json.decodeFromString(BangumiSubjectTopicsDto.serializer(), body)
    }

    override suspend fun getTopicDetail(topicId: Long): BangumiTopicDetailDto {
        require(topicId > 0)
        // Next API 的 subject 位固定为字面 "-", 详情由路径尾部的 topicId 决定; 网关路由为 /d/{id}
        val body = gateway?.topicDetail(topicId, limits.topicDetailBytes)
            ?: execute(
                baseUrl = nextBaseUrl,
                path = "/subjects/-/topics/$topicId",
                maxBytes = limits.topicDetailBytes,
            )
        return json.decodeFromString(BangumiTopicDetailDto.serializer(), body)
    }

    override suspend fun getSubjectReviews(subjectId: Long, limit: Int, offset: Int): BangumiSubjectReviewsDto {
        require(subjectId > 0)
        val lim = limit.coerceIn(1, MAX_REVIEW_PAGE_SIZE)
        val body = gateway?.subjectReviews(subjectId, lim, offset, limits.reviewListBytes)
            ?: execute(
                baseUrl = nextBaseUrl,
                path = "/subjects/$subjectId/reviews",
                parameters = mapOf(
                    "limit" to lim.toString(),
                    "offset" to offset.coerceAtLeast(0).toString(),
                ),
                maxBytes = limits.reviewListBytes,
            )
        return json.decodeFromString(BangumiSubjectReviewsDto.serializer(), body)
    }

    override suspend fun getReviewDetail(blogId: Long): BangumiBlogDetailDto {
        require(blogId > 0)
        // 长评正文在 blog 本体(回帖树不含主楼); 网关路由为 /rd/{id}
        val body = gateway?.reviewDetail(blogId, limits.reviewDetailBytes)
            ?: execute(
                baseUrl = nextBaseUrl,
                path = "/blogs/$blogId",
                maxBytes = limits.reviewDetailBytes,
            )
        return json.decodeFromString(BangumiBlogDetailDto.serializer(), body)
    }

    override suspend fun getReviewComments(blogId: Long): List<BangumiTopicReplyDto> {
        require(blogId > 0)
        // 回帖树与讨论帖回帖 DTO 同构(嵌套+mainID/relatedID); 网关路由为 /rdc/{id}
        val body = gateway?.reviewComments(blogId, limits.reviewCommentsBytes)
            ?: execute(
                baseUrl = nextBaseUrl,
                path = "/blogs/$blogId/comments",
                maxBytes = limits.reviewCommentsBytes,
            )
        return json.decodeFromString(ListSerializer(BangumiTopicReplyDto.serializer()), body)
    }

    private suspend fun execute(
        baseUrl: String,
        path: String,
        parameters: Map<String, String> = emptyMap(),
        maxBytes: Int,
    ): String = httpClient.prepareGet(baseUrl.trimEnd('/') + path) {
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        header(HttpHeaders.UserAgent, USER_AGENT)
        parameters.forEach { (name, value) -> parameter(name, value) }
    }.execute { response ->
        if (!response.status.isSuccess()) {
            response.bodyAsChannel().cancel(null)
            throw BangumiCommentApiException(response.status.value)
        }
        val contentType = response.headers[HttpHeaders.ContentType].orEmpty().lowercase()
        if (contentType.isNotBlank() && !contentType.startsWith(ContentType.Application.Json.toString())) {
            response.bodyAsChannel().cancel(null)
            throw BangumiCommentApiException(response.status.value, "Bangumi 返回了非 JSON 内容")
        }
        readLimitedJson(response.bodyAsChannel(), maxBytes)
    }

    private companion object {
        const val USER_AGENT = APP_USER_AGENT
        const val MAX_SEASON_PAGE_SIZE = 50
        const val MAX_EPISODE_PAGE_SIZE = 200
        const val MAX_TOPIC_PAGE_SIZE = 50
        const val MAX_REVIEW_PAGE_SIZE = 50
    }
}

data class BangumiCommentResponseLimits(
    val seasonCommentsBytes: Int = 1024 * 1024,
    val episodeIndexBytes: Int = 1024 * 1024,
    // 单集评论接口无分页(全量数组): 实测最热门集(芙莉莲最终回 440 主楼+188 回复)约 0.56MB,
    // 4MB 为 7 倍余量, 兼顾未来霸权新番破千楼场景; 超限 fail-closed(错误行可重试)。
    val episodeCommentsBytes: Int = 4 * 1024 * 1024,
    val subjectTopicsBytes: Int = 1024 * 1024,
    val topicDetailBytes: Int = 4 * 1024 * 1024,
    // 长评(日志)正文可达数千字 bbcode, 详情与回帖树同讨论帖详情档(4MB)
    val reviewListBytes: Int = 1024 * 1024,
    val reviewDetailBytes: Int = 4 * 1024 * 1024,
    val reviewCommentsBytes: Int = 4 * 1024 * 1024,
)

class BangumiCommentApiException(
    val statusCode: Int,
    message: String = "Bangumi HTTP $statusCode",
) : Exception(message)

@Serializable
data class BangumiSeasonCommentsDto(
    val data: List<BangumiSeasonCommentDto>,
    val total: Int,
)

@Serializable
data class BangumiSeasonCommentDto(
    val id: Long,
    val comment: String,
    val user: BangumiUserDto? = null,
    val rate: Int? = null,
    val type: Int? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class BangumiEpisodesDto(
    val data: List<BangumiEpisodeDto>,
    val total: Int,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class BangumiEpisodeDto(
    val id: Long,
    val subject_id: Long,
    val type: Int,
    val ep: Double? = null,
    val sort: Double = Double.NaN,
    val name: String = "",
    val name_cn: String = "",
    val comment: Int = 0,
)

@Serializable
data class BangumiEpisodeCommentDto(
    val id: Long,
    val mainID: Long,
    val content: String,
    val creatorID: Long = 0,
    val createdAt: Long = 0,
    val replies: List<BangumiEpisodeReplyDto> = emptyList(),
    val user: BangumiUserDto? = null,
    val reactions: List<BangumiReactionDto> = emptyList(),
)

@Serializable
data class BangumiEpisodeReplyDto(
    val id: Long,
    val mainID: Long,
    val content: String,
    val relatedID: Long = 0,
    val creatorID: Long = 0,
    val createdAt: Long = 0,
    val user: BangumiUserDto? = null,
)

@Serializable
data class BangumiReactionDto(
    val value: Int = 0,
    val users: List<BangumiUserDto> = emptyList(),
)

@Serializable
data class BangumiUserDto(
    val id: Long = 0,
    val username: String = "",
    val nickname: String = "",
    val sign: String? = null,
    val avatar: BangumiAvatarDto? = null,
)

@Serializable
data class BangumiAvatarDto(
    val small: String? = null,
    val medium: String? = null,
    val large: String? = null,
)

@Serializable
data class BangumiSubjectTopicsDto(
    val data: List<BangumiSubjectTopicDto>,
    val total: Int,
)

@Serializable
data class BangumiSubjectTopicDto(
    val id: Long,
    val title: String = "",
    val creatorID: Long = 0,
    val parentID: Long = 0,
    val replyCount: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val state: Int = 0,
    val display: Int = 1,
    val creator: BangumiUserDto? = null,
)

@Serializable
data class BangumiTopicDetailDto(
    val id: Long = 0,
    val creator: BangumiUserDto? = null,
    val replies: List<BangumiTopicReplyDto> = emptyList(),
)

// 长评(条目 reviews): 列表项 = 评论 id + 作者 + entry(blog 概要, entry.id 即 blogId)
@Serializable
data class BangumiSubjectReviewsDto(
    val data: List<BangumiSubjectReviewDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class BangumiSubjectReviewDto(
    val id: Long = 0,
    val user: BangumiUserDto? = null,
    val entry: BangumiReviewEntryDto? = null,
)

@Serializable
data class BangumiReviewEntryDto(
    val id: Long = 0,
    val title: String = "",
    val summary: String = "",
    val replies: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

// 长评正文(blog 详情): 主楼即 blog 本体, 回帖树单独接口且不含主楼
@Serializable
data class BangumiBlogDetailDto(
    val id: Long = 0,
    val uid: Long = 0,
    val title: String = "",
    val content: String = "",
    val replies: Int = 0,
    val createdAt: Long = 0,
    val user: BangumiUserDto? = null,
)

@Serializable
data class BangumiTopicReplyDto(
    val id: Long,
    val content: String,
    val creatorID: Long = 0,
    val createdAt: Long = 0,
    val state: Int = 0,
    // 实测讨论帖详情响应的回帖作者字段名是 creator, user 为防御性兼容(旧响应形态)
    val user: BangumiUserDto? = null,
    val creator: BangumiUserDto? = null,
    val mainID: Long = 0,
    val relatedID: Long = 0, // 防御性: 实测响应无此两字段, 缺省 0 表示未校验/由嵌套父楼推导
    val replies: List<BangumiTopicReplyDto> = emptyList(), // 实测响应为嵌套树
)

internal fun BangumiUserDto.toAuthor(
    fallbackId: Long,
    allowedAvatarHosts: Set<String>,
): BangumiCommentAuthor = BangumiCommentAuthor(
    id = if (id > 0) id else fallbackId,
    username = username.take(MAX_AUTHOR_NAME_LENGTH),
    nickname = nickname.take(MAX_AUTHOR_NAME_LENGTH),
    signature = sign?.take(MAX_AUTHOR_SIGNATURE_LENGTH),
    avatarUrl = sequenceOf(avatar?.small, avatar?.medium, avatar?.large)
        .firstOrNull { isAllowedBangumiAvatarUrl(it, allowedAvatarHosts) },
)

internal fun BangumiEpisodeDto.toRef(): BangumiEpisodeRef = BangumiEpisodeRef(
    id = id,
    type = type,
    sort = sort,
    episodeNumber = ep,
    title = name_cn.ifBlank { name }.take(MAX_EPISODE_TITLE_LENGTH),
    commentCount = comment,
)

private const val MAX_AUTHOR_NAME_LENGTH = 80
private const val MAX_AUTHOR_SIGNATURE_LENGTH = 280
private const val MAX_EPISODE_TITLE_LENGTH = 200
