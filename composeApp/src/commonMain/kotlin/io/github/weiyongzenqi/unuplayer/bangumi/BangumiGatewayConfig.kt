package io.github.weiyongzenqi.unuplayer.bangumi

import io.github.weiyongzenqi.unuplayer.core.network.APP_USER_AGENT
import io.github.weiyongzenqi.unuplayer.core.security.GATEWAY_CLIENT_MASK
import io.github.weiyongzenqi.unuplayer.core.security.OBFUSCATED_GATEWAY_API_KEY_HEX
import io.github.weiyongzenqi.unuplayer.core.security.decodeObfuscatedClientValue
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
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/**
 * Bangumi 自建网关(UnU-Gateway)内置配置与中性路由客户端。
 *
 * 端点与 key 的混淆策略同 [TmdbGatewayConfig]/弹弹代理: 只防明文被简单 grep, 滥用防护依赖
 * 网关侧限流/审计/吊销。中性短路由(/q /s /e /c /ec /t /d)不暴露上游路径; 响应为上游透传
 * (字段同构, DTO 复用官方解析); JSON 内 lain 图片 URL 已由网关重写为本域, 配合 GATEWAY 预设的
 * imageBaseUrl(网关 /i)实现头像/表情/图床单域名化。网关失败不静默回退官方(与 TMDB 决策一致)。
 */
internal object BangumiGatewayConfig {
    private const val OBFUSCATED_API_BASE_URL_HEX =
        "5f434347440d18184259425056435240564e1907000506070705194f4e4d1855505a"
    private const val OBFUSCATED_IMAGE_BASE_URL_HEX =
        "5f434347440d18184259425056435240564e1907000506070705194f4e4d1855505a185e"


    fun apiBaseUrl(): String = decodeObfuscatedClientValue(OBFUSCATED_API_BASE_URL_HEX, GATEWAY_CLIENT_MASK)

    fun imageBaseUrl(): String = decodeObfuscatedClientValue(OBFUSCATED_IMAGE_BASE_URL_HEX, GATEWAY_CLIENT_MASK)

    fun apiKey(): String = decodeObfuscatedClientValue(OBFUSCATED_GATEWAY_API_KEY_HEX, GATEWAY_CLIENT_MASK)
}

/** 网关 HTTP 错误(携带状态码); 继承 [BangumiApiException] 使现有刮削/目录错误路径无需适配。 */
internal class BangumiGatewayHttpException(val statusCode: Int) :
    BangumiApiException("Bangumi gateway HTTP $statusCode")

/** 网关 JSON 请求进程级节流门(FP3-4): 全网关实例/全部 API 类(刮削/评论/目录)共享。
 * 使用单调时钟和 275ms 留余量(约 218/min)，命中 429 时把 Retry-After 转成共享 deadline，
 * 防止墙钟回拨或并发实例把预算顶满后持续撞限流。 */
internal object BangumiGatewayRequestGate {
    private val mutex = Mutex()
    private var nextRequestAt = 0L
    private val monotonicOrigin = TimeSource.Monotonic.markNow()
    private var clock: () -> Long = { monotonicOrigin.elapsedNow().inWholeMilliseconds }
    private var sleeper: suspend (Long) -> Unit = { delay(it) }

    /** 等待到本轮窗口再放行(所有 BangumiGatewayEndpoint 实例共用同一闸)。 */
    suspend fun awaitRequestSlot() {
        while (true) {
            val waitMillis = mutex.withLock {
                val now = clock()
                val remaining = (nextRequestAt - now).coerceAtLeast(0L)
                if (remaining == 0L) {
                    nextRequestAt = now + REQUEST_INTERVAL_MS
                }
                remaining
            }
            if (waitMillis == 0L) return
            // 等待期间必须释放锁，让并发 429 能及时延长 deadline；醒来后重新竞争并复核窗口。
            sleeper(waitMillis)
        }
    }

    /** 429 后设置共享退避 deadline；无效/过大值按 1～60 秒封顶。 */
    suspend fun recordRateLimit(retryAfterSeconds: Long?) {
        val seconds = (retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS)
            .coerceIn(1L, MAX_RETRY_AFTER_SECONDS)
        mutex.withLock {
            val deadline = clock() + seconds * 1_000L
            nextRequestAt = maxOf(nextRequestAt, deadline)
        }
    }

