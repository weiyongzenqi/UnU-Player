package io.github.weiyongzenqi.unuplayer.mediaserver

interface MediaServerApi {
    val vendor: MediaServerVendor

    suspend fun getPublicInfo(
        baseUrl: String,
        allowCleartext: Boolean = false,
    ): MediaServerPublicInfo

    suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String,
        client: MediaServerClientIdentity,
        allowCleartext: Boolean = false,
    ): MediaServerSession

    suspend fun listLibraries(session: MediaServerSession): List<MediaServerLibrary>

    suspend fun listItems(
        session: MediaServerSession,
        query: MediaServerItemsQuery,
    ): MediaServerPage<MediaServerItem>

    /**
     * 单条目详情(播放边界取 ProviderIds/SeriesId/SeasonId 用)。
     *
     * 路径形态: `GET {base}/Users/{userId}/Items/{itemId}`(Jellyfin/Emby 一致)。
     * 默认返回完整 DTO 含 ProviderIds(列表端点即使 Fields=ProviderIds 也常为空, 故必须详情端点)。
     * 失败抛 [MediaServerHttpException]/[MediaServerProtocolException]; 调用方 runSuspendCatching 静默降级。
     */
    suspend fun getItemDetail(
        session: MediaServerSession,
        itemId: String,
    ): MediaServerItemDetail

    suspend fun getPlaybackInfo(
        session: MediaServerSession,
        request: MediaServerPlaybackRequest,
    ): MediaServerPlaybackInfo

    suspend fun preparePlayback(
        session: MediaServerSession,
        request: MediaServerPlaybackRequest,
    ): MediaServerPlaybackPlan

    fun imageRequest(
        session: MediaServerSession,
        itemId: String,
        imageType: MediaServerImageType = MediaServerImageType.PRIMARY,
        imageIndex: Int? = null,
        imageTag: String? = null,
        maxWidth: Int? = null,
        maxHeight: Int? = null,
    ): MediaServerImageRequest

    suspend fun reportPlaybackStarted(session: MediaServerSession, state: MediaServerPlaybackState)

    suspend fun reportPlaybackProgress(session: MediaServerSession, state: MediaServerPlaybackState)

    suspend fun reportPlaybackStopped(
        session: MediaServerSession,
        state: MediaServerPlaybackState,
        failed: Boolean,
    )

    suspend fun logout(session: MediaServerSession)
}

object MediaServerApiFactory {
    fun create(vendor: MediaServerVendor): MediaServerApi = when (vendor) {
        MediaServerVendor.JELLYFIN -> JellyfinApiAdapter()
        MediaServerVendor.EMBY -> EmbyApiAdapter()
    }
}

internal fun requireSessionVendor(session: MediaServerSession, vendor: MediaServerVendor) {
    require(session.vendor == vendor) { "媒体服务器会话类型不匹配" }
}

internal fun requireSuccessfulResponse(
    request: MediaServerHttpRequest,
    response: MediaServerHttpResponse,
) {
    if (response.statusCode !in 200..299) {
        throw MediaServerHttpException(request.operation, response.statusCode)
    }
}

internal fun credentialFreeUrlOrNull(value: String?, accessToken: String): String? {
    val url = value?.takeIf { it.isNotBlank() } ?: return null
    if (accessToken.isNotBlank() && url.contains(accessToken)) return null
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return url
    val queryEnd = url.indexOf('#', queryStart).let { if (it < 0) url.length else it }
    val hasCredential = url.substring(queryStart + 1, queryEnd)
        .split('&')
        .any { part ->
            val name = part.substringBefore('=').trim()
            isMediaServerCredentialParameter(name)
        }
    return url.takeUnless { hasCredential }
}

internal fun isMediaServerCredentialParameter(name: String): Boolean =
    name.equals("api_key", ignoreCase = true) ||
        name.equals("apikey", ignoreCase = true) ||
        name.equals("access_token", ignoreCase = true) ||
        name.equals("token", ignoreCase = true) ||
        name.equals("x-emby-token", ignoreCase = true)

internal fun mediaServerItemKind(type: String?, isFolder: Boolean): MediaServerItemKind = when {
    type.equals("Movie", ignoreCase = true) -> MediaServerItemKind.MOVIE
    type.equals("Series", ignoreCase = true) -> MediaServerItemKind.SERIES
    type.equals("Season", ignoreCase = true) -> MediaServerItemKind.SEASON
    type.equals("Episode", ignoreCase = true) -> MediaServerItemKind.EPISODE
    type.equals("Video", ignoreCase = true) -> MediaServerItemKind.VIDEO
    isFolder -> MediaServerItemKind.FOLDER
    else -> MediaServerItemKind.UNKNOWN
}

internal fun MediaServerItemKind.wireName(): String? = when (this) {
    MediaServerItemKind.FOLDER -> "Folder"
    MediaServerItemKind.MOVIE -> "Movie"
    MediaServerItemKind.SERIES -> "Series"
    MediaServerItemKind.SEASON -> "Season"
    MediaServerItemKind.EPISODE -> "Episode"
    MediaServerItemKind.VIDEO -> "Video"
    MediaServerItemKind.UNKNOWN -> null
}
