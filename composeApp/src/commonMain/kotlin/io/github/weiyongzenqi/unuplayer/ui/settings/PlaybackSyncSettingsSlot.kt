package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.playback.sync.PlaybackSyncTrigger
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P2 播放记录同步设置区。
 *
 * 开关 + 连接选择(RadioRow 模式) + 手动同步按钮(立即 sync, 显示结果)。
 * 隐私说明: 开启后观看记录(标题/进度/三元组/弹幕匹配)上传到所选 WebDAV 连接的
 * /.unuplayer/playback/<deviceId>.json, 同一 NAS 的多设备可互相拉取合并。不含凭据/URL 明文。
 */
@Composable
fun PlaybackSyncSettingsSlot(
    repository: SettingsRepository,
    webDavRepository: WebDavConnectionRepository,
    playbackSyncTrigger: PlaybackSyncTrigger?,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf(emptyList<WebDavConnection>()) }
    LaunchedEffect(Unit) { connections = withContext(Dispatchers.IO) { webDavRepository.loadAll() } }
    var syncing by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        SubsectionTitle("播放记录同步")
        Text(
            "开启后观看记录同步到所选 WebDAV 连接, 卸载重装可从服务器恢复。同一 NAS 多设备可互相合并进度。不含凭据/服务器地址。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 0.dp).padding(bottom = 8.dp),
        )
        // 总开关
        SwitchRow(
            title = "启用同步",
            subtitle = "卸载重装可恢复播放记录",
            checked = state.playbackSyncEnabled,
            onCheckedChange = { scope.launch { repository.update { it.copy(playbackSyncEnabled = !state.playbackSyncEnabled) } } },
        )
        // 仅开关开启时显示连接选择 + 手动按钮
        if (state.playbackSyncEnabled) {
            // 二级开关: 自动同步(总开关开时才显示)
            SwitchRow(
                title = "自动同步",
                subtitle = "启动时拉取、退出播放后自动推送; 关闭则仅手动同步",
                checked = state.playbackAutoSync,
                onCheckedChange = { scope.launch { repository.update { it.copy(playbackAutoSync = !state.playbackAutoSync) } } },
            )
            Spacer(Modifier.height(8.dp))
            Text("同步连接", style = MaterialTheme.typography.titleSmall)
            Text(
                "选一个 WebDAV 连接作为同步目标。仅该连接可访问你的观看记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RadioRow(
                label = "未选择",
                selected = state.playbackSyncConnectionId == null,
                onSelect = { scope.launch { repository.update { it.copy(playbackSyncConnectionId = null) } } },
            )
            connections.forEach { conn ->
                val label = if (conn.credentialUnavailable) "${conn.name} (凭据失效)" else conn.name
                RadioRow(
                    label = label,
                    selected = state.playbackSyncConnectionId == conn.id,
                    onSelect = {
                        if (conn.credentialUnavailable) return@RadioRow
                        scope.launch { repository.update { it.copy(playbackSyncConnectionId = conn.id) } }
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            // 手动同步按钮
            val canSync = playbackSyncTrigger != null && state.playbackSyncConnectionId != null && !syncing
            Button(
                onClick = {
                    val trigger = playbackSyncTrigger ?: return@Button
                    syncing = true
                    resultText = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { trigger.sync(state) }
                        syncing = false
                        resultText = when {
                            result == null -> "未满足同步条件(未开/未选连接/凭据失效)"
                            result.success -> "同步完成: 拉取 ${result.pulled} 文件, 合并记录 ${result.mergedRecords}/进度 ${result.mergedProgress}, 推送 ${result.pushed}"
                            else -> "同步失败: ${result.error ?: "未知错误"}"
                        }
                    }
                },
                enabled = canSync,
            ) { Text(if (syncing) "同步中…" else "立即同步") }
            resultText?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("同步完成")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                )
            }
            if (playbackSyncTrigger == null) {
                Text(
                    "此平台不支持同步",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
