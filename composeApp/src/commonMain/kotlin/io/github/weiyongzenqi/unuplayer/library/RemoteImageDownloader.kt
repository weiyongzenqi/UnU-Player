package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.network.APP_USER_AGENT
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import io.github.weiyongzenqi.unuplayer.webdav.createStrictHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// 在线刮削远程图片下载(commonMain 接口)。
//
// 弹弹 imageUrl / Bangumi images 是外网 http URL, 现有 MediaSource.downloadToFile 是
// 媒体源内路径下载(WebDAV/SAF), 不能直接下载外网图, 本接口是独立通道(见
// .claude/plans/online-scraping-2026-08-06.md 5.2.3 节)。
// 平台实现把图片下载到海报缓存稳定子目录(online-scrape/libraryId-hash(showPath)),
// 返回本地绝对路径，只写 ScrapedOnlineMeta；显示层以 LOCAL_FILE 显式直读，不污染 NFO 媒体源字段。
interface RemoteImageDownloader {
    /**
     * 通用远程图片下载: 下载到海报缓存稳定子目录(online-scrape/<libraryId>-<showPath>/<fileName>),
     * 返回本地绝对路径; null=失败(已记日志, 调用方跳过)。
     * 季照/TMDB 头图(backdrop)/剧集剧照(still)统一走此通道。
     * @param fileName 缓存文件名(如 "season1.jpg" / "backdrop.jpg" / "s1e5.jpg"; 调用方保证安全字符)。
     */
    suspend fun downloadImage(
        libraryId: Long,
        showPath: String,
        fileName: String,
        remoteUrl: String,
    ): String?

    /**
     * 季照下载(Bangumi/弹弹在线源): 文件名 season<N>.jpg。
     * 默认委托 [downloadImage], 平台实现只需实现通用通道。
     */
    suspend fun downloadSeasonPoster(
        libraryId: Long,
        showPath: String,
        seasonNumber: Int,
        remoteUrl: String,
    ): String? = downloadImage(libraryId, showPath, "season$seasonNumber.jpg", remoteUrl)
}

// 在线刮削季照的稳定缓存子目录键: 不随 title 变(刮削改 title 后已下载文件不孤儿化),
// 两端(android/desktop)共用同公式, 保证显示与下载路径一致。
fun onlineScrapeCacheKey(libraryId: Long, showPath: String): String =
    "online-scrape/${libraryId}-${sanitizeFileName(showPath)}"

// 远程图片 HTTP 获取(commonMain, 借鉴 BangumiAvatarRepository):
// GET + content-type image/* 校验 + 大小上限, 返回 ByteArray; 失败返回 null(不抛)。
// 共享进程级 client(createStrictHttpClient 同源 TLS 策略)。图片通常几十到几百 KB,
// 一次性加载可接受; 校验在写盘前完成, 防坏图/超限落缓存。
object RemoteImageFetcher {
    private const val USER_AGENT = APP_USER_AGENT

    // 进程级共享 strict client; 测试可注入替代(共享单例会被 DanmakuNetworkLifecycleTest 关闭且不重建)。
    private val httpClientDelegate by lazy(::createStrictHttpClient)

    @Volatile
    private var testHttpClient: HttpClient? = null

    private val httpClient: HttpClient
        get() = testHttpClient ?: httpClientDelegate

    /** 仅供测试: 注入替代 HTTP 客户端; 传 null 恢复进程级共享单例。 */
    internal fun setHttpClientForTest(client: HttpClient?) {
        testHttpClient = client
    }

    /** 单次图片获取结果: 成功带字节, 失败带分类原因(日志可见化, 不再一律静默 null)。 */
    sealed interface ImageFetchOutcome {
        data class Success(val bytes: ByteArray) : ImageFetchOutcome
        data class Failure(val reason: Reason) : ImageFetchOutcome

        /** 失败原因分类, [description] 为可直接进日志的中文短语。 */
        sealed interface Reason {
            val description: String

            /** 网络层异常(连接失败/中断等), 带异常类名。 */
            data class NetworkError(val errorClass: String) : Reason {
                override val description: String get() = "网络异常($errorClass)"
            }

            /** 非 2xx HTTP 状态(3xx 重定向另见 [RedirectError])。 */
            data class HttpError(val statusCode: Int) : Reason {
                override val description: String get() = "HTTP $statusCode"
            }

