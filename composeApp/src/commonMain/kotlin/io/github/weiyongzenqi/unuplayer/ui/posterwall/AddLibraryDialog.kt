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
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.library.rememberLocalDirPicker
import io.github.weiyongzenqi.unuplayer.library.ScanMode

internal data class LibrarySourceChoice(
    val sourceKind: MediaSourceKind,
    val connectionId: String?,
    val displayName: String,
    val available: Boolean = true,
)

internal fun buildLibrarySourceChoices(
    webDavConnections: List<WebDavConnection>,
    smbConnections: List<SmbConnection>,
): List<LibrarySourceChoice> = buildList {
    add(LibrarySourceChoice(MediaSourceKind.LOCAL, null, "本地目录"))
    webDavConnections.forEach { connection ->
        add(
            LibrarySourceChoice(
                sourceKind = MediaSourceKind.WEBDAV,
                connectionId = connection.id,
                displayName = "WebDAV · ${connection.name}" +
                    if (connection.credentialUnavailable) "（凭据不可用）" else "",
                available = !connection.credentialUnavailable,
            ),
        )
    }
    smbConnections.forEach { connection ->
        add(
            LibrarySourceChoice(
                sourceKind = MediaSourceKind.SMB,
                connectionId = connection.id,
                displayName = "SMB · ${connection.name}" +
                    if (connection.credentialUnavailable) "（凭据不可用）" else "",
                available = !connection.credentialUnavailable,
            ),
        )
    }
}

internal fun librarySourceKindLabel(sourceKind: MediaSourceKind): String = when (sourceKind) {
    MediaSourceKind.LOCAL -> "本地"
    MediaSourceKind.WEBDAV -> "WebDAV"
    MediaSourceKind.SMB -> "SMB"
    MediaSourceKind.FTP -> "FTP"
    MediaSourceKind.JELLYFIN -> "Jellyfin"
    MediaSourceKind.EMBY -> "Emby"
    MediaSourceKind.EXTERNAL -> "外部来源"
}

/**
 * 添加刮削库对话框。
 *
 * 直接选择可用的文件树来源：本地目录、已保存的 WebDAV 连接或 SMB 连接。
 * 确定 -> onConfirm(name, sourceKind, connectionId, localUri, rootPath)。
 *
 * - WebDAV/SMB: connectionId=选中连接 id, localUri=null, rootPath=路径输入(默认 "/")
 * - 本地: connectionId=null, localUri=pickedUri, rootPath=tree uri 本身
 */
@Composable
fun AddLibraryDialog(
    webDavConnections: List<WebDavConnection>,
    smbConnections: List<SmbConnection> = emptyList(),
    onConfirm: (name: String, sourceKind: MediaSourceKind, connectionId: String?, localUri: String?, rootPath: String, scanMode: ScanMode, anchorFilenames: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val sourceChoices = remember(webDavConnections, smbConnections) {
        buildLibrarySourceChoices(webDavConnections, smbConnections)
    }
    var selectedSource by remember(sourceChoices) {
        mutableStateOf(sourceChoices.firstOrNull { it.available })
    }
    var rootPath by remember { mutableStateOf("/") }
    var scanMode by remember { mutableStateOf(ScanMode.NFO) }
    var anchorInput by remember { mutableStateOf("folder.jpg") }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    val localPicker = rememberLocalDirPicker()

    val canConfirm = name.isNotBlank() && selectedSource?.let { source ->
        source.available && if (source.sourceKind == MediaSourceKind.LOCAL) {
            localPicker.pickedUri != null
        } else {
            source.connectionId != null && rootPath.isNotBlank()
        }
    } == true

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
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { sourceMenuExpanded = true },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "媒体来源",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(selectedSource?.displayName ?: "选择来源", style = MaterialTheme.typography.bodyLarge)
                        }
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = sourceMenuExpanded,
                        onDismissRequest = { sourceMenuExpanded = false },
                    ) {
                        sourceChoices.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(source.displayName) },
                                enabled = source.available,
                                onClick = {
                                    selectedSource = source
                                    sourceMenuExpanded = false
                                },
                            )
                        }
                    }
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
                    Text("锚点模式")
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
                        text = "多个用逗号分隔，如 folder.jpg,poster.jpg,cover.jpg（大小写不敏感）。季目录支持 Season 2、S02、第2季及名称前后附加文本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                if (selectedSource?.sourceKind != MediaSourceKind.LOCAL) {
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
                    val source = selectedSource ?: return@TextButton
                    if (source.sourceKind != MediaSourceKind.LOCAL) {
                        onConfirm(
                            name.trim(),
                            source.sourceKind,
                            source.connectionId,
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
