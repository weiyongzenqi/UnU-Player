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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.MediaKeys
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.domain.FileFormatUtil
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepositoryImpl
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavSource
import java.net.URI

/**
 * 播放记录区(desktopMain actual)。
 *
 * UI、文案和控件顺序与 Android 端保持一致；桌面提示使用 Compose 对话框。
 * 播放记录仓库取进程单例，初始化、查询、删除以及 WebDAV 连接加载均从协程进入，
 * 不在 Compose 主线程同步打开数据库或读取连接配置。
 */
@Composable
actual fun PlaybackHistorySlot(
    webDavRepository: WebDavConnectionRepository,
    onPlay: (PlayableMedia) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var recordRepo by remember { mutableStateOf<PlaybackRecordRepositoryImpl?>(null) }
    var records by remember { mutableStateOf<List<PlaybackRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    suspend fun refresh(repo: PlaybackRecordRepositoryImpl) {
        runSuspendCatching { repo.listPage(100, 0) }
            .onSuccess { records = it }
            .onFailure { errorMessage = "读取播放记录失败：${it.message ?: it.javaClass.simpleName}" }
        loading = false
    }

    LaunchedEffect(Unit) {
        val repoResult = withContext(Dispatchers.IO) {
            runCatching { PlaybackRecordRepositoryImpl.get() }
        }
        repoResult
            .onSuccess { repo ->
                recordRepo = repo
                refresh(repo)
            }
            .onFailure {
                loading = false
                errorMessage = "打开播放记录失败：${it.message ?: it.javaClass.simpleName}"
            }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("播放记录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (records.isNotEmpty()) showClearConfirm = true
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
                                val result = withContext(Dispatchers.IO) {
                                    runSuspendCatching { rebuildPlayableMedia(record, webDavRepository) }
                                }
                                result
                                    .onSuccess(onPlay)
                                    .onFailure {
                                        errorMessage = it.message ?: "无法继续播放这条记录"
                                    }
                            }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            record.title.ifBlank { "未命名媒体" },
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
                            progress = { record.watch_progress.toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        Text(
                            buildString {
                                append(formatTimeMs(record.position_ms))
                                append(" / ")
                                append(formatTimeMs(record.duration_ms))
                                append("    ")
                                append(FileFormatUtil.formatDate(record.last_played_at))
                                if (record.is_completed != 0L) append("    已完成")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        val repo = recordRepo ?: return@IconButton
                        scope.launch {
                            runSuspendCatching {
                                repo.deleteByKey(record.media_key)
                                repo.listPage(100, 0)
                            }.onSuccess {
                                records = it
                            }.onFailure {
                                errorMessage = "删除播放记录失败：${it.message ?: it.javaClass.simpleName}"
                            }
                        }
                    }) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
                }
                HorizontalDivider()
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空播放记录") },
            text = { Text("确定要清空全部播放记录吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    val repo = recordRepo ?: return@TextButton
                    scope.launch {
                        runSuspendCatching {
                            repo.deleteAll()
                            repo.listPage(100, 0)
                        }.onSuccess {
                            records = it
                        }.onFailure {
                            errorMessage = "清空播放记录失败：${it.message ?: it.javaClass.simpleName}"
                        }
                    }
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("无法继续操作") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) { Text("确定") }
            },
        )
    }
}

/** ms -> mm:ss 或 h:mm:ss。 */
private fun formatTimeMs(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds < 3600) "%02d:%02d".format(seconds / 60, seconds % 60)
    else "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
}

/**
 * 从记录重建可播放媒体。WebDAV 始终使用当前连接配置生成纯 URL 与 Authorization header，
 * 不复用历史 URL 中可能存在的 userInfo，也不在错误信息中输出 URL 或凭据。
 */
