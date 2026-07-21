package io.github.weiyongzenqi.unuplayer.library

import android.content.Context
import android.net.Uri
import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.local.LocalSource
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavSource
import io.github.weiyongzenqi.unuplayer.webdav.webDavCredentialsToken

/**
 * [MediaSourceFactory] Android 实现。
 *
 * - WEBDAV: 按 library.connectionId 从 [WebDavConnectionRepository] 查连接, 建 WebDavSource。
 *   连接被删/失效返回 null。
 * - LOCAL: 按 library.localUri(SAF tree uri)建 LocalSource。URI 失效返回 null。
 */
class AndroidMediaSourceFactory(
    private val context: Context,
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
            val uriStr = library.localUri ?: return null
            LocalSource(context, Uri.parse(uriStr), library.name)
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
