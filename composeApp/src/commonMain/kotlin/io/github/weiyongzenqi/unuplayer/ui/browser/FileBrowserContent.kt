package io.github.weiyongzenqi.unuplayer.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.domain.EpisodeNumberExtractor
import io.github.weiyongzenqi.unuplayer.domain.FileFormatUtil
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord

/**
 * 文件浏览器通用 UI 组件(WebDAV/本地浏览共享, 避免两处重复实现)。
 *
 * 数据模型统一用 [MediaEntry]([WebDavSource]/[LocalSource] 都产出该类型), 故列表项与
 * 面包屑可共享同一套渲染。
 *
 * - [EntryRow]: 文件/文件夹行(图标 + 名称 + 文件大小/修改日期)
 * - [Breadcrumb]: 路径面包屑(横向可滚动, 点击跳层)
 */

/** ms -> mm:ss 或 h:mm:ss(浏览列表进度时间显示用)。 */
internal fun formatTimeMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 3600) "%02d:%02d".format(s / 60, s % 60)
    else "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

/** 文件/文件夹行: 图标 + 名称(两行垂直居中, bodyMedium); 文件额外显示大小/SxxExx/日期/已播进度+时间。 */
@Composable
internal fun EntryRow(entry: MediaEntry, onClick: () -> Unit, playbackRecord: PlaybackRecord? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            // 文件名: 固定最小高度两行, 垂直居中(短名一行也居中, 不顶格); bodyMedium 缩小字体
            Box(
                modifier = Modifier.heightIn(min = 40.dp).fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!entry.isDirectory) {
                val sxxExx = remember(entry.name) { EpisodeNumberExtractor.formatSxxExx(entry.name) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        FileFormatUtil.formatSize(entry.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (sxxExx != null) SxxExxBadge(sxxExx)
                    Text(
                        FileFormatUtil.formatDate(entry.lastModified),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 已播放进度条 + 右侧当前/总时长(浏览列表披露式显示; 仅文件且播过才显示)
                val progress = playbackRecord?.watch_progress ?: 0.0
                if (playbackRecord != null && progress > 0.0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LinearProgressIndicator(
                            progress = { progress.toFloat() },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${formatTimeMs(playbackRecord.position_ms)} / ${formatTimeMs(playbackRecord.duration_ms)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** SxxExx 季集标识 badge(主题色半透明圆角, 仅文件名含 SxxExx 时由调用方条件显示)。 */
@Composable
internal fun SxxExxBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        contentColor = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 路径面包屑: 横向可滚动, 点击某段跳到对应层级。[names] 为显示名列表(首项通常是根)。 */
@Composable
internal fun Breadcrumb(names: List<String>, onNavigate: (Int) -> Unit) {
    LazyRow(verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(names) { index, name ->
            if (index > 0) {
                Text(
                    " / ",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onNavigate(index) },
            )
        }
    }
}
