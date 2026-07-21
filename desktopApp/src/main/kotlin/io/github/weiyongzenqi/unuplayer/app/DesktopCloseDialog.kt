package io.github.weiyongzenqi.unuplayer.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** 主窗口关闭选择。勾选后台运行时，“确定”只最小化；否则真正退出进程。 */
@Composable
internal fun DesktopCloseDialog(
    initialRunInBackground: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (runInBackground: Boolean, dontAskAgain: Boolean) -> Unit,
) {
    var runInBackground by remember(initialRunInBackground) { mutableStateOf(initialRunInBackground) }
    var dontAskAgain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关闭 UnU Player") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("你可以退出程序，也可以最小化到后台继续播放或扫描。")
                CheckRow(
                    text = "允许后台运行（关闭时最小化）",
                    checked = runInBackground,
                    onCheckedChange = { runInBackground = it },
                )
                CheckRow(
                    text = "以后不再提示",
                    checked = dontAskAgain,
                    onCheckedChange = { dontAskAgain = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(runInBackground, dontAskAgain) }) {
                Text(if (runInBackground) "最小化" else "退出程序")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun CheckRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 整行统一处理点击，避免 Checkbox 与父 Row 同时翻转两次。
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}
