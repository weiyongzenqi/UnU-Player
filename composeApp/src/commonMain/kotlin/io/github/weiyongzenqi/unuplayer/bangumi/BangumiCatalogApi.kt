package io.github.weiyongzenqi.unuplayer.bangumi

import io.github.weiyongzenqi.unuplayer.core.network.APP_USER_AGENT
import io.github.weiyongzenqi.unuplayer.webdav.createStrictHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface BangumiCatalog {
    suspend fun search(keyword: String, limit: Int = 20): List<BangumiCandidate>
    suspend fun getSubject(subjectId: Long): BangumiCandidate?
}

class BangumiCatalogApi(
    private val httpClient: HttpClient = createStrictHttpClient(),
    private val baseUrl: String = "https://api.bgm.tv",
    /** GATEWAY 预设注入: 非 null 时搜索/条目走网关中性路由(/q /s)。 */
    private val gateway: BangumiGatewayEndpoint? = null,
) : BangumiCatalog {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    override suspend fun search(keyword: String, limit: Int): List<BangumiCandidate> {
        val normalized = keyword.trim().take(MAX_KEYWORD_LENGTH)
        if (normalized.isEmpty()) return emptyList()
        val body = gateway?.searchSubjects(normalized, limit.coerceIn(1, MAX_SEARCH_LIMIT))
            ?: executeJson(
                path = "/v0/search/subjects",
                method = HttpMethod.Post,
                parameters = mapOf("limit" to limit.coerceIn(1, MAX_SEARCH_LIMIT).toString(), "offset" to "0"),
                requestBody = json.encodeToString(BangumiSearchRequest(keyword = normalized, sort = "match")),
            ) ?: return emptyList()
        return json.decodeFromString(BangumiSearchResponse.serializer(), body).data
            .asSequence()
            .filter { it.type == BANGUMI_ANIME_TYPE }
            .map { it.toCandidate(BangumiCandidateSource.TITLE_SEARCH) }
            .toList()
    }

    override suspend fun getSubject(subjectId: Long): BangumiCandidate? {
        if (subjectId <= 0) return null
        val body = gateway?.subject(subjectId, allowNotFound = true)
            ?: executeJson(path = "/v0/subjects/$subjectId", allowNotFound = true) ?: return null
        val subject = json.decodeFromString(BangumiSubjectDto.serializer(), body)
        return subject.takeIf { it.type == BANGUMI_ANIME_TYPE }
            ?.toCandidate(BangumiCandidateSource.ID_LOOKUP)
    }

    private suspend fun executeJson(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        parameters: Map<String, String> = emptyMap(),
        requestBody: String? = null,
        allowNotFound: Boolean = false,
    ): String? = httpClient.prepareRequest(baseUrl + path) {
        this.method = method
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        header(HttpHeaders.UserAgent, USER_AGENT)
        parameters.forEach { (name, value) -> parameter(name, value) }
        requestBody?.let {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(it)
        }
    }.execute { response ->
        if (allowNotFound && response.status.value == 404) {
            response.bodyAsChannel().cancel(null)
            return@execute null
        }
        if (!response.status.isSuccess()) {
            response.bodyAsChannel().cancel(null)
            throw BangumiApiException("Bangumi HTTP ${response.status.value}")
        }
        readLimitedJson(response.bodyAsChannel())
    }

    private fun BangumiSubjectDto.toCandidate(source: BangumiCandidateSource): BangumiCandidate =
        BangumiCandidate(
            subjectId = id,
            title = name_cn.takeIf { it.isNotBlank() } ?: name,
            originalTitle = name.takeIf { it.isNotBlank() && it != name_cn },
            date = date,
            type = type,
            episodeCount = total_episodes ?: eps,
            sources = setOf(source),
        )

    private companion object {
        const val USER_AGENT = APP_USER_AGENT
        const val MAX_SEARCH_LIMIT = 20
        const val MAX_KEYWORD_LENGTH = 120
        const val BANGUMI_ANIME_TYPE = 2
    }
}

open internal class BangumiApiException(message: String) : Exception(message)

@Serializable
private data class BangumiSearchRequest(
    val keyword: String,
    val sort: String,
)

@Serializable
private data class BangumiSearchResponse(
    val data: List<BangumiSubjectDto> = emptyList(),
)

@Serializable
private data class BangumiSubjectDto(
    val id: Long,
    val type: Int,
    val name: String = "",
    val name_cn: String = "",
    val date: String? = null,
    val eps: Int? = null,
    val total_episodes: Int? = null,
)

internal suspend fun readLimitedJson(
    channel: ByteReadChannel,
    limit: Int = DEFAULT_JSON_LIMIT_BYTES,
): String = try {
    require(limit in 1 until Int.MAX_VALUE)
    var bytes = ByteArray(INITIAL_JSON_BUFFER_BYTES.coerceAtMost(limit + 1))
    var total = 0
    while (true) {
        if (total == bytes.size) {
            val expanded = (bytes.size * 2).coerceAtMost(limit + 1)
            if (expanded == bytes.size) break
            bytes = bytes.copyOf(expanded)
        }
        val read = channel.readAvailable(bytes, total, bytes.size - total)
        if (read <= 0) break
        total += read
    }
    if (total > limit) throw BangumiApiException("Bangumi 响应超过大小上限")
    bytes.copyOf(total).decodeToString()
} finally {
    channel.cancel(null)
}

private const val DEFAULT_JSON_LIMIT_BYTES = 1024 * 1024
private const val INITIAL_JSON_BUFFER_BYTES = 32 * 1024
