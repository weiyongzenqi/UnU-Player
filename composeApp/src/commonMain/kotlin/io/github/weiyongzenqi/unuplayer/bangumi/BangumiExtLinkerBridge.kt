package io.github.weiyongzenqi.unuplayer.bangumi

import io.github.weiyongzenqi.unuplayer.core.network.APP_USER_AGENT
import io.github.weiyongzenqi.unuplayer.webdav.createStrictHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

fun interface BangumiTmdbBridge {
    suspend fun find(tmdbId: Long, seasonNumber: Long): List<BangumiCandidate>
}

/**
 * BangumiExtLinker 的季度映射。数据源为 CC BY 4.0：
 * https://github.com/Rhilip/BangumiExtLinker
 */
class BangumiExtLinkerBridge internal constructor(
    private val httpClient: HttpClient = createStrictHttpClient(),
    private val dataUrl: String = DEFAULT_DATA_URL,
    private val cache: BangumiExtLinkerCache = sharedBangumiExtLinkerCache,
) : BangumiTmdbBridge {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun find(tmdbId: Long, seasonNumber: Long): List<BangumiCandidate> {
        if (tmdbId <= 0 || seasonNumber < 0) return emptyList()
        val mappings = cache.getOrLoad { loadMappings() }
        val exactKey = "tv/$tmdbId/season/$seasonNumber"
        val exact = mappings[exactKey].orEmpty()
        if (exact.isNotEmpty()) return exact.map { it.copy(seasonExact = true) }
        return mappings["tv/$tmdbId"].orEmpty()
    }

    private suspend fun loadMappings(): Map<String, List<BangumiCandidate>> {
        val body = httpClient.prepareGet(dataUrl) {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            header(HttpHeaders.UserAgent, USER_AGENT)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                response.bodyAsChannel().cancel(null)
                throw BangumiApiException("BangumiExtLinker HTTP ${response.status.value}")
            }
            readLimitedJson(response.bodyAsChannel(), MAX_DATASET_BYTES)
        }
        return json.decodeFromString<List<BangumiExtLinkRecord>>(body)
            .asSequence()
            .mapNotNull { record ->
                val tmdbKey = record.tmdb_id.contentOrNull()?.takeIf { it.startsWith("tv/") }
                    ?: return@mapNotNull null
                val subjectId = record.bgm_id.contentOrNull()?.toLongOrNull()?.takeIf { it > 0 }
                    ?: return@mapNotNull null
                tmdbKey to BangumiCandidate(
                    subjectId = subjectId,
                    title = record.name_cn.takeIf { it.isNotBlank() } ?: record.name,
                    originalTitle = record.name.takeIf { it.isNotBlank() && it != record.name_cn },
                    date = record.date,
                    type = BANGUMI_ANIME_TYPE,
                    sources = setOf(BangumiCandidateSource.EXT_LINKER),
                    evidence = "BangumiExtLinker",
                    seasonExact = false,
                )
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    }

    private companion object {
        const val DEFAULT_DATA_URL = "https://rhilip.github.io/BangumiExtLinker/data/anime_map.json"
        const val USER_AGENT = APP_USER_AGENT
        const val MAX_DATASET_BYTES = 24 * 1024 * 1024
        const val BANGUMI_ANIME_TYPE = 2
    }
}

internal class BangumiExtLinkerCache {
    private val mutex = Mutex()
    private var mappings: Map<String, List<BangumiCandidate>>? = null

    suspend fun getOrLoad(loader: suspend () -> Map<String, List<BangumiCandidate>>): Map<String, List<BangumiCandidate>> =
        mutex.withLock {
            mappings ?: loader().also { mappings = it }
        }
}

private val sharedBangumiExtLinkerCache = BangumiExtLinkerCache()

@Serializable
private data class BangumiExtLinkRecord(
    val name: String = "",
    val name_cn: String = "",
    val date: String? = null,
    val bgm_id: JsonElement? = null,
    val tmdb_id: JsonElement? = null,
)

private fun JsonElement?.contentOrNull(): String? = runCatching { this?.jsonPrimitive?.content }.getOrNull()
