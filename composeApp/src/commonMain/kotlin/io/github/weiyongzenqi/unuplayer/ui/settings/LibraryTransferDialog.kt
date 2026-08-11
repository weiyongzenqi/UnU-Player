package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.export.ConnectionCandidate
import io.github.weiyongzenqi.unuplayer.library.export.ConnectionEdit
import io.github.weiyongzenqi.unuplayer.library.export.ExportOptions
import io.github.weiyongzenqi.unuplayer.library.export.ImportOptions
import io.github.weiyongzenqi.unuplayer.library.export.LIBRARY_EXPORT_MIN_PASSWORD_LENGTH
import io.github.weiyongzenqi.unuplayer.library.export.ZipPayload
import io.github.weiyongzenqi.unuplayer.library.export.hasLibraryNameConflict
import io.github.weiyongzenqi.unuplayer.library.export.passwordValue

/**
 * 媒体库导出/导入对话框(commonMain 纯展示, 无平台依赖)。
 * 平台层(androidMain/desktopMain)负责文件选择与执行流程。
 */
object LibraryTransferDialog {
    @Composable
    fun ExportOptionsDialog(
        libraryName: String,
        options: ExportOptions,
        onOptionsChange: (ExportOptions) -> Unit,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        var passwordConfirmation by remember(libraryName) { mutableStateOf("") }
        val passwordReady = !options.includePassword || (
            options.exportPassword.orEmpty().length >= LIBRARY_EXPORT_MIN_PASSWORD_LENGTH &&
                options.exportPassword == passwordConfirmation
            )
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("导出「$libraryName」") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OptionRow("带入连接密码", "使用迁移口令加密，目标设备导入时需要再次输入", options.includePassword) {
                        if (it) {
                            onOptionsChange(options.copy(includePassword = true))
                        } else {
                            passwordConfirmation = ""
                            onOptionsChange(options.copy(includePassword = false, exportPassword = null))
                        }
                    }
                    if (options.includePassword) {
                        OutlinedTextField(
                            value = options.exportPassword.orEmpty(),
                            onValueChange = { onOptionsChange(options.copy(exportPassword = it)) },
                            label = { Text("迁移口令（至少 8 个字符）") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        OutlinedTextField(
                            value = passwordConfirmation,
                            onValueChange = { passwordConfirmation = it },
                            label = { Text("再次输入迁移口令") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    OptionRow("包含图片", "本地缓存图（季照/头图/集照），默认关", options.includeImages) {
                        onOptionsChange(options.copy(includeImages = it))
                    }
                    OptionRow("播放进度", "各集看到哪、续播点", options.includePlayback) {
                        onOptionsChange(options.copy(includePlayback = it))
                    }
                    OptionRow("本部设置", "每部弹幕/字幕/音轨覆盖", options.includeOverrides) {
                        onOptionsChange(options.copy(includeOverrides = it))
                    }
                    OptionRow("屏蔽列表", "删除/屏蔽的番剧记录", options.includeBlocked) {
                        onOptionsChange(options.copy(includeBlocked = it))
                    }
                }
            },
            confirmButton = {
                Button(enabled = passwordReady, onClick = onConfirm) { Text("选择保存位置") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }

    @Composable
    fun ImportPreviewDialog(
        payload: ZipPayload,
        candidate: ConnectionCandidate,
        existingLibraries: List<LibraryConfig>,
        onConfirm: (
            targetName: String,
            edit: ConnectionEdit?,
            exportPassword: String?,
            options: ImportOptions,
        ) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val data = payload.data
        var targetName by remember { mutableStateOf(data.library.name) }
        var includeImages by remember { mutableStateOf(false) }
        var includePlayback by remember { mutableStateOf(true) }
        var includeOverrides by remember { mutableStateOf(true) }
        var includeBlocked by remember { mutableStateOf(true) }
        var scanAfterImport by remember { mutableStateOf(false) }
        var exportPassword by remember { mutableStateOf("") }
        var edit by remember(candidate) { mutableStateOf((candidate as? ConnectionCandidate.Create)?.edit) }
        val conflict = hasLibraryNameConflict(existingLibraries.map { it.name }, targetName)
        val editedPassword = edit?.passwordValue.orEmpty()
        val protectedPasswordForNewConnection =
            candidate is ConnectionCandidate.Create && candidate.passwordProtected
        val migrationPasswordReady = !protectedPasswordForNewConnection || editedPassword.isNotBlank() ||
            exportPassword.length >= LIBRARY_EXPORT_MIN_PASSWORD_LENGTH

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("导入媒体库") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when (candidate) {
                        is ConnectionCandidate.Reuse ->
                            Text("将复用现有连接：${candidate.name}（${candidate.type}）", style = MaterialTheme.typography.bodySmall)
                        is ConnectionCandidate.Create -> {
                            Text("将新建连接（${candidate.type}）", style = MaterialTheme.typography.bodySmall)
                            ConnectionEditFields(candidate.type, edit) { edit = it }
                        }
                    }
                    if (protectedPasswordForNewConnection) {
                        OutlinedTextField(
                            value = exportPassword,
                            onValueChange = { exportPassword = it },
                            label = { Text("迁移口令（手动填写连接密码时可留空）") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    OutlinedTextField(
                        value = targetName,
                        onValueChange = { targetName = it },
                        label = { Text("媒体库名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    if (conflict) {
                        Text("已存在同名媒体库，请换一个名称后再导入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "包含 ${data.shows.size} 部番剧${if (data.library.rootPath.isNotBlank()) "，根目录 ${data.library.rootPath}" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OptionRow("包含图片", "还原本地缓存图（季照/头图/集照）", includeImages) { includeImages = it }
                    OptionRow("播放进度", "各集看到哪、续播点", includePlayback) { includePlayback = it }
                    OptionRow("本部设置", "每部弹幕/字幕/音轨覆盖", includeOverrides) { includeOverrides = it }
                    OptionRow("屏蔽列表", "删除/屏蔽的番剧记录", includeBlocked) { includeBlocked = it }
                    OptionRow("导入后扫描验证", "导入完成立即扫描一次", scanAfterImport) { scanAfterImport = it }
                }
            },
            confirmButton = {
                Button(
                    enabled = targetName.isNotBlank() && !conflict && migrationPasswordReady,
                    onClick = {
                        onConfirm(
                            targetName.trim(),
                            edit,
                            exportPassword.takeIf { protectedPasswordForNewConnection && it.isNotBlank() },
                            ImportOptions(includeImages, includePlayback, includeOverrides, includeBlocked, scanAfterImport),
                        )
                    },
                ) { Text("开始导入") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }

    @Composable
    private fun ConnectionEditFields(type: String, edit: ConnectionEdit?, onEdit: (ConnectionEdit?) -> Unit) {
        when (type) {
            "WEBDAV" -> {
                val current = edit as? ConnectionEdit.WebDav ?: ConnectionEdit.WebDav("", "", "", "")
                OutlinedTextField(value = current.name, onValueChange = { onEdit(current.copy(name = it)) }, label = { Text("连接名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = current.baseUrl, onValueChange = { onEdit(current.copy(baseUrl = it)) }, label = { Text("地址") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                OutlinedTextField(value = current.username, onValueChange = { onEdit(current.copy(username = it)) }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                OutlinedTextField(value = current.password, onValueChange = { onEdit(current.copy(password = it)) }, label = { Text("密码（留空则不填）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                if (current.baseUrl.trim().startsWith("http://", ignoreCase = true)) {
                    OptionRow("允许 HTTP", "仅对明确授权的明文 WebDAV 地址生效", current.allowCleartext) {
                        onEdit(current.copy(allowCleartext = it))
                    }
                }
            }
            "SMB" -> {
                val current = edit as? ConnectionEdit.Smb ?: ConnectionEdit.Smb("", "", 445, "", "", "", false, "")
                OutlinedTextField(value = current.name, onValueChange = { onEdit(current.copy(name = it)) }, label = { Text("连接名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = current.host, onValueChange = { onEdit(current.copy(host = it)) }, label = { Text("主机") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                OutlinedTextField(value = current.port.toString(), onValueChange = { onEdit(current.copy(port = it.toIntOrNull() ?: 445)) }, label = { Text("端口") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                OutlinedTextField(value = current.share, onValueChange = { onEdit(current.copy(share = it)) }, label = { Text("共享名") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                OutlinedTextField(value = current.username, onValueChange = { onEdit(current.copy(username = it)) }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                OutlinedTextField(value = current.domain, onValueChange = { onEdit(current.copy(domain = it)) }, label = { Text("域（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                OptionRow("要求 SMB 加密", "连接必须协商 SMB3 加密", current.requireEncryption) {
                    onEdit(current.copy(requireEncryption = it))
                }
                OutlinedTextField(value = current.password, onValueChange = { onEdit(current.copy(password = it)) }, label = { Text("密码（留空则不填）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }

    @Composable
    private fun OptionRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

internal fun libraryImportCompletionMessage(
    showCount: Int,
    playbackImported: Boolean,
    imagesRestored: Boolean,
    scanStarted: Boolean,
): String {
    val failures = buildList {
        if (!playbackImported) add("播放进度导入")
        if (!imagesRestored) add("图片恢复")
        if (!scanStarted) add("扫描启动")
    }
    return if (failures.isEmpty()) {
        "媒体库导入完成：$showCount 部番剧"
    } else {
        "媒体库已导入，但${failures.joinToString("、")}失败"
    }
}
