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
 *
 * initialUri 非 null 时(MediaSourceScreen 嵌入模式): 初次进入直接浏览该目录而非目录选择列表,
 * 根目录返回调 onExit 退回调用方; null = 现 tab 行为完全不变。
 */
@Composable
expect fun LocalBrowserScreen(
    onPlay: (PlayableMedia) -> Unit,
    repository: LocalDirectoryRepository,
    initialUri: String? = null,
    onExit: (() -> Unit)? = null,
)
