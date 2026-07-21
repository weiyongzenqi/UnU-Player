package io.github.weiyongzenqi.unuplayer.ui.settings

import android.content.Intent
import android.os.Process
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.domain.FileFormatUtil
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.PosterWallScanCoordinator
import io.github.weiyongzenqi.unuplayer.library.PosterCache
import io.github.weiyongzenqi.unuplayer.library.PosterWallSort
import io.github.weiyongzenqi.unuplayer.library.ScrapedBlocked
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.playback.DatabaseLocationStore
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabaseProvider
import io.github.weiyongzenqi.unuplayer.ui.posterwall.AddLibraryDialog
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository

/** 海报墙设置区(androidMain actual)。配置项+刮削库管理+数据清理+功能介绍。 */
@Composable
actual fun PosterWallSettingsSlot(
    repository: SettingsRepository,
    scrapedRepo: ScrapedLibraryRepository,
    webDavRepo: WebDavConnectionRepository,
    scanCoordinator: PosterWallScanCoordinator?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repository.state.collectAsStateWithLifecycle()
    var libraries by remember { mutableStateOf(emptyList<LibraryConfig>()) }
    var cacheSize by remember { mutableStateOf(0L) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var dbLocation by remember { mutableStateOf(DatabaseLocationStore.get(context)) }
    var showDbLocationConfirm by remember { mutableStateOf(false) }
    var migrating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        libraries = scrapedRepo.listLibraries()
        cacheSize = PosterCache.get(context).sizeBytes()
    }

    // Column 不加 padding: SubsectionTitle/SwitchRow/SliderRow/RadioRow 自带 16dp 水平内边距,
    // 与 SettingsScreen 其他 xxxItems 风格一致(避免双重缩进)。
    Column(Modifier.fillMaxWidth()) {
        // === 通用 ===
        SubsectionTitle("通用")
        SwitchRow(
            title = "海报墙功能",
            subtitle = "关闭则番剧 tab 降级",
            checked = settings.posterWallEnabled,
            onCheckedChange = { v -> scope.launch { repository.update { it.copy(posterWallEnabled = v) } } },
        )
        SubsectionTitle("默认刮削库")
        RadioRow(
            label = "不自动选中",
            selected = settings.posterWallDefaultLibraryId == null,
            onSelect = { scope.launch { repository.update { it.copy(posterWallDefaultLibraryId = null) } } },
        )
        libraries.forEach { lib ->
            RadioRow(
                label = lib.name,
                selected = settings.posterWallDefaultLibraryId == lib.id,
                onSelect = { scope.launch { repository.update { it.copy(posterWallDefaultLibraryId = lib.id) } } },
            )
        }
        var columnsPortrait by remember { mutableFloatStateOf(settings.posterWallPosterColumnsPortrait.toFloat()) }
        LaunchedEffect(settings.posterWallPosterColumnsPortrait) { columnsPortrait = settings.posterWallPosterColumnsPortrait.toFloat() }
        SliderRow(
            title = "竖屏列数",
            valueText = "${settings.posterWallPosterColumnsPortrait}",
            value = columnsPortrait,
            onValueChange = { columnsPortrait = it },
            onValueChangeFinished = { scope.launch { repository.update { it.copy(posterWallPosterColumnsPortrait = columnsPortrait.toInt()) } } },
            valueRange = 2f..5f,
            steps = 2,
        )
        var columnsLandscape by remember { mutableFloatStateOf(settings.posterWallPosterColumnsLandscape.toFloat()) }
        LaunchedEffect(settings.posterWallPosterColumnsLandscape) { columnsLandscape = settings.posterWallPosterColumnsLandscape.toFloat() }
        SliderRow(
            title = "横屏列数",
            valueText = "${settings.posterWallPosterColumnsLandscape}",
            value = columnsLandscape,
            onValueChange = { columnsLandscape = it },
            onValueChangeFinished = { scope.launch { repository.update { it.copy(posterWallPosterColumnsLandscape = columnsLandscape.toInt()) } } },
            valueRange = 2f..8f,
            steps = 5,
        )
        SwitchRow(
            title = "按季度分组",
            subtitle = "按 season.releasedate 月份分组展示",
            checked = settings.posterWallGroupByQuarter,
            onCheckedChange = { v -> scope.launch { repository.update { it.copy(posterWallGroupByQuarter = v) } } },
        )
        SubsectionTitle("排序")
        PosterWallSort.entries.forEach { sort ->
            val sortLabel = when (sort) {
                PosterWallSort.QUARTER -> "季度"
                PosterWallSort.PINYIN -> "拼音"
                PosterWallSort.YEAR -> "年份"
                PosterWallSort.RECENT -> "最近扫描"
            }
            RadioRow(
                label = sortLabel,
                selected = settings.posterWallSortBy == sort,
                onSelect = { scope.launch { repository.update { it.copy(posterWallSortBy = sort) } } },
            )
        }
        SwitchRow(
            title = "显示剧集缩略图",
            subtitle = "关闭省流量",
            checked = settings.posterWallShowEpisodeThumb,
            onCheckedChange = { v -> scope.launch { repository.update { it.copy(posterWallShowEpisodeThumb = v) } } },
        )

        // === 扫描 ===
        SubsectionTitle("扫描")
        var requestInterval by remember { mutableFloatStateOf(settings.posterWallScanRequestIntervalMs.toFloat()) }
        LaunchedEffect(settings.posterWallScanRequestIntervalMs) { requestInterval = settings.posterWallScanRequestIntervalMs.toFloat() }
        SliderRow(
            title = "请求间隔",
            valueText = "${settings.posterWallScanRequestIntervalMs} ms",
            value = requestInterval,
            onValueChange = { requestInterval = it },
            onValueChangeFinished = { scope.launch { repository.update { it.copy(posterWallScanRequestIntervalMs = requestInterval.toInt()) } } },
            valueRange = 0f..2000f,
            steps = 19,
            description = "防压垮服务器, 0=不限",
        )
        var concurrency by remember { mutableFloatStateOf(settings.posterWallScanConcurrency.toFloat()) }
        LaunchedEffect(settings.posterWallScanConcurrency) { concurrency = settings.posterWallScanConcurrency.toFloat() }
        SliderRow(
            title = "并发数",
            valueText = "${settings.posterWallScanConcurrency}",
            value = concurrency,
            onValueChange = { concurrency = it },
            onValueChangeFinished = { scope.launch { repository.update { it.copy(posterWallScanConcurrency = concurrency.toInt()) } } },
            valueRange = 1f..8f,
            steps = 6,
        )
        var scanDepth by remember { mutableFloatStateOf(settings.posterWallScanDepth.toFloat()) }
        LaunchedEffect(settings.posterWallScanDepth) { scanDepth = settings.posterWallScanDepth.toFloat() }
        SliderRow(
            title = "递归深度",
            valueText = "${settings.posterWallScanDepth}",
            value = scanDepth,
            onValueChange = { scanDepth = it },
            onValueChangeFinished = { scope.launch { repository.update { it.copy(posterWallScanDepth = scanDepth.toInt()) } } },
            valueRange = 1f..15f,
            steps = 13,
        )
        var timeoutSec by remember { mutableFloatStateOf(settings.posterWallScanTimeoutSeconds.toFloat()) }
        LaunchedEffect(settings.posterWallScanTimeoutSeconds) { timeoutSec = settings.posterWallScanTimeoutSeconds.toFloat() }
        SliderRow(
            title = "超时",
            valueText = "${settings.posterWallScanTimeoutSeconds} 秒",
            value = timeoutSec,
            onValueChange = { timeoutSec = it },
            onValueChangeFinished = { scope.launch { repository.update { it.copy(posterWallScanTimeoutSeconds = timeoutSec.toInt()) } } },
            valueRange = 60f..1800f,
            steps = 28,
        )

        // === 存储 ===
        SubsectionTitle("存储")
        var cacheMb by remember { mutableFloatStateOf(settings.posterWallImageCacheSizeMb.toFloat()) }
        LaunchedEffect(settings.posterWallImageCacheSizeMb) { cacheMb = settings.posterWallImageCacheSizeMb.toFloat() }
        SliderRow(
            title = "图片缓存上限",
            valueText = "${settings.posterWallImageCacheSizeMb} MB",
            value = cacheMb,
            onValueChange = { cacheMb = it },
            onValueChangeFinished = {
                val newSizeMb = cacheMb.toInt()
                scope.launch {
                    repository.update { it.copy(posterWallImageCacheSizeMb = newSizeMb) }
                    PosterCache.get(context).updateMaxSizeBytes(newSizeMb.toLong() * 1024L * 1024L)
                    cacheSize = PosterCache.get(context).sizeBytes()
                }
            },
            valueRange = 50f..2000f,
            steps = 38,
        )
        SwitchRow(
            title = "WAL 自动 checkpoint",
            subtitle = "防数据库 wal 文件无限增长",
            checked = settings.posterWallWalAutoCheckpoint,
            onCheckedChange = { v -> scope.launch { repository.update { it.copy(posterWallWalAutoCheckpoint = v) } } },
        )
        Text(
            "图片缓存占用 ${FileFormatUtil.formatSize(cacheSize)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Button(
            onClick = {
                scope.launch {
                    PosterCache.get(context).clear()
                    cacheSize = PosterCache.get(context).sizeBytes()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text("清理图片缓存") }
        Button(
            onClick = { showClearConfirm = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text("清空刮削数据") }
        Button(
            onClick = { scope.launch { scrapedRepo.checkpointTruncate() } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text("优化数据库(TRUNCATE)") }

        // 数据库位置(internal /data 私有; external Android/data 用户可见可管理)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("数据库位置", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (dbLocation == DatabaseLocationStore.EXTERNAL)
                        "外部存储 · Android/data(用户可见, 隐私较低)"
                    else
                        "内部存储 · /data(隐私高)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                enabled = !migrating,
                onClick = { showDbLocationConfirm = true },
            ) {
                Text(if (dbLocation == DatabaseLocationStore.EXTERNAL) "切到内部" else "切到外部")
            }
        }
        if (showDbLocationConfirm) {
            val target = if (dbLocation == DatabaseLocationStore.EXTERNAL) DatabaseLocationStore.INTERNAL else DatabaseLocationStore.EXTERNAL
            AlertDialog(
                onDismissRequest = { showDbLocationConfirm = false },
                title = { Text("切换数据库位置") },
                text = {
                    Text(
                        if (target == DatabaseLocationStore.EXTERNAL)
                            "数据库将迁移到 Android/data 目录(用户可见可管理), 隐私性有所下降。WebDAV 密码等敏感信息仍保存在应用私有目录(/data)不受影响。切换后应用将自动重启。"
                        else
                            "数据库将迁移回应用私有目录(/data), 隐私性更高。切换后应用将自动重启。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDbLocationConfirm = false
                        migrating = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { UnuDatabaseProvider.migrate(context, target) }
                            if (ok) {
                                // 重启 app: 新进程 get 读新位置打开迁移后的库
                                context.packageManager.getLaunchIntentForPackage(context.packageName)
                                    ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ?.let { context.startActivity(it) }
                                Process.killProcess(Process.myPid())
                            } else {
                                migrating = false
                                dbLocation = DatabaseLocationStore.get(context)
                            }
                        }
                    }) { Text("确认切换并重启") }
                },
                dismissButton = {
                    TextButton(onClick = { showDbLocationConfirm = false }) { Text("取消") }
                },
            )
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("清空刮削数据") },
                text = { Text("将删除所有番剧/季/剧集索引(保留刮削库配置), 需重新扫描") },
                confirmButton = {
                    TextButton(onClick = {
                        showClearConfirm = false
                        scope.launch {
                            PosterCache.get(context).clear()
                            scrapedRepo.deleteAllScrapedData()
                            cacheSize = PosterCache.get(context).sizeBytes()
                            libraries = scrapedRepo.listLibraries()
                        }
                    }) { Text("确认") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
                },
            )
        }

        // === 刮削库管理 ===
        SubsectionTitle("刮削库管理")
        libraries.forEach { lib ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${lib.name}（${if (lib.sourceKind == MediaSourceKind.WEBDAV) "WebDAV" else "本地"}）",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    scope.launch { repository.update { it.copy(posterWallDefaultLibraryId = lib.id) } }
                }) { Text("设默认") }
                TextButton(onClick = {
                    scope.launch {
                        scrapedRepo.deleteLibrary(lib.id)
                        libraries = scrapedRepo.listLibraries()
                    }
                }) { Text("删除") }
            }
        }
        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text("添加刮削库") }

        if (showAddDialog) {
            var webDavConnections by remember { mutableStateOf(emptyList<WebDavConnection>()) }
            LaunchedEffect(Unit) { webDavConnections = webDavRepo.loadAll() }
            AddLibraryDialog(
                webDavConnections = webDavConnections,
                onConfirm = { name, sourceKind, connectionId, localUri, rootPath, scanMode, anchorFilenames ->
                    scope.launch {
                        scrapedRepo.addLibrary(
                            name = name,
                            sourceKind = sourceKind,
                            connectionId = connectionId,
                            localUri = localUri,
                            rootPath = rootPath,
                            scanDepth = settings.posterWallScanDepth,
                            scanMode = scanMode,
                            anchorFilenames = anchorFilenames,
                        )
                        libraries = scrapedRepo.listLibraries()
                    }
                    showAddDialog = false
                },
                onDismiss = { showAddDialog = false },
            )
        }

        // === 屏蔽管理 ===
        SubsectionTitle("屏蔽管理")
        var blocked by remember { mutableStateOf<List<ScrapedBlocked>>(emptyList()) }
        LaunchedEffect(libraries) {
            blocked = libraries.flatMap {
                runSuspendCatching { scrapedRepo.listBlocked(it.id) }.getOrDefault(emptyList())
            }
        }
        if (blocked.isEmpty()) {
            Text(
                "无屏蔽番剧",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            blocked.forEach { b ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        b.title ?: b.show_path.trimEnd('/').substringAfterLast('/'),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        scope.launch {
                            scrapedRepo.unblock(b.id)
                            blocked = libraries.flatMap {
                                runSuspendCatching { scrapedRepo.listBlocked(it.id) }.getOrDefault(emptyList())
                            }
                        }
                    }) { Text("恢复") }
                }
            }
        }

        // === 功能介绍 ===
        SubsectionTitle("功能介绍")
        Text(
            INTRO_TEXT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

private val INTRO_TEXT = """
海报墙功能说明

【匹配原理】
扫描你配置的刮削库（本地或 WebDAV），递归查找 tvshow.nfo 作为番剧锚点。每个含 tvshow.nfo 的文件夹视为一部番剧：
• tvshow.nfo 提供 tmdbid（番剧唯一标识）、标题、年份、简介、首播日期
• 其下的 Season N/season.nfo 提供季号与该季首播日期（季度分组依据）
• Season N/bangumi.ini 提供 Bangumi 条目 ID（可选，文件不存在则跳过，不影响识别）
• Season N/剧集.nfo + .mkv 提供单集信息与播放路径

【三层映射】
tmdbid(番剧) ↔ season.releasedate(季度) ↔ bangumi_id(Bangumi映射) ↔ 剧集media_key(播放进度)

【季度分组】
按 season.nfo 的 releasedate 字段归类到对应季度（如 2026-07-03 -> 2026年7月番）。

【数据存储】
图片缓存按番剧名-tmdbid 分文件夹存于 /sdcard/Android/data/<本应用>/files/postercache/，可手动清理。刮削索引存于应用数据库，支持手动清空与优化（WAL 截断）。数据库位置可在"内部存储(/data, 隐私高)"与"外部存储(Android/data, 用户可见)"间切换，切换自动迁移并重启；WebDAV 密码等敏感信息始终存于 /data 不受影响。

【收藏/屏蔽/隐藏/删除】
详情页可收藏（列表置顶）、隐藏（顶部下拉显示）、屏蔽或删除番剧。屏蔽与删除的番剧可在本页「屏蔽管理」恢复。

【扫描】
全盘扫描默认增量（已记录的番剧跳过）；"重扫当前目录"仅扫描指定目录下未记录的番剧。扫描受请求间隔与并发数限制，避免压垮服务器。
""".trimIndent()
