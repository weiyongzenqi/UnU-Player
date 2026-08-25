package io.github.weiyongzenqi.unuplayer.bangumi

import io.github.weiyongzenqi.unuplayer.core.network.APP_USER_AGENT
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.webdav.createStrictHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * TMDB Gateway 客户端。候选评分和图片落库仍由现有刮削层负责，本类只处理窄协议映射。
 *
 * 三类 JSON 元数据请求均通过自建网关，并只在 `X-API-Key` 请求头携带内置客户端 key；
 * 客户端不再保存 TMDB 官方 token，也不会在网关失败时静默回退直连。图片 CDN 当前仍按
 * 网关响应约定直连 `image.tmdb.org`，后续启用图片代理时只需替换 [imageUrl] 的边界。
 */
class TmdbScrapeApi(
    private val apiKey: String = TmdbGatewayConfig.apiKey(),
    private val httpClient: HttpClient = createStrictHttpClient(),
    baseUrl: String = TmdbGatewayConfig.baseUrl(),
    private val imageBaseUrl: String = DEFAULT_IMAGE_BASE_URL,
) {
    private val gatewayBaseUrl = baseUrl.trimEnd('/')
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 搜索 TV；简体结果没有中文时再尝试繁体，首个请求失败则直接向上传递。 */
    suspend fun searchTv(query: String, year: Int? = null): List<TmdbTvCandidate> {
        if (query.isBlank()) return emptyList()
        val normalized = query.trim().take(MAX_KEYWORD_LENGTH)
        val simplified = searchTvInLanguage(normalized, year, "zh-CN")
        if (simplified.any { it.name.containsChineseText() || it.originalName.orEmpty().containsChineseText() }) {
            return simplified
        }
        return runSuspendCatching { searchTvInLanguage(normalized, year, "zh-TW") }
            .getOrElse { simplified }
            .ifEmpty { simplified }
    }

    private suspend fun searchTvInLanguage(
        query: String,
        year: Int?,
        language: String,
    ): List<TmdbTvCandidate> {
        val parameters = buildMap {
            put("query", query)
            put("language", language)
            put("page", "1")
            if (year != null) put("year", year.toString())
        }
        val body = executeGet("/api/v1/tmdb/search/tv", parameters)
        return decodeResponse<TmdbGatewaySearchResponse>(body).candidates
            .mapNotNull { it.toCandidate() }
    }

    private fun String.containsChineseText(): Boolean = any { it in '\u4E00'..'\u9FFF' }

    /** 获取 TV 详情，包含中文简介、评分、类型和海报/背景图路径。 */
    suspend fun fetchTvDetails(tvId: Long): TmdbTvDetails? {
        if (tvId <= 0) return null
        val response = decodeResponse<TmdbGatewayDetailsResponse>(
            executeGet("/api/v1/tmdb/tv/$tvId", mapOf("language" to "zh-CN")),
        )
        if (response.tvId != tvId || response.name.isBlank()) return null
        return TmdbTvDetails(
            tmdbId = response.tvId,
            name = response.name,
            originalName = response.originalName?.takeIf { it.isNotBlank() },
            overview = response.overview?.takeIf { it.isNotBlank() },
            voteAverage = response.voteAverage?.takeIf { it > 0.0 },
            firstAirDate = response.firstAirDate?.takeIf { it.isNotBlank() },
            posterPath = response.posterPath?.takeIf { it.isNotBlank() },
            backdropPath = response.backdropPath?.takeIf { it.isNotBlank() },
            genres = response.genres.filter(String::isNotBlank),
        )
    }

    /** TV 头图与海报候选(一次 images 请求同时取回)。 */
    suspend fun fetchTvImagePaths(tvId: Long): TmdbTvImagePaths {
        if (tvId <= 0) return TmdbTvImagePaths(backdropPath = null, posterPath = null)
        val body = executeGet(
            "/api/v1/tmdb/tv/$tvId/images",
            mapOf("language" to "zh-CN"),
        )
        val response = decodeResponse<TmdbGatewayImagesResponse>(body)
        if (response.tvId != tvId) {
            throw TmdbApiException(message = "TMDB Gateway 图片响应身份不匹配")
        }
        return TmdbTvImagePaths(
            backdropPath = response.backdrops.firstNonBlankPathByLanguage(),
            posterPath = response.posters.firstNonBlankPathByLanguage(),
        )
    }

    /** 按语言优先级(中文 > 无语言 > 英文 > 其他)取第一个非空 filePath。 */
    private fun List<TmdbGatewayImageDto>.firstNonBlankPathByLanguage(): String? =
        sortedBy { it.languagePriority }
            .firstNotNullOfOrNull { it.filePath.takeIf(String::isNotBlank) }

    /** 一次获取整季剧照(seasonNumber -> stillPath)与季海报路径。 */
    suspend fun fetchSeasonImages(tvId: Long, seasonNumber: Int): TmdbSeasonImages {
        // TMDB 的特别篇使用 season 0；只有负季号才是无效输入。
        if (tvId <= 0 || seasonNumber < 0) return TmdbSeasonImages(stillPaths = emptyMap(), posterPath = null)
        val body = executeGet(
            "/api/v1/tmdb/tv/$tvId/season/$seasonNumber/episodes",
            mapOf("language" to "zh-CN"),
        )
        val response = decodeResponse<TmdbGatewaySeasonResponse>(body)
        if (response.tvId != tvId || response.seasonNumber != seasonNumber) {
            throw TmdbApiException(message = "TMDB Gateway 季度响应身份不匹配")
        }
        return TmdbSeasonImages(
            stillPaths = response.episodes
                .mapNotNull { episode ->
                    val still = episode.stillPath?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    episode.episodeNumber.takeIf { it > 0 }?.let { it to still }
                }
                .toMap(),
            posterPath = response.posterPath?.takeIf(String::isNotBlank),
            episodes = response.episodes.mapNotNull { episode ->
                episode.episodeNumber.takeIf { it > 0 }?.let { number ->
                    TmdbSeasonEpisode(
                        episodeNumber = number,
                        name = episode.name.takeIf(String::isNotBlank),
                        airDate = episode.airDate?.takeIf(String::isNotBlank),
                    )
                }
            },
        )
    }

    /** 拼接 TMDB 图片 CDN URL；当前图片链路不经过 Gateway。 */
    fun imageUrl(path: String, size: String): String = "$imageBaseUrl/$size$path"

    private suspend fun executeGet(path: String, parameters: Map<String, String>): String {
        require(apiKey.isNotBlank()) { "TMDB Gateway API key 不能为空" }
        requestMutex.withLock { awaitRequestSlot() }
        return httpClient.prepareGet(gatewayBaseUrl + path) {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(API_KEY_HEADER, apiKey)
            parameters.forEach { (name, value) -> parameter(name, value) }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val errorBody = try {
                    readLimitedJson(response.bodyAsChannel(), MAX_ERROR_RESPONSE_BYTES)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    null
                }
                val envelope = errorBody?.let { body ->
                    runCatching { json.decodeFromString(TmdbGatewayErrorEnvelope.serializer(), body) }.getOrNull()
                }
                val error = envelope?.error
                val retryAfter = error?.retryAfterSeconds
                    ?: response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()
                throw TmdbApiException(
                    statusCode = response.status.value,
                    errorCode = error?.code,
                    requestId = error?.requestId ?: response.headers[REQUEST_ID_HEADER],
                    retryAfterSeconds = retryAfter,
                )
            }
            try {
                readLimitedJson(response.bodyAsChannel(), MAX_SUCCESS_RESPONSE_BYTES)
            } catch (error: CancellationException) {
                throw error
            } catch (error: BangumiApiException) {
                throw TmdbApiException(message = "TMDB Gateway 响应超过大小上限", cause = error)
            } catch (error: TmdbApiException) {
                throw error
            } catch (error: Throwable) {
                throw TmdbApiException(message = "TMDB Gateway 响应读取失败", cause = error)
            }
        }
    }

    private inline fun <reified T> decodeResponse(body: String): T = try {
        json.decodeFromString(body)
    } catch (error: Throwable) {
        throw TmdbApiException(message = "TMDB Gateway 响应格式无效", cause = error)
    }

    private suspend fun awaitRequestSlot() {
        val now = platformTimeMillis()
        val waitMillis = (nextRequestAt - now).coerceAtLeast(0L)
        if (waitMillis > 0) delay(waitMillis)
        nextRequestAt = platformTimeMillis() + MIN_REQUEST_INTERVAL_MS
    }

    private companion object {
        val requestMutex = Mutex()
        var nextRequestAt = 0L
        const val API_KEY_HEADER = "X-API-Key"
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val USER_AGENT = APP_USER_AGENT
        const val DEFAULT_IMAGE_BASE_URL = "https://image.tmdb.org/t/p"
        const val MAX_KEYWORD_LENGTH = 120
        const val MIN_REQUEST_INTERVAL_MS = 250L
        const val MAX_SUCCESS_RESPONSE_BYTES = 1024 * 1024
        const val MAX_ERROR_RESPONSE_BYTES = 64 * 1024
    }
}