    /** 仅供测试: 注入可控时钟并清零节流窗口(避免真实时间等待与跨测试污染)。 */
    internal fun resetForTest(
        newClock: () -> Long = { monotonicOrigin.elapsedNow().inWholeMilliseconds },
        newSleeper: (suspend (Long) -> Unit)? = null,
    ) {
        clock = newClock
        sleeper = newSleeper ?: { delay(it) }
        nextRequestAt = 0L
    }

    private const val REQUEST_INTERVAL_MS = 275L
    private const val DEFAULT_RETRY_AFTER_SECONDS = 30L
    private const val MAX_RETRY_AFTER_SECONDS = 60L
}

/** GATEWAY 预设的传输端点: 中性短路由 + X-API-Key 鉴权头。 */
class BangumiGatewayEndpoint(
    private val httpClient: HttpClient = createStrictHttpClient(),
    baseUrl: String = BangumiGatewayConfig.apiBaseUrl(),
    private val apiKey: String = BangumiGatewayConfig.apiKey(),
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** POST /q: type=2 过滤由网关固定注入, limit/offset 走请求体(与官方 query 参数形态不同)。 */
    suspend fun searchSubjects(keyword: String, limit: Int, offset: Int = 0): String = execute(
        path = "/q",
        method = HttpMethod.Post,
        body = json.encodeToString(
            GatewaySearchRequest(
                k = keyword,
                limit = limit.coerceIn(1, MAX_SEARCH_LIMIT),
                offset = offset.coerceAtLeast(0),
            ),
        ),
    )

    suspend fun subject(subjectId: Long, allowNotFound: Boolean = false): String? = try {
        execute("/s/$subjectId")
    } catch (error: BangumiGatewayHttpException) {
        if (allowNotFound && error.statusCode == 404) null else throw error
    }

    /** GET /e: type 缺省 0(正片); -1=全量(SP/OP/ED, 评论区集数索引用); 网关单页上限 100。 */
    suspend fun episodes(
        subjectId: Long,
        limit: Int,
        offset: Int,
        maxBytes: Int = DEFAULT_JSON_LIMIT_BYTES,
        type: Int = 0,
    ): String = execute(
        "/e/$subjectId",
        parameters = mapOf("type" to type.toString()) + pageParameters(limit, offset),
        maxBytes = maxBytes,
    )

    suspend fun seasonComments(subjectId: Long, limit: Int, offset: Int, maxBytes: Int = DEFAULT_JSON_LIMIT_BYTES): String =
        execute("/c/$subjectId", parameters = pageParameters(limit, offset), maxBytes = maxBytes)

    suspend fun episodeComments(episodeId: Long, maxBytes: Int = DEFAULT_JSON_LIMIT_BYTES): String =
        execute("/ec/$episodeId", maxBytes = maxBytes)

    suspend fun subjectTopics(subjectId: Long, limit: Int, offset: Int, maxBytes: Int = DEFAULT_JSON_LIMIT_BYTES): String =
        execute("/t/$subjectId", parameters = pageParameters(limit, offset), maxBytes = maxBytes)

    suspend fun topicDetail(topicId: Long, maxBytes: Int = DEFAULT_JSON_LIMIT_BYTES): String =
        execute("/d/$topicId", maxBytes = maxBytes)

    /** GET /rv: 条目长评列表(data[].entry.id 为 blogId)。 */
    suspend fun subjectReviews(subjectId: Long, limit: Int, offset: Int, maxBytes: Int = DEFAULT_JSON_LIMIT_BYTES): String =
        execute("/rv/$subjectId", parameters = pageParameters(limit, offset), maxBytes = maxBytes)

    /** GET /rd: 长评(日志)正文, 主楼即 blog 本体。 */
    suspend fun reviewDetail(blogId: Long, maxBytes: Int = DEFAULT_JSON_LIMIT_BYTES): String =
        execute("/rd/$blogId", maxBytes = maxBytes)

    /** GET /rdc: 长评回帖树(嵌套, 与讨论帖回帖 DTO 同构)。 */
    suspend fun reviewComments(blogId: Long, maxBytes: Int = DEFAULT_JSON_LIMIT_BYTES): String =
        execute("/rdc/$blogId", maxBytes = maxBytes)

    /** GET /cal: Bangumi 每周放送日历。 */
    suspend fun calendar(maxBytes: Int = 2 * DEFAULT_JSON_LIMIT_BYTES): String =
        execute("/cal", maxBytes = maxBytes)

    /** GET /bd/{year}/{month}: bangumi-data 的当月窄数据文件。 */
    suspend fun bangumiData(year: Int, month: Int, maxBytes: Int = 2 * DEFAULT_JSON_LIMIT_BYTES): String {
        require(year in 2000..2100 && month in 1..12) { "非法 bangumi-data 年月" }
        return execute("/bd/$year/${month.toString().padStart(2, '0')}", maxBytes = maxBytes)
    }

    private fun pageParameters(limit: Int, offset: Int): Map<String, String> = mapOf(
        "limit" to limit.coerceIn(1, MAX_PAGE_LIMIT).toString(),
        "offset" to offset.coerceAtLeast(0).toString(),
    )

    private suspend fun execute(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        parameters: Map<String, String> = emptyMap(),
        body: String? = null,
        maxBytes: Int = DEFAULT_JSON_LIMIT_BYTES,
    ): String {
        // 进程级节流门: 所有 BangumiGatewayEndpoint 实例(刮削/评论/目录)共享单调时钟窗口。
        BangumiGatewayRequestGate.awaitRequestSlot()
        return httpClient.prepareRequest(base + path) {
            this.method = method
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            header(HttpHeaders.UserAgent, USER_AGENT)
            header("X-API-Key", apiKey)
            parameters.forEach { (name, value) -> parameter(name, value) }
            body?.let {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(it)
            }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val retryAfter = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()
                response.bodyAsChannel().cancel(null)
                if (response.status.value == HTTP_TOO_MANY_REQUESTS) {
                    BangumiGatewayRequestGate.recordRateLimit(retryAfter)
                }
                throw BangumiGatewayHttpException(response.status.value)
            }
            readLimitedJson(response.bodyAsChannel(), maxBytes)
        }
    }

    private companion object {
        const val USER_AGENT = APP_USER_AGENT
        const val MAX_SEARCH_LIMIT = 20
        const val MAX_PAGE_LIMIT = 100
        const val DEFAULT_JSON_LIMIT_BYTES = 1024 * 1024
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}

