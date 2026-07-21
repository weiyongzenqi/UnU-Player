package io.github.weiyongzenqi.unuplayer.library

import androidx.compose.runtime.Composable

/** 本地目录选择器状态(SAF ACTION_OPEN_DOCUMENT_TREE, actual 在 androidMain)。 */
class LocalDirPickerState(
    val pick: () -> Unit,        // 触发系统目录选择
    val pickedUri: String?,      // 已选 tree uri(content://); null=未选
    val pickedName: String?,     // 已选目录显示名
    val clear: () -> Unit,       // 清除选择
)

@Composable
expect fun rememberLocalDirPicker(): LocalDirPickerState
