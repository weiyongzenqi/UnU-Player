package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class StoredMediaServerCatalogSource(
    private val connection: MediaServerConnectionSummary,
    private val repository: MediaServerConnectionRepository,
    private val client: MediaServerClientIdentity,
    private val api: MediaServerApi,
    private val imageDownloader: MediaServerImageDownloader = sharedMediaServerImageDownloader,
) : MediaCatalogSource {
    init {
        require(api.vendor == connection.vendor) { "媒体服务器 API 类型不匹配" }
    }

    override val kind: MediaSourceKind = connection.vendor.sourceKind
    override val displayName: String = connection.name

    private val sessionMutex = Mutex()
    private var session: MediaServerSession? = null

    override suspend fun testConnection(): MediaServerPublicInfo {
        val publicInfo = api.getPublicInfo(
            baseUrl = connection.baseUrl,
            allowCleartext = connection.baseUrl.startsWith("http://", ignoreCase = true),
        )
        sessionMutex.withLock {
            session = repository.createSession(connection.id, publicInfo, client)
        }
        return publicInfo
    }

    override suspend fun listLibraries(): List<MediaServerLibrary> =
        withSessionRetry { activeSession -> api.listLibraries(activeSession) }

    override suspend fun listItems(query: MediaServerItemsQuery): MediaServerPage<MediaServerItem> =
        withSessionRetry { activeSession -> api.listItems(activeSession, query) }

    override fun imageReference(
        itemId: String,
        imageType: MediaServerImageType,
        imageIndex: Int?,
        imageTag: String?,
        maxWidth: Int?,
        maxHeight: Int?,
    ): MediaServerImageReference = buildImageReference(
        vendor = connection.vendor,
        serverId = connection.serverId,
        itemId = itemId,
        imageType = imageType,
        imageIndex = imageIndex,
        imageTag = imageTag,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
    )

    override suspend fun downloadImage(
        reference: MediaServerImageReference,
        destination: PlatformFile,
    ): Boolean {
        val expected = imageReference(
            itemId = reference.itemId,
            imageType = reference.imageType,
            imageIndex = reference.imageIndex,
            imageTag = reference.imageTag,
            maxWidth = reference.maxWidth,
            maxHeight = reference.maxHeight,
        )
        require(reference == expected) { "图片引用不属于当前媒体服务器" }
        val session = requireSession()
        val request = api.imageRequest(
            session = session,
            itemId = reference.itemId,
            imageType = reference.imageType,
            imageIndex = reference.imageIndex,
            imageTag = reference.imageTag,
            maxWidth = reference.maxWidth,
            maxHeight = reference.maxHeight,
        )
        check(request.cacheKey == reference.cacheKey) { "媒体服务器图片缓存键不一致" }
        return imageDownloader.download(request, destination)
    }

    override suspend fun preparePlayback(request: MediaServerPlaybackRequest): MediaServerPlaybackPlan =
        withSessionRetry { activeSession -> api.preparePlayback(activeSession, request) }

    internal suspend fun preparePlaybackSession(
        request: MediaServerPlaybackRequest,
    ): MediaServerPreparedPlayback = withSessionRetry { activeSession ->
        val plan = api.preparePlayback(activeSession, request)
        // 弹幕 hint: 整段 runSuspendCatching 静默降级, 失败(含 detail 401/网络/解析) -> hint=null,
        // plan 照常返回, 播放不受影响。preparePlayback 本身仍受 withSessionRetry 401 重建保护;
        // detail 的 401 不向上抛(避免重跑 preparePlayback 生成新 PlaySessionId), 走 hint=null 回退哈希。
        val hint = runSuspendCatching { buildDanmakuHint(activeSession, request.itemId) }.getOrNull()
        MediaServerPreparedPlayback(
            plan = plan.copy(danmakuHint = hint),
            reporter = MediaServerSessionReporter(api, activeSession),
        )
    }

    /**
     * 组 [MediaServerDanmakuHint]:
     *  - EPISODE: episode detail 的 ProviderIds 通常空 -> 用 [MediaServerItemDetail.seriesId] 二跳查 series detail,
     *    从 series 的 ProviderIds["Tmdb"] 取系列级 id(dandanplay 吃系列级)。
     *  - MOVIE/VIDEO/其它: 直取自身 ProviderIds["Tmdb"]。
     *  - detail/二跳失败、无 Tmdb、seriesId null -> 对应字段 null, hint 整体可能仍非空(只带季集号/番名)。
     * 季集号(IndexNumber/ParentIndexNumber)取不到时为 null, 调用方回退文件名 extractSeason/extractEpisode。
     */
    private suspend fun buildDanmakuHint(
        session: MediaServerSession,
        itemId: String,
    ): MediaServerDanmakuHint {
        val detail = api.getItemDetail(session, itemId)
        val seriesTmdb = when (detail.kind) {
            MediaServerItemKind.EPISODE -> detail.seriesId?.let { sid ->
                runSuspendCatching { api.getItemDetail(session, sid) }
                    .getOrNull()?.providerIds?.get("Tmdb")?.toLongOrNull()
            }
            else -> detail.providerIds["Tmdb"]?.toLongOrNull()
        }
        return MediaServerDanmakuHint(
            tmdbId = seriesTmdb,
            seasonNumber = detail.parentIndexNumber,
            episodeNumber = detail.indexNumber,
            seriesName = detail.seriesName,
        )
    }

    override fun close() = Unit

    /**
     * 401 表示缓存会话的 token 已失效(服务端吊销或连接被重新添加换了新 token)。
     * 丢弃缓存会话并按仓库当前凭据重建一次；重建后仍 401 则向上抛给 UI 提示重新登录，
     * 不做进一步自动重试，避免对失效凭据反复打认证请求。
     */
    private suspend fun <T> withSessionRetry(block: suspend (MediaServerSession) -> T): T {
        val first = requireSession()
        return try {
            block(first)
        } catch (error: MediaServerHttpException) {
            if (error.statusCode != UNAUTHORIZED_STATUS) throw error
            sessionMutex.withLock { if (session === first) session = null }
            block(requireSession())
        }
    }

    private suspend fun requireSession(): MediaServerSession {
        session?.let { return it }
        return sessionMutex.withLock {
            session ?: run {
                val publicInfo = api.getPublicInfo(
                    baseUrl = connection.baseUrl,
                    allowCleartext = connection.baseUrl.startsWith("http://", ignoreCase = true),
                )
                repository.createSession(connection.id, publicInfo, client).also { session = it }
            }
        }
    }
}

internal const val UNAUTHORIZED_STATUS = 401
