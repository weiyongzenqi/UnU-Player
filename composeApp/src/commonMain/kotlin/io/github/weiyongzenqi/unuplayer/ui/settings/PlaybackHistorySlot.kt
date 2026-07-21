package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.runtime.Composable
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository

/**
 * 播放记录区槽位(commonMain expect, androidMain actual)。
 *
 * 列表展示播放记录(标题/番剧/集标题/进度/时间), 支持单删/清空/点击继续播放。
 * 继续播放通过 [onPlay] 回调(平台侧启动 PlayerActivity), WebDAV 的 auth header 由
 * androidMain actual 从 webDavRepository 反查(baseUrl 前缀匹配)。
 */
@Composable
expect fun PlaybackHistorySlot(
    webDavRepository: WebDavConnectionRepository,
    onPlay: (PlayableMedia) -> Unit,
)
