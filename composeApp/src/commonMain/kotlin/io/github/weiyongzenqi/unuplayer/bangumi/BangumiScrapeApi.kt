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
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Bangumi 刮削专用 API(独立于 [BangumiCatalogApi], 返回刮削全字段)。
 *
 * 与 [BangumiCatalogApi] 的关系: 后者服务季度关联/评论(候选精简);
 * 本类服务在线刮削(要 images/infobox/tags/rating/episodes 全集)。两者共用一个免 key 公开读通道。
 * 端点: POST /v0/search/subjects(可按 type=2 过滤) / GET /v0/subjects/{id} / GET /v0/episodes。
 * 限流: bgm 公开读接口限流较严, 调用方(刮削管线)需控制 QPS; GATEWAY 分支的节流由
 * [BangumiGatewayEndpoint] 内建进程级请求门承担(全网关 JSON 请求共享 275ms 间隔, FP3-4),
 * 本类不再叠加(避免双闸把网关预算砍半)。
 */
class BangumiScrapeApi(
    private val httpClient: HttpClient = createStrictHttpClient(),
    private val baseUrl: String = "https://api.bgm.tv",
    /** GATEWAY 预设注入: 非 null 时搜索/条目/剧集走网关中性路由(/q /s /e)。 */
    private val gateway: BangumiGatewayEndpoint? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 按关键词搜索动画条目(type=2), 返回刮削候选(含季照/评分/标签)。 */
    suspend fun search(keyword: String, limit: Int = 10): List<BangumiScrapeSubject> {
        val normalized = keyword.trim().take(MAX_KEYWORD_LENGTH)
        if (normalized.isEmpty()) return emptyList()
        val body = gateway?.let { g -> g.searchSubjects(normalized, limit.coerceIn(1, MAX_SEARCH_LIMIT)) }
            ?: executeJson(
                path = "/v0/search/subjects",
                method = HttpMethod.Post,
                parameters = mapOf("limit" to limit.coerceIn(1, MAX_SEARCH_LIMIT).toString(), "offset" to "0"),
                requestBody = json.encodeToString(
                    BangumiScrapeSearchRequest(
                        keyword = normalized,
                        filter = BangumiScrapeSearchFilter(type = listOf(2)),
                    ),
                ),
            ) ?: return emptyList()
        return json.decodeFromString(BangumiScrapeSearchResponse.serializer(), body).data
            .filter { it.type == BANGUMI_ANIME_TYPE }
            .map { it.toSubject(BangumiSubjectSource.SEARCH) }
    }

    /** 获取条目全量(含 images/infobox/tags/rating)。404 返回 null。 */
    suspend fun getSubject(subjectId: Long): BangumiScrapeSubject? {
        if (subjectId <= 0) return null
        val body = gateway?.let { g -> g.subject(subjectId, allowNotFound = true) }
            ?: executeJson(path = "/v0/subjects/$subjectId", allowNotFound = true) ?: return null
        val dto = json.decodeFromString(BangumiScrapeSubjectDto.serializer(), body)
        return dto.takeIf { it.type == BANGUMI_ANIME_TYPE }
            ?.toSubject(BangumiSubjectSource.ID_LOOKUP)
    }

    /** 获取条目正片剧集(type=0)列表, 分页拉到全(每页 100, 上限 3 页)。 */
    suspend fun getEpisodes(subjectId: Long): List<BangumiScrapeEpisode> {
        if (subjectId <= 0) return emptyList()
        val result = mutableListOf<BangumiScrapeEpisode>()
        var offset = 0
        while (offset < MAX_EPISODE_PAGES * EPISODE_PAGE_SIZE) {
            val body = gateway?.let { g -> g.episodes(subjectId, EPISODE_PAGE_SIZE, offset) }
                ?: executeJson(
                    path = "/v0/episodes",
                    parameters = mapOf(
                        "subject_id" to subjectId.toString(),
                        "type" to "0",
                        "limit" to EPISODE_PAGE_SIZE.toString(),
                        "offset" to offset.toString(),
                    ),
                ) ?: break
            val page = json.decodeFromString(BangumiEpisodesResponse.serializer(), body).data
            if (page.isEmpty()) break
            result += page.mapNotNull { it.toEpisode() }
            if (page.size < EPISODE_PAGE_SIZE) break
            offset += EPISODE_PAGE_SIZE
        }
        return result
    }

    private suspend fun executeJson(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        parameters: Map<String, String> = emptyMap(),
        requestBody: String? = null,
        allowNotFound: Boolean = false,
    ): String? {
        requestMutex.withLock { awaitRequestSlot() }
        return httpClient.prepareRequest(baseUrl + path) {
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
                throw BangumiApiException("Bangumi scrape HTTP ${response.status.value}")
            }
            readLimitedJson(response.bodyAsChannel())
        }
    }

    private suspend fun awaitRequestSlot() {
        val now = platformTimeMillis()
        val waitMillis = (nextRequestAt - now).coerceAtLeast(0L)
        if (waitMillis > 0) delay(waitMillis)
        nextRequestAt = platformTimeMillis() + MIN_REQUEST_INTERVAL_MS
    }
    private fun BangumiScrapeSubjectDto.toSubject(source: BangumiSubjectSource): BangumiScrapeSubject =
        BangumiScrapeSubject(
            subjectId = id,
            title = name_cn.takeIf { it.isNotBlank() } ?: name,
            originalTitle = name.takeIf { it.isNotBlank() && it != name_cn },
            date = date,
            summary = summary,
            rating = rating?.score?.takeIf { it > 0.0 },
            posterUrl = images?.large?.takeIf { it.isNotBlank() } ?: images?.common,
            tags = tags.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }.distinct(),
            studios = infoboxStudioNames(infobox),
            episodeCount = total_episodes ?: eps,
            source = source,
        )

    /** infobox 提取制作方: key 含 "制作" / "动画制作" / "制作公司" 的条目; value 为字符串或 {v,k} 数组。 */
    private fun infoboxStudioNames(infobox: List<BangumiInfoboxItem>?): List<String> {
        if (infobox.isNullOrEmpty()) return emptyList()
        val names = mutableListOf<String>()
        for (item in infobox) {
            if (item.key.isBlank() || !item.key.contains("制作")) continue
            names += extractStringValues(item.value)
        }
        return names.distinct()
    }

    private fun extractStringValues(element: JsonElement?): List<String> = when {
        element == null -> emptyList()
        element is kotlinx.serialization.json.JsonPrimitive -> listOf(element.content)
        element is kotlinx.serialization.json.JsonArray -> element.mapNotNull { item ->
            when (item) {
                is kotlinx.serialization.json.JsonPrimitive -> item.content
                is kotlinx.serialization.json.JsonObject -> item["v"]?.toString()?.trim('"')
                else -> null
            }
        }.filter { it.isNotBlank() }
        else -> emptyList()
    }

    private companion object {
        val requestMutex = Mutex()
        var nextRequestAt = 0L
        const val USER_AGENT = APP_USER_AGENT
        const val MAX_SEARCH_LIMIT = 20
        const val MAX_KEYWORD_LENGTH = 120
        const val BANGUMI_ANIME_TYPE = 2
        const val EPISODE_PAGE_SIZE = 100
        const val MAX_EPISODE_PAGES = 3
        const val MIN_REQUEST_INTERVAL_MS = 250L
    }
}

