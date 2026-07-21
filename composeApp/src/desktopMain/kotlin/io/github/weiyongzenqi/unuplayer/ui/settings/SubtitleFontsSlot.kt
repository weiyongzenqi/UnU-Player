package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/** Windows 字幕字体设置；UI 结构与 Android 端保持一致。 */
@Composable
actual fun SubtitleFontsSlot(repository: SettingsRepository) {
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val store = remember { DesktopSubtitleFontStore() }
    var refreshToken by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val systemFonts by produceState<List<String>>(emptyList()) {
        // CA-005: 进入时作废缓存, 感知运行中装/卸系统字体。refresh() 仅置 null 不阻塞;
        // names() 在 IO 线程重新枚举。生产环境枚举约百毫秒, 不阻塞 EDT。
        DesktopSystemFontCatalog.refresh()
        value = withContext(Dispatchers.IO) {
            runCatching { DesktopSystemFontCatalog.names() }.getOrDefault(emptyList())
        }
    }
    val importedFonts by produceState<List<DesktopFontFace>>(emptyList(), refreshToken) {
        val result = withContext(Dispatchers.IO) { runCatching { store.listImportedFonts() } }
        result
            .onSuccess { value = it }
            .onFailure { errorMessage = "读取已导入字体失败：${it.message ?: it.javaClass.simpleName}" }
    }

    fun chooseFontFile() {
        SwingUtilities.invokeLater {
            val chooser = JFileChooser(File(System.getProperty("user.home", "."))).apply {
                dialogTitle = "选择字幕字体"
                fileSelectionMode = JFileChooser.FILES_ONLY
                isAcceptAllFileFilterUsed = false
                fileFilter = FileNameExtensionFilter("字体文件 (*.ttf, *.otf, *.ttc)", "ttf", "otf", "ttc")
            }
            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@invokeLater
            val selected = chooser.selectedFile ?: return@invokeLater
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { store.importFont(selected.toPath()) } }
                result
                    .onSuccess { imported ->
                        refreshToken++
                        val family = imported.faces.first().family
                        runSuspendCatching {
                            repository.update {
                                it.copy(
                                    subtitleFont = family,
                                    subtitleFontDir = store.directory.toString(),
                                )
                            }
                        }.onFailure {
                            errorMessage = "保存字体设置失败：${it.message ?: it.javaClass.simpleName}"
                        }
                    }
                    .onFailure {
                        errorMessage = "导入字体失败：${it.message ?: it.javaClass.simpleName}"
                    }
            }
        }
    }

    val selectedFont = state.subtitleFont.ifBlank { "默认" }
    SubsectionTitle("字幕字体  (当前: $selectedFont)")

    // 已导入字体优先显示；同一 TTC 文件可包含多个可选择的字体 family。
    if (importedFonts.isNotEmpty()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(importedFonts) { font ->
                FilterChip(
                    selected = state.subtitleFont == font.family && state.subtitleFontDir == store.directory.toString(),
                    onClick = {
                        scope.launch {
                            repository.update {
                                it.copy(
                                    subtitleFont = font.family,
                                    subtitleFontDir = store.directory.toString(),
                                )
                            }
                        }
                    },
                    label = { Text(font.family, maxLines = 1) },
                )
            }
        }
    }

    if (systemFonts.isNotEmpty()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(systemFonts) { name ->
                FilterChip(
                    selected = state.subtitleFont == name && state.subtitleFontDir == null,
                    onClick = {
                        scope.launch {
                            repository.update { it.copy(subtitleFont = name, subtitleFontDir = null) }
                        }
                    },
                    label = { Text(name, maxLines = 1) },
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::chooseFontFile) {
                Text("导入字体")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        runSuspendCatching {
                            repository.update { it.copy(subtitleFont = "", subtitleFontDir = null) }
                        }.onFailure {
                            errorMessage = "恢复默认字体失败：${it.message ?: it.javaClass.simpleName}"
                        }
                    }
                },
            ) {
                Text("恢复默认")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { store.clearImportedFonts() } }
                        result
                            .onSuccess {
                                refreshToken++
                                runSuspendCatching {
                                    repository.update { it.copy(subtitleFont = "", subtitleFontDir = null) }
                                }.onFailure {
                                    errorMessage = "保存字体设置失败：${it.message ?: it.javaClass.simpleName}"
                                }
                            }
                            .onFailure {
                                errorMessage = "清除自定义字体失败：${it.message ?: it.javaClass.simpleName}"
                            }
                    }
                },
            ) {
                Text("清除")
            }
        }
        Text(
            "导入的 .ttf/.otf/.ttc 供字幕使用；系统字体直接选名。字体最大 64 MiB。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("字幕字体操作失败") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) { Text("确定") }
            },
        )
    }
}
