package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.DesktopSubtitleTempStorage
import io.github.weiyongzenqi.unuplayer.domain.FileFormatUtil
import io.github.weiyongzenqi.unuplayer.library.PosterCache
import io.github.weiyongzenqi.unuplayer.platform.AppLogger

/** Windows 存储清理：只管理应用明确拥有的海报、日志和过期字幕临时文件。 */
@Composable
actual fun StorageSectionSlot(appLogger: AppLogger?) {
    val scope = rememberCoroutineScope()
    val subtitleStorage = remember { DesktopSubtitleTempStorage() }
    var items by remember { mutableStateOf<List<DesktopCacheEntry>>(emptyList()) }
    var isBusy by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    suspend fun readEntries(): List<DesktopCacheEntry> = withContext(Dispatchers.IO) {
        listOf(
            DesktopCacheEntry(
                kind = DesktopCacheKind.POSTERS,
                name = "海报图片缓存",
                detail = "海报、背景和剧集缩略图",
                size = PosterCache.get().sizeBytes(),
            ),
            DesktopCacheEntry(
                kind = DesktopCacheKind.LOGS,
                name = "日志文件",
                detail = "仅 unu-app-/unu-mpv- 前缀日志",
                size = appLogger?.logsSize() ?: 0L,
            ),
            DesktopCacheEntry(
                kind = DesktopCacheKind.STALE_SUBTITLES,
                name = "过期字幕临时文件",
                detail = "仅超过 24 小时的远程字幕会话",
                size = subtitleStorage.staleSizeBytes(),
            ),
        )
    }

    suspend fun refreshEntries(): Boolean {
        return runSuspendCatching { readEntries() }
            .onSuccess { items = it }
            .onFailure { error ->
                statusMessage = "读取存储占用失败：${error.message ?: error::class.simpleName}"
                statusIsError = true
            }
            .isSuccess
    }

    fun clearOne(entry: DesktopCacheEntry) {
        scope.launch {
            isBusy = true
            statusMessage = null
            val result = runSuspendCatching {
                withContext(Dispatchers.IO) {
                    when (entry.kind) {
                        DesktopCacheKind.POSTERS -> PosterCache.get().clear()
                        DesktopCacheKind.LOGS -> appLogger?.clearLogs()
                        DesktopCacheKind.STALE_SUBTITLES -> subtitleStorage.clearStaleSessions()
                    }
                }
            }
            val refreshed = refreshEntries()
            result.onSuccess {
                if (refreshed) {
                    statusMessage = "${entry.name}已清理"
                    statusIsError = false
                }
            }.onFailure { error ->
                statusMessage = "清理失败：${error.message ?: error::class.simpleName}"
                statusIsError = true
            }
            isBusy = false
        }
    }

    fun clearAll() {
        scope.launch {
            isBusy = true
            statusMessage = null
            val failures = withContext(Dispatchers.IO) {
                buildList {
                    runSuspendCatching { PosterCache.get().clear() }
                        .onFailure { add("海报图片缓存") }
                    if (appLogger != null) {
                        runCatching { appLogger.clearLogs() }
                            .onFailure { add("日志文件") }
                    }
                    runCatching { subtitleStorage.clearStaleSessions() }
                        .onFailure { add("过期字幕临时文件") }
                }
            }
            val refreshed = refreshEntries()
            if (failures.isEmpty() && refreshed) {
                statusMessage = "可清理缓存已全部清理"
                statusIsError = false
            } else if (failures.isNotEmpty()) {
                statusMessage = "部分清理失败：${failures.joinToString("、")}"
                statusIsError = true
            }
            isBusy = false
        }
    }

    LaunchedEffect(appLogger) {
        isBusy = true
        refreshEntries()
        isBusy = false
    }

    val totalSize = items.sumOf { it.size }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("存储清理", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (isBusy && items.isEmpty()) "正在计算占用…" else "总占用 ${FileFormatUtil.formatSize(totalSize)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        items.forEachIndexed { index, entry ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${FileFormatUtil.formatSize(entry.size)} · ${entry.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    enabled = !isBusy && entry.size > 0L,
                    onClick = { clearOne(entry) },
                ) { Text("清理") }
            }
            if (index < items.lastIndex) HorizontalDivider()
        }
        statusMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "字幕临时文件只统计并清理超过 24 小时的会话，当前播放会话不会删除。播放缓存仅使用内存，不占用硬盘。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = ::clearAll,
            enabled = !isBusy && items.any { it.size > 0L },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("一键清理") }
    }
}

private enum class DesktopCacheKind {
    POSTERS,
    LOGS,
    STALE_SUBTITLES,
}

private data class DesktopCacheEntry(
    val kind: DesktopCacheKind,
    val name: String,
    val detail: String,
    val size: Long,
)
