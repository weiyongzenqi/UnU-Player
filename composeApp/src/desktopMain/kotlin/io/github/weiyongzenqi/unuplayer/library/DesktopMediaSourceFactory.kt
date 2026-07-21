package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.local.DesktopLocalSource
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavSource
import io.github.weiyongzenqi.unuplayer.webdav.webDavCredentialsToken

/**
 * [MediaSourceFactory] 桌面实现。
 *
 * - WEBDAV: 按 library.connectionId 从 [WebDavConnectionRepository] 查连接，建 WebDavSource。
 * - LOCAL: 使用 library.localUri 或 rootPath 创建 [DesktopLocalSource]。
 */
class DesktopMediaSourceFactory(
    private val webDavRepo: WebDavConnectionRepository,
) : MediaSourceFactory {

    override suspend fun create(library: LibraryConfig): MediaSource? = when (library.sourceKind) {
        MediaSourceKind.WEBDAV -> {
            val connId = library.connectionId ?: return null
            val conn = webDavRepo.loadAll()
                .firstOrNull { it.id == connId } ?: return null
            WebDavSource(conn)
        }
        MediaSourceKind.LOCAL -> {
            val rootPath = library.localUri
                ?: library.rootPath.takeIf { it.isNotBlank() }
                ?: return null
            DesktopLocalSource(rootPath)
        }
        else -> null
    }

    /** WEBDAV 返回凭据哈希(密码编辑后缓存身份失配); LOCAL 等无凭据源返回 null。 */
    override suspend fun credentialsToken(library: LibraryConfig): String? = when (library.sourceKind) {
        MediaSourceKind.WEBDAV -> {
            val connId = library.connectionId ?: return null
            val conn = webDavRepo.loadAll()
                .firstOrNull { it.id == connId } ?: return null
            webDavCredentialsToken(conn)
        }
        else -> null
    }
}