@Serializable
private data class GatewaySearchRequest(
    val k: String,
    val limit: Int,
    val offset: Int,
    val sort: String = "match",
)

/** 图片等静态资源请求的网关鉴权头：同源 Gateway `/i` 同时要求应用 UA 与 X-API-Key。 */
internal fun gatewayImageAuthHeaders(url: String): Map<String, String> {
    val origin = httpOrigin(url) ?: return emptyMap()
    val gatewayOrigins = listOfNotNull(
        httpOrigin(BangumiGatewayConfig.apiBaseUrl()),
        httpOrigin(BangumiGatewayConfig.imageBaseUrl()),
    )
    return if (origin in gatewayOrigins) {
        mapOf(
            HttpHeaders.UserAgent to APP_USER_AGENT,
            "X-API-Key" to BangumiGatewayConfig.apiKey(),
        )
    } else emptyMap()
}

/** 所有 Bangumi 图片下载都使用的请求头，官方源也必须带应用 UA。 */
internal fun bangumiImageRequestHeaders(url: String): Map<String, String> = buildMap {
    put(HttpHeaders.UserAgent, APP_USER_AGENT)
    putAll(gatewayImageAuthHeaders(url))
}

private data class HttpOrigin(val scheme: String, val host: String, val port: Int)

private fun httpOrigin(url: String): HttpOrigin? {
    val parsed = runCatching { Url(url) }.getOrNull() ?: return null
    val scheme = parsed.protocol.name.lowercase()
    if (scheme != "http" && scheme != "https") return null
    return HttpOrigin(scheme, parsed.host.lowercase(), parsed.port)
}
