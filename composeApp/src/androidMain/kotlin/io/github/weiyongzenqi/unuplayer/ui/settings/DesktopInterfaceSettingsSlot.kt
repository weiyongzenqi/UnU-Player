package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.SettingsState

@Composable
actual fun DesktopInterfaceSettingsSlot(
    state: SettingsState,
    scope: CoroutineScope,
    repository: SettingsRepository,
) = Unit
