package io.github.weiyongzenqi.unuplayer.app

import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.domain.SettingsLoadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlayerStartupGateTest {

    @Test
    fun `设置加载中优先阻止播放器启动`() {
        val destination = resolvePlayerStartupDestination(
            SettingsLoadState.Loading,
            PlaybackCredentialLoadState.Ready(prepared(mapOf("Authorization" to "canary"))),
            disclaimerAccepted = true,
        )

        assertEquals(PlayerStartupDestination.Loading, destination)
    }

    @Test
    fun `设置失败只能进入设置恢复页`() {
        val destination = resolvePlayerStartupDestination(
            SettingsLoadState.Failed("settings failed"),
            PlaybackCredentialLoadState.Ready(prepared()),
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
            PlaybackCredentialLoadState.Ready(prepared()),
            disclaimerAccepted = false,
        )

        assertEquals(PlayerStartupDestination.Disclaimer, destination)
    }

    @Test
    fun `全部闸门通过后才把凭据交给播放器`() {
        val headers = mapOf("Authorization" to "canary")
        val destination = resolvePlayerStartupDestination(
            SettingsLoadState.Loaded,
            PlaybackCredentialLoadState.Ready(prepared(headers)),
            disclaimerAccepted = true,
        )

        assertEquals(headers, assertIs<PlayerStartupDestination.Player>(destination).playback.headers)
    }

    @Test
    fun `播放器启动资源字符串不展开 URL 与认证头`() {
        val playback = prepared(mapOf("Authorization" to "secret-header"))

        assertEquals(false, playback.toString().contains("secret-header"))
        assertEquals(false, playback.toString().contains("private.example.test"))
    }

    private fun prepared(headers: Map<String, String> = emptyMap()) = PreparedPlayerPlayback(
        url = "https://private.example.test/video",
        headers = headers,
        contentUri = null,
        mediaKey = "webdav:connection:/video",
        sourceKind = MediaSourceKind.WEBDAV,
    )
}