/** 刮削候选来源。 */
enum class BangumiSubjectSource { SEARCH, ID_LOOKUP }

/** 刮削候选(含季照/评分/标签/制作方)。 */
data class BangumiScrapeSubject(
    val subjectId: Long,
    val title: String,
    val originalTitle: String? = null,
    val date: String? = null,
    val summary: String? = null,
    val rating: Double? = null,
    val posterUrl: String? = null,
    val tags: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val episodeCount: Int? = null,
    val source: BangumiSubjectSource = BangumiSubjectSource.SEARCH,
)

/** 刮削剧集(正片 type=0)。 */
data class BangumiScrapeEpisode(
    val sort: Double,
    val title: String?,
    val aired: String?,
    val plot: String? = null,
)

@Serializable
private data class BangumiScrapeSearchRequest(
    val keyword: String,
    val filter: BangumiScrapeSearchFilter? = null,
)

@Serializable
private data class BangumiScrapeSearchFilter(val type: List<Int> = emptyList())

@Serializable
private data class BangumiScrapeSearchResponse(
    val data: List<BangumiScrapeSubjectDto> = emptyList(),
)

@Serializable
private data class BangumiScrapeSubjectDto(
    val id: Long,
    val type: Int,
    val name: String = "",
    val name_cn: String = "",
    val date: String? = null,
    val summary: String? = null,
    val rating: BangumiRatingDto? = null,
    val images: BangumiImagesDto? = null,
    val tags: List<BangumiTagDto> = emptyList(),
    val infobox: List<BangumiInfoboxItem> = emptyList(),
    val eps: Int? = null,
    val total_episodes: Int? = null,
)

@Serializable
private data class BangumiRatingDto(val score: Double = 0.0)

@Serializable
private data class BangumiImagesDto(val large: String? = null, val common: String? = null)

@Serializable
private data class BangumiTagDto(val name: String = "")

@Serializable
private data class BangumiInfoboxItem(val key: String = "", val value: JsonElement? = null)

@Serializable
private data class BangumiEpisodesResponse(val data: List<BangumiEpisodeDto> = emptyList())

@Serializable
private data class BangumiEpisodeDto(
    val type: Int = 0,
    val sort: Double = 0.0,
    val name: String = "",
    val name_cn: String = "",
    val airdate: String? = null,
    val desc: String = "",
) {
    /** 正片且 sort 为正整数(跳过 SP/OAD/特典的 0.x / 8.5 等)。 */
    fun toEpisode(): BangumiScrapeEpisode? {
        if (type != 0 || sort <= 0.0 || sort % 1.0 != 0.0) return null
        return BangumiScrapeEpisode(
            sort = sort,
            title = name_cn.takeIf { it.isNotBlank() } ?: name.takeIf { it.isNotBlank() },
            aired = airdate,
            plot = desc.takeIf { it.isNotBlank() },
        )
    }
}
