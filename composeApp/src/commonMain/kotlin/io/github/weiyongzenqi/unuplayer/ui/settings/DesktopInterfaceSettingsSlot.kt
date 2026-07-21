package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.SettingsState

/** Windows 专属的窗口/后台行为设置；Android actual 保持空实现。 */
@Composable
expect fun DesktopInterfaceSettingsSlot(
    state: SettingsState,
    scope: CoroutineScope,
    repository: SettingsRepository,
)
