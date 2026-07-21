package io.github.weiyongzenqi.unuplayer.webdav

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile
import io.github.weiyongzenqi.unuplayer.core.platform.deletePlatformFile
import io.github.weiyongzenqi.unuplayer.core.platform.openPlatformFileOutputStream
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.domain.WebDavSearchScope
import io.github.weiyongzenqi.unuplayer.domain.WebDavSearchTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import io.github.weiyongzenqi.unuplayer.core.network.readPrefixAndCancel
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching

/**
 * WebDAV 客户端。参考 NipaPlay webdav_service.dart 的双轨实现, 用 Ktor 替代 Dart http。
 *
 * 双轨:
 * - 主路径: 标准 PROPFIND(Depth:1 + XML body)
 * - 回退 legacy: 5 种 PROPFIND 变体 + 根地址子路径(/dav /webdav)
 *
 * 自动 HTTPS -> HTTP 降级会把 Basic 凭据发送到明文连接, 当前不启用。
 */
class WebDavClient(
    private val httpClient: HttpClient,
    val baseUrl: String,
    private val username: String,
    private val password: String,
    private val fallbackRequestIntervalMs: Long = DEFAULT_FALLBACK_REQUEST_INTERVAL_MS,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val propfindStateMutex = Mutex()
    private var preferredPropfindCandidate: PropfindRequestCandidate? = null

    /** 标准 PROPFIND 请求体(NipaPlay webdav_service.dart:951-959)。 */
    private val propfindBody = """<?xml version="1.0" encoding="utf-8" ?>
        |<D:propfind xmlns:D="DAV:">
        |  <D:prop>
        |    <D:displayname/>
        |    <D:getcontentlength/>
        |    <D:getlastmodified/>
        |    <D:resourcetype/>
        |  </D:prop>
        |</D:propfind>""".trimMargin()

    // === 主路径: 标准 PROPFIND ===

    suspend fun listDirectory(path: String): List<MediaEntry> {
        val entries = propfind(path)
        // 浏览用: 只保留目录 + 视频文件
        return withContext(cpuDispatcher) {
            entries.filter { it.isDirectory || isVideoFile(it.name) }
        }
    }

    /**
     * PROPFIND 列一层(Depth:1), 返回全量条目(已去请求目录自身, 不过滤类型)。
     * 浏览用 [listDirectory](过滤视频), 搜索用 [listDirectoryAll](全量统计)。
     */
    private suspend fun propfind(path: String): List<MediaEntry> {
        val candidates = buildPropfindCandidates(baseUrl)
        val preferred = propfindStateMutex.withLock { preferredPropfindCandidate }
        val orderedCandidates = preferred?.let { preferredCandidate ->
            listOf(preferredCandidate) + candidates.filterNot { it == preferredCandidate }
        } ?: candidates
        var lastFailure = "没有可用候选"

        for ((index, candidate) in orderedCandidates.withIndex()) {
            if (index > 0 && fallbackRequestIntervalMs > 0L) {
                delay(fallbackRequestIntervalMs)
            }

            val url = buildWebDavRequestUrl(candidate.baseUrl, path)
            val response = try {
                httpClient.prepareRequest(url) {
                    method = HttpMethod("PROPFIND")
                    header("Depth", candidate.depth)
                    header("Content-Type", candidate.contentType)
                    authHeader().takeIf { it.isNotEmpty() }?.let { header("Authorization", it) }
                    if (candidate.includeBody) setBody(propfindBody)
                }.execute { httpResponse ->
                    val channel = httpResponse.bodyAsChannel()
                    try {
                        httpResponse.status to if (httpResponse.status.isSuccess()) {
                            readLimitedText(channel)
                        } else {
                            null
                        }
                    } finally {
                        channel.cancel(null)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: WebDavException) {
                throw error
            } catch (error: Throwable) {
                throw WebDavException("PROPFIND 连接失败: $url (${error.message ?: error::class.simpleName})", error)
            }

            val responseStatus = response.first
            val statusCode = responseStatus.value
            if (statusCode == 401 || statusCode == 403) {
                throw WebDavException("PROPFIND 认证失败: $responseStatus; 已终止兼容回退")
            }
            if (!responseStatus.isSuccess()) {
                lastFailure = "${candidate.description} 返回 $responseStatus"
                if (statusCode !in RETRYABLE_PROPFIND_STATUS_CODES) {
                    throw WebDavException("PROPFIND 失败: $lastFailure")
                }
                continue
            }

            val body = checkNotNull(response.second)
            val entries = try {
                withContext(cpuDispatcher) { parsePropfindResponse(body) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastFailure = "${candidate.description} 响应解析失败: ${error.message ?: error::class.simpleName}"
                continue
            }
            propfindStateMutex.withLock {
                preferredPropfindCandidate = candidate.takeUnless { it == candidates.first() }
            }
            return withContext(cpuDispatcher) {
                val filtered = filterWebDavSelfEntry(candidate.baseUrl, path, entries)
                if (candidate.baseUrl.trimEnd('/') == baseUrl.trim().trimEnd('/')) {
                    filtered
                } else {
                    filtered.map { entry ->
                        entry.copy(path = normalizeFallbackHref(candidate.baseUrl, path, entry.path))
                    }
                }
            }
        }

        throw WebDavException(
            "PROPFIND 兼容回退失败: 已尝试 ${orderedCandidates.size} 个同源候选; 最后错误: $lastFailure",
        )
    }

    /** 全量列举(供搜索统计 searchedCount), 不过滤类型。 */
    suspend fun listDirectoryAll(path: String): List<MediaEntry> = propfind(path)

    /**
     * 递归搜索文件(移植自 NipaPlay webdav_service.dart searchFiles, DFS 协程)。
     *
     * - scope: CURRENT_DIRECTORY=只列起点一层(depthLimit 覆写 0);
     *   CURRENT_WITH_DEPTH=从 startPath 按 depthLimit 递归;
     *   GLOBAL=从 / 开始(仍受 depthLimit 约束, 非无限深)。
     * - depthLimit=N 列 N+1 层(判断 currentDepth > depthLimit 返回)。
     * - 关键词大小写不敏感包含匹配, 只匹配 file.name。
     * - 限流: 非首层且 requestIntervalMs>0 时 delay(每两次 PROPFIND 之间)。
     * - 超时: 墙钟比较, 超时 return 保留已收集结果(不抛异常)。
     * - 停止: onStopRequested getter 回调多处检查; maxResults 由调用方经此反馈。
     */
    suspend fun searchFiles(
        keyword: String,
        startPath: String,
        scope: WebDavSearchScope,
        depthLimit: Int,
        searchTargets: Set<WebDavSearchTarget>,
        timeoutSeconds: Int,
        requestIntervalMs: Int,
        connectionId: String,
        onProgress: ((searched: Int, found: Int) -> Unit)? = null,
        onResultFound: ((WebDavSearchResult) -> Unit)? = null,
        onStopRequested: (() -> Boolean)? = null,
    ): List<WebDavSearchResult> {
        val kw = keyword.trim().lowercase()
        if (kw.isEmpty()) return emptyList()

        val actualStartPath = when (scope) {
            WebDavSearchScope.CURRENT_DIRECTORY -> startPath
            WebDavSearchScope.CURRENT_WITH_DEPTH -> startPath
            WebDavSearchScope.GLOBAL -> "/"
        }
        val effectiveDepth = if (scope == WebDavSearchScope.CURRENT_DIRECTORY) 0 else depthLimit

        val results = mutableListOf<WebDavSearchResult>()
        var searched = 0
        val startTime = platformTimeMillis()
        fun timedOut(): Boolean =
            timeoutSeconds > 0 && (platformTimeMillis() - startTime) / 1000 >= timeoutSeconds

        suspend fun recurse(currentPath: String, currentDepth: Int) {
            if (onStopRequested?.invoke() == true) return
            if (timedOut()) return
            if (currentDepth > effectiveDepth) return

            if (currentDepth > 0 && requestIntervalMs > 0) {
                delay(requestIntervalMs.toLong())
            }

            val files = runSuspendCatching { listDirectoryAll(currentPath) }.getOrDefault(emptyList())
            if (onStopRequested?.invoke() == true) return
            searched += files.size

            // 目录内的大小写归一化、目标过滤和关键词匹配是纯 CPU 工作；只把结果
            // 交回调用者上下文，避免把 UI 回调也挪到 Default。
            val matched = withContext(cpuDispatcher) {
                BooleanArray(files.size) { index ->
                    val file = files[index]
                    matchesTarget(file, searchTargets) && file.name.lowercase().contains(kw)
                }
            }
            for (index in files.indices) {
                val file = files[index]
                if (onStopRequested?.invoke() == true) return
                if (timedOut()) return

                if (matched[index]) {
                    val result = WebDavSearchResult(
                        file = file,
                        fullPath = file.path,
                        relativePath = relativePath(file.path, actualStartPath),
                        connectionId = connectionId,
                    )
                    results.add(result)
                    onResultFound?.invoke(result)
                    onProgress?.invoke(searched, results.size)
                    if (onStopRequested?.invoke() == true) return
                }

                if (file.isDirectory) {
                    recurse(file.path, currentDepth + 1)
                }
                if ((index and 0x7F) == 0) yield()
            }
            onProgress?.invoke(searched, results.size)
        }

        recurse(actualStartPath, 0)
        return results
    }

    /** 搜索目标过滤: 文件夹看 FOLDER, 视频看 VIDEO+扩展名。 */
    private fun matchesTarget(file: MediaEntry, targets: Set<WebDavSearchTarget>): Boolean {
        if (file.isDirectory) return WebDavSearchTarget.FOLDER in targets
        return WebDavSearchTarget.VIDEO in targets && isVideoFile(file.name)
    }

    /** fullPath 相对 basePath 的路径; 不以 base 开头则去前导 /。 */
    private fun relativePath(fullPath: String, basePath: String): String {
        val base = basePath.trimEnd('/')
        return when {
            base.isEmpty() -> fullPath.trimStart('/')
            fullPath.startsWith("$base/") -> fullPath.substring(base.length + 1)
            else -> fullPath.trimStart('/')
        }
    }

    // === 连通性测试 ===

    suspend fun ping(): Boolean = runSuspendCatching {
        listDirectory("/")
        true
    }.getOrDefault(false)

    // === 播放 URL + 认证头 ===

    /**
     * 生成播放 URL(纯 URL, 不含凭据)。
     *
     * 认证改为通过 http-header-fields 的 Authorization 头传给 mpv(见 authHeader),
     * 不再用 URL 内嵌 user:pass@host —— 后者 mpv 对 percent-encoding 解码不可靠,
     * 密码含特殊字符时无法播放。纯 URL 也避免凭据出现在日志/历史里。
     *
     * path 来源是 PROPFIND 的 href, 三种形态都要正确处理:
     * - 绝对 URL(http(s)://...): 原样返回。
     * - 服务器绝对路径(以 / 开头, 含 mount, 如 123pan 的 /webdav/番剧/x.mkv):
     *   按 RFC 3986 URL 解析, 绝对路径"替换 base 的整个 path" → 用 scheme://host + path,
     *   否则 base(/webdav) + path(/webdav/...) = /webdav/webdav/...(双层, 实测 123pan 踩坑)。
     * - 相对路径(不以 / 开头): base + / + path。
     * 路径按段 percent-encode(先 decode 再 encode, 防止对已编码 href 双重编码),
     * 对齐 NipaPlay 的 %E7%95%AA... 形态, 提高 ffmpeg HTTP 兼容性。
     */
    fun resolvePlayUrl(path: String): String {
        return resolveWebDavUrl(baseUrl, path)
    }

    /**
     * 播放用的 HTTP 头(含 Authorization)。空凭据返回空 map(匿名)。
     * 传给 engine, 在 init 前通过 setOptionString("http-header-fields", ...) 设给 mpv。
     */
    fun playHeaders(): Map<String, String> {
        val auth = authHeader()
        return if (auth.isEmpty()) emptyMap() else mapOf("Authorization" to auth)
    }

    /**
     * 拉远程文件前 16MB 算弹弹play 哈希(Range GET)。
     *
     * 用 Range: bytes=0-16777215 拉前 16MB, 从 Content-Range/Content-Length 取文件大小,
     * MD5(前 16MB) -> hex 小写。文件 < 16MB 哈希整个文件。失败返回 null(调用方走回退)。
     *
     * @param path 文件路径(同 [resolvePlayUrl] 的 path)
     * @return (fileSize, fileHash); null=请求失败
     */
    suspend fun fetchRangeForHash(path: String): Pair<Long, String>? = try {
        val url = resolvePlayUrl(path)
        val limit = 16 * 1024 * 1024  // 16MB
        httpClient.prepareRequest(url) {
            method = HttpMethod.Get
            header("Range", "bytes=0-${limit - 1}")
            // 匿名(空凭据)时不发空 Authorization 头, 个别服务器/代理会拒绝空头。
            authHeader().takeIf { it.isNotEmpty() }?.let { header("Authorization", it) }
        }.execute { resp ->
            val channel = resp.bodyAsChannel()
            try {
                // 206 Partial Content(支持 Range)或 200(忽略 Range、返回完整文件)均可接受。
                if (!resp.status.isSuccess()) {
                    null
                } else {
                    // 有 Content-Range(206 分片)只认真实 total; total 未知("bytes 0-N/*")或畸形返回
                    // null, 绝不回退 Content-Length —— 206 的 Content-Length 是分片长度而非文件总长, 回退会
                    // 喂错 fileSize 给弹弹 match。仅无 Content-Range(200 完整响应)时才用 Content-Length。
                    val contentRange = resp.headers["Content-Range"]
                    val fileSize = if (contentRange != null) {
                        parseContentRangeTotal(contentRange)
                    } else {
                        resp.headers["Content-Length"]?.toLongOrNull()
                    }
                    if (fileSize == null) {
                        null
                    } else {
                        val bytes = readPrefixAndCancel(channel, limit)
                        val hash = io.github.weiyongzenqi.unuplayer.danmaku.Crypto.md5Hex(bytes)
                        Pair(fileSize, hash)
                    }
                }
            } finally {
                channel.cancel(null)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    /**
     * 下载远程文件到本地(字幕等小文件, 全量 GET 写盘)。失败返回 false。
     *
     * @param path 文件路径(同 [resolvePlayUrl] 的 path, PROPFIND href)
     * @param dest 目标平台文件
     */
    suspend fun downloadTo(
        path: String,
        dest: PlatformFile,
        maxBytes: Long = Long.MAX_VALUE,
    ): Boolean {
        var channel: io.ktor.utils.io.ByteReadChannel? = null
        try {
            if (maxBytes < 0L) {
                deletePlatformFile(dest.path)
                return false
            }
            val url = resolvePlayUrl(path)
            return httpClient.prepareRequest(url) {
                method = HttpMethod.Get
                // 匿名(空凭据)时不发空 Authorization 头, 个别服务器/代理会拒绝空头。
                authHeader().takeIf { it.isNotEmpty() }?.let { header("Authorization", it) }
            }.execute { resp ->
                val responseChannel = resp.bodyAsChannel()
                channel = responseChannel
                try {
                    if (!resp.status.isSuccess()) {
                        deletePlatformFile(dest.path)
                        return@execute false
                    }
                    val contentLength = resp.headers["Content-Length"]?.toLongOrNull()
                    if (contentLength != null && contentLength > maxBytes) {
                        deletePlatformFile(dest.path)
                        return@execute false
                    }

                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var exceeded = false
                    val output = openPlatformFileOutputStream(dest.path)
                    try {
                        while (true) {
                            val read = responseChannel.readAvailable(buffer)
                            if (read <= 0) break
                            if (total > maxBytes - read.toLong()) {
                                exceeded = true
                                break
                            }
                            output.write(buffer, 0, read)
                            total += read
                        }
                    } finally {
                        output.close()
                    }
                    if (exceeded) deletePlatformFile(dest.path)
                    !exceeded
                } finally {
                    responseChannel.cancel(null)
                }
            }
        } catch (cancelled: CancellationException) {
            runCatching { channel?.cancel(cancelled) }
            deletePlatformFile(dest.path)
            throw cancelled
        } catch (error: Throwable) {
            runCatching { channel?.cancel(error) }
            deletePlatformFile(dest.path)
            return false
        } finally {
            runCatching { channel?.cancel(null) }
        }
    }

    /**
     * DELETE 文件/目录(WebDAV DELETE, 删 collection 递归删内容, RFC 4918)。
     * @param path 同 [resolvePlayUrl] 的 path(PROPFIND href)
     * @return true=2xx 成功; false=失败/异常
     */
    suspend fun delete(path: String): Boolean = try {
        val url = resolvePlayUrl(path)
        httpClient.prepareRequest(url) {
            method = HttpMethod("DELETE")
            // 匿名(空凭据)时不发空 Authorization 头, 个别服务器/代理会拒绝空头。
            authHeader().takeIf { it.isNotEmpty() }?.let { header("Authorization", it) }
        }.execute { resp ->
            val channel = resp.bodyAsChannel()
            try {
                resp.status.isSuccess()
            } finally {
                channel.cancel(null)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    /**
     * 拉远程小文件文本(nfo/ini 解析用, 全量 GET)。
     * 带基本认证, 8MiB 限量(复用 readLimitedText 防 OOM)。失败/不存在返回 null。
     * @param path 文件路径(同 resolvePlayUrl 的 path, PROPFIND href)
     */
    suspend fun fetchText(path: String): String? = try {
        val url = resolvePlayUrl(path)
        httpClient.prepareRequest(url) {
            method = HttpMethod.Get
            // 匿名(空凭据)时不发空 Authorization 头, 个别服务器/代理会拒绝空头。
            authHeader().takeIf { it.isNotEmpty() }?.let { header("Authorization", it) }
        }.execute { resp ->
            val channel = resp.bodyAsChannel()
            try {
                if (!resp.status.isSuccess()) null else readLimitedText(channel)
            } finally {
                channel.cancel(null)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    // === 内部 ===

    /**
     * HTTP Basic Auth 头: "Basic base64(user:pass)"。
     * PROPFIND 列目录请求必须带(原裸请求致 401)。用原始 user/pass(非 urlEncode),
     * 服务器 base64 decode 后得到原始凭据。空凭据返回空串(匿名)。
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun authHeader(): String =
        if (username.isEmpty()) ""
        else "Basic " + Base64.encode("$username:$password".encodeToByteArray())

    /**
     * 限量读取响应体为文本(上限 8MiB)。
     * 防恶意/异常服务器返回超大 PROPFIND 响应耗尽内存。
     */
    private suspend fun readLimitedText(channel: io.ktor.utils.io.ByteReadChannel): String {
        // 先把响应字节完整读入内存(上限 8MiB), 再一次性按 UTF-8 整体解码。
        // 整体解码不存在"多字节字符横跨块边界"问题(不像逐块 decodeToString 会产生 U+FFFD)。
        // 缓冲从小起步、写满才按需倍增(封顶 limit+1 用于探到超限), 避免每次 PROPFIND
        // 无条件分配整块 8MiB+1(低内存机 GC 压力); 内存峰值 ≈ 实际响应大小(上限内), 对目录列举可接受。
        return try {
            withContext(cpuDispatcher) {
                val limit = 8 * 1024 * 1024
                var buffer = ByteArray(INITIAL_LIMITED_READ_BUFFER_SIZE)
                var totalRead = 0
                while (true) {
                    // 缓冲写满才扩容; 已到 limit+1 仍写满 => 后面还有数据, 与原实现一致判定超限。
                    if (totalRead == buffer.size) {
                        val expandedSize = (buffer.size * 2).coerceAtMost(limit + 1)
                        if (expandedSize == buffer.size) break
                        buffer = buffer.copyOf(expandedSize)
                    }
                    val n = channel.readAvailable(buffer, totalRead, buffer.size - totalRead)
                    if (n <= 0) break
                    totalRead += n
                }
                if (totalRead > limit) {
                    throw WebDavException("PROPFIND 响应超过 ${limit / 1024 / 1024}MiB 上限")
                }
                buffer.decodeToString(0, totalRead)
            }
        } finally {
            channel.cancel(null)
        }
    }
}

/**
 * 解析 Content-Range "bytes 0-16777215/754553960" 的 total(754553960)。
 * 头部缺失、无 "/"、或 total 未知(斜杠后为星号)/畸形 => null。调用方此时不得回退
 * Content-Length(206 响应里它只是分片长度), 否则会把分片长度当成文件总长喂错弹弹 match。
 */
internal fun parseContentRangeTotal(contentRange: String?): Long? {
    if (contentRange == null) return null
    val slash = contentRange.indexOf('/')
    if (slash < 0 || slash == contentRange.length - 1) return null
    return contentRange.substring(slash + 1).trim().toLongOrNull()
}

private const val DEFAULT_FALLBACK_REQUEST_INTERVAL_MS = 75L
private const val MAX_PROPFIND_CANDIDATES = 15

/** readLimitedText 起始缓冲: 小目录响应无需一次性分配 8MiB+1, 按需倍增到上限。 */
private const val INITIAL_LIMITED_READ_BUFFER_SIZE = 64 * 1024

private val RETRYABLE_PROPFIND_STATUS_CODES = setOf(
    400, // 部分服务器拒绝特定 Depth/body 组合
    404, // 用户只填 origin 时继续探测 /dav、/webdav
    405,
    415,
    501,
)

private data class PropfindVariant(
    val depth: String,
    val contentType: String,
    val includeBody: Boolean,
)

private val PROPFIND_VARIANTS = listOf(
    PropfindVariant("1", "text/xml; charset=\"utf-8\"", includeBody = true),
    PropfindVariant("0", "text/xml; charset=\"utf-8\"", includeBody = true),
    PropfindVariant("1", "text/xml; charset=\"utf-8\"", includeBody = false),
    PropfindVariant("1", "application/xml", includeBody = true),
    PropfindVariant("0", "application/xml", includeBody = true),
)

internal data class PropfindRequestCandidate(
    val baseUrl: String,
    val depth: String,
    val contentType: String,
    val includeBody: Boolean,
) {
    val description: String
        get() = "$baseUrl [Depth=$depth, Content-Type=$contentType, body=$includeBody]"
}

/**
 * 生成有限且同源的 PROPFIND 候选链。原地址已有挂载路径时不猜测子路径，避免把
 * 凭据发送到用户未配置的位置；只有纯 origin 才探测 /dav 与 /webdav。
 *
 * /dav 与 /dav/（/webdav 同理）在列目录路径拼接后请求 URL 相同，因此主动去重，
 * 最坏为 3 个地址 x 5 个变体 = 15 次，不会无限重试。
 */
internal fun buildPropfindCandidates(baseUrl: String): List<PropfindRequestCandidate> {
    val normalizedBase = baseUrl.trim().trimEnd('/')
    val parsed = WEB_DAV_BASE_URL.matchEntire(normalizedBase)
    val bases = buildList {
        add(normalizedBase)
        if (parsed != null && parsed.groupValues[3].trim('/').isEmpty()) {
            val origin = "${parsed.groupValues[1]}://${parsed.groupValues[2]}"
            add("$origin/dav")
            add("$origin/webdav")
        }
    }.distinct()

    return bases.flatMap { candidateBase ->
        PROPFIND_VARIANTS.map { variant ->
            PropfindRequestCandidate(
                baseUrl = candidateBase,
                depth = variant.depth,
                contentType = variant.contentType,
                includeBody = variant.includeBody,
            )
        }
    }.take(MAX_PROPFIND_CANDIDATES)
}

private val WEB_DAV_BASE_URL = Regex(
    pattern = "^(https?)://([^/?#]+)(/[^?#]*)?$",
    option = RegexOption.IGNORE_CASE,
)

private fun buildWebDavRequestUrl(baseUrl: String, path: String): String {
    // path 来源两种: 浏览用 name 拼的 mount-relative(如 /anime/); 搜索递归/跳转用
    // PROPFIND href(服务器绝对路径, 含 mount, 如 /webdav/anime/Season 1/)。后者若直接
    // base+path 会双层 mount(/webdav/webdav/...)致 404, 故含 mount 时用 origin+path。
    val base = baseUrl.trimEnd('/')
    val cleanPath = if (path.startsWith("/")) path else "/$path"
    val afterScheme = base.substringAfter("://")
    val host = afterScheme.substringBefore('/')
    val basePath = afterScheme.substringAfter('/', "")
    return if (basePath.isNotEmpty() &&
        (cleanPath == "/$basePath" || cleanPath.startsWith("/$basePath/"))) {
        val origin = "${base.substringBefore("://")}://$host"
        "$origin$cleanPath"
    } else {
        "$base$cleanPath"
    }
}

/** 根 origin 探测到 /dav 或 /webdav 后，按实际请求目录解析服务器返回的相对 href。 */
private fun normalizeFallbackHref(candidateBaseUrl: String, requestPath: String, href: String): String {
    if (href.startsWith('/') || href.startsWith("http://", true) || href.startsWith("https://", true)) {
        return href
    }
    val requestUrl = buildWebDavRequestUrl(candidateBaseUrl, requestPath)
    val resourceDirectory = requestUrl.substringAfter("://").substringAfter('/', "").trim('/')
    val relativeHref = href.trimStart('/')
    return if (resourceDirectory.isEmpty()) "/$relativeHref" else "/$resourceDirectory/$relativeHref"
}

/**
 * 只过滤与请求目录精确相同的 PROPFIND response。WebDAV 服务器可能不返回 self、
 * 把 self 放在任意位置，或分别使用绝对 URL、带挂载点路径、挂载点相对路径。
 */
internal fun filterWebDavSelfEntry(
    baseUrl: String,
    requestPath: String,
    entries: List<MediaEntry>,
): List<MediaEntry> {
    val requested = canonicalWebDavResourceUrl(resolveWebDavUrl(baseUrl, requestPath))
    return entries.filter { entry ->
        entry.name.isNotEmpty() &&
            canonicalWebDavResourceUrl(resolveWebDavUrl(baseUrl, entry.path)) != requested
    }
}

internal fun resolveWebDavUrl(baseUrl: String, path: String): String {
    val base = baseUrl.trimEnd('/')
    return when {
        path.startsWith("http://", true) || path.startsWith("https://", true) -> path
        path.startsWith("/") -> {
            val afterScheme = base.substringAfter("://")
            val host = afterScheme.substringBefore('/')
            val basePath = afterScheme.substringAfter('/', "")
            val origin = "${base.substringBefore("://")}://$host"
            val normalized = normalizeWebDavPath(path)
            if (basePath.isNotEmpty() && (path == "/$basePath" || path.startsWith("/$basePath/"))) {
                "$origin$normalized"
            } else {
                "$base$normalized"
            }
        }
        else -> "$base/${normalizeWebDavPath(path)}"
    }
}

/** 忽略查询、片段、尾斜杠、host 大小写与等价 percent-encoding，仅用于资源身份比较。 */
private fun canonicalWebDavResourceUrl(url: String): String {
    val withoutFragment = url.substringBefore('#').substringBefore('?')
    val schemeIndex = withoutFragment.indexOf("://")
    if (schemeIndex < 0) return canonicalWebDavPath(withoutFragment)
    val scheme = withoutFragment.substring(0, schemeIndex).lowercase()
    val remainder = withoutFragment.substring(schemeIndex + 3)
    val authority = remainder.substringBefore('/').lowercase()
    val path = remainder.substringAfter('/', "")
    return "$scheme://$authority${canonicalWebDavPath("/$path")}"
}

private fun canonicalWebDavPath(path: String): String {
    val decoded = percentDecodeWebDav(path).replace('\\', '/')
    val normalized = decoded.split('/').fold(mutableListOf<String>()) { parts, segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(segment)
        }
        parts
    }
    return if (normalized.isEmpty()) "/" else "/${normalized.joinToString("/")}"
}

/**
 * 路径规范化：先 percent-decode，再按段重新编码，避免对服务器 href 重复编码。
 */
private fun normalizeWebDavPath(path: String): String =
    percentDecodeWebDav(path).split('/').joinToString("/") { percentEncodeWebDavSegment(it) }

private fun percentDecodeWebDav(value: String): String {
    val out = StringBuilder()
    val bytes = ArrayList<Byte>()
    fun flushBytes() {
        if (bytes.isNotEmpty()) {
            out.append(ByteArray(bytes.size) { bytes[it] }.decodeToString())
            bytes.clear()
        }
    }
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val byte = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (byte != null) {
                bytes.add(byte.toByte())
                index += 3
                continue
            }
        }
        flushBytes()
        out.append(char)
        index++
    }
    flushBytes()
    return out.toString()
}

private val WEB_DAV_HEX_CHARS = "0123456789ABCDEF".toCharArray()

private fun percentEncodeWebDavSegment(segment: String): String {
    if (segment.isEmpty()) return ""
    val encoded = StringBuilder()
    for (byte in segment.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xFF
        val char = unsigned.toChar()
        val unreserved = unsigned in 0x30..0x39 || unsigned in 0x41..0x5A || unsigned in 0x61..0x7A ||
            char == '-' || char == '.' || char == '_' || char == '~'
        if (unreserved) {
            encoded.append(char)
        } else {
            encoded.append('%')
            encoded.append(WEB_DAV_HEX_CHARS[unsigned ushr 4])
            encoded.append(WEB_DAV_HEX_CHARS[unsigned and 0xF])
        }
    }
    return encoded.toString()
}

/** WebDAV 异常。 */
class WebDavException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 从 WebDavConnection 构造客户端的便捷工厂。 */
fun WebDavConnection.toClient(httpClient: HttpClient): WebDavClient =
    WebDavClient(httpClient, baseUrl, username, password)
