package io.github.weiyongzenqi.unuplayer.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.platform.SystemFonts
import java.io.File

/**
 * 字幕字体设置区(Android 实现)。
 *
 * - 系统字体: 枚举 /system/fonts 下的字体名, 点击设为 sub-font
 * - 已导入字体: SAF 选 .ttf/.otf 拷到私有目录, sub-fonts-dir 指向它, 重启后仍在
 * - 清除: 删除已导入字体, sub-font/sub-fonts-dir 复位
 *
 * 字体名设给 sub-font; 字体目录设给 sub-fonts-dir(供 mpv 加载非系统字体)。
 */
@Composable
actual fun SubtitleFontsSlot(repository: SettingsRepository) {
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var refreshToken by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val fontSnapshot by produceState<AndroidFontSnapshot?>(null, context, refreshToken) {
        value = null
        runSuspendCatching {
            withContext(Dispatchers.IO) {
                AndroidFontSnapshot(
                    systemFonts = SystemFonts.listSystemFontNames(),
                    importedFonts = SystemFonts.listImportedFonts(context),
                    fontDirPath = SystemFonts.fontDirPath(context),
                )
            }
        }.onSuccess { value = it }
            .onFailure { error ->
                errorMessage = "读取字体失败：${error.message ?: error.javaClass.simpleName}"
            }
    }
    val systemFonts = fontSnapshot?.systemFonts.orEmpty()
    val importedFonts = fontSnapshot?.importedFonts.orEmpty()
    val selectedFont = state.subtitleFont.ifBlank { "默认" }

    SubsectionTitle("字幕字体  (当前: $selectedFont)")

    if (fontSnapshot == null) {
        CircularProgressIndicator(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }

    // 已导入字体优先显示
    if (importedFonts.isNotEmpty()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(importedFonts) { name ->
                FilterChip(
                    selected = state.subtitleFont == name && state.subtitleFontDir == fontSnapshot?.fontDirPath,
                    onClick = {
                        scope.launch {
                            repository.update {
                                it.copy(
                                    subtitleFont = name,
                                    subtitleFontDir = fontSnapshot?.fontDirPath,
                                )
                            }
                        }
                    },
                    label = { Text(name) },
                )
            }
        }
    }

    // 系统字体(可滚动 chip 行)
    if (systemFonts.isNotEmpty()) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(systemFonts) { name ->
                FilterChip(
                    selected = state.subtitleFont == name && state.subtitleFontDir == null,
                    onClick = {
                        scope.launch {
                            repository.update {
                                it.copy(subtitleFont = name, subtitleFontDir = null)
                            }
                        }
                    },
                    label = { Text(name, maxLines = 1) },
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // SAF 导入字体(单个 .ttf/.otf)
            val pickFontLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri: Uri? ->
                if (uri != null) {
                    scope.launch {
                        val result = runSuspendCatching {
                            withContext(Dispatchers.IO) {
                                val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.ttf"
                                val extension = displayName.substringAfterLast('.', "ttf")
                                    .lowercase()
                                    .filter(Char::isLetterOrDigit)
                                    .take(4)
                                    .ifEmpty { "ttf" }
                                val name = displayName.substringBeforeLast('.').ifBlank { "imported" }
                                val tmp = File.createTempFile("font_import_", ".$extension", context.cacheDir)
                                try {
                                    val input = checkNotNull(context.contentResolver.openInputStream(uri)) {
                                        "无法打开字体文件"
                                    }
                                    input.use { source ->
                                        tmp.outputStream().use { output -> source.copyTo(output) }
                                    }
                                    name to SystemFonts.importFont(context, tmp, name)
                                } finally {
                                    tmp.delete()
                                }
                            }
                        }
                        result.onSuccess { (name, dir) ->
                            runSuspendCatching {
                                repository.update {
                                    it.copy(subtitleFont = name, subtitleFontDir = dir)
                                }
                            }.onSuccess {
                                refreshToken++
                            }.onFailure { error ->
                                errorMessage = "保存字体设置失败：${error.message ?: error.javaClass.simpleName}"
                            }
                        }.onFailure { error ->
                            errorMessage = "导入字体失败：${error.message ?: error.javaClass.simpleName}"
                        }
                    }
                }
            }
            Button(onClick = { pickFontLauncher.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream")) }) {
                Text("导入字体")
            }
            OutlinedButton(onClick = {
                scope.launch {
                    val result = runSuspendCatching {
                        withContext(Dispatchers.IO) { SystemFonts.clearFonts(context) }
                        repository.update { it.copy(subtitleFont = "", subtitleFontDir = null) }
                    }
                    result.onSuccess { refreshToken++ }
                        .onFailure { error ->
                            errorMessage = "清除字体失败：${error.message ?: error.javaClass.simpleName}"
                        }
                }
            }) {
                Text("清除")
            }
        }
        Text(
            "导入的 .ttf/.otf 供字幕使用; 系统字体直接选名。部分中文字体需导入 Noto Sans CJK。",
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

private data class AndroidFontSnapshot(
    val systemFonts: List<String>,
    val importedFonts: List<String>,
    val fontDirPath: String,
)
