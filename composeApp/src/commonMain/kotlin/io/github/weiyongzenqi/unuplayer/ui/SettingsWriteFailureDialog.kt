package io.github.weiyongzenqi.unuplayer.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import kotlinx.coroutines.launch

/** 进程级设置写失败出口；正文只使用仓库提供的安全消息，不展开底层异常。 */
@Composable
fun SettingsWriteFailureDialog(repository: SettingsRepository) {
    val failure by repository.writeFailure.collectAsState()
    val current = failure ?: return
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { scope.launch { repository.dismissWriteFailure() } },
        title = { Text("设置未保存") },
        text = { Text(current.message) },
        confirmButton = {
            if (current.retryAvailable) {
                TextButton(onClick = { scope.launch { repository.retryLastUpdate() } }) {
                    Text("重试")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { scope.launch { repository.dismissWriteFailure() } }) {
                Text("关闭")
            }
        },
    )
}
