package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchMethod

/** 可排序/可禁用的弹幕匹配方式展示条目(名称与说明文案 2026-08-14 用户确认)。 */
private data class DanmakuMatchMethodUi(
    val method: DanmakuMatchMethod,
    val title: String,
    val description: String,
)

private val danmakuMatchMethodUis = listOf(
    DanmakuMatchMethodUi(
        DanmakuMatchMethod.TMDB_DATABASE,
        "TMDB 身份",
        "海报墙库或媒体服务器的 TMDB ID",
    ),
    DanmakuMatchMethodUi(
        DanmakuMatchMethod.TMDB_PATH,
        "TMDB 路径标记",
        "从文件路径或链接正则提取 TMDB ID",
    ),
    DanmakuMatchMethodUi(
        DanmakuMatchMethod.HASH,
        "文件哈希",
        "前 16MB MD5 + 文件大小",
    ),
)

/**
 * 弹幕匹配方式排序列表(全局设置与本部专属设置共用):
 * 启用的方式按 [current] 顺序显示, 上移/下移调整优先级; 关闭开关禁用(排到「已禁用」分组),
 * 禁用分组里重新开启追加到启用列表末尾。纯展示组件, 状态由调用方持有。
 */
@Composable
fun DanmakuMatchOrderList(
    current: List<DanmakuMatchMethod>,
    onChange: (List<DanmakuMatchMethod>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val known = danmakuMatchMethodUis.map { it.method }
    val enabled = current.filter { it in known }
    val disabled = danmakuMatchMethodUis.filter { it.method !in enabled }
    Column(modifier = modifier) {
        enabled.forEachIndexed { index, method ->
            val ui = danmakuMatchMethodUis.first { it.method == method }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = true,
                    onCheckedChange = { on -> if (!on) onChange(enabled - method) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(ui.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        ui.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    enabled = index > 0,
                    onClick = {
                        val reordered = enabled.toMutableList()
                        reordered[index] = reordered[index - 1]
                        reordered[index - 1] = method
                        onChange(reordered)
                    },
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
                }
                IconButton(
                    enabled = index < enabled.lastIndex,
                    onClick = {
                        val reordered = enabled.toMutableList()
                        reordered[index] = reordered[index + 1]
                        reordered[index + 1] = method
                        onChange(reordered)
                    },
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
                }
            }
        }
        if (disabled.isNotEmpty()) {
            Text(
                "已禁用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            disabled.forEach { ui ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = false,
                        onCheckedChange = { on -> if (on) onChange(enabled + ui.method) },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ui.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            ui.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
