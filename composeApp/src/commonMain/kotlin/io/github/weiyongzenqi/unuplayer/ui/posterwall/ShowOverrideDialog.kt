package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.source.parseDanmakuMatchOrder
import io.github.weiyongzenqi.unuplayer.domain.SettingsState
import io.github.weiyongzenqi.unuplayer.domain.toDanmakuConfig
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideJson
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideSettings
import io.github.weiyongzenqi.unuplayer.library.diffUpdate
import io.github.weiyongzenqi.unuplayer.library.withOverride
import io.github.weiyongzenqi.unuplayer.ui.settings.DanmakuMatchOrderList

/** 正则文本输入防抖提交时长, 与设置页 SettingsScreen.SETTINGS_TEXT_DEBOUNCE_MS 同值(该常量 private 不便复用)。 */
private const val SETTINGS_TEXT_DEBOUNCE_MS = 400L

/**
 * 本部专属设置弹窗(详情页更多菜单): 管理该节目的弹幕 + 字幕 + 音轨偏好专属覆盖。
 *
 * 稀疏覆盖模型: 未调整的项跟随全局(弹幕基准由 [globalSettings] 派生 [DanmakuConfig], 字幕/音轨
 * 直接读 [globalSettings] 对应字段); 弹幕滑条调整后经 [diffUpdate] 差分仅写入变动字段, 字幕/音轨
 * 行直接写字段, 均自动 upsert 到 ShowSettingsOverride 表(仅影响本节目)。一键清除恢复全跟随全局。
 * 身份键 [identityKey] 由调用方用 ShowOverrideIdentity.keyFor 算好(有 tmdb 跨库共用一份)。
 * [appliesDuringPlayback]=false(ANCHOR 未刮削节目, 无 tmdb)时弹窗顶部加"播放时暂不生效"提示:
 * 播放端覆盖只认 tmdbId(锁定决策), 详情页仍可保存, 仅 UI 提示避免误导。
 */
