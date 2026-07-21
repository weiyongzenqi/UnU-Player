package io.github.weiyongzenqi.unuplayer.app

import io.github.weiyongzenqi.unuplayer.domain.SettingsLoadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlayerStartupGateTest {

    @Test
    fun `设置加载中优先阻止播放器启动`() {
        val destination = resolvePlayerStartupDestination(
            SettingsLoadState.Loading,
            PlaybackCredentialLoadState.Ready(mapOf("Authorization" to "canary")),
            disclaimerAccepted = true,
        )

        assertEquals(PlayerStartupDestination.Loading, destination)
    }

    @Test
    fun `设置失败只能进入设置恢复页`() {
        val destination = resolvePlayerStartupDestination(
            SettingsLoadState.Failed("settings failed"),
            PlaybackCredentialLoadState.Ready(emptyMap()),
            disclaimerAccepted = true,
        )

        assertEquals(PlayerStartupDestination.SettingsFailed("settings failed"), destination)
    }

    @Test
    fun `凭据加载和失败都阻止播放器启动`() {
        assertEquals(
            PlayerStartupDestination.Loading,
            resolvePlayerStartupDestination(
                SettingsLoadState.Loaded,
                PlaybackCredentialLoadState.Loading,
                disclaimerAccepted = true,
            ),
        )
        assertEquals(
            PlayerStartupDestination.CredentialsFailed("credentials failed"),
            resolvePlayerStartupDestination(
                SettingsLoadState.Loaded,
                PlaybackCredentialLoadState.Failed("credentials failed"),
                disclaimerAccepted = true,
            ),
        )
    }

    @Test
    fun `设置和凭据成功后仍先经过免责声明`() {
        val destination = resolvePlayerStartupDestination(
            SettingsLoadState.Loaded,
            PlaybackCredentialLoadState.Ready(emptyMap()),
            disclaimerAccepted = false,
        )

        assertEquals(PlayerStartupDestination.Disclaimer, destination)
    }

    @Test
    fun `全部闸门通过后才把凭据交给播放器`() {
        val headers = mapOf("Authorization" to "canary")
        val destination = resolvePlayerStartupDestination(
            SettingsLoadState.Loaded,
            PlaybackCredentialLoadState.Ready(headers),
            disclaimerAccepted = true,
        )

        assertEquals(headers, assertIs<PlayerStartupDestination.Player>(destination).headers)
    }
}
