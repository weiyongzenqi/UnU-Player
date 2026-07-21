package io.github.weiyongzenqi.unuplayer.app

import io.github.weiyongzenqi.unuplayer.domain.SettingsLoadState

internal sealed interface PlaybackCredentialLoadState {
    data object Loading : PlaybackCredentialLoadState
    data class Ready(val headers: Map<String, String>) : PlaybackCredentialLoadState
    data class Failed(val message: String) : PlaybackCredentialLoadState
}

internal sealed interface PlayerStartupDestination {
    data object Loading : PlayerStartupDestination
    data class SettingsFailed(val message: String) : PlayerStartupDestination
    data class CredentialsFailed(val message: String) : PlayerStartupDestination
    data object Disclaimer : PlayerStartupDestination
    data class Player(val headers: Map<String, String>) : PlayerStartupDestination
}

/** init-only 设置和播放凭据都成功后，才允许进入免责声明/播放器。 */
internal fun resolvePlayerStartupDestination(
    settingsLoadState: SettingsLoadState,
    credentialLoadState: PlaybackCredentialLoadState,
    disclaimerAccepted: Boolean,
): PlayerStartupDestination = when (settingsLoadState) {
    SettingsLoadState.Loading -> PlayerStartupDestination.Loading
    is SettingsLoadState.Failed -> PlayerStartupDestination.SettingsFailed(settingsLoadState.message)
    SettingsLoadState.Loaded -> when (credentialLoadState) {
        PlaybackCredentialLoadState.Loading -> PlayerStartupDestination.Loading
        is PlaybackCredentialLoadState.Failed ->
            PlayerStartupDestination.CredentialsFailed(credentialLoadState.message)
        is PlaybackCredentialLoadState.Ready -> if (disclaimerAccepted) {
            PlayerStartupDestination.Player(credentialLoadState.headers)
        } else {
            PlayerStartupDestination.Disclaimer
        }
    }
}