private suspend fun rebuildPlayableMedia(
    record: PlaybackRecord,
    webDavRepository: WebDavConnectionRepository,
): PlayableMedia = when (record.source_kind.trim().uppercase()) {
    MediaSourceKind.LOCAL.name -> {
        val url = record.url.takeIf { it.isNotBlank() }
            ?: throw PlaybackHistoryException("本地文件播放地址为空，无法继续播放")
        PlayableMedia(
            url = url,
            title = record.title.ifBlank { url.substringAfterLast('\\').substringAfterLast('/') },
            sourceKind = MediaSourceKind.LOCAL,
            contentUri = record.content_uri,
            mediaKey = record.media_key.ifBlank { MediaKeys.local(record.content_uri ?: url) },
        )
    }

    MediaSourceKind.WEBDAV.name -> rebuildWebDavMedia(record, webDavRepository)
    else -> throw PlaybackHistoryException("不支持的播放记录来源：${record.source_kind.ifBlank { "未知" }}")
}

private suspend fun rebuildWebDavMedia(
    record: PlaybackRecord,
    repository: WebDavConnectionRepository,
): PlayableMedia {
    val key = parseWebDavMediaKey(record.media_key)
    val cleanRecordUrl = record.url.takeIf { it.isNotBlank() }?.let(::removeUrlCredentials)
    val connections = repository.loadAll()
    val connection = key?.connectionId
        ?.let { id -> connections.firstOrNull { it.id == id } }
        ?: cleanRecordUrl?.let { url -> findConnectionForUrl(connections, url) }
        ?: throw PlaybackHistoryException("原 WebDAV 连接已删除或失效，请重新添加连接后再试")

    val path = key?.path?.takeIf { it.isNotBlank() }
        ?: cleanRecordUrl
        ?: throw PlaybackHistoryException("WebDAV 播放地址为空，无法继续播放")
    val source = WebDavSource(connection)
    val resolved = try {
        source.resolvePlayMedia(
            MediaEntry(
                name = record.title.ifBlank { path.substringAfterLast('/') },
                path = path,
                isDirectory = false,
            ),
        )
    } finally {
        source.close()
    }
    return resolved.copy(
        title = record.title.ifBlank { resolved.title },
        contentUri = record.content_uri,
        mediaKey = record.media_key.ifBlank { resolved.mediaKey },
    )
}

/** 只在连接 id 后第一个冒号处分割，后续路径中的冒号、中文和特殊字符全部原样保留。 */
internal fun parseWebDavMediaKey(mediaKey: String): ParsedWebDavKey? {
    if (!mediaKey.startsWith("webdav:")) return null
    val payload = mediaKey.removePrefix("webdav:")
    val separator = payload.indexOf(':')
    if (separator <= 0 || separator == payload.lastIndex) return null
    return ParsedWebDavKey(
        connectionId = payload.substring(0, separator),
        path = payload.substring(separator + 1),
    )
}

/** 去除旧记录 URL 里的 userInfo；无效或非 HTTP(S) URL 不参与 WebDAV 恢复。 */
internal fun removeUrlCredentials(url: String): String? = runCatching {
    val parsed = URI(url.trim())
    val scheme = parsed.scheme?.lowercase()
    require(scheme == "http" || scheme == "https")
    val host = parsed.host?.lowercase()?.takeIf { it.isNotBlank() } ?: error("缺少主机")
    val hostForUrl = if (':' in host && !host.startsWith('[')) "[$host]" else host
    buildString {
        append(scheme)
        append("://")
        append(hostForUrl)
        if (parsed.port >= 0) append(":${parsed.port}")
        append(parsed.rawPath.orEmpty())
        parsed.rawQuery?.let { append('?').append(it) }
        parsed.rawFragment?.let { append('#').append(it) }
    }
}.getOrNull()

/** URL 回退匹配选最长 baseUrl，避免同一主机下根路径连接抢到更具体的挂载点。 */
internal fun findConnectionForUrl(
    connections: List<WebDavConnection>,
    url: String,
): WebDavConnection? = connections.mapNotNull { connection ->
    val base = removeUrlCredentials(connection.baseUrl)?.trimEnd('/') ?: return@mapNotNull null
    connection to base
}.filter { (_, base) ->
    url == base || url.startsWith("$base/") || url.startsWith("$base?")
}.maxByOrNull { (_, base) -> base.length }?.first

internal data class ParsedWebDavKey(val connectionId: String, val path: String)

private class PlaybackHistoryException(message: String) : IllegalStateException(message)
