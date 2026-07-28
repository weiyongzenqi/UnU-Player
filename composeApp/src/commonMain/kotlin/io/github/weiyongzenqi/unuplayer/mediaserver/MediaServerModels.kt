package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile

enum class MediaServerVendor(val sourceKind: MediaSourceKind) {
    JELLYFIN(MediaSourceKind.JELLYFIN),
    EMBY(MediaSourceKind.EMBY),
}

data class MediaServerClientIdentity(
    val clientName: String,
    val clientVersion: String,
    val deviceName: String,
    val deviceId: String,
) {
    init {
        require(clientName.isNotBlank()) { "客户端名称不能为空" }
        require(clientVersion.isNotBlank()) { "客户端版本不能为空" }
        require(deviceName.isNotBlank()) { "设备名称不能为空" }
        require(deviceId.isNotBlank()) { "设备 ID 不能为空" }
    }
}

data class MediaServerPublicInfo(
    val vendor: MediaServerVendor,
    val serverId: String,
    val serverName: String,
    val version: String,
    val productName: String?,
    val apiBaseUrl: String,
)

/** 已登录会话。覆盖 toString，避免 data class 展开 access token。 */
data class MediaServerSession(
    val vendor: MediaServerVendor,
    val apiBaseUrl: String,
    val serverId: String,
    val serverVersion: String?,
    val userId: String,
    val username: String,
    val accessToken: String,
    val client: MediaServerClientIdentity,
    val connectionId: String? = null,
) {
    init {
        require(serverId.isNotBlank()) { "服务器 ID 不能为空" }
        require(userId.isNotBlank()) { "用户 ID 不能为空" }
        require(accessToken.isNotBlank()) { "访问令牌不能为空" }
    }

    override fun toString(): String =
        "MediaServerSession(vendor=$vendor, connectionId=$connectionId, apiBaseUrl=<redacted>, serverId=$serverId, " +
            "serverVersion=$serverVersion, userId=$userId, username=$username, accessToken=<redacted>, " +
            "client=$client)"
}

data class MediaServerLibrary(
    val id: String,
    val name: String,
    val collectionType: String?,
    val primaryImageTag: String?,
)

enum class MediaServerItemKind {
    FOLDER,
    MOVIE,
    SERIES,
    SEASON,
    EPISODE,
    VIDEO,
    UNKNOWN,
}

data class MediaServerItemsQuery(
    val parentId: String? = null,
    val startIndex: Int = 0,
    val limit: Int = 100,
    val recursive: Boolean = false,
    val includeItemTypes: Set<MediaServerItemKind> = emptySet(),
    val searchTerm: String? = null,
) {
    init {
        require(startIndex >= 0) { "分页起点不能为负数" }
        require(limit in 1..MAX_MEDIA_SERVER_PAGE_SIZE) {
            "分页大小必须在 1..$MAX_MEDIA_SERVER_PAGE_SIZE"
        }
    }
}

