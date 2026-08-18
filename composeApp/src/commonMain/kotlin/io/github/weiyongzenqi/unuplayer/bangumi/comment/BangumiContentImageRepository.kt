package io.github.weiyongzenqi.unuplayer.bangumi.comment

import io.github.weiyongzenqi.unuplayer.bangumi.BangumiImageUrlPolicy
import io.github.weiyongzenqi.unuplayer.bangumi.bangumiContentImageUrlPolicy
import io.github.weiyongzenqi.unuplayer.core.network.APP_USER_AGENT
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.webdav.createStrictHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 评论/吐槽/讨论正文内联图片(表情包 gif、[img])加载仓库。
 * - per-URL 单飞 + 全局并发闸门(Semaphore 4);
 * - 下载任务由仓库级 supervisor 持有, 列表项离开视口不会取消已开始的请求;
 * - 手动跟随最多 [MAX_CONTENT_IMAGE_REDIRECTS] 次重定向, 每跳重新执行图片 URL 策略;
 * - 失败不缓存, TTL 10 分钟 / 32 条 / 总字节 ≤ 16MB / 单张 ≤ 2MB。
 */
internal class BangumiContentImageRepository(
    private val httpClient: HttpClient = createStrictHttpClient(),
    private val ttlMillis: Long = CONTENT_IMAGE_CACHE_TTL_MILLIS,
    private val maxEntries: Int = 32,
    private val nowMillis: () -> Long = ::platformTimeMillis,
) {
    private val cache = CommentMemoryCache<ContentImageKey, ByteArray>(
        ttlMillis = ttlMillis,
        maxEntries = maxEntries,
        maxWeight = MAX_CONTENT_IMAGE_CACHE_BYTES.toLong(),
        weightOf = { it.size.toLong() },
        nowMillis = nowMillis,
    )
    private val loadSlots = Semaphore(4)
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private val activeLoads = mutableMapOf<ContentImageKey, Deferred<ByteArray>>()

    suspend fun load(url: String, allowedHosts: Set<String> = DEFAULT_ALLOWED_IMAGE_HOSTS): ByteArray {
        val key = ContentImageKey(url, allowedHosts.sorted())
        val deferred = loadMutex.withLock {
            activeLoads[key] ?: loaderScope.async {
                try {
                    cache.getOrLoad(key, refresh = false) {
                        // 只限制真正成为 getOrLoad leader 的 fetch; 缓存命中和单飞跟随者不占槽位。
                        loadSlots.withPermit { fetchFollowingRedirects(url, allowedHosts) }
                    }
                } finally {
                    loadMutex.withLock { activeLoads.remove(key) }
                }
            }.also { activeLoads[key] = it }
        }
        // await 对调用方可取消, 但不会取消仓库级下载任务; 滚出 LazyColumn 后仍能完成并进入缓存。
        return deferred.await()
    }

    suspend fun shouldRestore(url: String, allowedHosts: Set<String>): Boolean {
        val key = ContentImageKey(url, allowedHosts.sorted())
        return cache.contains(key) || loadMutex.withLock { activeLoads.containsKey(key) }
    }

    suspend fun invalidate(url: String, allowedHosts: Set<String>) {
        cache.invalidate(ContentImageKey(url, allowedHosts.sorted()))
    }

    private suspend fun fetchFollowingRedirects(url: String, allowedHosts: Set<String>): ByteArray {
        var currentUrl = url
        val initialPolicy = bangumiContentImageUrlPolicy(currentUrl, allowedHosts)
        if (initialPolicy == BangumiImageUrlPolicy.REJECT) {
            throw BangumiContentImageException("内容图片地址不允许加载")
        }
        val requireTrustedRedirects = initialPolicy == BangumiImageUrlPolicy.AUTO_LOAD
        val visited = mutableSetOf<String>()

        repeat(MAX_CONTENT_IMAGE_REDIRECTS + 1) { hop ->
            if (!visited.add(currentUrl)) {
                throw BangumiContentImageException("内容图片重定向形成循环")
            }
            var loadedBytes: ByteArray? = null
            httpClient.prepareGet(currentUrl) {
                header(HttpHeaders.Accept, "image/*")
                header(HttpHeaders.UserAgent, APP_USER_AGENT)
                io.github.weiyongzenqi.unuplayer.bangumi.gatewayImageAuthHeaders(currentUrl)
                    .forEach { (name, value) -> header(name, value) }
            }.execute { response ->
                if (response.status.value in 300..399) {
                    val location = response.headers[HttpHeaders.Location]
                    response.bodyAsChannel().cancel(null)
                    val target = resolveBangumiRedirect(currentUrl, location)
                        ?: throw BangumiContentImageException("内容图片重定向地址无效")
                    val targetPolicy = bangumiContentImageUrlPolicy(target, allowedHosts)
                    if (
                        targetPolicy == BangumiImageUrlPolicy.REJECT ||
                        (requireTrustedRedirects && targetPolicy != BangumiImageUrlPolicy.AUTO_LOAD)
                    ) {
                        throw BangumiContentImageException("内容图片重定向目标不允许加载")
                    }
                    if (hop == MAX_CONTENT_IMAGE_REDIRECTS) {
                        throw BangumiContentImageException("内容图片重定向次数超过上限")
                    }
                    currentUrl = target
                    return@execute
                }
                if (!response.status.isSuccess()) {
                    response.bodyAsChannel().cancel(null)
                    throw BangumiContentImageException("内容图片请求失败：HTTP ${response.status.value}")
                }
                val contentType = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';').lowercase()
                if (!contentType.startsWith("image/")) {
                    response.bodyAsChannel().cancel(null)
                    throw BangumiContentImageException("内容图片响应不是图片")
                }
                val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (declaredLength != null && declaredLength > MAX_CONTENT_IMAGE_BYTES) {
                    response.bodyAsChannel().cancel(null)
                    throw BangumiContentImageException("内容图片响应超过大小上限")
                }
                try {
                    loadedBytes = readLimitedImageBytes(response.bodyAsChannel(), MAX_CONTENT_IMAGE_BYTES)
                } catch (_: ImageBytesLimitExceededException) {
                    throw BangumiContentImageException("内容图片响应超过大小上限")
                }
            }
            loadedBytes?.let { return it }
        }
        throw BangumiContentImageException("内容图片重定向次数超过上限")
    }
}

