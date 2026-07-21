package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.domain.FileFormatUtil
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepositoryImpl
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository

/**
 * 播放记录区(androidMain actual)。
 *
 * 列表展示(标题/番剧/集标题/进度条/时间), 单删/清空/点击继续播放。
 * 大小计算与删除走 IO; 继续播放 WebDAV 反查连接 auth header, 本地直接用 content_uri。
 * 分页: 先加载前 100 条(按 last_played_at 倒序), 够日常用; 超出再分页(后续优化)。
 */
@Composable
actual fun PlaybackHistorySlot(
    webDavRepository: WebDavConnectionRepository,
    onPlay: (PlayableMedia) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recordRepo = remember { PlaybackRecordRepositoryImpl.get(context) }
    var records by remember { mutableStateOf<List<PlaybackRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    suspend fun refresh() {
        val list = withContext(Dispatchers.IO) { recordRepo.listPage(100, 0) }
        records = list
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("播放记录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch { withContext(Dispatchers.IO) { recordRepo.deleteAll() }; refresh() }
            }) { Text("清空") }
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading -> CircularProgressIndicator()
            records.isEmpty() -> Text(
                "暂无播放记录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> records.forEach { record ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                val headers = if (record.source_kind == "WEBDAV")
                                    withContext(Dispatchers.IO) { headersForUrl(webDavRepository, record.url) }
                                else emptyMap()
                                onPlay(
                                    PlayableMedia(
                                        url = record.url,
                                        headers = headers,
                                        title = record.title,
                                        sourceKind = runCatching { MediaSourceKind.valueOf(record.source_kind) }
                                            .getOrDefault(MediaSourceKind.WEBDAV),
                                        contentUri = record.content_uri,
                                        mediaKey = record.media_key,
                                    ),
                                )
                            }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            record.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!record.danmaku_anime_title.isNullOrBlank()) {
                            Text(
                                "${record.danmaku_anime_title} ${record.danmaku_episode_title ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { record.watch_progress.toFloat() },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        Text(
                            "${formatTimeMs(record.position_ms)} / ${formatTimeMs(record.duration_ms)}    ${FileFormatUtil.formatDate(record.last_played_at)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        scope.launch { withContext(Dispatchers.IO) { recordRepo.deleteByKey(record.media_key) }; refresh() }
                    }) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
                }
                HorizontalDivider()
            }
        }
    }
}

/** ms -> mm:ss 或 h:mm:ss。 */
private fun formatTimeMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 3600) "%02d:%02d".format(s / 60, s % 60)
    else "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

/** 从 url 反查 WebDAV 连接的 Authorization header(baseUrl 前缀匹配)。无匹配/无凭证返回空。 */
private suspend fun headersForUrl(repo: WebDavConnectionRepository, url: String): Map<String, String> {
    val conns = repo.loadAll()
    val conn = conns.firstOrNull { url.startsWith(it.baseUrl.removeSuffix("/")) } ?: return emptyMap()
    if (conn.username.isBlank()) return emptyMap()
    val credential = android.util.Base64.encodeToString(
        "${conn.username}:${conn.password}".toByteArray(),
        android.util.Base64.NO_WRAP,
    )
    return mapOf("Authorization" to "Basic $credential")
}
