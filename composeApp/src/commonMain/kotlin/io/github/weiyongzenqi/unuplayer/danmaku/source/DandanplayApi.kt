package io.github.weiyongzenqi.unuplayer.danmaku.source

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.danmaku.dandanplaySignature
import io.github.weiyongzenqi.unuplayer.webdav.createHttpClient

/**
 * 弹弹play 开放弹幕网络 API v2 客户端。
 *
 * 签名: `base64(sha256(AppId + Timestamp + Path + AppSecret))`, Path 不含 query。
 * 凭证(appId/appSecret)由上层注入, 不硬编码(见 DESIGN.md §12.1.2)。
 *
 * 手动用 [Json] 解析响应(不装 ContentNegotiation, 避免改全局 [createHttpClient]
 * 配置与加依赖)。非 2xx 响应抛异常, 由 [DanmakuSourceProvider] 实现 catch。
 *
 * 接口:
 * - [searchEpisodesByTmdb]  tmdb 快速匹配(WebDAV 快捷设置用)
 * - [match]                  文件 hash+文件名 -> episodeId
 * - [comment]                episodeId -> 弹幕
 * - [searchAnime] / [bangumi] 回退链(文件名搜索 -> 集数定位)用
 */
class DandanplayApi(
    private val appId: String = "",
    private val appSecret: String = "",
    private val httpClient: HttpClient = createHttpClient(),
    private val baseUrl: String = "https://api.dandanplay.net",
    /** 代理模式: 非空时走自建代理(baseUrl=代理地址), 发 Bearer 不发签名头(签名下沉服务端)。 */
    private val proxyApiKey: String? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 代理模式: proxyApiKey 非空时发 Bearer, 不发签名头(签名下沉服务端)。 */
    private val useProxy: Boolean get() = !proxyApiKey.isNullOrBlank()

    /** 计算请求头。代理模式发 Bearer; 直连模式发签名头。timestamp = Unix 秒。 */
    private fun authHeaders(path: String): Map<String, String> {
        if (useProxy) {
            return mapOf(
                "Authorization" to "Bearer $proxyApiKey",
                "Accept" to "application/json",
                "User-Agent" to "UnU-Player/0.1",
            )
        }
        val ts = platformTimeMillis() / 1000
        return mapOf(
            "X-AppId" to appId,
            "X-Timestamp" to ts.toString(),
            "X-Signature" to dandanplaySignature(appId, ts, path, appSecret),
            "Accept" to "application/json",
            "User-Agent" to "UnU-Player/0.1",
        )
    }

    private suspend fun get(path: String, query: String = ""): String {
        val url = baseUrl + path + if (query.isEmpty()) "" else "?$query"
        val resp = httpClient.get(url) {
            authHeaders(path).forEach { (k, v) -> header(k, v) }
        }
        if (!resp.status.isSuccess()) {
            throw RuntimeException("dandanplay HTTP ${resp.status.value}: ${resp.bodyAsText().take(300)}")
        }
        return resp.bodyAsText()
    }

    private suspend fun post(path: String, body: String): String {
        val resp = httpClient.post(baseUrl + path) {
            authHeaders(path).forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!resp.status.isSuccess()) {
            throw RuntimeException("dandanplay HTTP ${resp.status.value}: ${resp.bodyAsText().take(300)}")
        }
        return resp.bodyAsText()
    }

    /** 按 tmdbId 搜索番剧剧集列表。 */
    suspend fun searchEpisodesByTmdb(tmdbId: Long): DandanplaySearchEpisodesResponse {
        val body = get("/api/v2/search/episodes", "tmdbId=$tmdbId")
        return json.decodeFromString(DandanplaySearchEpisodesResponse.serializer(), body)
    }

    /** 用文件名+哈希+大小匹配番剧节目。matchMode=hashAndFileName(默认)。 */
    suspend fun match(fileName: String, fileHash: String, fileSize: Long): DandanplayMatchResponse {
        val req = DandanplayMatchRequest(fileName, fileHash, fileSize, "hashAndFileName")
        val body = post("/api/v2/match", json.encodeToString(DandanplayMatchRequest.serializer(), req))
        return json.decodeFromString(DandanplayMatchResponse.serializer(), body)
    }

    /** 按 episodeId 拉弹幕。withRelated 聚合第三方源, chConvert=1 繁转简。 */
    suspend fun comment(episodeId: Long, withRelated: Boolean = true): DandanplayCommentResponse {
        val q = "withRelated=${if (withRelated) "true" else "false"}&chConvert=1"
        val body = get("/api/v2/comment/$episodeId", q)
        return json.decodeFromString(DandanplayCommentResponse.serializer(), body)
    }

    /** 按关键词搜索番剧(回退链: 文件名匹配失败时用)。 */
    suspend fun searchAnime(keyword: String): DandanplaySearchAnimeResponse {
        val body = get("/api/v2/search/anime", "keyword=${keyword.encodeURLParameter()}")
        return json.decodeFromString(DandanplaySearchAnimeResponse.serializer(), body)
    }

    /** 获取番剧详情(含 episodeNumber, 回退链按集数定位 episodeId 用)。 */
    suspend fun bangumi(animeId: Long): DandanplayBangumiResponse {
        val body = get("/api/v2/bangumi/$animeId")
        return json.decodeFromString(DandanplayBangumiResponse.serializer(), body)
    }
}