data class MediaServerPage<T>(
    val items: List<T>,
    val startIndex: Int,
    val limit: Int,
    val totalRecordCount: Int?,
    val returnedItemCount: Int = items.size,
) {
    init {
        require(startIndex >= 0) { "分页起点不能为负数" }
        require(limit > 0) { "分页大小必须大于 0" }
        require(totalRecordCount == null || totalRecordCount >= 0) { "总条目数不能为负数" }
        require(returnedItemCount >= items.size) { "服务端返回条目数不能小于有效条目数" }
    }

    val nextStartIndex: Int
        get() = (startIndex.toLong() + returnedItemCount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    val hasMore: Boolean
        get() = returnedItemCount > 0 &&
            (totalRecordCount?.let { nextStartIndex.toLong() < it.toLong() } ?: (returnedItemCount >= limit))
}

data class MediaServerUserData(
    val playbackPositionMs: Long,
    val played: Boolean,
    val playedPercentage: Double?,
)

data class MediaServerItem(
    val id: String,
    val name: String,
    val kind: MediaServerItemKind,
    val isFolder: Boolean,
    val mediaType: String?,
    val container: String?,
    val runTimeMs: Long?,
    val overview: String?,
    val productionYear: Int?,
    val seriesName: String?,
    val indexNumber: Int?,
    val parentIndexNumber: Int?,
    val primaryImageTag: String?,
    val userData: MediaServerUserData?,
)

/**
 * 单条目详情(播放边界 [MediaServerApi.getItemDetail] 用)。
 *
 * 与 [MediaServerItem] 的区别: 带 [providerIds](刮削 Provider 映射, 如 `{Tmdb: 285574}`)、
 * [seriesId]/[seasonId](供二跳查系列/季)。其余字段沿用 [MediaServerItemKind] 与季集号。
 * 全字段可空: 服务端元数据缺失时降级为 null, 不影响播放。
 */
data class MediaServerItemDetail(
    val id: String,
    val kind: MediaServerItemKind,
    val providerIds: Map<String, String> = emptyMap(),
    val seriesId: String? = null,
    val seasonId: String? = null,
    val seriesName: String? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
)

/**
 * 媒体服务器弹幕 hint(挂进 [MediaServerPlaybackPlan] 进程内传递, 不进 Intent/SavedState)。
 *
 * - [tmdbId]: 系列级 TMDB id(EPISODE 经 seriesId 二跳查 series detail 的 ProviderIds["Tmdb"];
 *   MOVIE/VIDEO 直取自身 ProviderIds["Tmdb"]); dandanplay `search/episodes?tmdbId=` 吃系列级 id。
 * - [seasonNumber]: 季号(ParentIndexNumber); null 时调用方回退文件名 extractSeason。
 * - [episodeNumber]: 集号(IndexNumber); null 时调用方回退文件名 extractEpisode。
 * - [seriesName]: 备用番剧名(手动匹配预填关键词等场景)。
 * 全字段可空: 取不到任何字段则 hint=null, 弹幕链原样回落哈希, 不影响播放。
 * 非秘密元数据, 不含 token/URL; toString 原样列出安全。
 */
data class MediaServerDanmakuHint(
    val tmdbId: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val seriesName: String? = null,
)

data class MediaServerPlaybackRequest(
    val itemId: String,
    val mediaSourceId: String? = null,
    val startPositionMs: Long = 0,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val maxStreamingBitrate: Long? = null,
) {
    init {
        require(itemId.isNotBlank()) { "媒体 item ID 不能为空" }
        require(startPositionMs >= 0) { "播放起点不能为负数" }
        require(maxStreamingBitrate == null || maxStreamingBitrate > 0) { "最大码率必须大于 0" }
    }
}

/** 可跨 UI/Activity 传递的无秘密播放定位；真实 URL、认证头和播放会话仅在播放器内重建。 */
data class MediaServerPlaybackLocator(
    val connectionId: String,
    val itemId: String,
    val title: String,
    val startPositionMs: Long = 0,
) {
    init {
        require(connectionId.isNotBlank()) { "媒体服务器连接 ID 不能为空" }
        require(itemId.isNotBlank()) { "媒体 item ID 不能为空" }
        require(startPositionMs >= 0) { "播放起点不能为负数" }
    }
}

data class MediaServerPlaybackInfo(
    val playSessionId: String?,
    val errorCode: String? = null,
    val mediaSources: List<MediaServerMediaSource>,
) {
    override fun toString(): String =
        "MediaServerPlaybackInfo(playSessionId=<redacted>, errorCode=$errorCode, mediaSources=$mediaSources)"
}

enum class MediaServerPlayMethod(val wireName: String) {
    DIRECT_PLAY("DirectPlay"),
    DIRECT_STREAM("DirectStream"),
    TRANSCODE("Transcode"),
}

data class MediaServerExternalSubtitle(
    val streamIndex: Int,
    val url: String,
    val title: String?,
    val language: String?,
    val codec: String?,
) {
    override fun toString(): String =
        "MediaServerExternalSubtitle(streamIndex=$streamIndex, url=<redacted>, title=$title, " +
            "language=$language, codec=$codec)"
}

data class MediaServerPlaybackPlan(
    val vendor: MediaServerVendor,
    val connectionId: String,
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String?,
    val playMethod: MediaServerPlayMethod,
    val url: String,
    val headers: Map<String, String>,
    val externalSubtitles: List<MediaServerExternalSubtitle>,
    val initialPositionMs: Long,
    // 弹幕匹配 hint(系列级 tmdbId + 季集号); 非秘密元数据, 失败 null 不影响播放。
    val danmakuHint: MediaServerDanmakuHint? = null,
) {
    override fun toString(): String =
        "MediaServerPlaybackPlan(vendor=$vendor, connectionId=$connectionId, itemId=$itemId, " +
            "mediaSourceId=$mediaSourceId, playSessionId=<redacted>, playMethod=$playMethod, " +
            "url=<redacted>, headers=<redacted>, externalSubtitles=$externalSubtitles, " +
            "initialPositionMs=$initialPositionMs, danmakuHint=$danmakuHint)"
}

/** 仅存在于播放器进程内；reporter 持有解密会话，但不向 Android 壳暴露。 */
class MediaServerPreparedPlayback internal constructor(
    val plan: MediaServerPlaybackPlan,
    internal val reporter: MediaServerSessionReporter,
) {
    override fun toString(): String = "MediaServerPreparedPlayback(plan=$plan, reporter=<redacted>)"
}

enum class MediaServerImageType(val wireName: String) {
    PRIMARY("Primary"),
    BACKDROP("Backdrop"),
    THUMB("Thumb"),
    LOGO("Logo"),
}

data class MediaServerImageRequest(
    val url: String,
    val headers: Map<String, String>,
    val cacheKey: String,
) {
    override fun toString(): String =
        "MediaServerImageRequest(url=<redacted>, headers=<redacted>, cacheKey=$cacheKey)"
}

/** UI 可持有的无凭据图片引用；实际 URL 与认证头仅在 catalog 下载边界内生成。 */
data class MediaServerImageReference(
    val itemId: String,
    val imageType: MediaServerImageType,
    val imageIndex: Int?,
    val imageTag: String?,
    val maxWidth: Int?,
    val maxHeight: Int?,
    val cacheKey: String,
)

data class MediaServerPlaybackState(
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String?,
    val playMethod: MediaServerPlayMethod,
    val positionMs: Long,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val isPaused: Boolean,
    val isMuted: Boolean,
    val canSeek: Boolean = true,
) {
    init {
        require(itemId.isNotBlank()) { "媒体 item ID 不能为空" }
        require(mediaSourceId.isNotBlank()) { "媒体源 ID 不能为空" }
        require(positionMs >= 0) { "播放位置不能为负数" }
    }

    override fun toString(): String =
        "MediaServerPlaybackState(itemId=$itemId, mediaSourceId=$mediaSourceId, " +
            "playSessionId=<redacted>, playMethod=$playMethod, positionMs=$positionMs, " +
            "audioStreamIndex=$audioStreamIndex, subtitleStreamIndex=$subtitleStreamIndex, " +
            "isPaused=$isPaused, isMuted=$isMuted, canSeek=$canSeek)"
}

data class MediaServerMediaSource(
    val id: String,
    val name: String?,
    val container: String?,
    val runTimeMs: Long?,
    val supportsDirectPlay: Boolean,
    val supportsDirectStream: Boolean,
    val supportsTranscoding: Boolean,
    val directStreamUrl: String?,
    val transcodingUrl: String?,
    val requiredHttpHeaders: Map<String, String>,
    val defaultAudioStreamIndex: Int?,
    val defaultSubtitleStreamIndex: Int?,
    val mediaStreams: List<MediaServerMediaStream>,
) {
    override fun toString(): String =
        "MediaServerMediaSource(id=$id, name=$name, container=$container, runTimeMs=$runTimeMs, " +
            "supportsDirectPlay=$supportsDirectPlay, supportsDirectStream=$supportsDirectStream, " +
            "supportsTranscoding=$supportsTranscoding, directStreamUrl=<redacted>, " +
            "transcodingUrl=<redacted>, requiredHttpHeaders=<redacted>, " +
            "defaultAudioStreamIndex=$defaultAudioStreamIndex, " +
            "defaultSubtitleStreamIndex=$defaultSubtitleStreamIndex, mediaStreams=$mediaStreams)"
}

data class MediaServerMediaStream(
    val index: Int,
    val type: String?,
    val codec: String?,
    val language: String?,
    val displayTitle: String?,
    val isExternal: Boolean,
    val deliveryMethod: String?,
    val deliveryUrl: String?,
    val supportsExternalStream: Boolean,
) {
    override fun toString(): String =
        "MediaServerMediaStream(index=$index, type=$type, codec=$codec, language=$language, " +
            "displayTitle=$displayTitle, isExternal=$isExternal, deliveryMethod=$deliveryMethod, " +
            "deliveryUrl=<redacted>, supportsExternalStream=$supportsExternalStream)"
}

interface MediaCatalogSource : AutoCloseable {
    val kind: MediaSourceKind
    val displayName: String

    suspend fun testConnection(): MediaServerPublicInfo
    suspend fun listLibraries(): List<MediaServerLibrary>
    suspend fun listItems(query: MediaServerItemsQuery): MediaServerPage<MediaServerItem>
    fun imageReference(
        itemId: String,
        imageType: MediaServerImageType = MediaServerImageType.PRIMARY,
        imageIndex: Int? = null,
        imageTag: String? = null,
        maxWidth: Int? = null,
        maxHeight: Int? = null,
    ): MediaServerImageReference
    suspend fun downloadImage(reference: MediaServerImageReference, destination: PlatformFile): Boolean
    suspend fun preparePlayback(request: MediaServerPlaybackRequest): MediaServerPlaybackPlan
}

internal const val MAX_MEDIA_SERVER_PAGE_SIZE = 500

internal fun ticksToMilliseconds(ticks: Long?): Long? = ticks?.coerceAtLeast(0)?.div(TICKS_PER_MILLISECOND)

internal fun millisecondsToTicks(milliseconds: Long): Long {
    require(milliseconds >= 0) { "毫秒值不能为负数" }
    require(milliseconds <= Long.MAX_VALUE / TICKS_PER_MILLISECOND) { "毫秒值超出 ticks 范围" }
    return milliseconds * TICKS_PER_MILLISECOND
}

private const val TICKS_PER_MILLISECOND = 10_000L