internal class TmdbApiException(
    val statusCode: Int? = null,
    val errorCode: String? = null,
    val requestId: String? = null,
    val retryAfterSeconds: Long? = null,
    message: String = buildMessage(statusCode, errorCode),
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    companion object {
        private fun buildMessage(statusCode: Int?, errorCode: String?): String = buildString {
            append("TMDB Gateway 请求失败")
            statusCode?.let { append(" (HTTP ").append(it).append(')') }
            errorCode?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
        }
    }
}

/** TMDB TV 搜索候选（定位 tmdb_id 用）。 */
data class TmdbTvCandidate(
    val tmdbId: Long,
    val name: String,
    val originalName: String? = null,
    val firstAirDate: String? = null,
    val backdropPath: String? = null,
)

data class TmdbTvDetails(
    val tmdbId: Long,
    val name: String,
    val originalName: String? = null,
    val overview: String? = null,
    val voteAverage: Double? = null,
    val firstAirDate: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val genres: List<String> = emptyList(),
)

/** TV 级图片路径(backdrop=宽幅头图, poster=竖版海报), 均按语言优先级取第一个非空。 */
data class TmdbTvImagePaths(
    val backdropPath: String?,
    val posterPath: String?,
)

/** 季级图片: 逐集剧照路径 + 季海报路径(旧网关无海报字段时为 null)。 */
data class TmdbSeasonImages(
    val stillPaths: Map<Int, String>,
    val posterPath: String?,
    val episodes: List<TmdbSeasonEpisode> = emptyList(),
)

