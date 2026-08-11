package io.github.weiyongzenqi.unuplayer.ui.source

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import kotlin.random.Random

@Composable
internal fun AddSmbConnectionDialog(
    onConfirm: (SmbConnection) -> Unit,
    onDismiss: () -> Unit,
    initialConnection: SmbConnection? = null,
) {
    var name by remember(initialConnection) { mutableStateOf(initialConnection?.name.orEmpty()) }
    var host by remember(initialConnection) { mutableStateOf(initialConnection?.host.orEmpty()) }
    var port by remember(initialConnection) { mutableStateOf((initialConnection?.port ?: 445).toString()) }
    var share by remember(initialConnection) { mutableStateOf(initialConnection?.share.orEmpty()) }
    var username by remember(initialConnection) { mutableStateOf(initialConnection?.username.orEmpty()) }
    var password by remember(initialConnection) { mutableStateOf("") }
    var useEmptyPassword by remember(initialConnection) { mutableStateOf(false) }
    var domain by remember(initialConnection) { mutableStateOf(initialConnection?.domain.orEmpty()) }
    var requireEncryption by remember(initialConnection) { mutableStateOf(initialConnection?.requireEncryption ?: false) }
    val validPort = port.toIntOrNull()?.let { it in 1..65535 } == true
    val valid = name.isNotBlank() && host.isNotBlank() && share.isNotBlank() && username.isNotBlank() && validPort &&
        smbPasswordReady(initialConnection, password, useEmptyPassword)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialConnection == null) "添加 SMB 连接" else "编辑 SMB 连接") },
        text = {
            Column {
                field("名称", name) { name = it }
                field("主机或 IP", host) { host = it }
                field("端口", port, KeyboardType.Number) { port = it }
                field("共享名", share) { share = it }
                field("用户名", username) { username = it }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; useEmptyPassword = false },
                    label = { Text(if (initialConnection == null) "密码" else "密码（留空保持不变）") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !useEmptyPassword,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                if (initialConnection != null) {
                    androidx.compose.foundation.layout.Row {
                        Checkbox(checked = useEmptyPassword, onCheckedChange = { useEmptyPassword = it })
                        Text("使用空密码", modifier = Modifier.padding(top = 12.dp))
                    }
                }
                field("域（可选）", domain) { domain = it }
                androidx.compose.foundation.layout.Row {
                    Checkbox(checked = requireEncryption, onCheckedChange = { requireEncryption = it })
                    Text("要求 SMB3 加密", modifier = Modifier.padding(top = 12.dp))
                }
                Text(
                    "默认启用签名协商；勾选后服务端不支持 SMB3 加密时连接会失败。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        SmbConnection(
                            id = initialConnection?.id ?: randomId(),
                            name = name.trim(), host = host.trim(), port = port.toInt(), share = share.trim(),
                            username = username.trim(),
                            password = effectiveSmbPassword(
                                enteredPassword = password,
                                storedPassword = initialConnection?.password.orEmpty(),
                                useEmptyPassword = useEmptyPassword,
                            ),
                            domain = domain.trim(), requireEncryption = requireEncryption,
                        ),
                    )
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal fun smbPasswordReady(
    initialConnection: SmbConnection?,
    enteredPassword: String,
    useEmptyPassword: Boolean,
): Boolean = initialConnection == null || useEmptyPassword ||
    enteredPassword.isNotEmpty() || initialConnection.password.isNotEmpty()

internal fun effectiveSmbPassword(
    enteredPassword: String,
    storedPassword: String,
    useEmptyPassword: Boolean,
): String = when {
    useEmptyPassword -> ""
    enteredPassword.isNotEmpty() -> enteredPassword
    else -> storedPassword
}

@Composable
private fun field(label: String, value: String, keyboardType: KeyboardType = KeyboardType.Text, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

private fun randomId(): String = Random.nextBytes(16).joinToString("") { "%02x".format(it) }
