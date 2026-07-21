package io.github.weiyongzenqi.unuplayer.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.player.MediaInfo
import io.github.weiyongzenqi.unuplayer.core.player.PlayerEngine
import io.github.weiyongzenqi.unuplayer.core.player.PlayerState
import io.github.weiyongzenqi.unuplayer.core.player.TrackInfo
import io.github.weiyongzenqi.unuplayer.core.player.TrackList
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEngineType
import kotlin.math.abs

/** Android 与 Windows 共用的播放控制层，尺寸、颜色、文案保持 Android 当前实现。 */
@Composable
internal fun PlayerControls(
    state: PlayerState,
    positionFlow: StateFlow<Long>,
    mediaInfo: MediaInfo?,
    playTitle: String = "",
    episodeTitle: String = "",
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekStarted: () -> Unit,
    onSeekFinished: () -> Unit,
    onToggleInfo: () -> Unit,
    onToggleSettings: () -> Unit,
    danmakuEnabled: Boolean,
    onToggleDanmaku: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = playTitle.ifBlank { mediaInfo?.title ?: "播放中" },
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).basicMarquee().padding(start = 8.dp),
                )
                if (episodeTitle.isNotBlank()) {
                    Text(
                        text = episodeTitle,
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.weight(0.7f).basicMarquee().padding(start = 6.dp),
                    )
                }
            }
            IconButton(onClick = onToggleSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "播放设置", tint = Color.White)
            }
            IconButton(onClick = onToggleInfo) {
                Icon(Icons.Filled.Info, contentDescription = "技术信息", tint = Color.White)
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f)).padding(8.dp),
        ) {
            val duration = state.durationMs.coerceAtLeast(1)
            val positionMs by positionFlow.collectAsStateWithLifecycle()
            var sliderDragging by remember { mutableStateOf(false) }
            var sliderValue by remember { mutableFloatStateOf(0f) }
            var pendingSeekMs by remember { mutableLongStateOf(-1L) }
            var seekFromMs by remember { mutableLongStateOf(0L) }
            val displayPos = when {
                sliderDragging -> (sliderValue * duration).toLong()
                pendingSeekMs >= 0 -> pendingSeekMs
                else -> positionMs
            }
            Slider(
                value = (displayPos.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = { ratio ->
                    if (!sliderDragging) {
                        sliderDragging = true
                        onSeekStarted()
                    }
                    sliderValue = ratio
                },
                onValueChangeFinished = {
                    val target = (sliderValue * duration).toLong()
                    seekFromMs = positionMs
                    onSeek(target)
                    pendingSeekMs = target
                    sliderDragging = false
                    onSeekFinished()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            LaunchedEffect(positionMs, pendingSeekMs) {
                if (pendingSeekMs >= 0) {
                    val moved = abs(positionMs - seekFromMs) > 500
                    val nearTarget = abs(positionMs - pendingSeekMs) < 2_000
                    if (moved && nearTarget) pendingSeekMs = -1L
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = "播放/暂停",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onToggleDanmaku) {
                    Icon(
                        Icons.Filled.Subtitles,
                        contentDescription = if (danmakuEnabled) "关闭弹幕" else "开启弹幕",
                        tint = if (danmakuEnabled) Color.White else Color.Gray,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${formatPlayerTime(displayPos)} / ${formatPlayerTime(state.durationMs)}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.buffering) {
                    Text(
                        "缓冲中…",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** Android 与 Windows 共用的技术信息面板。 */
@Composable
internal fun TechInfoPanel(
    mediaInfo: MediaInfo,
    state: PlayerState,
    systemVolumePct: Int,
    engine: PlayerEngine,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var videoBitrate by remember { mutableStateOf(mediaInfo.videoBitrate) }
    var audioBitrate by remember { mutableStateOf(mediaInfo.audioBitrate) }
    LaunchedEffect(Unit) {
        while (true) {
            val (video, audio) = withContext(Dispatchers.Default) {
                (engine.getPropertyDouble("video-bitrate")?.toInt() ?: 0) to
                    (engine.getPropertyDouble("audio-bitrate")?.toInt() ?: 0)
            }
            if (video > 0) videoBitrate = video
            if (audio > 0) audioBitrate = audio
            delay(1_000)
        }
    }
    Column(
        modifier = modifier.background(Color.Black.copy(alpha = 0.85f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("技术信息", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Box(Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Info, contentDescription = "关闭", tint = Color.White)
            }
        }
        InfoGroup("视频") {
            InfoRow("编码", mediaInfo.videoCodec)
            InfoRow("分辨率", "${mediaInfo.width}x${mediaInfo.height}")
            InfoRow("帧率", "%.2f".format(mediaInfo.fps))
            InfoRow("码率", "${videoBitrate / 1_000} kbps")
            if (mediaInfo.rotation != 0) InfoRow("旋转", "${mediaInfo.rotation}°")
            mediaInfo.hdrInfo?.let { InfoRow("HDR", it.gamma ?: "是") }
        }
        InfoGroup("音频") {
            InfoRow("编码", mediaInfo.audioCodec)
            InfoRow("采样率", "${mediaInfo.audioSampleRate} Hz")
            InfoRow("声道", "${mediaInfo.audioChannels}")
            InfoRow("码率", "${audioBitrate / 1_000} kbps")
        }
        InfoGroup("解码") {
            InfoRow("解码器", mediaInfo.hwdecCurrent ?: "—")
            InfoRow("请求", mediaInfo.requestedHwdec ?: "—")
            InfoRow("实际提交", mediaInfo.effectiveHwdec ?: "—")
        }
        InfoGroup("渲染") {
            InfoRow("渲染器", mediaInfo.vo ?: "—")
            InfoRow("图形API", mediaInfo.gpuApi ?: "—")
        }
        InfoGroup("状态") {
            InfoRow("速度", "${state.rate}x")
            InfoRow("音量", "$systemVolumePct%")
        }
    }
}

@Composable
private fun InfoGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        Text(value ?: "—", color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

private enum class PlayerSettingsPane { SUBTITLE, AUDIO, SPEED, DANMAKU }

/** Android 与 Windows 共用的播放设置弹层。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSettingsSheet(
    tracks: TrackList,
    currentSpeed: Float,
    speedPresets: List<Float>,
    onPickSubtitle: () -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
    scale: Float,
    borderSize: Float,
    bold: Boolean,
    onScaleChange: (Float) -> Unit,
    onBorderChange: (Float) -> Unit,
    onBoldChange: (Boolean) -> Unit,
    danmakuConfig: DanmakuConfig,
    onDanmakuConfigChange: (DanmakuConfig) -> Unit,
    danmakuShowMatchToast: Boolean,
    onDanmakuMatchToastChange: (Boolean) -> Unit,
    danmakuApiReady: Boolean = false,
    onManualMatch: () -> Unit = {},
    onPickSiblingSubtitle: () -> Unit = {},
) {
    var pane by remember { mutableStateOf(PlayerSettingsPane.SUBTITLE) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.fillMaxWidth().height(440.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("播放设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.width(112.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsPaneButton("字幕", pane == PlayerSettingsPane.SUBTITLE) { pane = PlayerSettingsPane.SUBTITLE }
                    SettingsPaneButton("音轨", pane == PlayerSettingsPane.AUDIO) { pane = PlayerSettingsPane.AUDIO }
                    SettingsPaneButton("倍速", pane == PlayerSettingsPane.SPEED) { pane = PlayerSettingsPane.SPEED }
                    SettingsPaneButton("弹幕", pane == PlayerSettingsPane.DANMAKU) { pane = PlayerSettingsPane.DANMAKU }
                }
                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        when (pane) {
                            PlayerSettingsPane.SUBTITLE -> {
                                val subtitles = tracks.subtitle
                                if (subtitles.isNotEmpty()) {
                                    item {
                                        SheetOptionRow("关闭字幕", subtitles.none { it.selected }) { onSelectSubtitle(0) }
                                    }
                                    items(subtitles) { track ->
                                        SheetOptionRow(trackLabel(track), track.selected) { onSelectSubtitle(track.id) }
                                    }
                                } else {
                                    item {
                                        Text(
                                            "无可用字幕轨",
                                            Modifier.padding(12.dp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                item {
                                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                    OutlinedButton(onClick = onPickSubtitle, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Filled.Subtitles, null, Modifier.padding(end = 4.dp).size(18.dp))
                                        Text("加载外挂字幕(.srt/.ass)")
                                    }
                                }
                                item {
                                    OutlinedButton(onClick = onPickSiblingSubtitle, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Filled.Subtitles, null, Modifier.padding(end = 4.dp).size(18.dp))
                                        Text("从同目录选择字幕")
                                    }
                                }
                                item {
                                    Text("字号缩放  ${"%.1f".format(scale)}x", style = MaterialTheme.typography.bodySmall)
                                    Slider(scale, onScaleChange, valueRange = 0.5f..4f, steps = 34)
                                }
                                item {
                                    Text("描边  ${"%.1f".format(borderSize)}", style = MaterialTheme.typography.bodySmall)
                                    Slider(borderSize, onBorderChange, valueRange = 0f..6f, steps = 59)
                                }
                                item {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("粗体", style = MaterialTheme.typography.bodySmall)
                                        Switch(bold, onBoldChange)
                                    }
                                }
                            }
                            PlayerSettingsPane.AUDIO -> {
                                if (tracks.audio.isEmpty()) {
                                    item {
                                        Text(
                                            "无可用音轨",
                                            Modifier.padding(12.dp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                items(tracks.audio) { track ->
                                    SheetOptionRow(trackLabel(track), track.selected) { onSelectAudio(track.id) }
                                }
                            }
                            PlayerSettingsPane.SPEED -> items(speedPresets) { speed ->
                                SheetOptionRow(
                                    formatSpeed(speed),
                                    abs(currentSpeed - speed) < 0.001f,
                                ) { onSelectSpeed(speed) }
                            }
                            PlayerSettingsPane.DANMAKU -> {
                                item {
                                    SheetSwitchRow(
                                        label = "显示弹幕",
                                        checked = danmakuConfig.enabled,
                                        onCheckedChange = {
                                            onDanmakuConfigChange(danmakuConfig.copy(enabled = it))
                                        },
                                    )
                                }
                                item {
                                    OutlinedButton(
                                        onClick = onManualMatch,
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = danmakuApiReady,
                                    ) {
                                        Icon(Icons.Filled.Search, null, Modifier.size(18.dp).padding(end = 4.dp))
                                        Text("手动匹配弹幕")
                                    }
                                }
                                item {
                                    SheetSliderRow("不透明度", "%.0f%%".format(danmakuConfig.opacity * 100),
                                        danmakuConfig.opacity, 0.2f..1f) {
                                        onDanmakuConfigChange(danmakuConfig.copy(opacity = it))
                                    }
                                }
                                item {
                                    SheetSliderRow("字号", if (danmakuConfig.fontSize <= 0) "默认" else "%.0f".format(danmakuConfig.fontSize),
                                        if (danmakuConfig.fontSize <= 0) 0f else danmakuConfig.fontSize, 0f..48f) {
                                        onDanmakuConfigChange(danmakuConfig.copy(fontSize = it))
                                    }
                                }
                                item {
                                    SheetSliderRow("滚动速度", "%.1fx".format(danmakuConfig.speedMultiplier),
                                        danmakuConfig.speedMultiplier, 0.5f..2f) {
                                        onDanmakuConfigChange(danmakuConfig.copy(speedMultiplier = it))
                                    }
                                }
                                item {
                                    SheetSliderRow("显示区域", "%.0f%%".format(danmakuConfig.displayArea * 100),
                                        danmakuConfig.displayArea, 0.3f..1f) {
                                        onDanmakuConfigChange(danmakuConfig.copy(displayArea = it))
                                    }
                                }
                                item {
                                    SheetSliderRow("同屏上限", if (danmakuConfig.maxOnScreen <= 0) "自动（最多5000）" else danmakuConfig.maxOnScreen.toString(),
                                        danmakuConfig.maxOnScreen.toFloat(), 0f..300f) {
                                        onDanmakuConfigChange(danmakuConfig.copy(maxOnScreen = it.toInt()))
                                    }
                                }
                                item {
                                    Text("渲染内核", style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                                }
                                item {
                                    SheetOptionRow(
                                        "Canvas drawText(默认, 描边+填充, 效果好)",
                                        danmakuConfig.engineType == DanmakuEngineType.COMPOSE,
                                    ) { onDanmakuConfigChange(danmakuConfig.copy(engineType = DanmakuEngineType.COMPOSE)) }
                                }
                                item {
                                    SheetOptionRow(
                                        "位图缓存(高密度推荐, 预渲染贴图)",
                                        danmakuConfig.engineType == DanmakuEngineType.BITMAP,
                                    ) { onDanmakuConfigChange(danmakuConfig.copy(engineType = DanmakuEngineType.BITMAP)) }
                                }
                                item {
                                    SheetOptionRow(
                                        "Atlas 批渲染(高密度首选, draw call N->1-3)",
                                        danmakuConfig.engineType == DanmakuEngineType.ATLAS,
                                    ) { onDanmakuConfigChange(danmakuConfig.copy(engineType = DanmakuEngineType.ATLAS)) }
                                }
                                item {
                                    Text(
                                        "内核说明:\n• Canvas: 每帧 drawText 描边+填充(Skia GPU), 跨平台, 文字最清晰。" +
                                            "高密度弹幕时 GPU 负载较高。\n• 位图缓存: 每条唯一弹幕预渲染一次，" +
                                            "之后每帧只贴图；桌面和 Android 均使用有界缓存。\n" +
                                            "• Atlas 批渲染: 文本烘焙到有界 atlas page, draw 时批合并提交, " +
                                            "draw call N->1-3, 内存比位图缓存更低。高密度首选。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                }
                                item {
                                    SheetSwitchRow(
                                        "匹配方式气泡提醒",
                                        danmakuShowMatchToast,
                                        onDanmakuMatchToastChange,
                                        "每次匹配到弹幕弹 2s 提示(tmdb/哈希/文件名)",
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SettingsPaneButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SheetOptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onSelect)
        Text(label, Modifier.padding(start = 8.dp), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun SheetSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked, onCheckedChange)
    }
}

@Composable
private fun SheetSliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: (Float) -> Unit,
) {
    var local by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { local = value }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(local, { local = it }, valueRange = range, onValueChangeFinished = { onValueChangeFinished(local) })
    }
}

private fun trackLabel(track: TrackInfo): String = buildString {
    track.title?.let { append(it) }
    track.lang?.let { if (isNotEmpty()) append(" · "); append(it) }
    if (track.external) { if (isNotEmpty()) append(" · "); append("外挂") }
    if (isEmpty()) append("轨道 ${track.id}")
}

internal fun TrackInfo.matchesTrackPattern(pattern: String): Boolean {
    val searchable = buildString {
        title?.let { append(it); append(' ') }
        lang?.let(::append)
    }
    val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
    return regex?.containsMatchIn(searchable) ?: searchable.contains(pattern, ignoreCase = true)
}

private fun formatSpeed(speed: Float): String =
    if (speed == 1f) "1x" else "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x"

internal fun formatPlayerTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
