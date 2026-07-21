package io.github.weiyongzenqi.unuplayer.ui.settings

import io.github.weiyongzenqi.unuplayer.ui.AppBackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.core.player.HdrMode
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.SettingsState
import io.github.weiyongzenqi.unuplayer.domain.StartupHome
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.library.PosterWallScanCoordinator
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.domain.WebDavSortPreset
import io.github.weiyongzenqi.unuplayer.domain.WebDavSearchScope
import io.github.weiyongzenqi.unuplayer.domain.WebDavSearchTarget
import io.github.weiyongzenqi.unuplayer.domain.WebDavSearchTimeout
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayApi
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayProxyConfig
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatcher
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatchConfig
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching

private const val SETTINGS_TEXT_DEBOUNCE_MS = 400L

/**
 * 设置页。二级菜单: 根页是分类列表, 点进分类看具体项(根页不再一屏铺开所有设置)。
 *
 * @param onBack 可选返回回调。作为底部导航 tab 进入时传 null(根页无返回箭头);
 *        作为独立页面 push 进入时传非 null(根页显示返回箭头退出设置)。
 *        进入子分类后, 返回箭头/系统返回键始终先回到根页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    repository: SettingsRepository,
    webDavRepository: WebDavConnectionRepository,
    scrapedRepository: ScrapedLibraryRepository? = null,
    posterWallScanCoordinator: PosterWallScanCoordinator? = null,
    appLogger: AppLogger? = null,
    onPlay: (PlayableMedia) -> Unit = {},
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf(emptyList<WebDavConnection>()) }
    LaunchedEffect(Unit) { connections = webDavRepository.loadAll() }
    // 用 String 持久化当前分类 key(rememberSaveable 对 String 最稳, 跨配置变更/切 tab 保留),
    // 空串=根页; 取值时 valueOf 映射回枚举, 容错非法值。
    var sectionKey by rememberSaveable { mutableStateOf("") }
    val section = if (sectionKey.isEmpty()) null
        else runCatching { SettingsSection.valueOf(sectionKey) }.getOrNull()

    // 子分类下拦截系统返回键: 先回根页, 而非直接退出设置。
    AppBackHandler(enabled = section != null) { sectionKey = "" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(section?.title ?: "设置") },
                navigationIcon = {
                    if (section != null || onBack != null) {
                        IconButton(onClick = {
                            if (section != null) sectionKey = "" else onBack?.invoke()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val s = section
            if (s == null) {
                SettingsSection.entries.forEachIndexed { index, cat ->
                    item(key = cat.name) { CategoryRow(cat) { sectionKey = cat.name } }
                    if (index < SettingsSection.entries.lastIndex) {
                        item { HorizontalDivider() }
                    }
                }
            } else {
                when (s) {
                    SettingsSection.PLAYBACK -> playbackItems(state, scope, repository)
                    SettingsSection.SUBTITLE -> subtitleItems(state, scope, repository)
                    SettingsSection.ANIME -> animeItems(state, scope, repository)
                    SettingsSection.POSTER_WALL -> item {
                        if (scrapedRepository != null) {
                            PosterWallSettingsSlot(
                                repository = repository,
                                scrapedRepo = scrapedRepository,
                                webDavRepo = webDavRepository,
                                scanCoordinator = posterWallScanCoordinator,
                            )
                        } else {
                            Text("海报墙不可用")
                        }
                    }
                    SettingsSection.INTERFACE -> interfaceItems(state, scope, repository)
                    SettingsSection.SECURITY -> securityItems(state, scope, repository)
                    SettingsSection.LOG -> item { LogSettingsSlot(repository = repository) }
                    SettingsSection.STORAGE -> item { StorageSectionSlot(appLogger) }
                    SettingsSection.WEBDAV -> webdavItems(state, scope, repository, connections)
                    SettingsSection.PLAYBACK_HISTORY -> item { PlaybackHistorySlot(webDavRepository = webDavRepository, onPlay = onPlay) }
                    SettingsSection.ABOUT -> aboutItems()
                }
            }
        }
    }
}

/** 设置分类(二级菜单根项)。 */
enum class SettingsSection(val title: String, val icon: ImageVector, val summary: String) {
    PLAYBACK("播放", Icons.Rounded.PlayCircle, "解码、音频后端、HDR、缓存、倍速"),
    SUBTITLE("字幕", Icons.Rounded.Subtitles, "样式、字体、颜色、默认匹配"),
    ANIME("番剧", Icons.Rounded.LiveTv, "番剧识别开关"),
    POSTER_WALL("海报墙", Icons.Rounded.VideoLibrary, "扫描、刮削库、缓存、季度分组"),
    INTERFACE("界面", Icons.Rounded.Palette, "启动首页、窗口行为、主题、动态取色、返回动画"),
    SECURITY("安全", Icons.Rounded.Security, "TLS 证书验证"),
    LOG("日志", Icons.AutoMirrored.Rounded.Article, "日志开关、级别、输出目录"),
    STORAGE("存储", Icons.Rounded.Storage, "缓存清理、临时文件、日志、字体"),
    WEBDAV("WebDAV", Icons.Rounded.Cloud, "默认连接/目录、排序、Season、面包屑、搜索、番剧匹配"),
    PLAYBACK_HISTORY("播放记录", Icons.Rounded.History, "续播、进度、弹幕匹配"),
    ABOUT("关于", Icons.Rounded.Info, "开源依赖与项目地址"),
}

