package io.github.weiyongzenqi.unuplayer.webdav

import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry

/** WebDAV 搜索结果(移植自 NipaPlay WebDAVSearchResult)。 */
data class WebDavSearchResult(
    val file: MediaEntry,
    val fullPath: String,        // 服务端相对路径(href)
    val relativePath: String,    // 相对搜索起点
    val connectionId: String,    // 命中所在连接 id
)
