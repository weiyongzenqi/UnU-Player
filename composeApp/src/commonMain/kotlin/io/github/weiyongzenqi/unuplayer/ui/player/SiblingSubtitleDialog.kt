package io.github.weiyongzenqi.unuplayer.ui.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.weiyongzenqi.unuplayer.domain.EpisodeNumberExtractor

/** Android 与 Windows 共用的同目录字幕选择对话框。 */
@Composable
internal fun SiblingSubtitleDialog(
    displayNames: List<String>,
    videoTitle: String,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentEpisode = EpisodeNumberExtractor.extractEpisode(videoTitle)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择同目录字幕") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
            ) {
                itemsIndexed(displayNames) { index, displayName ->
                    val episode = EpisodeNumberExtractor.extractEpisode(displayName)
                    val badge = EpisodeNumberExtractor.formatSxxExx(displayName)
                        ?: episode?.let { "第${it}集" }
                    val isCurrent = episode != null && episode == currentEpisode
                    TextButton(
                        onClick = { onSelect(index) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${if (isCurrent) "▶ " else ""}${badge?.let { "[$it] " } ?: ""}$displayName",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
