package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.SettingsState

@Composable
actual fun DesktopInterfaceSettingsSlot(
    state: SettingsState,
    scope: CoroutineScope,
    repository: SettingsRepository,
) {
    Column(Modifier.fillMaxWidth()) {
        SubsectionTitle("Windows 窗口")
        SwitchRow(
            title = "允许后台运行",
            subtitle = "关闭主窗口时最小化到任务栏，播放和扫描继续运行",
            checked = state.desktopRunInBackground,
            onCheckedChange = { enabled ->
                scope.launch { repository.update { it.copy(desktopRunInBackground = enabled) } }
            },
        )
        SwitchRow(
            title = "关闭时询问",
            subtitle = "关闭主窗口前选择退出或最小化；可用于恢复已关闭的提示",
            checked = state.desktopClosePrompt,
            onCheckedChange = { enabled ->
                scope.launch { repository.update { it.copy(desktopClosePrompt = enabled) } }
            },
        )
    }
}
