package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.ScanMode

/**
 * 编辑刮削库对话框。
 *
 * 可改: name; WebDAV 库的 root_path。
 * 不可改: source_kind / connection / local_uri(改了关联数据不匹配; 本地 SAF tree uri 改要重授权)。
 *
 * - WebDAV: root_path 可编辑(预填当前值)
 * - 本地: root_path 只读(提示"路径不可改, 请删除后重新添加")
 *
 * scan_depth 保持原值(不暴露 UI, 调用方 updateLibrary 传 library.scanDepth)。
 */
@Composable
fun EditLibraryDialog(
    library: LibraryConfig,
    onConfirm: (name: String, rootPath: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(library.name) }
    var rootPath by remember { mutableStateOf(library.rootPath) }
    val isLocal = library.sourceKind == MediaSourceKind.LOCAL

    val canConfirm = name.isNotBlank() && (name != library.name || rootPath != library.rootPath)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑刮削库") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                // 来源只读标签
                Text(
                    text = "来源: ${if (isLocal) "本地" else "WebDAV"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                // 扫描模式只读(建库时定, 不可改, 同来源)
                Text(
                    text = "扫描模式: ${if (library.scanMode == ScanMode.ANCHOR) "本地锚点" else "NFO 刮削"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                if (isLocal) {
                    // 本地 SAF tree uri 不可改(改要重新授权), 只读展示 + 提示
                    OutlinedTextField(
                        value = rootPath,
                        onValueChange = { },
                        label = { Text("根路径(不可改)") },
                        singleLine = true,
                        readOnly = true,
                        enabled = false,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Text(
                        text = "本地库路径不可改, 如需更换请删除后重新添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                } else {
                    OutlinedTextField(
                        value = rootPath,
                        onValueChange = { rootPath = it },
                        label = { Text("根路径") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { onConfirm(name.trim(), rootPath.trim().ifBlank { library.rootPath }) },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
