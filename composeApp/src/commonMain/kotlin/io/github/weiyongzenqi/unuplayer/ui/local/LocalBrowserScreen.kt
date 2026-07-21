package io.github.weiyongzenqi.unuplayer.ui.local

import androidx.compose.runtime.Composable
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.local.LocalDirectoryRepository

/**
 * 本地文件浏览 tab(跨平台 expect)。
 *
 * - Android actual: SAF(DocumentFile) 保留 content URI，由 Android 引擎每次 load 时转 fdclose://
 * - 桌面 actual: 平台文件系统遍历(阶段4 接入; 当前占位提示)
 *
 * 浏览状态(directories/selectedDir/pathStack)由各平台 actual 自行 rememberSaveable。
 */
@Composable
expect fun LocalBrowserScreen(
    onPlay: (PlayableMedia) -> Unit,
    repository: LocalDirectoryRepository,
)
