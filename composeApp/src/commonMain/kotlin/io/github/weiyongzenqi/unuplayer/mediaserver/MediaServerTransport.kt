package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.webdav.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

internal enum class MediaServerHttpMethod { GET, POST }

internal data class MediaServerHttpRequest(
    val operation: String,
    val method: MediaServerHttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    override fun toString(): String =
        "MediaServerHttpRequest(operation=$operation, method=$method, url=<redacted>, " +
            "headers=<redacted>, body=${if (body == null) "null" else "<redacted>"})"
}

internal data class MediaServerHttpResponse(
    val statusCode: Int,
    val body: String,
) {
    override fun toString(): String =
        "MediaServerHttpResponse(statusCode=$statusCode, body=<redacted>)"
}

internal fun interface MediaServerTransport {
    suspend fun execute(request: MediaServerHttpRequest): MediaServerHttpResponse
}

internal class KtorMediaServerTransport(
    baseHttpClient: HttpClient = createHttpClient(),
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
) : MediaServerTransport, AutoCloseable {
    // Emby 的自定义 token 头不会被 Ktor 在跨源重定向时自动移除。
    private val httpClient = baseHttpClient.config {
        followRedirects = false
    }

    init {
        require(maxResponseBytes in 1 until Int.MAX_VALUE) { "响应上限必须在 1..${Int.MAX_VALUE - 1}" }
    }

    override suspend fun execute(request: MediaServerHttpRequest): MediaServerHttpResponse =
        httpClient.prepareRequest(request.url) {
            method = when (request.method) {
                MediaServerHttpMethod.GET -> HttpMethod.Get
                MediaServerHttpMethod.POST -> HttpMethod.Post
            }
            request.headers.forEach { (name, value) -> header(name, value) }
            request.body?.let(::setBody)
        }.execute { response ->
            MediaServerHttpResponse(
                statusCode = response.status.value,
                body = readLimitedBodyAndCancel(response.bodyAsChannel(), maxResponseBytes).decodeToString(),
            )
        }

    override fun close() {
        httpClient.close()
    }
}

private val sharedMediaServerTransportDelegate = lazy { KtorMediaServerTransport() }

internal val sharedMediaServerTransport: MediaServerTransport
    get() = sharedMediaServerTransportDelegate.value

internal fun closeSharedMediaServerTransport() {
    closeSharedMediaServerImageDownloader()
    if (sharedMediaServerTransportDelegate.isInitialized()) {
        sharedMediaServerTransportDelegate.value.close()
    }
}

internal open class MediaServerException(message: String) : Exception(message)

internal class MediaServerHttpException(
    val operation: String,
    val statusCode: Int,
) : MediaServerException("媒体服务器请求失败: operation=$operation, status=$statusCode")

internal class MediaServerProtocolException(
    operation: String,
) : MediaServerException("媒体服务器响应格式无效: operation=$operation")

private suspend fun readLimitedBodyAndCancel(
    channel: ByteReadChannel,
    limit: Int,
): ByteArray = try {
    val bytes = ByteArray(limit + 1)
    var total = 0
    while (total < bytes.size) {
        val read = channel.readAvailable(bytes, total, bytes.size - total)
        if (read <= 0) break
        total += read
    }
    if (total > limit) throw MediaServerException("媒体服务器响应超过大小上限")
    bytes.copyOf(total)
} finally {
    channel.cancel(null)
}

private const val DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024
