package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.platform.DesktopAppDirectories
import java.io.File
import java.nio.file.Files
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/**
 * 日志设置区(桌面实现)。
 *
 * UI、文案和控件顺序与 Android 端保持一致；目录选择改用 Swing [JFileChooser]。
 * 本组件只持久化设置，进程级 AppLogger 会监听设置并自行切换目录。
 */
@Composable
actual fun LogSettingsSlot(repository: SettingsRepository) {
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun chooseDirectory() {
        val configuredDirectory = state.logDirUri
        val initialDirectory = configuredDirectory
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isDirectory }
            ?: runCatching {
                Files.createDirectories(DesktopAppDirectories.logsDirectory).toFile()
            }.getOrElse {
                DesktopAppDirectories.logsDirectory.toFile()
            }

        // Compose Desktop 回调通常在 EDT，但不依赖这一实现细节；始终安全调度到 Swing EDT。
        SwingUtilities.invokeLater {
            val chooser = JFileChooser(initialDirectory).apply {
                dialogTitle = "选择日志输出目录"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
                return@invokeLater // 用户取消，不改设置
            }
            val selected = chooser.selectedFile ?: return@invokeLater
            scope.launch {
                val validation = withContext(Dispatchers.IO) { validateLogDirectory(selected) }
                validation
                    .onSuccess { normalizedPath ->
                        runSuspendCatching {
                            repository.update { it.copy(logDirUri = normalizedPath) }
                        }.onFailure {
                            errorMessage = "保存日志目录失败：${it.message ?: it.javaClass.simpleName}"
                        }
                    }
                    .onFailure {
                        errorMessage = it.message ?: "所选日志目录不可用"
                    }
            }
        }
    }

    // 开关
    SwitchRow(
        title = "启用日志",
        subtitle = "默认关闭。开启后把播放日志输出到选定目录(便于排查问题)",
        checked = state.enableLogs,
        onCheckedChange = { value ->
            scope.launch { repository.update { it.copy(enableLogs = value) } }
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
            val directoryDisplay = state.logDirUri ?: "未选择"
            Text(
                "当前: $directoryDisplay",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Button(onClick = ::chooseDirectory) {
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

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("日志目录不可用") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) { Text("确定") }
            },
        )
    }
}

/** 验证目录并返回规范化绝对路径；不创建目录或日志文件。 */
internal fun validateLogDirectory(directory: File): Result<String> = runCatching {
    val normalized = directory.toPath().toAbsolutePath().normalize().toFile()
    require(normalized.isDirectory) { "所选路径不是目录，请重新选择" }
    require(normalized.canWrite()) { "所选目录不可写，请检查权限后重试" }
    normalized.absolutePath
}
