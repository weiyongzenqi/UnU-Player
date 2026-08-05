package io.github.weiyongzenqi.unuplayer.bangumi.comment

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
}

class BangumiCommentApi(
    private val httpClient: HttpClient = createStrictHttpClient(),
    private val officialBaseUrl: String = "https://api.bgm.tv",
    private val nextBaseUrl: String = "https://next.bgm.tv/p1",
    private val limits: BangumiCommentResponseLimits = BangumiCommentResponseLimits(),
) : BangumiCommentTransport {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    override suspend fun getSeasonComments(subjectId: Long, limit: Int, offset: Int): BangumiSeasonCommentsDto {
        require(subjectId > 0)
        return execute(
            baseUrl = nextBaseUrl,
            path = "/subjects/$subjectId/comments",
            parameters = mapOf(
                "limit" to limit.coerceIn(1, MAX_SEASON_PAGE_SIZE).toString(),
                "offset" to offset.coerceAtLeast(0).toString(),
            ),
            maxBytes = limits.seasonCommentsBytes,
        ).let { json.decodeFromString(BangumiSeasonCommentsDto.serializer(), it) }
    }

    override suspend fun getEpisodes(subjectId: Long, limit: Int, offset: Int): BangumiEpisodesDto {
        require(subjectId > 0)
        return execute(
            baseUrl = officialBaseUrl,
            path = "/v0/episodes",
            parameters = mapOf(
                "subject_id" to subjectId.toString(),
                "limit" to limit.coerceIn(1, MAX_EPISODE_PAGE_SIZE).toString(),
                "offset" to offset.coerceAtLeast(0).toString(),
            ),
            maxBytes = limits.episodeIndexBytes,
        ).let { json.decodeFromString(BangumiEpisodesDto.serializer(), it) }
    }

    override suspend fun getEpisodeComments(episodeId: Long): List<BangumiEpisodeCommentDto> {
        require(episodeId > 0)
        val body = execute(
            baseUrl = nextBaseUrl,
            path = "/episodes/$episodeId/comments",
            maxBytes = limits.episodeCommentsBytes,
        )
        return json.decodeFromString(ListSerializer(BangumiEpisodeCommentDto.serializer()), body)
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
        const val USER_AGENT = "UnU-Player/0.1.6"
        const val MAX_SEASON_PAGE_SIZE = 50
        const val MAX_EPISODE_PAGE_SIZE = 200
    }
}

data class BangumiCommentResponseLimits(
    val seasonCommentsBytes: Int = 1024 * 1024,
    val episodeIndexBytes: Int = 1024 * 1024,
    val episodeCommentsBytes: Int = 2 * 1024 * 1024,
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
