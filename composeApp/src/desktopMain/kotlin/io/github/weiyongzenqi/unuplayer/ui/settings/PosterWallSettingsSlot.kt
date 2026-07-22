package io.github.weiyongzenqi.unuplayer.ui.settings

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.domain.FileFormatUtil
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.PosterCache
import io.github.weiyongzenqi.unuplayer.library.PosterWallScanCoordinator
import io.github.weiyongzenqi.unuplayer.library.PosterWallSort
import io.github.weiyongzenqi.unuplayer.library.ScrapedBlocked
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.cacheKey
import io.github.weiyongzenqi.unuplayer.ui.posterwall.AddLibraryDialog
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository

/**
 * 海报墙设置区(desktopMain actual)。
 *
 * UI 顺序、文案与 Android 当前实现保持一致；仅移除数据库位置/SAF/进程重启等 Android 专属项，
 * 并使用桌面 [PosterCache] 与已注入的 [ScrapedLibraryRepository] 完成维护操作。
 */
@Composable
actual fun PosterWallSettingsSlot(
    repository: SettingsRepository,
    scrapedRepo: ScrapedLibraryRepository,
    webDavRepo: WebDavConnectionRepository,
    scanCoordinator: PosterWallScanCoordinator?,
) {
    val scope = rememberCoroutineScope()
    val settings by repository.state.collectAsStateWithLifecycle()
    val isScanning = if (scanCoordinator != null) {
        val scanState by scanCoordinator.state.collectAsStateWithLifecycle()
        scanState.isScanning
    } else {
        false
    }
    fun scanInProgressNow(): Boolean = scanCoordinator?.state?.value?.isScanning == true

    var libraries by remember { mutableStateOf(emptyList<LibraryConfig>()) }
    var blocked by remember { mutableStateOf(emptyList<ScrapedBlocked>()) }
    var cacheSize by remember { mutableStateOf(0L) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var showClearIndexConfirm by remember { mutableStateOf(false) }
    var pendingDeleteLibrary by remember { mutableStateOf<LibraryConfig?>(null) }
    var operationMessage by remember { mutableStateOf<String?>(null) }

    suspend fun loadBlocked(currentLibraries: List<LibraryConfig> = libraries): List<ScrapedBlocked> =
        currentLibraries.flatMap { library ->
            runSuspendCatching { scrapedRepo.listBlocked(library.id) }.getOrDefault(emptyList())
        }

    LaunchedEffect(Unit) {
        runSuspendCatching {
            val loadedLibraries = scrapedRepo.listLibraries()
            Triple(loadedLibraries, loadBlocked(loadedLibraries), PosterCache.get().sizeBytes())
        }.onSuccess { (loadedLibraries, loadedBlocked, loadedCacheSize) ->
            libraries = loadedLibraries
            blocked = loadedBlocked
            cacheSize = loadedCacheSize
        }.onFailure { error ->
            operationMessage = "加载海报墙设置失败：${error.message ?: error::class.simpleName}"
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // === 通用 ===
        SubsectionTitle("通用")
        SwitchRow(
            title = "海报墙功能",
            subtitle = "关闭则番剧 tab 降级",
            checked = settings.posterWallEnabled,
            onCheckedChange = { value ->
                scope.launch { repository.update { it.copy(posterWallEnabled = value) } }
            },
        )
        SubsectionTitle("默认刮削库")
        RadioRow(
            label = "不自动选中",
            selected = settings.posterWallDefaultLibraryId == null,
            onSelect = {
                scope.launch { repository.update { it.copy(posterWallDefaultLibraryId = null) } }
            },
        )
        libraries.forEach { library ->
            RadioRow(
                label = library.name,
                selected = settings.posterWallDefaultLibraryId == library.id,
                onSelect = {
                    scope.launch { repository.update { it.copy(posterWallDefaultLibraryId = library.id) } }
                },
            )
        }

        var columnsPortrait by remember {
            mutableFloatStateOf(settings.posterWallPosterColumnsPortrait.toFloat())
        }
        LaunchedEffect(settings.posterWallPosterColumnsPortrait) {
            columnsPortrait = settings.posterWallPosterColumnsPortrait.toFloat()
        }
        SliderRow(
            title = "竖屏列数",
            valueText = "${settings.posterWallPosterColumnsPortrait}",
            value = columnsPortrait,
            onValueChange = { columnsPortrait = it },
            onValueChangeFinished = {
                scope.launch {
                    repository.update { it.copy(posterWallPosterColumnsPortrait = columnsPortrait.toInt()) }
                }
            },
            valueRange = 2f..5f,
            steps = 2,
        )

        var columnsLandscape by remember {
            mutableFloatStateOf(settings.posterWallPosterColumnsLandscape.toFloat())
        }
        LaunchedEffect(settings.posterWallPosterColumnsLandscape) {
            columnsLandscape = settings.posterWallPosterColumnsLandscape.toFloat()
        }
        SliderRow(
            title = "横屏列数",
            valueText = "${settings.posterWallPosterColumnsLandscape}",
            value = columnsLandscape,
            onValueChange = { columnsLandscape = it },
            onValueChangeFinished = {
                scope.launch {
                    repository.update { it.copy(posterWallPosterColumnsLandscape = columnsLandscape.toInt()) }
                }
            },
            valueRange = 2f..8f,
            steps = 5,
        )

        SwitchRow(
            title = "按季度分组",
            subtitle = "按 season.releasedate 月份分组展示",
            checked = settings.posterWallGroupByQuarter,
            onCheckedChange = { value ->
                scope.launch { repository.update { it.copy(posterWallGroupByQuarter = value) } }
            },
        )
        SubsectionTitle("排序")
        PosterWallSort.entries.forEach { sort ->
            val label = when (sort) {
                PosterWallSort.QUARTER -> "季度"
                PosterWallSort.PINYIN -> "拼音"
                PosterWallSort.YEAR -> "年份"
                PosterWallSort.RECENT -> "最近扫描"
            }
            RadioRow(
                label = label,
                selected = settings.posterWallSortBy == sort,
                onSelect = {
                    scope.launch { repository.update { it.copy(posterWallSortBy = sort) } }
                },
            )
        }
        SwitchRow(
            title = "显示剧集缩略图",
            subtitle = "关闭省流量",
            checked = settings.posterWallShowEpisodeThumb,
            onCheckedChange = { value ->
                scope.launch { repository.update { it.copy(posterWallShowEpisodeThumb = value) } }
            },
        )
        SwitchRow(
            title = "详情页海报使用季度海报",
            subtitle = "开启后详情页头部海报改用当前季的 seasonXX-poster.jpg",
            checked = settings.posterWallDetailUseSeasonPoster,
            onCheckedChange = { value ->
                scope.launch { repository.update { it.copy(posterWallDetailUseSeasonPoster = value) } }
            },
        )
        SwitchRow(
            title = "显示第1季徽章",
            subtitle = "关闭则第1季不显示季徽章(减少干扰)",
            checked = settings.posterWallBadgeShowSeason1,
            onCheckedChange = { value ->
                scope.launch { repository.update { it.copy(posterWallBadgeShowSeason1 = value) } }
            },
        )

        // === 扫描 ===
        SubsectionTitle("扫描")
        var requestInterval by remember {
            mutableFloatStateOf(settings.posterWallScanRequestIntervalMs.toFloat())
        }
        LaunchedEffect(settings.posterWallScanRequestIntervalMs) {
            requestInterval = settings.posterWallScanRequestIntervalMs.toFloat()
        }
        SliderRow(
            title = "请求间隔",
            valueText = "${settings.posterWallScanRequestIntervalMs} ms",
            value = requestInterval,
            onValueChange = { requestInterval = it },
            onValueChangeFinished = {
                scope.launch {
                    repository.update { it.copy(posterWallScanRequestIntervalMs = requestInterval.toInt()) }
                }
            },
            valueRange = 0f..2000f,
            steps = 19,
            description = "防压垮服务器, 0=不限",
        )

        var concurrency by remember {
            mutableFloatStateOf(settings.posterWallScanConcurrency.toFloat())
        }
        LaunchedEffect(settings.posterWallScanConcurrency) {
            concurrency = settings.posterWallScanConcurrency.toFloat()
        }
        SliderRow(
            title = "并发数",
            valueText = "${settings.posterWallScanConcurrency}",
            value = concurrency,
            onValueChange = { concurrency = it },
            onValueChangeFinished = {
                scope.launch {
                    repository.update { it.copy(posterWallScanConcurrency = concurrency.toInt()) }
                }
            },
            valueRange = 1f..8f,
            steps = 6,
        )

        var scanDepth by remember {
            mutableFloatStateOf(settings.posterWallScanDepth.toFloat())
        }
        LaunchedEffect(settings.posterWallScanDepth) {
            scanDepth = settings.posterWallScanDepth.toFloat()
        }
        SliderRow(
            title = "递归深度",
            valueText = "${settings.posterWallScanDepth}",
            value = scanDepth,
            onValueChange = { scanDepth = it },
            onValueChangeFinished = {
                scope.launch {
                    repository.update { it.copy(posterWallScanDepth = scanDepth.toInt()) }
                }
            },
            valueRange = 1f..15f,
            steps = 13,
        )

        var timeoutSeconds by remember {
            mutableFloatStateOf(settings.posterWallScanTimeoutSeconds.toFloat())
        }
        LaunchedEffect(settings.posterWallScanTimeoutSeconds) {
            timeoutSeconds = settings.posterWallScanTimeoutSeconds.toFloat()
        }
        SliderRow(
            title = "超时",
            valueText = "${settings.posterWallScanTimeoutSeconds} 秒",
            value = timeoutSeconds,
            onValueChange = { timeoutSeconds = it },
            onValueChangeFinished = {
                scope.launch {
                    repository.update { it.copy(posterWallScanTimeoutSeconds = timeoutSeconds.toInt()) }
                }
            },
            valueRange = 60f..1800f,
            steps = 28,
        )

        // === 存储 ===
        SubsectionTitle("存储")
        var cacheMb by remember {
            mutableFloatStateOf(settings.posterWallImageCacheSizeMb.toFloat())
        }
        LaunchedEffect(settings.posterWallImageCacheSizeMb) {
            cacheMb = settings.posterWallImageCacheSizeMb.toFloat()
        }
        SliderRow(
            title = "图片缓存上限",
            valueText = "${settings.posterWallImageCacheSizeMb} MB",
            value = cacheMb,
            onValueChange = { cacheMb = it },
            onValueChangeFinished = {
                scope.launch {
                    repository.update { it.copy(posterWallImageCacheSizeMb = cacheMb.toInt()) }
                }
            },
            valueRange = 50f..2000f,
            steps = 38,
        )
        SwitchRow(
            title = "WAL 自动 checkpoint",
            subtitle = "防数据库 wal 文件无限增长",
            checked = settings.posterWallWalAutoCheckpoint,
            onCheckedChange = { value ->
                scope.launch { repository.update { it.copy(posterWallWalAutoCheckpoint = value) } }
            },
        )
        Text(
            "图片缓存占用 ${FileFormatUtil.formatSize(cacheSize)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Button(
            onClick = { showClearCacheConfirm = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text("清理图片缓存") }
        Button(
            onClick = { showClearIndexConfirm = true },
            enabled = !isScanning,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text("清空刮削数据") }
        Button(
            onClick = {
                if (!scanInProgressNow()) {
                    scope.launch {
                        if (scanInProgressNow()) {
                            operationMessage = "扫描进行中，暂不能优化数据库"
                            return@launch
                        }
                        runSuspendCatching { scrapedRepo.checkpointTruncate() }
                            .onSuccess { operationMessage = "数据库优化完成" }
                            .onFailure { error ->
                                operationMessage = "数据库优化失败：${error.message ?: error::class.simpleName}"
                            }
                    }
                }
            },
            enabled = !isScanning,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text("优化数据库(TRUNCATE)") }
        if (isScanning) {
            Text(
                "扫描进行中，已暂停删除库、清空索引和数据库优化操作",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        operationMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // === 刮削库管理 ===
        SubsectionTitle("刮削库管理")
        libraries.forEach { library ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${library.name}（${if (library.sourceKind == MediaSourceKind.WEBDAV) "WebDAV" else "本地"}）",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    scope.launch {
                        repository.update { it.copy(posterWallDefaultLibraryId = library.id) }
                    }
                }) { Text("设默认") }
                TextButton(
                    onClick = { pendingDeleteLibrary = library },
                    enabled = !isScanning,
                ) { Text("删除") }
            }
        }
        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text("添加刮削库") }

        // === 屏蔽管理 ===
        SubsectionTitle("屏蔽管理")
        if (blocked.isEmpty()) {
            Text(
                "无屏蔽番剧",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            blocked.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.title ?: item.show_path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\'),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        scope.launch {
                            runSuspendCatching { scrapedRepo.unblock(item.id) }
                                .onSuccess {
                                    blocked = loadBlocked()
                                    operationMessage = "已解除屏蔽，下次扫描可重新收录"
                                }
                                .onFailure { error ->
                                    operationMessage = "恢复失败：${error.message ?: error::class.simpleName}"
                                }
                        }
                    }) { Text("恢复") }
                }
            }
        }

        // === 功能介绍 ===
        SubsectionTitle("功能介绍")
        Text(
            DESKTOP_INTRO_TEXT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    if (showAddDialog) {
        var webDavConnections by remember { mutableStateOf(emptyList<WebDavConnection>()) }
        LaunchedEffect(Unit) { webDavConnections = webDavRepo.loadAll() }
        AddLibraryDialog(
            webDavConnections = webDavConnections,
            onConfirm = { name, sourceKind, connectionId, localUri, rootPath, scanMode, anchorFilenames ->
                scope.launch {
                    runSuspendCatching {
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
                        scrapedRepo.listLibraries()
                    }.onSuccess { updatedLibraries ->
                        libraries = updatedLibraries
                        operationMessage = "刮削库已添加"
                    }.onFailure { error ->
                        operationMessage = "添加失败：${error.message ?: error::class.simpleName}"
                    }
                }
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("清理图片缓存") },
            text = { Text("将删除所有已缓存的海报、背景和剧集缩略图，需要时会重新下载。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheConfirm = false
                    scope.launch {
                        runSuspendCatching {
                            PosterCache.get().clear()
                            PosterCache.get().sizeBytes()
                        }.onSuccess { updatedCacheSize ->
                            cacheSize = updatedCacheSize
                            operationMessage = "图片缓存已清理"
                        }.onFailure { error ->
                            operationMessage = "清理失败：${error.message ?: error::class.simpleName}"
                        }
                    }
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showClearIndexConfirm) {
        AlertDialog(
            onDismissRequest = { showClearIndexConfirm = false },
            title = { Text("清空刮削数据") },
            text = { Text("将删除所有番剧/季/剧集索引和图片缓存（保留刮削库配置），需重新扫描。") },
            confirmButton = {
                TextButton(
                    enabled = !isScanning,
                    onClick = {
                        if (!scanInProgressNow()) {
                            showClearIndexConfirm = false
                            scope.launch {
                                if (scanInProgressNow()) {
                                    operationMessage = "扫描进行中，暂不能清空刮削数据"
                                    return@launch
                                }
                                runSuspendCatching {
                                    PosterCache.get().clear()
                                    scrapedRepo.deleteAllScrapedData()
                                    PosterCache.get().sizeBytes()
                                }.onSuccess { updatedCacheSize ->
                                    cacheSize = updatedCacheSize
                                    operationMessage = "刮削数据已清空"
                                }.onFailure { error ->
                                    operationMessage = "清空失败：${error.message ?: error::class.simpleName}"
                                }
                            }
                        }
                    },
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showClearIndexConfirm = false }) { Text("取消") }
            },
        )
    }

    pendingDeleteLibrary?.let { library ->
        AlertDialog(
            onDismissRequest = { pendingDeleteLibrary = null },
            title = { Text("删除刮削库") },
            text = { Text("确定删除「${library.name}」？番剧、季和剧集索引将一并删除，图片缓存也会同步清理。") },
            confirmButton = {
                TextButton(
                    enabled = !isScanning,
                    onClick = {
                        if (!scanInProgressNow()) {
                            pendingDeleteLibrary = null
                            scope.launch {
                                if (scanInProgressNow()) {
                                    operationMessage = "扫描进行中，暂不能删除刮削库"
                                    return@launch
                                }
                                runSuspendCatching {
                                    val normal = scrapedRepo.listShows(library.id, settings.posterWallSortBy)
                                    val hidden = scrapedRepo.listHidden(library.id)
                                    (normal + hidden).distinctBy { it.id }.forEach { show ->
                                        PosterCache.get().clearShow(show.cacheKey)
                                    }
                                    scrapedRepo.deleteLibrary(library.id)
                                    if (settings.posterWallDefaultLibraryId == library.id) {
                                        repository.update { it.copy(posterWallDefaultLibraryId = null) }
                                    }
                                    val updatedLibraries = scrapedRepo.listLibraries()
                                    updatedLibraries to loadBlocked(updatedLibraries)
                                }.onSuccess { (updatedLibraries, updatedBlocked) ->
                                    libraries = updatedLibraries
                                    blocked = updatedBlocked
                                    operationMessage = "刮削库已删除"
                                }.onFailure { error ->
                                    operationMessage = "删除失败：${error.message ?: error::class.simpleName}"
                                }
                            }
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteLibrary = null }) { Text("取消") }
            },
        )
    }
}

private val DESKTOP_INTRO_TEXT = """
海报墙功能说明

【匹配原理】
扫描你配置的刮削库（本地或 WebDAV），递归查找 tvshow.nfo 作为番剧锚点。每个含 tvshow.nfo 的文件夹视为一部番剧：
• tvshow.nfo 提供 tmdbid（番剧唯一标识）、标题、年份、简介、首播日期
• 其下的 Season N/season.nfo 提供季号与该季首播日期（季度分组依据）
• Season N/bangumi.ini 提供 Bangumi 条目 ID（可选，文件不存在则跳过，不影响识别）
• Season N/剧集.nfo + 视频文件提供单集信息与播放路径

【三层映射】
tmdbid(番剧) ↔ season.releasedate(季度) ↔ bangumi_id(Bangumi映射) ↔ 剧集media_key(播放进度)

【季度分组】
按 season.nfo 的 releasedate 字段归类到对应季度（如 2026-07-03 → 2026年7月番）。

【数据存储】
图片缓存位于 %LOCALAPPDATA%/UnU-Player/cache/posters，刮削索引和播放记录位于 %LOCALAPPDATA%/UnU-Player/data/unu_playback.db，可在本页清理缓存、清空索引或优化数据库。

【收藏/屏蔽/隐藏/删除】
详情页可收藏（列表置顶）、隐藏（顶部下拉显示）、屏蔽或删除番剧。屏蔽与删除的番剧可在本页「屏蔽管理」恢复。

【扫描】
全盘扫描默认增量（已记录的番剧跳过）；“重扫当前目录”仅扫描指定目录下未记录的番剧。扫描受请求间隔与并发数限制，避免压垮服务器。
""".trimIndent()