            /** 响应 Content-Type 不是 image 开头, 带实际类型。 */
            data class NotImageType(val contentType: String) : Reason {
                override val description: String get() = "非图片类型($contentType)"
            }

            /** 声明长度或实际读取字节数超过 maxBytes 上限。 */
            data object ExceededSizeLimit : Reason {
                override val description: String get() = "超过大小上限"
            }

            /** 重定向 Location 缺失/非法或次数超限。 */
            data class RedirectError(val detail: String) : Reason {
                override val description: String get() = "重定向$detail"
            }

            /** 响应体读取失败(空响应体等)。 */
            data object ReadFailed : Reason {
                override val description: String get() = "读取失败(空响应体)"
            }
        }
    }

    suspend fun fetchImage(url: String, maxBytes: Long = DEFAULT_MAX_IMAGE_BYTES): ByteArray? =
        when (val outcome = fetchImageDetailed(url, maxBytes)) {
            is ImageFetchOutcome.Success -> outcome.bytes
            is ImageFetchOutcome.Failure -> null
        }

    /** 带失败原因的图片获取(原 [fetchImage] 委托本方法, 行为不变)。 */
    suspend fun fetchImageDetailed(url: String, maxBytes: Long = DEFAULT_MAX_IMAGE_BYTES): ImageFetchOutcome {
        val effectiveMaxBytes = maxBytes.coerceAtMost(MAX_IMAGE_BYTES)
        if (effectiveMaxBytes <= 0L) {
            return ImageFetchOutcome.Failure(ImageFetchOutcome.Reason.ExceededSizeLimit)
        }
        var currentUrl = url
        repeat(MAX_REDIRECTS + 1) {
            // 目标主机仍在退避期则先等待: 只等被限流的 CDN, 不拖累其它主机(FP3-13);
            // delay 可取消, 抢占/取消场景直接传播。
            val waitMs = rateLimitBackoffRemainingMs(hostKeyOf(currentUrl))
            if (waitMs > 0L) delay(waitMs.coerceAtMost(MAX_RATE_LIMIT_BACKOFF_SECONDS * 1000L))
            val result = runSuspendCatching {
                httpClient.prepareGet(currentUrl) {
                    header(HttpHeaders.Accept, "image/avif,image/webp,image/png,image/jpeg")
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    io.github.weiyongzenqi.unuplayer.bangumi.gatewayImageAuthHeaders(currentUrl)
                        .forEach { (name, value) -> header(name, value) }
                }.execute { response ->
                    if (response.status.value in 300..399) {
                        val location = response.headers[HttpHeaders.Location]
                        response.bodyAsChannel().cancel(null)
                        return@execute ImageFetchResult.Redirect(resolveRedirect(currentUrl, location))
                    }
                    if (!response.status.isSuccess()) {
                        response.bodyAsChannel().cancel(null)
                        if (response.status.value == HTTP_TOO_MANY_REQUESTS) {
                            recordRateLimitBackoff(
                                host = hostKeyOf(currentUrl).orEmpty(),
                                retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull(),
                            )
                        }
                        return@execute ImageFetchResult.Failed(ImageFetchOutcome.Reason.HttpError(response.status.value))
                    }
                    val contentType = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';').lowercase()
                    if (!contentType.startsWith("image/")) {
                        response.bodyAsChannel().cancel(null)
                        return@execute ImageFetchResult.Failed(ImageFetchOutcome.Reason.NotImageType(contentType))
                    }
                    val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (declaredLength != null && declaredLength > effectiveMaxBytes) {
                        response.bodyAsChannel().cancel(null)
                        return@execute ImageFetchResult.Failed(ImageFetchOutcome.Reason.ExceededSizeLimit)
                    }
                    when (val body = readLimitedImage(response.bodyAsChannel(), effectiveMaxBytes)) {
                        is BodyReadResult.Ok -> ImageFetchResult.Body(body.bytes)
                        BodyReadResult.Oversize -> ImageFetchResult.Failed(ImageFetchOutcome.Reason.ExceededSizeLimit)
                        BodyReadResult.Empty -> ImageFetchResult.Failed(ImageFetchOutcome.Reason.ReadFailed)
                    }
                }
            }.getOrElse { error ->
                ImageFetchResult.Failed(
                    ImageFetchOutcome.Reason.NetworkError(error::class.simpleName ?: error.toString()),
                )
            }
            when (result) {
                is ImageFetchResult.Body -> return ImageFetchOutcome.Success(result.bytes)
                is ImageFetchResult.Redirect -> currentUrl = result.url
                    ?: return ImageFetchOutcome.Failure(ImageFetchOutcome.Reason.RedirectError("Location缺失或非法"))
                is ImageFetchResult.Failed -> return ImageFetchOutcome.Failure(result.reason)
            }
        }
        return ImageFetchOutcome.Failure(ImageFetchOutcome.Reason.RedirectError("次数超限(>${MAX_REDIRECTS}次)"))
    }

    // === 图片 CDN 限流(429)退避, 按主机隔离 ===
    // 网关 /i 图片默认每 IP 240/min, 批量刮削后半段易整段撞 429; 命中后对该主机记录退避截止时间
    // (Retry-After/默认30s/上限60s)。fetchImageDetailed 是唯一图片下载咽喉: 请求前按目标主机自延迟,
    // 避免连环失败。按 host[:port] 区分主机(FP3-13): image.tmdb.org 的 429 不得拖慢网关 /i 等其它 CDN。

    private val rateLimitMutex = Mutex()
    private val rateLimitUntilByHost = mutableMapOf<String, Long>()

    private fun hostKeyOf(url: String): String? =
        parseHttpUrl(url)?.authority?.lowercase()?.takeIf { it.isNotBlank() }

    /** 命中 429 时按主机记录退避: Retry-After 秒数, 无/非法则默认 30s, 上限 60s。 */
    private suspend fun recordRateLimitBackoff(host: String, retryAfterSeconds: Long?) {
        if (host.isBlank()) return
        val seconds = (retryAfterSeconds ?: DEFAULT_RATE_LIMIT_BACKOFF_SECONDS)
            .coerceIn(1L, MAX_RATE_LIMIT_BACKOFF_SECONDS)
        rateLimitMutex.withLock {
            rateLimitUntilByHost[host] = platformTimeMillis() + seconds * 1000L
        }
    }

    /** 指定主机(host[:port])的图片限流退避剩余毫秒; 未在退避期返回 0。 */
    suspend fun rateLimitBackoffRemainingMs(host: String?): Long = rateLimitMutex.withLock {
        if (host.isNullOrBlank()) return@withLock 0L
        val deadline = rateLimitUntilByHost[host.lowercase()] ?: return@withLock 0L
        (deadline - platformTimeMillis()).coerceAtLeast(0L)
    }

    /** 按图片 URL 目标主机的退避剩余毫秒(URL 无法解析返回 0)。 */
    suspend fun rateLimitBackoffRemainingMsForUrl(url: String): Long =
        rateLimitBackoffRemainingMs(hostKeyOf(url))

    /** 仅供测试: 清除进程级限流退避状态, 防止跨测试污染。 */
    internal suspend fun resetRateLimitBackoffForTest() {
        rateLimitMutex.withLock { rateLimitUntilByHost.clear() }
    }

    internal fun resolveRedirect(baseUrl: String, location: String?): String? {
        val target = location?.trim().orEmpty()
        if (target.isEmpty()) return null
        val base = parseHttpUrl(baseUrl) ?: return null
        val origin = "${base.scheme}://${base.authority}"
        val resolved = when {
            target.startsWith("http://", true) || target.startsWith("https://", true) -> target
            target.startsWith("//") -> "${base.scheme}:$target"
            target.startsWith('/') -> origin + normalizePathAndSuffix(target)
            target.startsWith('?') -> origin + base.path + target
            target.startsWith('#') -> origin + base.path + target
            else -> {
                val directory = base.path.substringBeforeLast('/', missingDelimiterValue = "") + "/"
                origin + normalizePathAndSuffix(directory + target)
            }
        }
        val parsedResolved = parseHttpUrl(resolved) ?: return null
        if (base.scheme == "https" && parsedResolved.scheme != "https") return null
        return resolved
    }

    private fun parseHttpUrl(url: String): ParsedHttpUrl? {
        val normalized = url.trim().substringBefore('#')
        val schemeSeparator = normalized.indexOf("://")
        if (schemeSeparator <= 0) return null
        val scheme = normalized.substring(0, schemeSeparator).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val remainder = normalized.substring(schemeSeparator + 3)
        val authorityEnd = listOf(remainder.indexOf('/'), remainder.indexOf('?'))
            .filter { it >= 0 }
            .minOrNull() ?: remainder.length
        val authority = remainder.substring(0, authorityEnd)
        if (authority.isBlank()) return null
        val pathAndQuery = remainder.substring(authorityEnd)
        val path = pathAndQuery.substringBefore('?').takeIf { it.startsWith('/') } ?: "/"
        return ParsedHttpUrl(scheme, authority, path)
    }

    private fun normalizePathAndSuffix(value: String): String {
        val suffixIndex = listOf(value.indexOf('?'), value.indexOf('#'))
            .filter { it >= 0 }
            .minOrNull() ?: value.length
        val rawPath = value.substring(0, suffixIndex)
        val suffix = value.substring(suffixIndex)
        val segments = mutableListOf<String>()
        rawPath.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        val trailingSlash = rawPath.endsWith('/') && segments.isNotEmpty()
        return "/" + segments.joinToString("/") + if (trailingSlash) "/$suffix" else suffix
    }

    private data class ParsedHttpUrl(
        val scheme: String,
        val authority: String,
        val path: String,
    )

    private sealed interface ImageFetchResult {
        data class Body(val bytes: ByteArray) : ImageFetchResult
        data class Redirect(val url: String?) : ImageFetchResult
        data class Failed(val reason: ImageFetchOutcome.Reason) : ImageFetchResult
    }

    /** 响应体受控读取结果: Ok / 超限 / 空。 */
    private sealed interface BodyReadResult {
        data class Ok(val bytes: ByteArray) : BodyReadResult
        data object Oversize : BodyReadResult
        data object Empty : BodyReadResult
    }

    private suspend fun readLimitedImage(channel: ByteReadChannel, maxBytes: Long): BodyReadResult {
        try {
            // 从 64KiB 起按需倍增，主体缓冲最多到 limit；额外 1 字节用独立探针判断超限，
            // 避免刚好 32MiB 时为 limit+1 再复制一整块大数组。
            if (maxBytes <= 0L) return BodyReadResult.Oversize
            val cap = maxBytes.toInt()
            var bytes = ByteArray(minOf(INITIAL_IMAGE_READ_BUFFER_SIZE, cap))
            var total = 0
            while (total < cap) {
                if (total == bytes.size) {
                    val expandedSize = (bytes.size * 2).coerceAtMost(cap)
                    bytes = bytes.copyOf(expandedSize)
                }
                val read = channel.readAvailable(bytes, total, bytes.size - total)
                if (read <= 0) break
                total += read
            }
            if (total == cap) {
                val probe = ByteArray(1)
                if (channel.readAvailable(probe, 0, 1) > 0) return BodyReadResult.Oversize
            }
            if (total == 0) return BodyReadResult.Empty
            return BodyReadResult.Ok(if (total == bytes.size) bytes else bytes.copyOf(total))
        } finally {
            channel.cancel(null)
        }
    }

    private const val DEFAULT_MAX_IMAGE_BYTES = 4L * 1024L * 1024L
    private const val MAX_IMAGE_BYTES = 32L * 1024L * 1024L
    private const val INITIAL_IMAGE_READ_BUFFER_SIZE = 64 * 1024
    private const val MAX_REDIRECTS = 3
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val DEFAULT_RATE_LIMIT_BACKOFF_SECONDS = 30L
    private const val MAX_RATE_LIMIT_BACKOFF_SECONDS = 60L
}

/**
 * 在线图片下载失败日志(两平台下载器共用, 成功不记防噪声):
 * `在线图片下载失败 url=<url> 原因=<reason>`; 命中 429 时额外附退避剩余毫秒。
 */
internal suspend fun logFetchFailure(
    logger: AppLogger?,
    reason: RemoteImageFetcher.ImageFetchOutcome.Reason,
    remoteUrl: String,
) {
    if (logger == null) return
    val rateLimitNote = if (
        reason is RemoteImageFetcher.ImageFetchOutcome.Reason.HttpError &&
        reason.statusCode == HTTP_TOO_MANY_REQUESTS
    ) {
        val remaining = RemoteImageFetcher.rateLimitBackoffRemainingMsForUrl(remoteUrl)
        ", 限流退避剩余=${remaining}ms"
    } else {
        ""
    }
    logger.appEvent(
        "online-image",
        "在线图片下载失败 url=<$remoteUrl> 原因=<${reason.description}>$rateLimitNote",
        LogLevel.WARN,
    )
}

private const val HTTP_TOO_MANY_REQUESTS = 429
