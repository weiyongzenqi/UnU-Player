package io.github.weiyongzenqi.unuplayer.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository

/**
 * 日志设置区(Android 实现)。
 *
 * - 开关: 默认关闭(防默认开启产生大量日志文件)
 * - 级别: error/warn/info/v/debug/trace
 * - 输出目录: SAF 选定(tree URI + takePersistableUriPermission), 持久化 logDirUri
 *
 * 日志写到选定目录下 unu-app-YYYY-MM-DD.txt(程序日志) + unu-mpv-YYYY-MM-DD.txt(内核日志), 见 AppLogger。
 */
@Composable
actual fun LogSettingsSlot(repository: SettingsRepository) {
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val pickDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            // 持久化读写权限, 重启后仍可写日志
            runCatching {
                val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            scope.launch {
                repository.update { it.copy(logDirUri = uri.toString()) }
            }
        }
    }

    // 开关
    SwitchRow(
        title = "启用日志",
        subtitle = "默认关闭。开启后把播放日志输出到选定目录(便于排查问题)",
        checked = state.enableLogs,
        onCheckedChange = { v ->
            scope.launch { repository.update { it.copy(enableLogs = v) } }
        },
    )

    if (state.enableLogs) {
        // 内核日志级别(mpv)
        SubsectionTitle("内核日志级别(mpv)")
        val levelOptions = listOf(
            "error" to "error(仅错误)",
            "warn" to "warn(警告)",
            "info" to "info(常规, 推荐)",
            "v" to "v(详细)",
            "debug" to "debug(调试)",
            "trace" to "trace(最详细)",
        )
        levelOptions.forEach { (value, label) ->
            RadioRow(
                label = label,
                selected = state.logLevel == value,
                onSelect = {
                    scope.launch { repository.update { it.copy(logLevel = value) } }
                },
            )
        }

        // 程序日志级别(应用自身事件, 写 unu-app 文件; 无 mpv 的 v 级别, 用 trace 最详细)
        SubsectionTitle("程序日志级别")
        val appLevelOptions = listOf(
            "error" to "error(仅错误)",
            "warn" to "warn(警告)",
            "info" to "info(常规, 推荐)",
            "debug" to "debug(调试)",
            "trace" to "trace(最详细)",
        )
        appLevelOptions.forEach { (value, label) ->
            RadioRow(
                label = label,
                selected = state.appLogLevel == value,
                onSelect = {
                    scope.launch { repository.update { it.copy(appLogLevel = value) } }
                },
            )
        }

        // 输出目录
        SubsectionTitle("输出目录")
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            val dirDisplay = state.logDirUri?.let {
                runCatching { Uri.parse(it).lastPathSegment ?: it }.getOrDefault(it)
            } ?: "未选择"
            Text(
                "当前: $dirDisplay",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Button(onClick = { pickDirLauncher.launch(null) }) {
                Text("选择目录")
            }
            Text(
                "日志文件: unu-app-YYYY-MM-DD.txt(程序日志) + unu-mpv-YYYY-MM-DD.txt(内核日志), 按日期追加写入",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
