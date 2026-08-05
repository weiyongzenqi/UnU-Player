package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.danmaku.Crypto

internal fun buildDirectPlayPlan(
    session: MediaServerSession,
    request: MediaServerPlaybackRequest,
    playbackInfo: MediaServerPlaybackInfo,
    authenticationHeaders: Map<String, String>,
): MediaServerPlaybackPlan {
    val connectionId = session.connectionId?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("媒体服务器会话缺少连接 ID")
    val source = if (request.mediaSourceId == null) {
        playbackInfo.mediaSources.firstOrNull { it.supportsDirectPlay }
    } else {
        playbackInfo.mediaSources.firstOrNull {
            it.id == request.mediaSourceId && it.supportsDirectPlay
        }
    } ?: throw MediaServerPlaybackUnavailableException(playbackInfo.errorCode)

    val streamName = source.container
        ?.takeIf { it.matches(CONTAINER_PATTERN) }
        ?.let { "stream.$it" }
        ?: "stream"
    val url = buildMediaServerUrl(
        session.apiBaseUrl,
        listOf("Videos", request.itemId, streamName),
        mapOf(
            "Static" to "true",
            "MediaSourceId" to source.id,
            "PlaySessionId" to playbackInfo.playSessionId,
            "DeviceId" to session.client.deviceId,
        ),
    )
    check(resolveMediaServerResourceUrl(session.apiBaseUrl, url, session.accessToken) == url) {
        "媒体服务器播放地址无效"
    }

    val requiredHeaders = source.requiredHttpHeaders.filter { (name, value) ->
        // mpv http-header-fields 是无转义的逗号分隔列表(STRING_LIST): 头值含逗号会被 mpv 拆成
        // 非法头行, 服务器直接 400(与已修的认证头换行拆分同根因), 故逗号随 CR/LF 一并滤除。
        name.matches(HTTP_HEADER_NAME_PATTERN) &&
            '\r' !in value && '\n' !in value && ',' !in value &&
            name.lowercase() !in RESERVED_PLAYBACK_HEADERS
    }
    val subtitles = source.mediaStreams.mapNotNull { stream ->
        if (!stream.isExternal || stream.index < 0) return@mapNotNull null
        val subtitleUrl = resolveMediaServerResourceUrl(
            session.apiBaseUrl,
            stream.deliveryUrl,
            session.accessToken,
        ) ?: return@mapNotNull null
        MediaServerExternalSubtitle(
            streamIndex = stream.index,
            url = subtitleUrl,
            title = stream.displayTitle,
            language = stream.language,
            codec = stream.codec,
        )
    }
    return MediaServerPlaybackPlan(
        vendor = session.vendor,
        connectionId = connectionId,
        serverId = session.serverId,
        userId = session.userId,
        itemId = request.itemId,
        mediaSourceId = source.id,
        playSessionId = playbackInfo.playSessionId,
        playMethod = MediaServerPlayMethod.DIRECT_PLAY,
        url = url,
        headers = requiredHeaders + authenticationHeaders,
        externalSubtitles = subtitles,
        defaultSubtitleStreamIndex = source.defaultSubtitleStreamIndex,
        initialPositionMs = request.startPositionMs,
    )
}

internal fun buildImageRequest(
    session: MediaServerSession,
    itemId: String,
    imageType: MediaServerImageType,
    imageIndex: Int?,
    imageTag: String?,
    maxWidth: Int?,
    maxHeight: Int?,
    authenticationHeaders: Map<String, String>,
): MediaServerImageRequest {
    validateImageParameters(itemId, imageIndex, maxWidth, maxHeight)
    val path = buildList {
        add("Items")
        add(itemId)
        add("Images")
        add(imageType.wireName)
        imageIndex?.let { add(it.toString()) }
    }
    val url = buildMediaServerUrl(
        session.apiBaseUrl,
        path,
        mapOf(
            "tag" to imageTag?.takeIf { it.isNotBlank() },
            "maxWidth" to maxWidth?.toString(),
            "maxHeight" to maxHeight?.toString(),
        ),
    )
    return MediaServerImageRequest(
        url = url,
        headers = authenticationHeaders,
        cacheKey = buildImageCacheKey(
            vendor = session.vendor,
            serverId = session.serverId,
            itemId = itemId,
            imageType = imageType,
            imageIndex = imageIndex,
            imageTag = imageTag,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
        ),
    )
}

internal fun buildImageReference(
    vendor: MediaServerVendor,
    serverId: String,
    itemId: String,
    imageType: MediaServerImageType,
    imageIndex: Int?,
    imageTag: String?,
    maxWidth: Int?,
    maxHeight: Int?,
): MediaServerImageReference {
    validateImageParameters(itemId, imageIndex, maxWidth, maxHeight)
    return MediaServerImageReference(
        itemId = itemId,
        imageType = imageType,
        imageIndex = imageIndex,
        imageTag = imageTag,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        cacheKey = buildImageCacheKey(
            vendor = vendor,
            serverId = serverId,
            itemId = itemId,
            imageType = imageType,
            imageIndex = imageIndex,
            imageTag = imageTag,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
        ),
    )
}

private fun validateImageParameters(
    itemId: String,
    imageIndex: Int?,
    maxWidth: Int?,
    maxHeight: Int?,
) {
    require(itemId.isNotBlank()) { "图片 item ID 不能为空" }
    require(imageIndex == null || imageIndex >= 0) { "图片序号不能为负数" }
    require(maxWidth == null || maxWidth in 1..MAX_IMAGE_DIMENSION) { "图片宽度超出范围" }
    require(maxHeight == null || maxHeight in 1..MAX_IMAGE_DIMENSION) { "图片高度超出范围" }
}

private fun buildImageCacheKey(
    vendor: MediaServerVendor,
    serverId: String,
    itemId: String,
    imageType: MediaServerImageType,
    imageIndex: Int?,
    imageTag: String?,
    maxWidth: Int?,
    maxHeight: Int?,
): String = Crypto.sha256Base64(listOf(
    vendor.name,
    serverId,
    itemId,
    imageType.wireName,
    imageIndex?.toString().orEmpty(),
    imageTag.orEmpty(),
    maxWidth?.toString().orEmpty(),
    maxHeight?.toString().orEmpty(),
).joinToString(IMAGE_CACHE_KEY_SEPARATOR))
    .replace('+', '-')
    .replace('/', '_')
    .trimEnd('=')

internal class MediaServerPlaybackUnavailableException(
    val errorCode: String?,
) : MediaServerException("媒体服务器未返回可直放版本")

private val CONTAINER_PATTERN = Regex("[A-Za-z0-9]+")
private val HTTP_HEADER_NAME_PATTERN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
private const val MAX_IMAGE_DIMENSION = 4_096
private const val IMAGE_CACHE_KEY_SEPARATOR = "\u001f"
private val RESERVED_PLAYBACK_HEADERS = setOf(
    "authorization",
    "connection",
    "content-length",
    "host",
    "keep-alive",
    "proxy-authorization",
    "proxy-connection",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
    "x-emby-authorization",
    "x-emby-token",
)
