package io.github.weiyongzenqi.unuplayer.bangumi.comment

import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.webdav.createStrictHttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BangumiAvatarRepository {
    private val httpClient by lazy(::createStrictHttpClient)
    private val cache = CommentMemoryCache<String, ByteArray>(
        ttlMillis = AVATAR_CACHE_TTL_MILLIS,
        maxEntries = AVATAR_CACHE_MAX_ENTRIES,
        nowMillis = ::platformTimeMillis,
    )

    suspend fun load(url: String): ByteArray = withContext(Dispatchers.IO) {
        cache.getOrLoad(url, refresh = false) {
            httpClient.prepareGet(url) {
                header(HttpHeaders.Accept, "image/avif,image/webp,image/png,image/jpeg")
                io.github.weiyongzenqi.unuplayer.bangumi.bangumiImageRequestHeaders(url)
                    .forEach { (name, value) -> header(name, value) }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    response.bodyAsChannel().cancel(null)
                    throw BangumiAvatarException("头像请求失败：HTTP ${response.status.value}")
                }
                val contentType = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';').lowercase()
                if (!contentType.startsWith("image/")) {
                    response.bodyAsChannel().cancel(null)
                    throw BangumiAvatarException("头像响应不是图片")
                }
                val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (declaredLength != null && declaredLength > MAX_AVATAR_BYTES) {
                    response.bodyAsChannel().cancel(null)
                    throw BangumiAvatarException("头像响应超过大小上限")
                }
                readLimitedAvatar(response.bodyAsChannel())
            }
        }
    }
}

private suspend fun readLimitedAvatar(channel: ByteReadChannel): ByteArray = try {
    readLimitedImageBytes(channel, MAX_AVATAR_BYTES)
} catch (_: ImageBytesLimitExceededException) {
    throw BangumiAvatarException("头像响应超过大小上限")
}

/**
 * 限长读取图片响应体(头像与评论内容图片共用):
 * 预分配 maxBytes+1, readAvailable 循环读取; 实际读到超过 maxBytes 抛 [ImageBytesLimitExceededException],
 * 最后 cancel 通道。
 */
internal suspend fun readLimitedImageBytes(channel: ByteReadChannel, maxBytes: Int): ByteArray = try {
    val bytes = ByteArray(maxBytes + 1)
    var total = 0
    while (total < bytes.size) {
        val read = channel.readAvailable(bytes, total, bytes.size - total)
        if (read <= 0) break
        total += read
    }
    if (total > maxBytes) throw ImageBytesLimitExceededException()
    bytes.copyOf(total)
} finally {
    channel.cancel(null)
}

internal class ImageBytesLimitExceededException : Exception()

private class BangumiAvatarException(message: String) : Exception(message)

private const val MAX_AVATAR_BYTES = 256 * 1024
private const val AVATAR_CACHE_MAX_ENTRIES = 64
private const val AVATAR_CACHE_TTL_MILLIS = 30L * 60L * 1000L