data class TmdbSeasonEpisode(
    val episodeNumber: Int,
    val name: String? = null,
    val airDate: String? = null,
)

@Serializable
private data class TmdbGatewaySearchResponse(
    val page: Int = 1,
    val totalPages: Int = 0,
    val candidates: List<TmdbGatewayTvCandidateDto>,
)

@Serializable
private data class TmdbGatewayDetailsResponse(
    val tvId: Long,
    val name: String = "",
    val originalName: String? = null,
    val overview: String? = null,
    val voteAverage: Double? = null,
    val firstAirDate: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
private data class TmdbGatewayTvCandidateDto(
    val tmdbId: Long,
    val name: String,
    val originalName: String? = null,
    val firstAirDate: String? = null,
    val backdropPath: String? = null,
) {
    fun toCandidate(): TmdbTvCandidate? {
        if (tmdbId <= 0 || name.isBlank()) return null
        return TmdbTvCandidate(
            tmdbId = tmdbId,
            name = name,
            originalName = originalName?.takeIf { it.isNotBlank() && it != name },
            firstAirDate = firstAirDate,
            backdropPath = backdropPath?.takeIf(String::isNotBlank),
        )
    }
}

@Serializable
private data class TmdbGatewayImagesResponse(
    val tvId: Long,
    val backdrops: List<TmdbGatewayImageDto>,
    /** 旧网关响应无此字段(默认空容错): TV 竖版海报候选, 条目结构与 backdrops 一致。 */
    val posters: List<TmdbGatewayImageDto> = emptyList(),
    val imageBaseUrl: String = "",
)

@Serializable
private data class TmdbGatewayImageDto(
    val filePath: String,
    val language: String? = null,
) {
    val languagePriority: Int get() = when (language) {
        "zh", "zh-CN", "zh-TW" -> 0
        null -> 1
        "en" -> 2
        else -> 3
    }
}

@Serializable
private data class TmdbGatewaySeasonResponse(
    val tvId: Long,
    val seasonNumber: Int,
    val episodes: List<TmdbGatewayEpisodeDto>,
    /** 旧网关响应无此字段(默认 null 容错): 季海报路径(与 backdrops 同源的 TMDB path)。 */
    val posterPath: String? = null,
    val imageBaseUrl: String = "",
)

@Serializable
private data class TmdbGatewayEpisodeDto(
    val episodeNumber: Int,
    val name: String = "",
    val stillPath: String? = null,
    val airDate: String? = null,
)

@Serializable
private data class TmdbGatewayErrorEnvelope(
    val error: TmdbGatewayErrorDto? = null,
)

@Serializable
private data class TmdbGatewayErrorDto(
    val code: String? = null,
    val requestId: String? = null,
    val retryAfterSeconds: Long? = null,
)
