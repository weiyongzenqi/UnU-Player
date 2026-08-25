package io.github.weiyongzenqi.unuplayer.core.media

/**
 * 可播放媒体。播放器只认这个, 不关心来源是 WebDAV/SMB/Emby/本地/外部。
 *
 * 认证策略(见 DESIGN.md §6.4, 2026-06-25 更新):
 * - WebDAV basic auth 通过 Authorization 头传给 mpv(http-header-fields, init 前设),
 *   不再用 URL 内嵌 user:pass@host(mpv 对 percent-encoding 解码不可靠, 特殊字符密码会失败)。
 * - headers 保留为高级/兼容选项(当前播放路径用 init 时注入的 http-header-fields, 此字段预留)。
 */
data class AnimePlaybackContext(
    val seriesTitle: String,
    val episodeTitle: String? = null,
    val episodeDescription: String? = null,
    val bangumiSubjectId: Long? = null,
    val bangumiEpisodeOffset: Long = 0L,
    /** 本地媒体库季度坐标；与 TMDB 合并季坐标分开，专供弹幕等按原始季度组织的数据源使用。 */
    val localSeasonNumber: Long? = null,
    /** 本地媒体库集坐标；Ani-RSS 漂移只影响 TMDB 映射，不得改写此值。 */
    val localEpisodeNumber: Long? = null,
    /** 当前季在线刮削已经确认的弹弹 animeId。 */
    val dandanplayAnimeId: Long? = null,
    /**
     * 当前集在已验证的 TMDB 映射内无对应集号(先行篇/第0话等, 如 Bangumi 收录而 TMDB 缺失)。
     * 此类集的各源话数体系互相分裂, 弹幕匹配自动优先文件哈希。
     */
    val episodeOutsideTmdb: Boolean = false,
)

fun resolveDanmakuSeasonHint(
    animeContext: AnimePlaybackContext?,
    mediaSeasonNumber: Long?,
    fallbackSeasonNumber: Int? = null,
): Int? = animeContext?.localSeasonNumber.toPositiveIntOrNull()
    ?: mediaSeasonNumber.toPositiveIntOrNull()
    ?: fallbackSeasonNumber?.takeIf { it > 0 }

fun resolveDanmakuEpisodeHint(
    animeContext: AnimePlaybackContext?,
    mediaEpisodeNumber: Long?,
    fallbackEpisodeNumber: Int? = null,
): Int? = animeContext?.localEpisodeNumber.toPositiveIntOrNull()
    ?: mediaEpisodeNumber.toPositiveIntOrNull()
    ?: fallbackEpisodeNumber?.takeIf { it > 0 }

private fun Long?.toPositiveIntOrNull(): Int? = this
    ?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
    ?.toInt()

/**
 * 单次播放器会话内的有界选集队列。队列只在应用进程内传递，不进入 Intent/SavedState；
 * 条目会移除 HTTP 认证头及嵌套队列，避免秘密被跨边界序列化或形成递归对象图。
 */
data class PlaybackQueue(
    val items: List<PlayableMedia>,
    val currentIndex: Int,
) {
    init {
        require(items.isNotEmpty()) { "播放队列不能为空" }
        require(currentIndex in items.indices) { "当前播放索引越界" }
    }
}

data class PlayableMedia(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val title: String,
    val sourceKind: MediaSourceKind,
    /** 原始 content://(本地 SAF 视频的 url 同样保持稳定 URI；Android 引擎每次 load 时转 fdclose://，哈希通过 ContentResolver 读取)。非 content 为 null。 */
    val contentUri: String? = null,
    /**
     * 播放记录稳定 key(WebDAV=webdav:{connId}:{path}; 本地=local:{contentUri}; 见 [io.github.weiyongzenqi.unuplayer.core.media.MediaKeys])。
     * 以"导航位置"区分文件, 不受 WebDAV 302 签名直链变更影响。source 层 fill;
     * 外部 Intent 拉起无导航上下文, 传 null, PlayerScreen fallback 用 url/contentUri 作 key。
     */
    val mediaKey: String? = null,
    /** TMDB ID(刮削番剧跨库续播锚点)。非刮削路径为 null。 */
    val tmdbId: Long? = null,
    /** 季号(刮削番剧跨库续播锚点)。非刮削路径为 null。 */
    val seasonNumber: Long? = null,
    /** 集号(刮削番剧跨库续播锚点)。非刮削路径为 null。 */
    val episodeNumber: Long? = null,
    /** 仅海报墙明确剧集携带；用于 Android 竖屏播放详情与本集评论，不包含媒体 URL 或凭据。 */
    val animeContext: AnimePlaybackContext? = null,
    /** 仅应用进程内使用的有界选集上下文；Android Activity 通过内存注册表传递，不写 Intent。 */
    val playbackQueue: PlaybackQueue? = null,
) {
    override fun toString(): String =
        "PlayableMedia(url=<redacted>, headers=<redacted>, title=$title, sourceKind=$sourceKind, " +
            "contentUri=${if (contentUri == null) "null" else "<redacted>"}, mediaKey=$mediaKey, " +
            "tmdbId=$tmdbId, seasonNumber=$seasonNumber, episodeNumber=$episodeNumber, " +
            "animeContext=${animeContext?.let { "subjectId=${it.bangumiSubjectId}, offset=${it.bangumiEpisodeOffset}, " +
                "localSeason=${it.localSeasonNumber}, localEpisode=${it.localEpisodeNumber}, dandanplayId=${it.dandanplayAnimeId}" }}, " +
            "queueSize=${playbackQueue?.items?.size ?: 0})"
}

fun PlayableMedia.withPlaybackQueue(
    orderedMedia: List<PlayableMedia>,
    currentIndex: Int,
    maxItems: Int = 500,
): PlayableMedia {
    require(maxItems > 0) { "播放队列上限必须为正数" }
    require(currentIndex in orderedMedia.indices) { "当前媒体不在播放队列内" }
    val windowStart = (currentIndex - maxItems / 2)
        .coerceAtLeast(0)
        .coerceAtMost((orderedMedia.size - maxItems).coerceAtLeast(0))
    val bounded = orderedMedia.drop(windowStart).take(maxItems).map { item ->
        item.copy(headers = emptyMap(), playbackQueue = null)
    }
    return copy(
        playbackQueue = PlaybackQueue(
            items = bounded,
            currentIndex = currentIndex - windowStart,
        ),
    )
}
