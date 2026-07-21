package io.github.weiyongzenqi.unuplayer.core.media

/** 媒体来源类型。 */
enum class MediaSourceKind {
    WEBDAV,
    SMB,
    FTP,
    EMBY,
    JELLYFIN,
    LOCAL,
    EXTERNAL,   // intent-filter 外部拉起(P1-6)
}
