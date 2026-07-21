package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.SettingsState

/**
 * 播放模块"解码"与"音频后端"两段(平台相关, commonMain expect)。
 *
 * 选项列表与文案平台不同:
 * - Android: MediaCodec 硬解 + audiotrack/opensles
 * - 桌面: vaapi/nvdec/d3d11va + pipewire/pulse/alsa/wasapi(audiotrack/opensles 是 Android 专属)
 * 故抽 slot 各平台 actual。其余播放项(音轨匹配/倍速/HDR/缓存)跨平台通用, 留在 SettingsScreen.playbackItems。
 */
@Composable
expect fun DecodingSection(state: SettingsState, scope: CoroutineScope, repository: SettingsRepository)

@Composable
expect fun AudioOutputSection(state: SettingsState, scope: CoroutineScope, repository: SettingsRepository)