@Composable
fun ShowOverrideDialog(
    showTitle: String,
    identityKey: String,
    globalSettings: SettingsState,
    scrapedRepo: ScrapedLibraryRepository,
    appliesDuringPlayback: Boolean = true,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var override by remember { mutableStateOf(ShowOverrideSettings()) }
    // 弹幕叠加基准: 由全局设置派生(逻辑与旧版一致, 弹幕 7 行全部沿用此值)
    val globalDanmakuConfig = globalSettings.toDanmakuConfig()

    // 打开即加载已有覆盖(弹窗进入组合时按 identityKey 跑一次)
    LaunchedEffect(identityKey) {
        scrapedRepo.getShowOverrideJson(identityKey)?.let { raw ->
            ShowOverrideJson.decode(raw)?.let { override = it }
        }
    }

    // 全局叠加本部覆盖后的有效配置(弹幕滑条显示与取值基准)
    val effective = globalDanmakuConfig.withOverride(override)
    // 字幕/音轨有效值(覆盖非 null 用覆盖, 否则回落全局; 各行显示基准)
    val effSubtitleScale = override.subtitleScale ?: globalSettings.subtitleScale
    val effSubtitleBorder = override.subtitleBorderSize ?: globalSettings.subtitleBorderSize
    val effSubtitleBold = override.subtitleBold ?: globalSettings.subtitleBold

    // 提交助手(通用): 覆盖有变化才写库(幂等 upsert); 弹幕/字幕/音轨各行共用
    fun persist(next: ShowOverrideSettings) {
        if (next != override) {
            override = next
            scope.launch {
                scrapedRepo.upsertShowOverride(identityKey, ShowOverrideJson.encode(next), platformTimeMillis())
            }
        }
    }

    // 提交助手(弹幕行): 差分(旧有效 -> 新有效)仅记变动字段后 persist
    fun commit(newEffective: DanmakuConfig) {
        val old = globalDanmakuConfig.withOverride(override)
        val next = override.diffUpdate(old, newEffective)
        persist(next)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本部专属设置") },
        text = {
            Column {
                Text(
                    "「$showTitle」\n未自定义的项跟随全局；调整后自动保存为本部专属，仅影响本节目。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                // 未刮削(ANCHOR)节目无 tmdb, 播放端覆盖不生效(锁定决策), 顶部弱化提示避免误导
                if (!appliesDuringPlayback) {
                    Text(
                        "未刮削节目：专属设置可保存，但播放时暂不生效（按全局播放）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                    item {
                        OverrideSliderRow(
                            title = "不透明度",
                            valueText = "%.0f%%".format(effective.opacity * 100),
                            value = effective.opacity,
                            range = 0.2f..1f,
                            customized = override.danmakuOpacity != null,
                            onValueChangeFinished = { commit(effective.copy(opacity = it)) },
                        )
                    }
                    item {
                        OverrideSliderRow(
                            title = "字号",
                            valueText = if (effective.fontSize <= 0f) "默认" else "%.0f".format(effective.fontSize),
                            value = effective.fontSize,
                            range = 0f..48f,
                            customized = override.danmakuFontSize != null,
                            onValueChangeFinished = { commit(effective.copy(fontSize = it)) },
                        )
                    }
                    item {
                        OverrideSliderRow(
                            title = "滚动速度",
                            valueText = "%.1fx".format(effective.speedMultiplier),
                            value = effective.speedMultiplier,
                            range = 0.5f..2f,
                            customized = override.danmakuSpeedMultiplier != null,
                            onValueChangeFinished = { commit(effective.copy(speedMultiplier = it)) },
                        )
                    }
                    item {
                        OverrideSliderRow(
                            title = "显示区域",
                            valueText = "%.0f%%".format(effective.displayArea * 100),
                            value = effective.displayArea,
                            range = 0.3f..1f,
                            customized = override.danmakuDisplayArea != null,
                            onValueChangeFinished = { commit(effective.copy(displayArea = it)) },
                        )
                    }
                    item {
                        OverrideSliderRow(
                            title = "描边粗细",
                            valueText = if (effective.strokeWidth <= 0f) "无描边" else "%.1fpx".format(effective.strokeWidth),
                            value = effective.strokeWidth,
                            range = 0f..6f,
                            customized = override.danmakuStrokeWidth != null,
                            onValueChangeFinished = { commit(effective.copy(strokeWidth = it)) },
                        )
                    }
                    item {
                        OverrideSliderRow(
                            title = "时间偏移",
                            valueText = when {
                                effective.timeOffsetSec > 0.0 -> "推迟 %.1f秒".format(effective.timeOffsetSec)
                                effective.timeOffsetSec < 0.0 -> "提前 %.1f秒".format(-effective.timeOffsetSec)
                                else -> "同步"
                            },
                            value = effective.timeOffsetSec.toFloat(),
                            range = -5f..5f,
                            customized = override.danmakuTimeOffsetSec != null,
                            onValueChangeFinished = { commit(effective.copy(timeOffsetSec = it.toDouble())) },
                        )
                    }
                    item {
                        OverrideSliderRow(
                            title = "同屏上限",
                            valueText = if (effective.maxOnScreen <= 0) "自动（最多5000）" else effective.maxOnScreen.toString(),
                            value = effective.maxOnScreen.toFloat(),
                            range = 0f..300f,
                            customized = override.danmakuMaxOnScreen != null,
                            onValueChangeFinished = { commit(effective.copy(maxOnScreen = it.toInt())) },
                        )
                    }
                    // === 弹幕匹配方式(本部)节: 与全局设置同款排序列表, 改回与全局一致即自动回归跟随全局 ===
                    item {
                        Text(
                            "弹幕匹配方式(本部)",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                        Text(
                            if (override.danmakuMatchPriority != null) "已自定义" else "默认跟随全局设置",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (override.danmakuMatchPriority != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        val globalOrder = parseDanmakuMatchOrder(globalSettings.danmakuMatchPriority)
                        val localOrder = override.danmakuMatchPriority
                            ?.let(::parseDanmakuMatchOrder)
                            ?: globalOrder
                        DanmakuMatchOrderList(
                            current = localOrder,
                            onChange = { newOrder ->
                                val globalNames = globalOrder.map { it.name }
                                val newNames = newOrder.map { it.name }
                                // 与全局一致时写 null(回归跟随全局, 稀疏覆盖语义)。
                                persist(override.copy(danmakuMatchPriority = newNames.takeUnless { it == globalNames }))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // === 字幕节 ===
                    item {
                        Text(
                            "字幕",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    item {
                        OverrideSliderRow(
                            title = "字幕缩放",
                            valueText = "%.2fx".format(effSubtitleScale),
                            value = effSubtitleScale,
                            range = 0.5f..4.0f,
                            customized = override.subtitleScale != null,
                            onValueChangeFinished = { persist(override.copy(subtitleScale = it)) },
                        )
                    }
                    item {
                        OverrideSliderRow(
                            title = "字幕描边",
                            valueText = if (effSubtitleBorder <= 0f) "无" else "%.1f".format(effSubtitleBorder),
                            value = effSubtitleBorder,
                            range = 0.0f..6.0f,
                            customized = override.subtitleBorderSize != null,
                            onValueChangeFinished = { persist(override.copy(subtitleBorderSize = it)) },
                        )
                    }
                    item {
                        OverrideSwitchRow(
                            title = "字幕加粗",
                            checked = effSubtitleBold,
                            customized = override.subtitleBold != null,
                            onCheckedChange = { persist(override.copy(subtitleBold = it)) },
                        )
                    }
                    // === 音轨节 ===
                    item {
                        Text(
                            "轨道偏好",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    item {
                        OverrideTextField(
                            title = "字幕轨匹配正则",
                            value = override.defaultSubtitleTrackPattern ?: globalSettings.defaultSubtitleTrackPattern,
                            customized = override.defaultSubtitleTrackPattern != null,
                            onCommit = { persist(override.copy(defaultSubtitleTrackPattern = it)) },
                        )
                    }
                    item {
                        OverrideTextField(
                            title = "音轨匹配正则",
                            value = override.defaultAudioTrackPattern ?: globalSettings.defaultAudioTrackPattern,
                            customized = override.defaultAudioTrackPattern != null,
                            onCommit = { persist(override.copy(defaultAudioTrackPattern = it)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            TextButton(
                enabled = !override.isEmpty(),
                onClick = {
                    override = ShowOverrideSettings()
                    scope.launch { scrapedRepo.clearShowOverride(identityKey) }
                },
            ) { Text("一键清除自定义") }
        },
    )
}

/**
 * 覆盖滑条行: 标题 + 「已自定义/跟随全局」小标签 + 当前值文本 + 滑条。
 * local 态拖动跟手, 松手(onValueChangeFinished)才提交; remember(value)+LaunchedEffect(value)
 * 保证外部 effective 变化(如一键清除回全局)后滑条 local 同步复位。
 */
@Composable
private fun OverrideSliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    customized: Boolean,
    onValueChangeFinished: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    LaunchedEffect(value) { local = value }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Text(
                if (customized) "已自定义" else "跟随全局",
                style = MaterialTheme.typography.bodySmall,
                color = if (customized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(valueText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(local, { local = it }, valueRange = range, onValueChangeFinished = { onValueChangeFinished(local) })
    }
}

/**
 * 覆盖开关行: 标题 + 「已自定义/跟随全局」小标签 + 开关。
 * 用于布尔覆盖项(如字幕加粗); 切换即提交。
 */
@Composable
private fun OverrideSwitchRow(
    title: String,
    checked: Boolean,
    customized: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Text(
            if (customized) "已自定义" else "跟随全局",
            style = MaterialTheme.typography.bodySmall,
            color = if (customized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 覆盖文本行: 标题 + 「已自定义/跟随全局」小标签 + 单行输入框。
 * local 态跟手输入, 防抖合并提交(时长与设置页 [SETTINGS_TEXT_DEBOUNCE_MS] 一致): 避免每次击键都
 * upsert, 也避免无效正则中间态(未闭合括号)入库; remember(value)+LaunchedEffect(value) 保证外部
 * value 变化(如一键清除回全局)后输入框 local 同步复位, 复位后 local==value 防抖 effect 不提交。
 */
@Composable
private fun OverrideTextField(
    title: String,
    value: String,
    customized: Boolean,
    onCommit: (String) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    LaunchedEffect(value) { local = value }
    // 防抖提交: 输入稳定后才合并提交一次, 与滑条行"松手才提交"节奏一致
    LaunchedEffect(local) {
        delay(SETTINGS_TEXT_DEBOUNCE_MS)
        if (local != value) onCommit(local)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Text(
                if (customized) "已自定义" else "跟随全局",
                style = MaterialTheme.typography.bodySmall,
                color = if (customized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextField(
            value = local,
            onValueChange = { local = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
        )
    }
}
