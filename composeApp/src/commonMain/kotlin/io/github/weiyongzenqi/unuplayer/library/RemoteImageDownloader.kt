package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.webdav.createStrictHttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

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
    private const val USER_AGENT = "UnU-Player/0.1"
    private val httpClient by lazy(::createStrictHttpClient)

    suspend fun fetchImage(url: String, maxBytes: Long = DEFAULT_MAX_IMAGE_BYTES): ByteArray? {
        var currentUrl = url
        repeat(MAX_REDIRECTS + 1) {
            when (val result = runSuspendCatching {
                httpClient.prepareGet(currentUrl) {
                    header(HttpHeaders.Accept, "image/avif,image/webp,image/png,image/jpeg")
                    header(HttpHeaders.UserAgent, USER_AGENT)
                }.execute { response ->
                    if (response.status.value in 300..399) {
                        val location = response.headers[HttpHeaders.Location]
                        response.bodyAsChannel().cancel(null)
                        return@execute ImageFetchResult.Redirect(resolveRedirect(currentUrl, location))
                    }
                    if (!response.status.isSuccess()) {
                        response.bodyAsChannel().cancel(null)
                        return@execute ImageFetchResult.Failed
                    }
                    val contentType = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';').lowercase()
                    if (!contentType.startsWith("image/")) {
                        response.bodyAsChannel().cancel(null)
                        return@execute ImageFetchResult.Failed
                    }
                    val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (declaredLength != null && declaredLength > maxBytes) {
                        response.bodyAsChannel().cancel(null)
                        return@execute ImageFetchResult.Failed
                    }
                    ImageFetchResult.Body(readLimitedImage(response.bodyAsChannel(), maxBytes))
                }
            }.getOrNull() ?: ImageFetchResult.Failed) {
                is ImageFetchResult.Body -> return result.bytes
                is ImageFetchResult.Redirect -> currentUrl = result.url ?: return null
                ImageFetchResult.Failed -> return null
            }
        }
        return null
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
        data class Body(val bytes: ByteArray?) : ImageFetchResult
        data class Redirect(val url: String?) : ImageFetchResult
        data object Failed : ImageFetchResult
    }

    private suspend fun readLimitedImage(channel: ByteReadChannel, maxBytes: Long): ByteArray? {
        try {
            if (maxBytes >= Int.MAX_VALUE) return null
            val cap = maxBytes.toInt()
            val bytes = ByteArray(cap + 1)
            var total = 0
            while (total < bytes.size) {
                val read = channel.readAvailable(bytes, total, bytes.size - total)
                if (read <= 0) break
                total += read
            }
            return if (total > cap) null else bytes.copyOf(total)
        } finally {
            channel.cancel(null)
        }
    }

    private const val DEFAULT_MAX_IMAGE_BYTES = 4L * 1024L * 1024L
    private const val MAX_REDIRECTS = 3
}