private data class ContentImageKey(val url: String, val allowedHosts: List<String>)

private fun resolveBangumiRedirect(baseUrl: String, location: String?): String? {
    val target = location?.trim().orEmpty()
    if (target.isEmpty()) return null
    val base = runCatching { Url(baseUrl) }.getOrNull() ?: return null
    val origin = "${base.protocol.name}://${base.host}${base.port.takeIf { it != 0 }?.let { ":$it" }.orEmpty()}"
    val resolved = when {
        target.startsWith("http://", true) || target.startsWith("https://", true) -> target
        target.startsWith("//") -> "${base.protocol.name}:$target"
        target.startsWith('/') -> origin + normalizeBangumiPath(target)
        target.startsWith('?') -> origin + base.encodedPath + target
        else -> {
            val directory = base.encodedPath.substringBeforeLast('/', missingDelimiterValue = "") + "/"
            origin + normalizeBangumiPath(directory + target)
        }
    }
    val parsed = runCatching { Url(resolved) }.getOrNull() ?: return null
    if (parsed.protocol.name !in setOf("http", "https")) return null
    if (base.protocol.name == "https" && parsed.protocol.name != "https") return null
    return resolved.substringBefore('#')
}

private fun normalizeBangumiPath(value: String): String {
    val suffixIndex = listOf(value.indexOf('?'), value.indexOf('#')).filter { it >= 0 }.minOrNull() ?: value.length
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
    return "/" + segments.joinToString("/") + suffix
}

private class BangumiContentImageException(message: String) : Exception(message)

private const val MAX_CONTENT_IMAGE_BYTES = 2 * 1024 * 1024
private const val MAX_CONTENT_IMAGE_CACHE_BYTES = 16 * 1024 * 1024
private const val MAX_CONTENT_IMAGE_REDIRECTS = 4
private const val CONTENT_IMAGE_CACHE_TTL_MILLIS = 10L * 60L * 1000L

private val DEFAULT_ALLOWED_IMAGE_HOSTS = setOf("lain.bgm.tv")

private val sharedBangumiContentImageRepository by lazy { BangumiContentImageRepository() }

/** 加载 Bangumi 评论正文内联图片(表情包/[img]), 走进程级共享单例(缓存 + 并发闸门)。 */
internal suspend fun loadBangumiContentImage(
    url: String,
    allowedHosts: Set<String> = DEFAULT_ALLOWED_IMAGE_HOSTS,
): ByteArray = sharedBangumiContentImageRepository.load(url, allowedHosts)

internal suspend fun shouldRestoreBangumiContentImage(url: String, allowedHosts: Set<String>): Boolean =
    sharedBangumiContentImageRepository.shouldRestore(url, allowedHosts)

internal suspend fun invalidateBangumiContentImage(url: String, allowedHosts: Set<String>) =
    sharedBangumiContentImageRepository.invalidate(url, allowedHosts)
