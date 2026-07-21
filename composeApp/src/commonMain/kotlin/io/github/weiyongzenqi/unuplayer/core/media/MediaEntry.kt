package io.github.weiyongzenqi.unuplayer.core.media

/** 目录项(WebDAV/其他来源列出的文件或子目录)。 */
data class MediaEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val mimeType: String? = null,
)
