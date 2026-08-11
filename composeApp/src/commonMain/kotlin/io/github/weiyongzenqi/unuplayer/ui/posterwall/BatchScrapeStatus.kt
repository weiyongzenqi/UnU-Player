package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class BatchScrapeProgress(
    val completed: Int,
    val total: Int,
    val currentTitle: String,
)

@Composable
internal fun BatchScrapeStatus(
    progress: BatchScrapeProgress?,
    status: String = "",
    isRunning: Boolean = true,
    isStopping: Boolean = false,
    onStop: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (!isRunning && status.isNotBlank()) status else progress?.let {
                if (it.total > 0) {
                    "正在补刮 ${it.completed}/${it.total}" +
                        it.currentTitle.takeIf(String::isNotBlank)?.let { title -> " · $title" }.orEmpty()
                } else {
                    "正在准备补刮..."
                }
            } ?: "正在准备补刮...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (isRunning && progress != null && progress.total > 0) {
            LinearProgressIndicator(
                progress = { (progress.completed.toFloat() / progress.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        } else if (isRunning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        if (isRunning) {
            TextButton(onClick = onStop, enabled = !isStopping) {
                Text(if (isStopping) "停止中..." else "停止")
            }
        }
    }
}
