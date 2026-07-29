package io.github.weiyongzenqi.unuplayer.app

import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.domain.SettingsLoadState
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerPreparedPlayback

internal class PreparedPlayerPlayback(
    val url: String,
    val headers: Map<String, String>,
    val contentUri: String?,
    val mediaKey: String?,
    val sourceKind: MediaSourceKind,
    val initialPositionMs: Long = 0L,
    val mediaServerPlayback: MediaServerPreparedPlayback? = null,
    /** TMDB ID(刮削番剧跨库续播锚点)。非刮削路径为 null。 */
    val tmdbId: Long? = null,
    /** 季号(刮削番剧跨库续播锚点)。非刮削路径为 null。 */
    val seasonNumber: Long? = null,
    /** 集号(刮削番剧跨库续播锚点)。非刮削路径为 null。 */
    val episodeNumber: Long? = null,
) {
    override fun toString(): String =
        "PreparedPlayerPlayback(url=<redacted>, headers=<redacted>, " +
            "contentUri=${if (contentUri == null) "null" else "<redacted>"}, mediaKey=$mediaKey, " +
            "sourceKind=$sourceKind, initialPositionMs=$initialPositionMs, " +
            "mediaServerPlayback=${if (mediaServerPlayback == null) "null" else "<redacted>"}, " +
            "tmdbId=$tmdbId, seasonNumber=$seasonNumber, episodeNumber=$episodeNumber)"
}

internal sealed interface PlaybackCredentialLoadState {
    data object Loading : PlaybackCredentialLoadState
    data class Ready(val playback: PreparedPlayerPlayback) : PlaybackCredentialLoadState
    data class Failed(val message: String) : PlaybackCredentialLoadState
}

internal sealed interface PlayerStartupDestination {
    data object Loading : PlayerStartupDestination
    data class SettingsFailed(val message: String) : PlayerStartupDestination
    data class CredentialsFailed(val message: String) : PlayerStartupDestination
    data object Disclaimer : PlayerStartupDestination
    data class Player(val playback: PreparedPlayerPlayback) : PlayerStartupDestination
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
            PlayerStartupDestination.Player(credentialLoadState.playback)
        } else {
            PlayerStartupDestination.Disclaimer
        }
    }
}
