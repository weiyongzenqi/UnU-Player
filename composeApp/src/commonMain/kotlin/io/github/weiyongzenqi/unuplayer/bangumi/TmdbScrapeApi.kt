package io.github.weiyongzenqi.unuplayer.bangumi

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

    /** 获取网关已按语言归一化的 backdrop 候选，优先使用中文、无语言、英文顺序。 */
    suspend fun fetchBackdropPath(tvId: Long): String? {
        if (tvId <= 0) return null
        val body = executeGet(
            "/api/v1/tmdb/tv/$tvId/images",
            mapOf("language" to "zh-CN"),
        )
        val response = decodeResponse<TmdbGatewayImagesResponse>(body)
        if (response.tvId != tvId) {
            throw TmdbApiException(message = "TMDB Gateway 图片响应身份不匹配")
        }
        return response.backdrops
            .sortedBy { it.languagePriority }
            .firstNotNullOfOrNull { it.filePath.takeIf(String::isNotBlank) }
    }

    /** 一次获取整季剧集并映射 `episodeNumber -> stillPath`。 */
    suspend fun fetchSeasonStillPaths(tvId: Long, seasonNumber: Int): Map<Int, String> {
        if (tvId <= 0 || seasonNumber <= 0) return emptyMap()
        val body = executeGet(
            "/api/v1/tmdb/tv/$tvId/season/$seasonNumber/episodes",
            mapOf("language" to "zh-CN"),
        )
        val response = decodeResponse<TmdbGatewaySeasonResponse>(body)
        if (response.tvId != tvId || response.seasonNumber != seasonNumber) {
            throw TmdbApiException(message = "TMDB Gateway 季度响应身份不匹配")
        }
        return response.episodes
            .mapNotNull { episode ->
                val still = episode.stillPath?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                episode.episodeNumber.takeIf { it > 0 }?.let { it to still }
            }
            .toMap()
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
        const val USER_AGENT = "UnU-Player/0.1.7"
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

@Serializable
private data class TmdbGatewaySearchResponse(
    val page: Int = 1,
    val totalPages: Int = 0,
    val candidates: List<TmdbGatewayTvCandidateDto>,
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
