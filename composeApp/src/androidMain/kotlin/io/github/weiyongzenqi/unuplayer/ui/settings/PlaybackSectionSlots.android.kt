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
 * Android actual: MediaCodec 硬解 + audiotrack/opensles/aaudio 音频后端。
 * 原内联在 SettingsScreen.playbackItems 的代码搬出, 行为不变。
 */
@Composable
actual fun DecodingSection(state: SettingsState, scope: CoroutineScope, repository: SettingsRepository) {
    SubsectionTitle("解码")
    Text(
        "*copy 档把解码帧拷回 CPU 再上传 GPU, 每帧多一次大拷贝, 更耗电;" +
            " \"MediaCodec 直出\"最省电但个别片源可能花屏。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    val hwdecOptions = listOf(
        "auto-copy" to "自动(安全)",
        "mediacodec-copy" to "MediaCodec 拷回",
        "mediacodec" to "MediaCodec 直出",
        "no" to "软件解码",
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
    val aoOptions = listOf(
        "audiotrack" to "audiotrack(保留系统音效)",
        "opensles" to "opensles(低延迟)",
        "aaudio" to "aaudio(原生低延迟)",
    )
    aoOptions.forEach { (value, label) ->
        RadioRow(
            label = label,
            selected = state.audioOutput == value,
            onSelect = { scope.launch { repository.update { it.copy(audioOutput = value) } } },
        )
    }
}

@Composable
actual fun AnimePortraitPlaybackSection(
    state: SettingsState,
    scope: CoroutineScope,
    repository: SettingsRepository,
) {
    SubsectionTitle("海报墙剧集播放")
    SwitchRow(
        title = "使用竖屏播放详情",
        subtitle = "开启时在视频下方显示本集简介与评论；关闭后直接进入全屏播放",
        checked = state.animePortraitPlaybackEnabled,
        onCheckedChange = { enabled ->
            scope.launch { repository.update { it.copy(animePortraitPlaybackEnabled = enabled) } }
        },
    )
    SwitchRow(
        title = "默认隐藏竖屏评论",
        subtitle = "开启后进入竖屏播放详情时默认收起评论列表，评论仍在后台预加载",
        checked = state.animePortraitCommentsHiddenByDefault,
        onCheckedChange = { hidden ->
            scope.launch { repository.update { it.copy(animePortraitCommentsHiddenByDefault = hidden) } }
        },
    )
}
