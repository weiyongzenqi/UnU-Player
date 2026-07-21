package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.platform.decodeUrlComponentPreservingPlus
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.MediaSourceCache
import io.github.weiyongzenqi.unuplayer.webdav.isVideoFile

/**
 * 原始目录浏览器(详情页剧集列表底部兜底)。
 *
 * ANCHOR 模式靠文件名/目录名匹配, 难免遗漏(第一季/S01/非标准视频名)。打开开关后实时列
 * 番剧文件夹原始结构, 可进任意子目录、点任意视频直接播放(复用 resolvePlayMedia)。
 * 数据不存 DB, 实时从 [mediaSourceCache] 租用 source 列目录。浏览限 [rootPath] 内(不往上越界)。
 * 开关默认关, 关时零开销(produceState 在 if 内不组合); 开时 withContext(IO) 列目录不卡 UI。
 *
 * @param mediaSourceCache 页面级 source 所有者
 * @param library 当前番剧所属库配置
 * @param rootPath 番剧文件夹 showPath, 浏览上限
 * @param onPlay 点视频播放(AnimeDetailScreen 传 playMediaEntry)
 */
@Composable
fun DirBrowser(
    library: LibraryConfig,
    mediaSourceCache: MediaSourceCache,
    rootPath: String,
    onPlay: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showOriginal by remember { mutableStateOf(false) }
    var currentPath by remember { mutableStateOf(rootPath) }
    // 切番剧(rootPath 变)重置: 当前路径回根 + 关开关
    LaunchedEffect(rootPath) {
        currentPath = rootPath
        showOriginal = false
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 开关行
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("显示原始目录", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = showOriginal, onCheckedChange = { showOriginal = it })
        }
        if (showOriginal) {
            HorizontalDivider()
            // 当前路径名 + 返回上一级(限 rootPath 内)
            val atRoot = currentPath == rootPath
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!atRoot) {
                    IconButton(onClick = {
                        currentPath = parentPathWithinRoot(currentPath, rootPath)
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一级") }
                }
                Text(
                    text = decodeName(pathLeafName(currentPath).ifBlank { currentPath }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = if (atRoot) 0.dp else 4.dp),
                )
            }
            // 列目录(produceState: null=加载中, empty=空, else=有内容)
            // withContext(IO): WebDAV PROPFIND/SAF 查询在 IO 线程, 不卡主线程; 开关关时不组合(零开销)
            val entries by produceState<List<MediaEntry>?>(null, currentPath, library, mediaSourceCache) {
                value = withContext(Dispatchers.IO) {
                    runSuspendCatching {
                        mediaSourceCache.withSource(library) { source ->
                            source.listFolderAll(currentPath)
                        } ?: emptyList()
                    }.getOrDefault(emptyList())
                }
            }
            // 捕获局部变量: delegated property(produceState) 不能 smart cast, 用局部 val 才能在 when 里判 null 后 non-null 用
            val list = entries
            when {
                list == null -> Text(
                    "加载中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                list.isEmpty() -> Text(
                    "（空）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                else -> {
                    // 文件夹在前, 视频在后, 各自按名排序; 非视频非文件夹不显示
                    val dirs = list.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                    val videos = list.filter { !it.isDirectory && isVideoFile(it.name) }
                        .sortedBy { it.name.lowercase() }
                    dirs.forEach { e ->
                        DirRow(decodeName(e.name), Icons.Filled.Folder) { currentPath = e.path }
                        HorizontalDivider()
                    }
                    videos.forEach { e ->
                        DirRow(decodeName(e.name), Icons.Filled.PlayCircle) { onPlay(e) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun DirRow(name: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 解码路径段: WebDAV PROPFIND href / SAF document id 末段百分号编码, 解出中文; + 保护为 %2B 防空格误伤。 */
private fun decodeName(s: String): String =
    decodeUrlComponentPreservingPlus(s)

private fun pathLeafName(path: String): String {
    val trimmed = path.trimEnd('/', '\\')
    val separator = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    return trimmed.substring(separator + 1)
}

private fun parentPathWithinRoot(path: String, rootPath: String): String {
    val trimmed = path.trimEnd('/', '\\')
    val separator = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    if (separator < 0) return rootPath
    val parent = trimmed.substring(0, separator)
    return if (parent.length <= rootPath.trimEnd('/', '\\').length) rootPath else parent
}
