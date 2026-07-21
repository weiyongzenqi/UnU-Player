package io.github.weiyongzenqi.unuplayer.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/**
 * 桌面实现, 对应 androidMain 的 LocalDirPicker.android.kt。
 *
 * Android 用 SAF ACTION_OPEN_DOCUMENT_TREE; 桌面用 javax.swing.JFileChooser
 * (DIRECTORIES_ONLY)。JFileChooser 必须在 EDT 调用, 这里用 SwingUtilities.invokeLater。
 *
 * pickedUri 存选中目录的绝对路径字符串(与 Android content:// 对应, 桌面为 file path)。
 */
@Composable
actual fun rememberLocalDirPicker(): LocalDirPickerState {
    var uri by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    return LocalDirPickerState(
        pick = {
            // JFileChooser 必须在 EDT 调用; invokeLater 将其投递到 EDT 事件队列
            SwingUtilities.invokeLater {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    val dir = chooser.selectedFile
                    uri = dir.absolutePath
                    name = dir.name
                }
            }
        },
        pickedUri = uri,
        pickedName = name,
        clear = { uri = null; name = null },
    )
}
