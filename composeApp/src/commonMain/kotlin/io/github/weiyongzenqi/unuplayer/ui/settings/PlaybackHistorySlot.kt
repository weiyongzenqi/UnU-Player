package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.runtime.Composable
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerPlaybackLocator
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerConnectionService
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository

/**
 * 播放记录区槽位(commonMain expect，平台 actual)。
 *
 * 列表展示播放记录(标题/番剧/集标题/进度/时间), 支持单删/清空/点击继续播放。
 * 普通媒体通过 [onPlay] 回调，媒体服务器记录通过 [onPlayMediaServer] 传递无秘密定位；
 * WebDAV 的 auth header 由平台 actual 从 webDavRepository 反查并重新生成。
 */
@Composable
expect fun PlaybackHistorySlot(
    webDavRepository: WebDavConnectionRepository,
    mediaServerService: MediaServerConnectionService?,
    onPlay: (PlayableMedia) -> Unit,
    onPlayMediaServer: (MediaServerPlaybackLocator) -> Unit,
)
