package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.media.MediaKeys
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind

/**
 * 扫描器与播放记录共用的媒体身份策略。
 * 新来源必须显式增加 key 规则，未知来源不能静默伪造路径 key。
 */
internal object MediaIdentityResolver {
    fun mediaKey(sourceKind: MediaSourceKind, connectionId: String?, path: String): String? = when (sourceKind) {
        MediaSourceKind.WEBDAV -> connectionId?.let { MediaKeys.webDav(it, path) }
        MediaSourceKind.LOCAL -> MediaKeys.local(path)
        else -> null
    }
}