@Composable
private fun CategoryRow(cat: SettingsSection, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(cat.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(cat.title, style = MaterialTheme.typography.titleMedium)
            Text(
                cat.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// === 各分类的设置项 ===

private fun LazyListScope.playbackItems(
    state: SettingsState,
    scope: CoroutineScope,
    repository: SettingsRepository,
) {
    // 解码(平台相关: Android MediaCodec / 桌面 vaapi/nvdec/d3d11va, 见 PlaybackSectionSlots actual)
    item { DecodingSection(state, scope, repository) }

    // 音频后端(平台相关: Android audiotrack/opensles / 桌面 pipewire/pulse/alsa/wasapi)
    item { AudioOutputSection(state, scope, repository) }

    // 默认音轨匹配(自动选轨, 支持正则)
    item {
        SubsectionTitle("默认音轨匹配(自动选轨, 支持正则)")
        Text(
            "按正则匹配轨道标题/语言; 留空则不自动选。例: ja-Jpan-JP|Japanese|日语",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        var localAudioPattern by rememberSaveable { mutableStateOf(state.defaultAudioTrackPattern) }
        LaunchedEffect(state.defaultAudioTrackPattern) {
            if (localAudioPattern != state.defaultAudioTrackPattern) {
                localAudioPattern = state.defaultAudioTrackPattern
            }
        }
        LaunchedEffect(localAudioPattern, state.defaultAudioTrackPattern) {
            if (localAudioPattern != state.defaultAudioTrackPattern) {
                delay(SETTINGS_TEXT_DEBOUNCE_MS)
                repository.update { it.copy(defaultAudioTrackPattern = localAudioPattern) }
            }
        }
        OutlinedTextField(
            value = localAudioPattern,
            onValueChange = { localAudioPattern = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            label = { Text("音轨关键词 / 正则") },
        )
    }

    // 长按倍速(播放时长按屏幕临时加速, 松手恢复原速)--档位滑条, 紧凑好调
    item {
        PresetSlider(
            title = "长按倍速",
            presets = listOf(1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f),
            current = state.longPressSpeed,
            valueLabel = { "${"%.1f".format(it).trimEnd('0').trimEnd('.')}x" },
            onValueChange = { speed -> scope.launch { repository.update { it.copy(longPressSpeed = speed) } } },
            description = "播放时长按屏幕临时加速, 松手恢复原速",
        )
    }

    // HDR(运行时热切换: target-colorspace-hint/tone-mapping/hdr-compute-peak)
    item {
        SubsectionTitle("HDR")
        val hdrOptions = listOf(
            HdrMode.AUTO to "自动",
            HdrMode.TONE_MAP_SDR to "SDR 映射(可靠)",
            HdrMode.HDR_PASSTHROUGH to "HDR 直通(实验性, 依赖设备支持)",
            HdrMode.OFF to "关闭",
        )
        hdrOptions.forEach { (value, label) ->
            RadioRow(
                label = label,
                selected = state.hdrMode == value,
                onSelect = {
                    scope.launch { repository.update { it.copy(hdrMode = value) } }
                },
            )
        }
    }

    // 缓存大小(内存-only 不写盘)
    item {
        PresetSlider(
            title = "缓存大小",
            presets = listOf(16, 32, 64, 128, 256, 512),
            current = state.cacheSize,
            valueLabel = { "$it MiB" },
            onValueChange = { miB -> scope.launch { repository.update { it.copy(cacheSize = miB) } } },
            description = "内存, 不写入硬盘",
        )
    }

    // 缓存时长(cache-secs, 需重新进播放器生效)
    item {
        PresetSlider(
            title = "缓存时长",
            presets = listOf(10, 20, 30, 60, 120),
            current = state.cacheSecs,
            valueLabel = { "$it 秒" },
            onValueChange = { secs -> scope.launch { repository.update { it.copy(cacheSecs = secs) } } },
            description = "需重进播放器生效",
        )
    }
}

private fun LazyListScope.subtitleItems(
    state: SettingsState,
    scope: CoroutineScope,
    repository: SettingsRepository,
) {
    // 自动加载同目录字幕
    item {
        SwitchRow(
            title = "自动加载同目录字幕",
            subtitle = "无内封字幕时自动加载同目录同名 .ass/.ssa/.srt 文件",
            checked = state.autoLoadSiblingSubtitle,
            onCheckedChange = { v -> scope.launch { repository.update { it.copy(autoLoadSiblingSubtitle = v) } } },
        )
    }
    // 字幕语言偏好(同目录有多语言字幕时, 自动加载按此优先)
    item {
        SubsectionTitle("字幕语言偏好")
        listOf(
            "sc" to "简中优先(ZC/SC/CHS)",
            "tc" to "繁中优先(TC/CHT)",
            "none" to "不限(严格同名, 不识别语言段)",
        ).forEach { (value, label) ->
            RadioRow(
                label = label,
                selected = state.subtitleLanguagePreference == value,
                onSelect = { scope.launch { repository.update { it.copy(subtitleLanguagePreference = value) } } },
            )
        }
    }
    // ASS 样式覆盖模式
    item {
        SubsectionTitle("ASS 样式覆盖")
        val overrideOptions = listOf(
            "force" to "强制覆盖(用下方统一样式)",
            "scale" to "仅缩放(保留 ASS 原样式)",
            "yes" to "覆盖(部分)",
            "no" to "不覆盖(用字幕自带样式)",
        )
        overrideOptions.forEach { (value, label) ->
            RadioRow(
                label = label,
                selected = state.subtitleStyleOverride == value,
                onSelect = {
                    scope.launch { repository.update { it.copy(subtitleStyleOverride = value) } }
                },
            )
        }
    }

    // 字体选择(系统字体 + 已导入字体, 平台 slot 实现)
    item { SubtitleFontsSlot(repository = repository) }

    // 字号缩放(连续, 0.1 步进; 松手才写盘, 见 SliderRow 说明)
    item {
        var scale by remember { mutableFloatStateOf(state.subtitleScale) }
        LaunchedEffect(state.subtitleScale) { scale = state.subtitleScale }
        SliderRow(
            title = "字号缩放",
            valueText = "${"%.1f".format(scale)}x",
            value = scale,
            onValueChange = { scale = it },
            onValueChangeFinished = {
                scope.launch { repository.update { it.copy(subtitleScale = scale) } }
            },
            valueRange = 0.5f..4.0f,
            steps = 34,
            description = "1.0x 为原始大小",
        )
    }

    // 描边粗细(连续, 0.1 步进)
    item {
        var border by remember { mutableFloatStateOf(state.subtitleBorderSize) }
        LaunchedEffect(state.subtitleBorderSize) { border = state.subtitleBorderSize }
        SliderRow(
            title = "描边",
            valueText = "%.1f".format(border),
            value = border,
            onValueChange = { border = it },
            onValueChangeFinished = {
                scope.launch { repository.update { it.copy(subtitleBorderSize = border) } }
            },
            valueRange = 0.0f..6.0f,
            steps = 59,
            description = "0 为无描边, 越大越粗",
        )
    }

    // 粗体
    item {
        SwitchRow(
            title = "粗体",
            subtitle = null,
            checked = state.subtitleBold,
            onCheckedChange = { v ->
                scope.launch { repository.update { it.copy(subtitleBold = v) } }
            },
        )
    }

    // 字幕颜色
    item {
        SubsectionTitle("字幕颜色")
        val colorOptions = listOf(
            "#FFFFFFFF" to "白",
            "#FFFFFF00" to "黄",
            "#FF00CCFF" to "青",
            "#FFFF8800" to "橙",
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            colorOptions.forEach { (hex, label) ->
                FilterChip(
                    selected = state.subtitleColor.equals(hex, ignoreCase = true),
                    onClick = {
                        scope.launch { repository.update { it.copy(subtitleColor = hex) } }
                    },
                    label = { Text(label) },
                )
            }
        }
    }

    // 默认字幕匹配(自动选轨, 支持正则)
    item {
        SubsectionTitle("默认字幕匹配(自动选轨, 支持正则)")
        Text(
            "按正则匹配轨道标题/语言; 留空则不自动选。默认优先简体中文",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        var localSubtitlePattern by rememberSaveable { mutableStateOf(state.defaultSubtitleTrackPattern) }
        LaunchedEffect(state.defaultSubtitleTrackPattern) {
            if (localSubtitlePattern != state.defaultSubtitleTrackPattern) {
                localSubtitlePattern = state.defaultSubtitleTrackPattern
            }
        }
        LaunchedEffect(localSubtitlePattern, state.defaultSubtitleTrackPattern) {
            if (localSubtitlePattern != state.defaultSubtitleTrackPattern) {
                delay(SETTINGS_TEXT_DEBOUNCE_MS)
                repository.update { it.copy(defaultSubtitleTrackPattern = localSubtitlePattern) }
            }
        }
        OutlinedTextField(
            value = localSubtitlePattern,
            onValueChange = { localSubtitlePattern = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            label = { Text("字幕关键词 / 正则") },
        )
    }
}

private fun LazyListScope.animeItems(
    state: SettingsState,
    scope: CoroutineScope,
    repository: SettingsRepository,
) {
    // 番剧识别开关
    item {
        SwitchRow(
            title = "识别视频为番剧",
            subtitle = "关闭则纯播放器模式, 不发任何番剧相关请求",
            checked = state.recognizeAnime,
            onCheckedChange = { v ->
                scope.launch { repository.update { it.copy(recognizeAnime = v) } }
            },
        )
    }

    // 弹弹play 凭证(弹幕数据源; 用户手动填写, 见 DESIGN.md §12.1.2)
    item { SubsectionTitle("弹弹play 凭证") }
    item {
        // 本地 state 缓存输入避免光标跳动(同"默认目录" OutlinedTextField 写法)
        var localAppId by rememberSaveable { mutableStateOf(state.dandanplayAppId) }
        LaunchedEffect(state.dandanplayAppId) {
            if (localAppId != state.dandanplayAppId) {
                localAppId = state.dandanplayAppId
            }
        }
        LaunchedEffect(localAppId, state.dandanplayAppId) {
            if (localAppId != state.dandanplayAppId) {
                delay(SETTINGS_TEXT_DEBOUNCE_MS)
                repository.update { it.copy(dandanplayAppId = localAppId) }
            }
        }
        OutlinedTextField(
            value = localAppId,
            onValueChange = { localAppId = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            label = { Text("AppId") },
        )
    }
    item {
        // AppSecret 只留在当前 Compose 内存，不进入 SavedState/Bundle。
        var localAppSecret by remember { mutableStateOf(state.dandanplayAppSecret) }
        LaunchedEffect(state.dandanplayAppSecret) {
            if (localAppSecret != state.dandanplayAppSecret) {
                localAppSecret = state.dandanplayAppSecret
            }
        }
        LaunchedEffect(localAppSecret, state.dandanplayAppSecret) {
            if (localAppSecret != state.dandanplayAppSecret) {
                delay(SETTINGS_TEXT_DEBOUNCE_MS)
                repository.update { it.copy(dandanplayAppSecret = localAppSecret) }
            }
        }
        OutlinedTextField(
            value = localAppSecret,
            onValueChange = { localAppSecret = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            label = { Text("AppSecret") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
    }
    // 代理缓存模式(配置内置于 DandanplayProxyConfig, 混淆存储, 不在 UI 展示任何细节)
    item { SubsectionTitle("代理缓存模式") }
    item {
        SwitchRow(
            title = "启用代理模式",
            subtitle = "开启后弹幕请求经代理转发(签名/缓存/限流在服务端); 关闭则用上方 AppId/AppSecret 直连弹弹",
            checked = state.dandanplayUseProxy,
            onCheckedChange = { v ->
                scope.launch { repository.update { it.copy(dandanplayUseProxy = v) } }
            },
        )
    }
    item {
        // 测试连接: 用填写的凭证调 search/episodes?tmdbId=206629(义妹生活),
        // 验证签名 + 网络 + 凭证是否有效。结果直接显示在按钮下。
        var testResult by remember { mutableStateOf<String?>(null) }
        var testing by remember { mutableStateOf(false) }
        Button(
            onClick = {
                val proxyReady = state.dandanplayUseProxy   // 端点 + Key 内置, 开关开即就绪
                val directReady = !state.dandanplayUseProxy && state.dandanplayAppId.isNotBlank() && state.dandanplayAppSecret.isNotBlank()
                if (!proxyReady && !directReady) {
                    testResult = "请先填写 AppId / AppSecret"; return@Button
                }
                testing = true; testResult = null
                scope.launch {
                    testResult = runSuspendCatching {
                        withContext(Dispatchers.IO) {
                            val api = if (state.dandanplayUseProxy) DandanplayApi(baseUrl = DandanplayProxyConfig.proxyUrl(), proxyApiKey = DandanplayProxyConfig.apiKey()) else DandanplayApi(state.dandanplayAppId, state.dandanplayAppSecret)
                            val r = api.searchEpisodesByTmdb(206629L)
                            val first = r.animes.firstOrNull()
                            "✅ 连接成功: 匹配 ${r.animes.size} 部番剧" +
                                first?.let { "(${it.animeTitle}, ${it.episodes.size}集)" }.orEmpty()
                        }
                    }.getOrElse { "❌ 失败: ${it.message}" }
                    testing = false
                }
            },
            enabled = !testing,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(if (testing) "测试中..." else "测试连接(义妹生活 tmdbId=206629)")
        }
        testResult?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }

    // 测试匹配策略: tmdb 快速匹配 + 哈希回落(用已知义妹生活 S01E03 数据, 期望 episodeId=175290003)
    item {
        var matchResult by remember { mutableStateOf<String?>(null) }
        var matching by remember { mutableStateOf(false) }
        Button(
            onClick = {
                val proxyReady = state.dandanplayUseProxy   // 端点 + Key 内置, 开关开即就绪
                val directReady = !state.dandanplayUseProxy && state.dandanplayAppId.isNotBlank() && state.dandanplayAppSecret.isNotBlank()
                if (!proxyReady && !directReady) {
                    matchResult = "请先填写 AppId / AppSecret"; return@Button
                }
                matching = true; matchResult = null
                scope.launch {
                    matchResult = runSuspendCatching {
                        withContext(Dispatchers.IO) {
                            val api = if (state.dandanplayUseProxy) DandanplayApi(baseUrl = DandanplayProxyConfig.proxyUrl(), proxyApiKey = DandanplayProxyConfig.apiKey()) else DandanplayApi(state.dandanplayAppId, state.dandanplayAppSecret)
                            val matcher = DanmakuMatcher(api)
                            val fileName = "[LoliHouse] 义妹生活 S01E03-反射与修正.mkv"
                            // 1. tmdb 快速匹配(url 含 tmdbid=206629)
                            val tmdbRes = matcher.match(
                                fileName = fileName,
                                urlOrPath = "https://example.com/tmdbid=206629/义妹生活.mkv",
                                config = DanmakuMatchConfig(true, "tmdb(id)?[=-](\\d+)", false),
                            )
                            // 2. 哈希匹配(url 不含 tmdbId -> tmdb 失败 -> 哈希回落, 硬编码已知 hash)
                            val hashRes = matcher.match(
                                fileName = fileName,
                                urlOrPath = "https://example.com/义妹生活.mkv",
                                config = DanmakuMatchConfig(true, "tmdb(id)?[=-](\\d+)", true),
                                hashProvider = { 754553960L to "e78917bc796a7d792b8f3dd3d8830a01" },
                            )
                            val tmdbStr = tmdbRes?.let { "tmdb: ${it.episodeId} (${it.animeTitle} ${it.episodeTitle})" } ?: "tmdb: 未匹配"
                            val hashStr = hashRes?.let { "哈希: ${it.episodeId} (${it.animeTitle} ${it.episodeTitle})" } ?: "哈希: 未匹配"
                            "$tmdbStr\n$hashStr"
                        }
                    }.getOrElse { "❌ 失败: ${it.message}" }
                    matching = false
                }
            },
            enabled = !matching,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(if (matching) "测试中..." else "测试匹配(tmdb+哈希, 期望 175290003)")
        }
        matchResult?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }

    // 弹幕渲染设置
    item { SubsectionTitle("弹幕显示") }
    item {
        SwitchRow(
            title = "显示弹幕",
            subtitle = "播放时叠加弹幕(需番剧识别开 + 凭证)",
            checked = state.danmakuEnabled,
            onCheckedChange = { v -> scope.launch { repository.update { it.copy(danmakuEnabled = v) } } },
        )
    }
    item {
        SwitchRow(
            title = "未匹配自动弹出手动匹配",
            subtitle = "关闭后匹配失败不自动弹窗, 需手动点'手动匹配弹幕'",
            checked = state.danmakuAutoManualMatch,
            onCheckedChange = { v -> scope.launch { repository.update { it.copy(danmakuAutoManualMatch = v) } } },
        )
    }
    item {
        var localOpacity by remember { mutableStateOf(state.danmakuOpacity) }
        SliderRow(
            title = "不透明度",
            valueText = "%.0f%%".format(localOpacity * 100),
            value = localOpacity,
            onValueChange = { localOpacity = it },
            onValueChangeFinished = { scope.launch { repository.update { it.copy(danmakuOpacity = localOpacity) } } },
            valueRange = 0.2f..1f,
        )
    }
    item {
        var localSize by remember { mutableStateOf(state.danmakuFontSize) }
        SliderRow(
            title = "字号",
            valueText = if (localSize <= 0) "默认" else "%.0f".format(localSize),
            value = if (localSize <= 0) 0f else localSize,
            onValueChange = { localSize = it },
            onValueChangeFinished = { scope.launch { repository.update { it.copy(danmakuFontSize = localSize) } } },
            valueRange = 0f..48f,
            description = "0 = 默认字号",
        )
    }
    item {
        var localSpeed by remember { mutableStateOf(state.danmakuSpeedMultiplier) }
        SliderRow(
            title = "滚动速度",
            valueText = "%.1fx".format(localSpeed),
            value = localSpeed,
            onValueChange = { localSpeed = it },
            onValueChangeFinished = { scope.launch { repository.update { it.copy(danmakuSpeedMultiplier = localSpeed) } } },
            valueRange = 0.5f..2f,
        )
    }
    item {
        var localArea by remember { mutableStateOf(state.danmakuDisplayArea) }
        SliderRow(
            title = "显示区域",
            valueText = "%.0f%%".format(localArea * 100),
            value = localArea,
            onValueChange = { localArea = it },
            onValueChangeFinished = { scope.launch { repository.update { it.copy(danmakuDisplayArea = localArea) } } },
            valueRange = 0.3f..1f,
        )
    }
    item {
        var localMax by remember { mutableStateOf(state.danmakuMaxOnScreen.toFloat()) }
        SliderRow(
            title = "同屏上限",
            valueText = if (localMax <= 0f) "自动（最多 5000）" else localMax.toInt().toString(),
            value = localMax,
            onValueChange = { localMax = it },
            onValueChangeFinished = { scope.launch { repository.update { it.copy(danmakuMaxOnScreen = localMax.toInt()) } } },
            valueRange = 0f..300f,
            description = "0 = 自动上限 5000（防高密度卡顿/遮挡）",
        )
    }
    item {
        SubsectionTitle("弹幕渲染内核")
        val engineOptions = listOf(
            "ATLAS" to "Atlas 批渲染(默认, draw call N->1-3, 高密度首选)",
            "BITMAP" to "位图缓存(预渲染贴图, 高密度推荐)",
            "COMPOSE" to "Canvas drawText(描边+填充, 文字最清晰)",
        )
        engineOptions.forEach { (value, label) ->
            RadioRow(
                label = label,
                selected = state.danmakuEngine == value,
                onSelect = { scope.launch { repository.update { it.copy(danmakuEngine = value) } } },
            )
        }
        Text(
            "内核说明:\n" +
            "• Atlas 批渲染(默认): 文本烘焙到有界 atlas page(1024×1024), draw 时同 atlas 的 drawBitmap/drawVertices 批合并, draw call 从 N 降到 1-3, 内存最低。高密度首选。\n" +
            "• 位图缓存: 每条唯一弹幕预渲染一次(描边+填充烘焙到位图), 之后每帧只贴图(1 次 GPU blit 替代 2 次 drawText)。高密度场景更省 GPU, 但首次出现新文本有微小光栅化开销, 内存略增。\n" +
            "• Canvas: 每帧 drawText 描边+填充(Skia GPU), 跨平台, 文字最清晰。高密度弹幕时 GPU 负载较高。\n" +
            "三者运动/轨道/倍速/暂停行为一致(共享 BaseDanmakuEngine)。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
    item {
        SwitchRow(
            title = "匹配方式气泡提醒",
            subtitle = "每次匹配到弹幕弹 2s 提示匹配方式(tmdb快速/哈希/文件名搜索)",
            checked = state.danmakuShowMatchToast,
            onCheckedChange = { v -> scope.launch { repository.update { it.copy(danmakuShowMatchToast = v) } } },
        )
    }
}

private fun LazyListScope.interfaceItems(
    state: SettingsState,
    scope: CoroutineScope,
    repository: SettingsRepository,
) {
    item { DesktopInterfaceSettingsSlot(state, scope, repository) }

    item {
        SubsectionTitle("启动首页")
        val options = listOf(
            StartupHome.WEBDAV to "WebDAV",
            StartupHome.ANIME to "番剧",
            StartupHome.LOCAL to "本地",
        )
        options.forEach { (value, label) ->
            RadioRow(
                label = label,
                selected = state.startupHome == value,
                onSelect = {
                    scope.launch { repository.update { it.copy(startupHome = value) } }
                },
            )
        }
        Text(
            text = "下次启动生效",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    item {
        SwitchRow(
            title = "预测性返回动画",
            subtitle = "返回时播放器跟手缩放平移(Android 14+)",
            checked = state.predictiveBack,
            onCheckedChange = { v ->
                scope.launch { repository.update { it.copy(predictiveBack = v) } }
            },
        )
    }

    item {
        SwitchRow(
            title = "动态取色",
            subtitle = "Android 12+ 跟随系统壁纸配色",
            checked = state.dynamicColor,
            onCheckedChange = { v ->
                scope.launch { repository.update { it.copy(dynamicColor = v) } }
            },
        )
    }
    item {
        SwitchRow(
            title = "深色主题",
            subtitle = null,
            checked = state.darkTheme,
            onCheckedChange = { v ->
                scope.launch { repository.update { it.copy(darkTheme = v) } }
            },
        )
    }
}

private fun LazyListScope.securityItems(
    state: SettingsState,
    scope: CoroutineScope,
    repository: SettingsRepository,
) {
    // TLS 降级开关(init-only, 需重进播放器生效)
    item {
        SwitchRow(
            title = "允许 TLS 降级",
            subtitle = "默认关闭。系统 CA 不可用时, 默认宁可播放失败也不关闭证书验证。" +
                "打开后: HTTPS 连接不再验证服务端证书身份, 中间人可窃听/篡改流量" +
                "(WebDAV 账号密码、视频内容)。仅用于自签证书等无法正常播放的特殊服务器," +
                "且需自行承担风险。改后需重新进入播放器生效。",
            checked = state.allowTlsInsecure,
            onCheckedChange = { v ->
                scope.launch { repository.update { it.copy(allowTlsInsecure = v) } }
            },
        )
    }
}

@Composable
internal fun SubsectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
internal fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * 设置滑条统一版式: 标题左 + 当前值右(主题色)一行, 可选说明小字, 下面 Slider。
 * 连续值(字号/描边)与离散预设(长按倍速/缓存)共用此版式, 外观一致。
 *
 * 本地态拖动, 松手(onValueChangeFinished)才持久化: 避免 onValueChange 每 tick 都写
 * DataStore(IO 风暴+耗电)。
 */
@Composable
internal fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    description: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(valueText, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

/**
 * 预设档位滑条: 把一组离散预设值排成可拖动滑条(档位吸附), 比单选列表省竖向空间,
 * 也比无级滑条好调(只能落在预设值上)。预设非等距(如 16/32/64/128/256/512)也能用--
 * 滑条位置=预设下标, 显示/持久化时映射回预设值。布局复用 [SliderRow]。
 */
@Composable
internal fun <T> PresetSlider(
    title: String,
    presets: List<T>,
    current: T,
    valueLabel: (T) -> String,
    onValueChange: (T) -> Unit,
    description: String? = null,
) {
    val lastIndex = presets.lastIndex
    var idx by remember { mutableFloatStateOf(presets.indexOf(current).coerceIn(0, lastIndex).toFloat()) }
    LaunchedEffect(current) { idx = presets.indexOf(current).coerceIn(0, lastIndex).toFloat() }
    SliderRow(
        title = title,
        valueText = valueLabel(presets[idx.toInt().coerceIn(0, lastIndex)]),
        value = idx,
        onValueChange = { idx = it },
        onValueChangeFinished = { onValueChange(presets[idx.toInt().coerceIn(0, lastIndex)]) },
        valueRange = 0f..lastIndex.toFloat(),
        steps = (presets.size - 2).coerceAtLeast(0),
        description = description,
    )
}

private fun LazyListScope.webdavItems(
    state: SettingsState,
    scope: CoroutineScope,
    repository: SettingsRepository,
    connections: List<WebDavConnection>,
) {
    // 默认连接
    item {
        SubsectionTitle("默认连接")
        Text(
            "打开浏览器时自动选中的连接",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        RadioRow(
            label = "不自动选中",
            selected = state.webdavDefaultConnectionId == null,
            onSelect = { scope.launch { repository.update { it.copy(webdavDefaultConnectionId = null) } } },
        )
        connections.forEach { conn ->
            RadioRow(
                label = conn.name,
                selected = state.webdavDefaultConnectionId == conn.id,
                onSelect = { scope.launch { repository.update { it.copy(webdavDefaultConnectionId = conn.id) } } },
            )
        }
    }

    // 默认目录
    item {
        SubsectionTitle("默认目录")
        // 本地 state 缓存输入: value 直接绑 settings flow + onValueChange 异步写回 flow 会让
        // TextField 值在"旧值→新值"往返中丢失光标位置(输入 / 等字符时光标乱跳)。
        // 改为本地持有并防抖写回；外部重置时仍同步回灌输入框。
        var localDir by rememberSaveable { mutableStateOf(state.webdavDefaultDirectory) }
        LaunchedEffect(state.webdavDefaultDirectory) {
            if (localDir != state.webdavDefaultDirectory) {
                localDir = state.webdavDefaultDirectory
            }
        }
        LaunchedEffect(localDir, state.webdavDefaultDirectory) {
            if (localDir != state.webdavDefaultDirectory) {
                delay(SETTINGS_TEXT_DEBOUNCE_MS)
                repository.update { it.copy(webdavDefaultDirectory = localDir) }
            }
        }
        OutlinedTextField(
            value = localDir,
            onValueChange = { localDir = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            label = { Text("例如: /视频/动画") },
            trailingIcon = {
                IconButton(onClick = {
                    localDir = "/"
                }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "重置")
                }
            },
        )
    }

    // 排序预设
    item {
        SubsectionTitle("文件排序")
        WebDavSortPreset.entries.forEach { p ->
            RadioRow(
                label = "${p.displayName}（${p.description}）",
                selected = state.webdavSortPreset == p,
                onSelect = { scope.launch { repository.update { it.copy(webdavSortPreset = p) } } },
            )
        }
    }

    // 显示面包屑
    item {
        SwitchRow(
            title = "显示路径导航",
            subtitle = "顶部显示可点击的路径面包屑",
            checked = state.webdavShowBreadcrumb,
            onCheckedChange = { v -> scope.launch { repository.update { it.copy(webdavShowBreadcrumb = v) } } },
        )
    }

    // 自动进入 Season 文件夹
    item {
        SwitchRow(
            title = "自动进入 Season 文件夹",
            subtitle = "打开默认目录时自动进入匹配的子文件夹",
            checked = state.webdavAutoEnterSeasonFolder,
            onCheckedChange = { v -> scope.launch { repository.update { it.copy(webdavAutoEnterSeasonFolder = v) } } },
        )
    }
    if (state.webdavAutoEnterSeasonFolder) {
        item {
            var localPattern by rememberSaveable { mutableStateOf(state.webdavSeasonFolderPattern) }
            LaunchedEffect(state.webdavSeasonFolderPattern) {
                if (localPattern != state.webdavSeasonFolderPattern) {
                    localPattern = state.webdavSeasonFolderPattern
                }
            }
            LaunchedEffect(localPattern, state.webdavSeasonFolderPattern) {
                if (localPattern != state.webdavSeasonFolderPattern) {
                    delay(SETTINGS_TEXT_DEBOUNCE_MS)
                    repository.update { it.copy(webdavSeasonFolderPattern = localPattern) }
                }
            }
            OutlinedTextField(
                value = localPattern,
                onValueChange = { localPattern = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                label = { Text("匹配模式（支持 * 和 ?）") },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Season*", "Season ??", "S*", "Disc*").forEach { preset ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            localPattern = preset
                        },
                        label = { Text(preset) },
                    )
                }
            }
        }
    }

    // 番剧识别匹配（预留, 需番剧识别后端支持）
    item {
        SubsectionTitle("番剧识别匹配（预留, 需后端支持）")
        Text(
            "以下设置当前仅保存, 后端(P2)实现后自动生效",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SwitchRow(
            title = "bgmid 快速匹配",
            subtitle = "从 URL 提取 bgmid 获取番剧信息",
            checked = state.bgmIdQuickMatch,
            onCheckedChange = { v -> scope.launch { repository.update { it.copy(bgmIdQuickMatch = v) } } },
        )
    }
    if (state.bgmIdQuickMatch) {
        item {
            var localBgm by rememberSaveable { mutableStateOf(state.bgmIdMatchPattern) }
            LaunchedEffect(state.bgmIdMatchPattern) {
                if (localBgm != state.bgmIdMatchPattern) {
                    localBgm = state.bgmIdMatchPattern
                }
            }
            LaunchedEffect(localBgm, state.bgmIdMatchPattern) {
                if (localBgm != state.bgmIdMatchPattern) {
                    delay(SETTINGS_TEXT_DEBOUNCE_MS)
                    repository.update { it.copy(bgmIdMatchPattern = localBgm) }
                }
            }
            OutlinedTextField(
                value = localBgm,
                onValueChange = { localBgm = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                label = { Text("bgmid 匹配正则") },
            )
        }
    }
    item {
        SwitchRow(
            title = "tmdbId 快速匹配",
            subtitle = "从 URL 提取 tmdbId 获取番剧信息",
            checked = state.tmdbIdQuickMatch,
            onCheckedChange = { v -> scope.launch { repository.update { it.copy(tmdbIdQuickMatch = v) } } },
        )
    }
    if (state.tmdbIdQuickMatch) {
        item {
            var localTmdb by rememberSaveable { mutableStateOf(state.tmdbIdMatchPattern) }
            LaunchedEffect(state.tmdbIdMatchPattern) {
                if (localTmdb != state.tmdbIdMatchPattern) {
                    localTmdb = state.tmdbIdMatchPattern
                }
            }
            LaunchedEffect(localTmdb, state.tmdbIdMatchPattern) {
                if (localTmdb != state.tmdbIdMatchPattern) {
                    delay(SETTINGS_TEXT_DEBOUNCE_MS)
                    repository.update { it.copy(tmdbIdMatchPattern = localTmdb) }
                }
            }
            OutlinedTextField(
                value = localTmdb,
                onValueChange = { localTmdb = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                label = { Text("tmdbId 匹配正则") },
            )
        }
    }
    // 剧集偏移与 tmdbId 提取无依赖, 独立开关(原误嵌套在 tmdbId 块内, 不开 tmdbId 就看不到)
    item {
        SwitchRow(
            title = "弹幕自动剧集偏移（实验中）",
            subtitle = "修复 SxEx 季内编号与弹幕库绝对编号不匹配",
            checked = state.episodeOffsetEnabled,
            onCheckedChange = { v -> scope.launch { repository.update { it.copy(episodeOffsetEnabled = v) } } },
        )
    }

    // 文件搜索
    item {
        SubsectionTitle("文件搜索")
        SwitchRow(
            title = "文件搜索",
            subtitle = "在浏览器显示搜索按钮",
            checked = state.webdavEnableSearch,
            onCheckedChange = { v -> scope.launch { repository.update { it.copy(webdavEnableSearch = v) } } },
        )
    }
    if (state.webdavEnableSearch) {
        item {
            SubsectionTitle("搜索范围")
            WebDavSearchScope.entries.forEach { s ->
                RadioRow(
                    label = "${s.displayName}（${s.description}）",
                    selected = state.webdavSearchScope == s,
                    onSelect = { scope.launch { repository.update { it.copy(webdavSearchScope = s) } } },
                )
            }
        }
        item {
            var depth by remember { mutableStateOf(state.webdavSearchDepthLimit.toFloat()) }
            LaunchedEffect(state.webdavSearchDepthLimit) { depth = state.webdavSearchDepthLimit.toFloat() }
            SliderRow(
                title = "层级限制",
                valueText = "${state.webdavSearchDepthLimit} 层",
                value = depth,
                onValueChange = { depth = it },
                onValueChangeFinished = { scope.launch { repository.update { it.copy(webdavSearchDepthLimit = depth.toInt()) } } },
                valueRange = 1f..10f,
                steps = 8,
                description = "向下遍历子目录的层数",
            )
        }
        item {
            SubsectionTitle("搜索目标（可多选）")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WebDavSearchTarget.entries.forEach { t ->
                    FilterChip(
                        selected = t in state.webdavSearchTargets,
                        onClick = {
                            val newTargets = if (t in state.webdavSearchTargets) state.webdavSearchTargets - t
                            else state.webdavSearchTargets + t
                            // 至少保留一个: 若移除后为空则不改
                            if (newTargets.isNotEmpty()) {
                                scope.launch { repository.update { it.copy(webdavSearchTargets = newTargets) } }
                            }
                        },
                        label = { Text(t.displayName) },
                    )
                }
            }
        }
        item {
            SubsectionTitle("搜索超时")
            WebDavSearchTimeout.entries.forEach { t ->
                RadioRow(
                    label = t.displayName,
                    selected = state.webdavSearchTimeout == t,
                    onSelect = { scope.launch { repository.update { it.copy(webdavSearchTimeout = t) } } },
                )
            }
        }
        item {
            var interval by remember { mutableStateOf(state.webdavSearchRequestInterval.toFloat()) }
            LaunchedEffect(state.webdavSearchRequestInterval) { interval = state.webdavSearchRequestInterval.toFloat() }
            SliderRow(
                title = "请求间隔",
                valueText = "${state.webdavSearchRequestInterval} ms",
                value = interval,
                onValueChange = { interval = it },
                onValueChangeFinished = { scope.launch { repository.update { it.copy(webdavSearchRequestInterval = interval.toInt()) } } },
                valueRange = 0f..1000f,
                steps = 19,
                description = "防止请求过快被服务器限制, 0 表示无延迟",
            )
        }
        item {
            var maxR by remember { mutableStateOf(state.webdavSearchMaxResults.toFloat()) }
            LaunchedEffect(state.webdavSearchMaxResults) { maxR = state.webdavSearchMaxResults.toFloat() }
            SliderRow(
                title = "最大结果数",
                valueText = "${state.webdavSearchMaxResults}",
                value = maxR,
                onValueChange = { maxR = it },
                onValueChangeFinished = { scope.launch { repository.update { it.copy(webdavSearchMaxResults = maxR.toInt()) } } },
                valueRange = 50f..2000f,
                steps = 38,
                description = "达到上限时自动停止",
            )
        }
    }

    // 重置所有 WebDAV 设置
    item {
        TextButton(
            onClick = {
                scope.launch {
                    repository.update {
                        it.copy(
                            webdavDefaultConnectionId = null,
                            webdavDefaultDirectory = "/",
                            webdavSortPreset = WebDavSortPreset.DEFAULT,
                            webdavShowBreadcrumb = true,
                            webdavAutoEnterSeasonFolder = false,
                            webdavSeasonFolderPattern = "Season*",
                            bgmIdQuickMatch = false,
                            bgmIdMatchPattern = "bgm(id)?[=-](\\d+)",
                            tmdbIdQuickMatch = false,
                            tmdbIdMatchPattern = "tmdb(id)?[=-](\\d+)",
                            episodeOffsetEnabled = false,
                            webdavEnableSearch = true,
                            webdavSearchScope = WebDavSearchScope.CURRENT_WITH_DEPTH,
                            webdavSearchDepthLimit = 3,
                            webdavSearchTargets = WebDavSearchTarget.DEFAULT,
                            webdavSearchTimeout = WebDavSearchTimeout.SECONDS_30,
                            webdavSearchRequestInterval = 100,
                            webdavSearchMaxResults = 500,
                        )
                    }
                }
            },
            modifier = Modifier.padding(16.dp),
        ) { Text("重置所有 WebDAV 设置") }
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
