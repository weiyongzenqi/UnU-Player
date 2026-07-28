package io.github.weiyongzenqi.unuplayer.ui.mediaserver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerConnectionSummary
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerVendor
import kotlinx.coroutines.launch

@Composable
internal fun AddMediaServerConnectionDialog(
    vendor: MediaServerVendor,
    connect: suspend (AddMediaServerConnectionSubmission) -> MediaServerConnectionSummary,
    onConnected: (MediaServerConnectionSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = remember(vendor) { AddMediaServerConnectionState(vendor) }
    val scope = rememberCoroutineScope()

    fun submit(submission: AddMediaServerConnectionSubmission) {
        scope.launch {
            state.isSubmitting = true
            state.errorMessage = null
            try {
                runSuspendCatching { connect(submission) }.fold(
                    onSuccess = { summary ->
                        state.password = ""
                        onConnected(summary)
                    },
                    onFailure = { state.errorMessage = "连接失败，请检查地址、凭据和网络" },
                )
            } finally {
                state.isSubmitting = false
            }
        }
    }

    if (state.awaitingCleartextConfirmation) {
        AlertDialog(
            onDismissRequest = state::returnToForm,
            title = { Text("确认使用明文 HTTP") },
            text = {
                Text("HTTP 不会加密认证信息和媒体数据，仅应在可信局域网或 VPN 内使用。")
            },
            confirmButton = {
                TextButton(onClick = { state.confirmCleartext()?.let(::submit) }) {
                    Text("仍然连接")
                }
            },
            dismissButton = {
                TextButton(onClick = state::returnToForm) { Text("返回") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!state.isSubmitting) onDismiss() },
        title = { Text("添加 ${vendor.displayName()}") },
        text = {
            Column {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { state.name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    enabled = !state.isSubmitting,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = { state.baseUrl = it },
                    label = { Text("服务器地址") },
                    singleLine = true,
                    enabled = !state.isSubmitting,
                    isError = !state.urlValidation.isValid,
                    supportingText = state.urlValidation.errorMessage?.let { message ->
                        ({ Text(message) })
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = state.username,
                    onValueChange = { state.username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    enabled = !state.isSubmitting,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { state.password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    enabled = !state.isSubmitting,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                state.errorMessage?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { state.requestSubmit()?.let(::submit) },
                enabled = state.canSubmit,
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text("连接")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isSubmitting) { Text("取消") }
        },
    )
}

private fun MediaServerVendor.displayName(): String = when (this) {
    MediaServerVendor.JELLYFIN -> "Jellyfin"
    MediaServerVendor.EMBY -> "Emby"
}
