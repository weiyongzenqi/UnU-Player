package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile
import io.github.weiyongzenqi.unuplayer.core.platform.deletePlatformFile
import io.github.weiyongzenqi.unuplayer.core.platform.openPlatformFileOutputStream
import io.github.weiyongzenqi.unuplayer.webdav.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException

internal fun interface MediaServerImageDownloader {
    suspend fun download(request: MediaServerImageRequest, destination: PlatformFile): Boolean
}

/** 媒体服务器图片流式下载器；认证头请求禁止 30x，且任何失败都清理缓存层提供的 .part。 */
internal class KtorMediaServerImageDownloader(
    baseHttpClient: HttpClient = createHttpClient(),
    private val maxImageBytes: Long = DEFAULT_MAX_IMAGE_BYTES,
) : MediaServerImageDownloader, AutoCloseable {
    private val httpClient = baseHttpClient.config {
        followRedirects = false
    }

    init {
        require(maxImageBytes > 0L) { "图片响应上限必须大于 0" }
    }

    override suspend fun download(
        request: MediaServerImageRequest,
        destination: PlatformFile,
    ): Boolean {
        var channel: ByteReadChannel? = null
        try {
            deletePlatformFile(destination.path)
            return httpClient.prepareRequest(request.url) {
                method = HttpMethod.Get
                request.headers.forEach { (name, value) -> header(name, value) }
            }.execute { response ->
                val responseChannel = response.bodyAsChannel()
                channel = responseChannel
                try {
                    if (response.status.value !in 200..299) return@execute false
                    val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (contentLength != null && contentLength > maxImageBytes) return@execute false

                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    var exceeded = false
                    val output = openPlatformFileOutputStream(destination.path)
                    try {
                        while (true) {
                            val read = responseChannel.readAvailable(buffer)
                            if (read <= 0) break
                            if (total > maxImageBytes - read.toLong()) {
                                exceeded = true
                                break
                            }
                            output.write(buffer, 0, read)
                            total += read
                        }
                    } finally {
                        output.close()
                    }
                    if (exceeded || total == 0L) {
                        deletePlatformFile(destination.path)
                        false
                    } else {
                        true
                    }
                } finally {
                    responseChannel.cancel(null)
                }
            }
        } catch (cancelled: CancellationException) {
            runCatching { channel?.cancel(cancelled) }
            deletePlatformFile(destination.path)
            throw cancelled
        } catch (error: Throwable) {
            runCatching { channel?.cancel(error) }
            deletePlatformFile(destination.path)
            return false
        } finally {
            runCatching { channel?.cancel(null) }
        }
    }

    override fun close() {
        httpClient.close()
    }
}

private val sharedMediaServerImageDownloaderDelegate = lazy { KtorMediaServerImageDownloader() }

internal val sharedMediaServerImageDownloader: MediaServerImageDownloader
    get() = sharedMediaServerImageDownloaderDelegate.value

internal fun closeSharedMediaServerImageDownloader() {
    if (sharedMediaServerImageDownloaderDelegate.isInitialized()) {
        sharedMediaServerImageDownloaderDelegate.value.close()
    }
}

private const val BUFFER_SIZE = 64 * 1024
private const val DEFAULT_MAX_IMAGE_BYTES = 16L * 1024L * 1024L
