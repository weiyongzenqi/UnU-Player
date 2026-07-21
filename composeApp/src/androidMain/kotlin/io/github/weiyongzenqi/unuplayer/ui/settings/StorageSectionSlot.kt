package io.github.weiyongzenqi.unuplayer.ui.settings

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.domain.FileFormatUtil
import io.github.weiyongzenqi.unuplayer.platform.AndroidAppLogger
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.platform.SystemFonts
import java.io.File

/**
 * 存储清理区(androidMain actual)。
 *
 * 列出各项缓存占用大小, 支持单项清理与一键清理。大小计算走 IO 线程, 不阻塞 UI。
 * 字体为用户导入, 不参与一键清理(仅单项), 避免误删。
 */
@Composable
actual fun StorageSectionSlot(appLogger: AppLogger?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logger = appLogger ?: AndroidAppLogger.get(context)
    var items by remember { mutableStateOf<List<CacheEntry>>(emptyList()) }
    var totalSize by remember { mutableStateOf(0L) }

    suspend fun compute(): List<CacheEntry> = withContext(Dispatchers.IO) {
        listOf(
            CacheEntry("播放临时文件", sizeOfTempFiles(context)),
            CacheEntry("字幕字体", SystemFonts.fontDir(context).listFiles()?.sumOf { it.length() } ?: 0L),
            CacheEntry("日志文件", logger.logsSize()),
            CacheEntry("系统 CA 证书", File(context.cacheDir, "system-ca-bundle.pem").takeIf { it.exists() }?.length() ?: 0L),
        )
    }

    LaunchedEffect(Unit) {
        val list = compute()
        items = list
        totalSize = list.sumOf { it.size }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("存储清理", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "总占用 ${FileFormatUtil.formatSize(totalSize)}",
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
                        FileFormatUtil.formatSize(entry.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    enabled = entry.size > 0,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { clearOne(context, index, logger) }
                            val list = compute()
                            items = list
                            totalSize = list.sumOf { it.size }
                        }
                    },
                ) { Text("清理") }
            }
            if (index < items.lastIndex) HorizontalDivider()
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "字体为用户导入, 不参与一键清理; 一键清理仅清临时文件/日志/证书。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        clearTempFiles(context)
                        logger.clearLogs()
                        File(context.cacheDir, "system-ca-bundle.pem").takeIf { it.exists() }?.delete()
                    }
                    val list = compute()
                    items = list
                    totalSize = list.sumOf { it.size }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("一键清理(临时文件/日志/证书)") }
    }
}

private data class CacheEntry(val name: String, val size: Long)

private fun sizeOfTempFiles(context: Context): Long {
    val cache = context.cacheDir
    return cache.listFiles()?.filter {
        val n = it.name
        n.startsWith("local_play_") || n.startsWith("sub_import_") ||
            n.startsWith("sub_auto_") || n.startsWith("font_import_")
    }?.sumOf { it.length() } ?: 0L
}

private fun clearTempFiles(context: Context) {
    val cache = context.cacheDir
    cache.listFiles()?.filter {
        val n = it.name
        n.startsWith("local_play_") || n.startsWith("sub_import_") ||
            n.startsWith("sub_auto_") || n.startsWith("font_import_")
    }?.forEach { it.delete() }
}

/** 单项清理(index 与上方 CacheEntry 列表顺序对应); logger.clearLogs() 为 suspend, 调用方已在 IO 协程内。 */
private suspend fun clearOne(context: Context, index: Int, logger: AppLogger) {
    when (index) {
        0 -> clearTempFiles(context)
        1 -> SystemFonts.clearFonts(context)
        2 -> logger.clearLogs()
        3 -> File(context.cacheDir, "system-ca-bundle.pem").takeIf { it.exists() }?.delete()
    }
}
