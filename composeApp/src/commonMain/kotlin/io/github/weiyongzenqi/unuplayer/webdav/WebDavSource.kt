package io.github.weiyongzenqi.unuplayer.webdav

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.MediaKeys
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile
import io.github.weiyongzenqi.unuplayer.danmaku.Crypto
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.domain.WebDavSearchScope
import io.github.weiyongzenqi.unuplayer.domain.WebDavSearchTarget

/**
 * WebDAV 媒体来源。MediaSource 的 P0 实现。
 *
 * 用 WebDavClient 列目录/测连通, 用 resolvePlayUrl 生成带 basic auth 的播放 URL。
 * 实现 AutoCloseable: HttpClient 由 [createHttpClient] 进程级共享单例提供,
 * close() 为 no-op(不 close 共享实例, 见 HttpClientFactory)。
 */
class WebDavSource(
    private val conn: WebDavConnection,
    private val httpClient: HttpClient = createHttpClient(),
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MediaSource {

    override val kind: MediaSourceKind = MediaSourceKind.WEBDAV
    override val displayName: String = conn.name

    private val client = WebDavClient(
        httpClient = httpClient,
        baseUrl = conn.baseUrl,
        username = conn.username,
        password = conn.password,
        cpuDispatcher = cpuDispatcher,
    )

    override suspend fun listFolder(path: String): List<MediaEntry> =
        client.listDirectory(path)

    /** 递归搜索(委托 client, 填连接 id)。参数语义见 WebDavClient.searchFiles。 */
    suspend fun searchFiles(
        keyword: String,
        startPath: String,
        scope: WebDavSearchScope,
        depthLimit: Int,
        searchTargets: Set<WebDavSearchTarget>,
        timeoutSeconds: Int,
        requestIntervalMs: Int,
        onProgress: ((searched: Int, found: Int) -> Unit)? = null,
        onResultFound: ((WebDavSearchResult) -> Unit)? = null,
        onStopRequested: (() -> Boolean)? = null,
    ): List<WebDavSearchResult> = client.searchFiles(
        keyword = keyword,
        startPath = startPath,
        scope = scope,
        depthLimit = depthLimit,
        searchTargets = searchTargets,
        timeoutSeconds = timeoutSeconds,
        requestIntervalMs = requestIntervalMs,
        connectionId = conn.id,
        onProgress = onProgress,
        onResultFound = onResultFound,
        onStopRequested = onStopRequested,
    )

    override suspend fun resolvePlayMedia(entry: MediaEntry): PlayableMedia =
        PlayableMedia(
            url = client.resolvePlayUrl(entry.path),
            headers = client.playHeaders(),   // Authorization: Basic, init 前设 http-header-fields
            title = entry.name,
            sourceKind = MediaSourceKind.WEBDAV,
            mediaKey = MediaKeys.webDav(conn.id, entry.path),
        )

    override suspend fun testConnection(): Boolean = client.ping()

    override suspend fun listFolderAll(path: String): List<MediaEntry> =
        client.listDirectoryAll(path)   // 已有, 全量不过滤

    override suspend fun readTextFile(path: String): String? = client.fetchText(path)

    override suspend fun downloadToFile(path: String, dest: PlatformFile): Boolean =
        client.downloadTo(path, dest)   // 已有

    override suspend fun deleteFile(path: String): Boolean = client.delete(path)

    /**
     * no-op: HttpClient 是进程级共享单例(createHttpClient), 不在此 close(会影响弹幕/远程哈希
     * 等所有调用方)。OkHttp 连接池随单例常驻复用, 非泄漏。保留 close() 满足 AutoCloseable 契约。
     */
    override fun close() {
        // 共享 HttpClient, 不 close
    }
}

/**
 * WebDAV 连接凭据指纹: 对 username + NUL + password 的拼接取 SHA-256(Base64)。
 *
 * 仅用于 MediaSourceCache 缓存身份比较——密码编辑后指纹变化即令缓存源失效重建。
 * 密码只进哈希, 不以明文进入身份/日志; 字段间用 NUL 分隔, 避免 username 含 ':' 时的拼接碰撞歧义。
 */
fun webDavCredentialsToken(connection: WebDavConnection): String =
    Crypto.sha256Base64(connection.username + Char.MIN_VALUE + connection.password)