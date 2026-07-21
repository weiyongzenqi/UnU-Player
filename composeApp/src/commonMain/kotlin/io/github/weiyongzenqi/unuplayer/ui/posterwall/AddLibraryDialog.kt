package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.library.rememberLocalDirPicker
import io.github.weiyongzenqi.unuplayer.library.ScanMode

/**
 * 添加刮削库对话框。
 *
 * 来源单选: 本地(SAF 目录) / WebDAV(已添加连接 + 路径)。
 * 确定 -> onConfirm(name, sourceKind, connectionId, localUri, rootPath)。
 *
 * - WebDAV: connectionId=选中连接 id, localUri=null, rootPath=路径输入(默认 "/")
 * - 本地: connectionId=null, localUri=pickedUri, rootPath=tree uri 本身
 */
@Composable
fun AddLibraryDialog(
    webDavConnections: List<WebDavConnection>,
    onConfirm: (name: String, sourceKind: MediaSourceKind, connectionId: String?, localUri: String?, rootPath: String, scanMode: ScanMode, anchorFilenames: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var isWebDav by remember { mutableStateOf(true) }
    var selectedConn by remember { mutableStateOf<WebDavConnection?>(null) }
    var rootPath by remember { mutableStateOf("/") }
    var scanMode by remember { mutableStateOf(ScanMode.NFO) }
    var anchorInput by remember { mutableStateOf("folder.jpg") }
    var connMenuExpanded by remember { mutableStateOf(false) }
    val localPicker = rememberLocalDirPicker()

    val canConfirm = name.isNotBlank() && (
        (isWebDav && selectedConn != null && rootPath.isNotBlank()) ||
        (!isWebDav && localPicker.pickedUri != null)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加刮削库") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    RadioButton(selected = !isWebDav, onClick = { isWebDav = false })
                    Text("本地")
                    RadioButton(selected = isWebDav, onClick = { isWebDav = true })
                    Text("WebDAV")
                }
                // 扫描模式: NFO(tvshow.nfo 刮削) / ANCHOR(本地锚点封面+文件夹名, 不刮削)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    RadioButton(selected = scanMode == ScanMode.NFO, onClick = { scanMode = ScanMode.NFO })
                    Text("NFO 刮削")
                    RadioButton(selected = scanMode == ScanMode.ANCHOR, onClick = { scanMode = ScanMode.ANCHOR })
                    Text("本地锚点")
                }
                if (scanMode == ScanMode.ANCHOR) {
                    OutlinedTextField(
                        value = anchorInput,
                        onValueChange = { anchorInput = it },
                        label = { Text("锚点封面文件名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Text(
                        text = "多个用逗号分隔, 如 folder.jpg,poster.jpg,cover.jpg(大小写不敏感)。季文件夹需命名为 Season N",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                if (isWebDav) {
                    // 不用 OutlinedTextField(readOnly + modifier.clickable): readOnly TextField 内部
                    // 消费点击(聚焦), 外层 clickable 不触发, 下拉不弹。改 Box+Text+clickable, Box 直接消费。
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { connMenuExpanded = true },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "WebDAV 连接",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    selectedConn?.name ?: "选择连接",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = connMenuExpanded,
                            onDismissRequest = { connMenuExpanded = false },
                        ) {
                            if (webDavConnections.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("还没有 WebDAV 连接") },
                                    onClick = { connMenuExpanded = false },
                                )
                            } else {
                                webDavConnections.forEach { conn ->
                                    DropdownMenuItem(
                                        text = { Text(conn.name) },
                                        onClick = { selectedConn = conn; connMenuExpanded = false },
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = rootPath,
                        onValueChange = { rootPath = it },
                        label = { Text("根路径") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        Button(onClick = { localPicker.pick() }) { Text("选择目录") }
                        Text(
                            text = localPicker.pickedName ?: "未选择",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    val anchors = anchorInput.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (isWebDav) {
                        onConfirm(
                            name.trim(),
                            MediaSourceKind.WEBDAV,
                            selectedConn?.id,
                            null,
                            rootPath.trim().ifBlank { "/" },
                            scanMode,
                            anchors,
                        )
                    } else {
                        val uri = localPicker.pickedUri ?: return@TextButton
                        onConfirm(name.trim(), MediaSourceKind.LOCAL, null, uri, uri, scanMode, anchors)
                    }
                },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
