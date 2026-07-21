package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.SettingsState

/**
 * 桌面 actual: 桌面 mpv 解码/音频后端选项(见桌面 libmpv 调研报告 调研2)。
 *
 * - hwdec: auto-copy(默认稳健硬解) / no(软解) / auto(直出实验) /
 *   nvdec、nvdec-copy(NVIDIA) / d3d11va-copy(Windows)。当前 WGL 不提供 d3d11va 原生直出。
 * - ao: 自动(空串, mpv autoprobe: Linux pipewire>pulse>alsa, Windows wasapi) / pipewire / pulse /
 *   alsa / wasapi。audiotrack/opensles 是 Android 专属已移除。
 */
@Composable
actual fun DecodingSection(state: SettingsState, scope: CoroutineScope, repository: SettingsRepository) {
    SubsectionTitle("解码")
    Text(
        "Windows 使用 copy-back 硬解：GPU 解码后拷回视频帧，再交给 Direct3D 界面合成。" +
            "auto-copy 为推荐设置；nvdec 与 auto 的旧设置会自动映射到对应 copy 模式。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    val hwdecOptions = listOf(
        "auto-copy" to "自动硬解(推荐兼容)",
        "no" to "软件解码",
        "auto" to "自动硬解(兼容旧设置)",
        "nvdec" to "nvdec(NVIDIA，自动使用 copy)",
        "nvdec-copy" to "nvdec-copy(NVIDIA 兼容)",
        "d3d11va-copy" to "d3d11va-copy(Windows 兼容)",
    )
    hwdecOptions.forEach { (value, label) ->
        RadioRow(
            label = label,
            selected = state.hwdec == value,
            onSelect = { scope.launch { repository.update { it.copy(hwdec = value) } } },
        )
    }
}

@Composable
actual fun AudioOutputSection(state: SettingsState, scope: CoroutineScope, repository: SettingsRepository) {
    SubsectionTitle("音频后端")
    Text(
        "自动(默认)让 mpv 自选后端(Linux pipewire>pulse>alsa, Windows wasapi); " +
            "无声音或冲突时可手动指定。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    val aoOptions = listOf(
        "" to "自动(推荐)",
        "pipewire" to "pipewire",
        "pulse" to "pulse",
        "alsa" to "alsa",
        "wasapi" to "wasapi(Windows)",
    )
    aoOptions.forEach { (value, label) ->
        RadioRow(
            label = label,
            selected = state.audioOutput == value,
            onSelect = { scope.launch { repository.update { it.copy(audioOutput = value) } } },
        )
    }
}
