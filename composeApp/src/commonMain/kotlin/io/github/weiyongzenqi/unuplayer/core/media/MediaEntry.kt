package io.github.weiyongzenqi.unuplayer.core.media

/** 目录项(WebDAV/其他来源列出的文件或子目录)。 */
data class MediaEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val mimeType: String? = null,
    /** TMDB ID(刮削番剧跨库续播锚点)。非刮削路径为 null。 */
    val tmdbId: Long? = null,
    /** 季号(刮削番剧跨库续播锚点)。非刮削路径为 null。 */
    val seasonNumber: Long? = null,
    /** 集号(刮削番剧跨库续播锚点)。非刮削路径为 null。 */
    val episodeNumber: Long? = null,
)
