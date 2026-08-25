package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.weiyongzenqi.unuplayer.anirss.AniRssConnectionState
import io.github.weiyongzenqi.unuplayer.anirss.AniRssCreateRequest
import io.github.weiyongzenqi.unuplayer.anirss.AniRssFilterCombination
import io.github.weiyongzenqi.unuplayer.anirss.AniRssGroup
import io.github.weiyongzenqi.unuplayer.anirss.AniRssMikanCandidate
import io.github.weiyongzenqi.unuplayer.anirss.AniRssPreparedSubscription
import io.github.weiyongzenqi.unuplayer.anirss.AniRssPreview
import io.github.weiyongzenqi.unuplayer.anirss.AniRssRepository
import io.github.weiyongzenqi.unuplayer.anirss.AniRssServerProfile
import io.github.weiyongzenqi.unuplayer.anirss.AniRssSubscription
import io.github.weiyongzenqi.unuplayer.bangumi.bangumiImageModel
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.platform.AppNotif
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleEntry
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
internal fun AniRssSubscriptionManager(
    repository: AniRssRepository,
    connection: AniRssConnectionState?,
    subscriptions: List<AniRssSubscription>,
    scheduleEntries: List<ScheduleEntry>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    resolvePosterUrl: (String?) -> String?,
    onOpenSubject: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var operationId by remember { mutableStateOf<String?>(null) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<AniRssSubscription?>(null) }

    fun runOperation(id: String, successMessage: String, block: suspend () -> Unit) {
        if (operationId != null) return
        scope.launch {
            operationId = id
            operationError = null
            runSuspendCatching { block() }
                .onSuccess {
                    AppNotif.toast(successMessage)
                    onRefresh()
                }
                .onFailure { operationError = it.message ?: "Ani-RSS 操作失败" }
            operationId = null
        }
    }

    when {
        connection != null && !connection.configured -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ani-RSS 尚未连接", style = MaterialTheme.typography.titleMedium)
                Text("请先前往 设置 > Ani-RSS 完成连接验证", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRefresh) { Text("重试") }
            }
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            operationError?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (subscriptions.isEmpty()) {
                item {
                    Text(
                        "暂无订阅",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(subscriptions, key = { it.id }) { subscription ->
                val scheduleEntry = subscription.subjectId?.let { subjectId ->
                    scheduleEntries.firstOrNull { it.subjectId == subjectId }
                }
                val posterUrl = resolvePosterUrl(subscription.posterUrl ?: scheduleEntry?.posterUrl)
                val posterModel = remember(posterUrl) {
                    posterUrl?.let { url -> bangumiImageModel(context, url) }
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            subscription.subjectId?.let { subjectId ->
                                Modifier.clickable { onOpenSubject(subjectId) }
                            } ?: Modifier,
                        ),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(58.dp, 82.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(subscription.title.firstOrNull()?.toString().orEmpty())
                                posterModel?.let {
                                    AsyncImage(
                                        model = it,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(subscription.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(subscription.subgroup, subscription.subjectId?.let { "Bangumi #$it" })
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                subscription.rssUrl?.takeIf(String::isNotBlank)?.let { rssUrl ->
                                    Text(
                                        rssUrl,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (operationId == subscription.id) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Switch(
                                    checked = subscription.enabled,
                                    enabled = operationId == null,
                                    onCheckedChange = { enabled ->
                                        runOperation(subscription.id, if (enabled) "订阅已启用" else "订阅已停用") {
                                            repository.setSubscriptionEnabled(subscription.id, enabled)
                                        }
                                    },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                enabled = operationId == null,
                                onClick = {
                                    runOperation(subscription.id, "已提交刷新任务") {
                                        repository.refreshSubscription(subscription.id)
                                    }
                                },
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("刷新")
                            }
                            TextButton(enabled = operationId == null, onClick = { deleteTarget = subscription }) {
                                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { subscription ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除订阅") },
            text = { Text("只删除「${subscription.title}」的 Ani-RSS 订阅配置，不删除下载任务和本地文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        runOperation(subscription.id, "订阅已删除") { repository.deleteSubscription(subscription.id) }
                    },
                ) { Text("删除配置") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

private data class AniRssPreviewBundle(
    val request: AniRssCreateRequest,
    val prepared: AniRssPreparedSubscription,
    val preview: AniRssPreview,
)

@Composable
private fun AniRssCandidateBadge(
    text: String,
    emphasized: Boolean = false,
    warning: Boolean = false,
) {
    val containerColor = when {
        warning -> MaterialTheme.colorScheme.errorContainer
        emphasized -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when {
        warning -> MaterialTheme.colorScheme.onErrorContainer
        emphasized -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = containerColor, contentColor = contentColor, shape = MaterialTheme.shapes.small) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AniRssSubscriptionWizard(
    entry: ScheduleEntry,
    repository: AniRssRepository,
    resolvePosterUrl: (String?) -> String?,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var connection by remember { mutableStateOf<AniRssConnectionState?>(null) }
    var profile by remember { mutableStateOf<AniRssServerProfile?>(null) }
    var alreadySubscribed by remember { mutableStateOf(false) }
    var query by remember(entry.subjectId) { mutableStateOf(entry.title) }
    var candidates by remember(entry.subjectId) { mutableStateOf<List<AniRssMikanCandidate>>(emptyList()) }
    var selectedCandidate by remember(entry.subjectId) { mutableStateOf<AniRssMikanCandidate?>(null) }
    var unverifiedConfirmed by remember(entry.subjectId) { mutableStateOf(false) }
    var groups by remember(entry.subjectId) { mutableStateOf<List<AniRssGroup>>(emptyList()) }
    var primaryGroup by remember(entry.subjectId) { mutableStateOf<AniRssGroup?>(null) }
    var standbyGroups by remember(entry.subjectId) { mutableStateOf<List<AniRssGroup>>(emptyList()) }
    var filterCombinationsByRss by remember(entry.subjectId) {
        mutableStateOf<Map<String, AniRssFilterCombination>>(emptyMap())
    }
    var showAdvanced by remember(entry.subjectId) { mutableStateOf(false) }
    var customPathEnabled by remember(entry.subjectId) { mutableStateOf(false) }
    var customPath by remember(entry.subjectId) { mutableStateOf("") }
    var priorityEnabled by remember(entry.subjectId) { mutableStateOf(false) }
    var priorityText by remember(entry.subjectId) { mutableStateOf("") }
    var offsetEnabled by remember(entry.subjectId) { mutableStateOf(false) }
    var offsetText by remember(entry.subjectId) { mutableStateOf("0") }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var previewBundle by remember(entry.subjectId) { mutableStateOf<AniRssPreviewBundle?>(null) }

    fun loadGroups(candidate: AniRssMikanCandidate) {
        if (busyLabel != null || !candidate.identityVerified && !unverifiedConfirmed) return
        scope.launch {
            busyLabel = "正在读取字幕组…"
            error = null
            runSuspendCatching {
                repository.loadMikanGroups(entry.subjectId, candidate, unverifiedConfirmed)
            }.onSuccess { loaded ->
                if (selectedCandidate == candidate) {
                    groups = loaded
                    primaryGroup = loaded.firstOrNull()
                    standbyGroups = emptyList()
                    filterCombinationsByRss = emptyMap()
                }
            }.onFailure { error = it.message ?: "字幕组加载失败" }
            busyLabel = null
        }
    }

    fun search() {
        val text = query.trim()
        if (text.isEmpty() || busyLabel != null || profile == null) return
        scope.launch {
            busyLabel = "正在搜索 Mikan…"
            error = null
            val captured = text
            runSuspendCatching { repository.searchMikan(entry.subjectId, captured) }
                .onSuccess { loaded ->
                    if (query.trim() == captured) {
                        candidates = loaded
                        selectedCandidate = null
                        groups = emptyList()
                        primaryGroup = null
                        standbyGroups = emptyList()
                        filterCombinationsByRss = emptyMap()
                    }
                }
                .onFailure { error = it.message ?: "Mikan 搜索失败" }
            busyLabel = null
        }
    }

    LaunchedEffect(repository, entry.subjectId) {
        busyLabel = "正在检查 Ani-RSS…"
        error = null
        runSuspendCatching { repository.connectionState() }
            .onSuccess { state ->
                connection = state
                if (state.configured) {
                    runSuspendCatching {
                        repository.serverProfile() to repository.isSubscribed(entry.subjectId)
                    }
                        .onSuccess { (verifiedProfile, subscribed) ->
                            profile = verifiedProfile
                            alreadySubscribed = subscribed
                        }
                        .onFailure { error = it.message ?: "Ani-RSS 版本检查失败" }
                }
            }
            .onFailure { error = it.message ?: "Ani-RSS 连接状态读取失败" }
        busyLabel = null
        if (connection?.configured == true && profile != null && error == null && !alreadySubscribed) search()
    }

    val offset = if (offsetEnabled) offsetText.trim().toIntOrNull() else null
    val currentRequest = primaryGroup?.let { primary ->
        val selectedGroups = listOf(primary) + standbyGroups
        val identityReady = primary.identityVerified && standbyGroups.all { it.identityVerified } || unverifiedConfirmed
        val pathReady = !customPathEnabled || customPath.isNotBlank()
        val offsetReady = !offsetEnabled || offset != null
        if (!identityReady || !pathReady || !offsetReady) null else AniRssCreateRequest(
            subjectId = entry.subjectId,
            title = entry.title,
            primaryGroup = primary,
            standbyGroups = standbyGroups,
            filterCombinationsByRss = selectedGroups.mapNotNull { group ->
                filterCombinationsByRss[group.rss]?.let { group.rss to it }
            }.toMap(),
            unverifiedIdentityConfirmed = unverifiedConfirmed,
            customDownloadPath = customPath.trim().takeIf { customPathEnabled },
            // 只有勾选且确实有关键词时才非空; 空文本视为未启用, 不下发 enable=false, 保留服务端默认。
            customPriorityKeywords = priorityText.split(',', '\n')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .takeIf { priorityEnabled && it.isNotEmpty() },
            episodeOffset = offset,
        )
    }
    val currentPreview = previewBundle?.takeIf { it.request == currentRequest }

    Dialog(
        onDismissRequest = { if (busyLabel == null) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = busyLabel == null) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭")
                        }
                    },
                    title = {
                        Column {
                            Text("添加 Ani-RSS 订阅", maxLines = 1)
                            Text(entry.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    ) {
                        OutlinedButton(onClick = onDismiss, enabled = busyLabel == null) { Text("取消") }
                        Button(
                            enabled = currentRequest != null && busyLabel == null && !alreadySubscribed,
                            onClick = {
                                val request = currentRequest ?: return@Button
                                scope.launch {
                                    busyLabel = "正在生成并预览订阅…"
                                    error = null
                                    runSuspendCatching {
                                        val prepared = repository.prepareSubscription(request)
                                        AniRssPreviewBundle(request, prepared, repository.preview(prepared))
                                    }.onSuccess { previewBundle = it }
                                        .onFailure { error = it.message ?: "订阅预览失败" }
                                    busyLabel = null
                                }
                            },
                        ) { Text(if (currentPreview == null) "生成预览" else "重新预览") }
                        Button(
                            enabled = currentPreview != null && busyLabel == null && !alreadySubscribed,
                            onClick = {
                                val bundle = currentPreview ?: return@Button
                                scope.launch {
                                    busyLabel = "正在添加订阅…"
                                    error = null
                                    runSuspendCatching { repository.add(bundle.prepared) }
                                        .onSuccess { onAdded() }
                                        .onFailure { error = it.message ?: "添加订阅失败" }
                                    busyLabel = null
                                }
                            },
                        ) { Text("确认添加") }
                    }
                }
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                when {
                                    connection == null -> "正在读取连接状态"
                                    connection?.configured == false -> "Ani-RSS 尚未连接"
                                    else -> "已连接 ${profile?.version?.let { "· v${it.removePrefix("v")}" }.orEmpty()}"
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                connection?.baseUrl.orEmpty().ifBlank { "请先前往 设置 > Ani-RSS 配置服务地址和 API Key" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                if (alreadySubscribed) {
                    item { Text("Ani-RSS 中已存在这部番剧的订阅，已停止重复添加。", color = MaterialTheme.colorScheme.primary) }
                }
                if (connection?.configured == true && profile != null && !alreadySubscribed) {
                    item {
                        Text("1. 选择 Mikan 番剧", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("搜索标题") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = ::search, enabled = busyLabel == null && query.isNotBlank()) {
                                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }),
                        )
                    }
                    if (candidates.isEmpty() && busyLabel == null) {
                        item { Text("没有候选。可调整搜索标题后重试。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(candidates, key = { it.pageUrl }) { candidate ->
                        val selected = candidate == selectedCandidate
                        val candidateMatchesEntry = candidate.title.trim().equals(entry.title.trim(), ignoreCase = true) ||
                            entry.originalTitle?.let { candidate.title.trim().equals(it.trim(), ignoreCase = true) } == true
                        val fallbackPoster = entry.posterUrl.takeIf { candidate.identityVerified || candidateMatchesEntry }
                        val posterUrls = listOfNotNull(
                            resolvePosterUrl(candidate.coverUrl),
                            resolvePosterUrl(fallbackPoster),
                        ).distinct()
                        var posterIndex by remember(candidate.pageUrl, posterUrls) { mutableStateOf(0) }
                        var posterUnavailable by remember(candidate.pageUrl, posterUrls) { mutableStateOf(false) }
                        val currentPosterUrl = posterUrls.getOrNull(posterIndex)
                        val posterModel = remember(currentPosterUrl) {
                            currentPosterUrl?.let { bangumiImageModel(context, it) }
                        }
                        val candidateScore = candidate.score?.takeIf { it.isFinite() && it > 0.0 }
                        val displayedScore = candidateScore?.let { "Mikan %.1f 分".format(it) }
                            ?: entry.rating?.takeIf { candidate.identityVerified && it > 0.0 }?.let {
                                "Bangumi %.1f 分".format(it)
                            }
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable(
                                enabled = !candidate.alreadyExists && busyLabel == null,
                            ) {
                                selectedCandidate = candidate
                                unverifiedConfirmed = false
                                groups = emptyList()
                                primaryGroup = null
                                if (candidate.identityVerified) loadGroups(candidate)
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    candidate.alreadyExists -> MaterialTheme.colorScheme.surfaceVariant
                                    selected -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceContainer
                                },
                            ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(76.dp, 108.dp)
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        candidate.title.firstOrNull()?.toString().orEmpty(),
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f),
                                    )
                                    posterModel?.let { model ->
                                        AsyncImage(
                                            model = model,
                                            contentDescription = candidate.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                            onSuccess = { posterUnavailable = false },
                                            onError = {
                                                if (posterIndex < posterUrls.lastIndex) {
                                                    posterIndex++
                                                } else {
                                                    posterUnavailable = true
                                                }
                                            },
                                        )
                                    }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(candidate.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (candidate.identityVerified) {
                                        entry.originalTitle?.takeIf {
                                            it.isNotBlank() && !it.equals(candidate.title, ignoreCase = true)
                                        }?.let { originalTitle ->
                                            Text(
                                                originalTitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        AniRssCandidateBadge(
                                            text = when {
                                                candidate.alreadyExists -> "已订阅"
                                                candidate.identityVerified -> "身份已核对"
                                                else -> "身份待确认"
                                            },
                                            emphasized = selected || candidate.identityVerified,
                                            warning = !candidate.identityVerified,
                                        )
                                        candidate.bangumiSubjectId?.let {
                                            AniRssCandidateBadge("Bangumi #$it")
                                        }
                                        candidate.mikanId?.let { AniRssCandidateBadge("Mikan #$it") }
                                        candidate.weekLabel?.takeIf(String::isNotBlank)?.let { AniRssCandidateBadge(it) }
                                        entry.airDate?.takeIf { candidate.identityVerified && it.isNotBlank() }?.let {
                                            AniRssCandidateBadge(it)
                                        }
                                        displayedScore?.let { AniRssCandidateBadge(it) }
                                    }
                                    Text(
                                        if (posterUnavailable) {
                                            "没有可加载的候选封面"
                                        } else if (posterIndex > 0 || candidate.coverUrl == null && fallbackPoster != null) {
                                            "已使用当前番剧海报作为候选参考"
                                        } else if (candidate.coverUrl == null) {
                                            "Mikan 未提供可用封面"
                                        } else {
                                            "封面与条目信息来自 Mikan"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = "已选择",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                    selectedCandidate?.takeIf { !it.identityVerified }?.let { candidate ->
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "此候选没有可核对的 Bangumi 身份。请确认它确实对应「${entry.title}」（Bangumi #${entry.subjectId}）。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = unverifiedConfirmed, onCheckedChange = { unverifiedConfirmed = it })
                                    Text("我已人工核对标题与季度", color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                                Button(
                                    onClick = { loadGroups(candidate) },
                                    enabled = unverifiedConfirmed && busyLabel == null,
                                ) { Text("读取字幕组") }
                            }
                            }
                        }
                    }
                    if (groups.isNotEmpty()) {
                        item { Text("2. 选择主字幕组", style = MaterialTheme.typography.titleMedium) }
                        items(groups, key = { "primary-${it.rss}" }) { group ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    primaryGroup = group
                                    standbyGroups = standbyGroups - group
                                }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = primaryGroup == group, onClick = null)
                                Column(Modifier.weight(1f)) {
                                    Text(group.label, fontWeight = FontWeight.Medium)
                                    Text(
                                        listOfNotNull(
                                            group.updateDay,
                                            group.resources.takeIf { it.isNotEmpty() }?.let { "${it.size} 条近期资源" },
                                            group.resources.firstOrNull()?.formatSize,
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    group.tags.takeIf { it.isNotEmpty() }?.let { tags ->
                                        Text(
                                            tags.take(4).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        if (profile?.standbyRssEnabled == true && groups.size > 1) {
                            item { Text("3. 备用字幕组（按所列顺序）", style = MaterialTheme.typography.titleMedium) }
                            items(groups.filter { it != primaryGroup }, key = { "standby-${it.rss}" }) { group ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = group in standbyGroups,
                                        onCheckedChange = { checked ->
                                            standbyGroups = if (checked) standbyGroups + group else standbyGroups - group
                                            if (!checked) filterCombinationsByRss = filterCombinationsByRss - group.rss
                                        },
                                    )
                                    Text(group.label, modifier = Modifier.weight(1f))
                                    val index = standbyGroups.indexOf(group)
                                    if (index >= 0) {
                                        IconButton(
                                            enabled = index > 0,
                                            onClick = { standbyGroups = moveAniRssGroup(standbyGroups, index, index - 1) },
                                        ) { Icon(Icons.Filled.ArrowUpward, contentDescription = "上移") }
                                        IconButton(
                                            enabled = index < standbyGroups.lastIndex,
                                            onClick = { standbyGroups = moveAniRssGroup(standbyGroups, index, index + 1) },
                                        ) { Icon(Icons.Filled.ArrowDownward, contentDescription = "下移") }
                                    }
                                }
                            }
                        } else if (groups.size > 1) {
                            item {
                                Text(
                                    "服务端未开启备用 RSS，本次只会使用主字幕组。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        val filterGroups = listOfNotNull(primaryGroup) + standbyGroups
                        if (filterGroups.any { it.filterCombinations.isNotEmpty() }) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("4. 各字幕组资源筛选（可选）", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "每一项都是 Ani-RSS 返回的一整套匹配规则；不选择则保留服务端默认值。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        filterGroups.forEach { filterGroup ->
                            if (filterGroup.filterCombinations.isNotEmpty()) {
                                item(key = "filter-title-${filterGroup.rss}") {
                                    Text(filterGroup.label, fontWeight = FontWeight.Medium)
                                }
                                item {
                                    Row(
                                        Modifier.fillMaxWidth().clickable {
                                            filterCombinationsByRss = filterCombinationsByRss - filterGroup.rss
                                        }.padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = filterCombinationsByRss[filterGroup.rss] == null, onClick = null)
                                        Text("使用服务端默认匹配")
                                    }
                                }
                                items(
                                    filterGroup.filterCombinations,
                                    key = { "filter-${filterGroup.rss}-${it.options.joinToString("|") { option -> option.regex }}" },
                                ) { combination ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable {
                                            filterCombinationsByRss = filterCombinationsByRss + (filterGroup.rss to combination)
                                        }.padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = filterCombinationsByRss[filterGroup.rss] == combination,
                                            onClick = null,
                                        )
                                        Text(combination.label)
                                    }
                                }
                            }
                        }
                        item {
                            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                                Text(if (showAdvanced) "收起高级设置" else "展开高级设置")
                            }
                            if (showAdvanced) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = customPathEnabled, onCheckedChange = { customPathEnabled = it })
                                        Text("自定义下载路径")
                                    }
                                    if (customPathEnabled) {
                                        OutlinedTextField(
                                            value = customPath,
                                            onValueChange = { customPath = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text("下载路径模板") },
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = priorityEnabled, onCheckedChange = { priorityEnabled = it })
                                        Text("自定义多文件保留优先级")
                                    }
                                    if (priorityEnabled) {
                                        OutlinedTextField(
                                            value = priorityText,
                                            onValueChange = { priorityText = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text("关键词（逗号或换行分隔）") },
                                            minLines = 2,
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = offsetEnabled, onCheckedChange = { offsetEnabled = it })
                                        Text("自定义剧集偏移")
                                    }
                                    if (offsetEnabled) {
                                        OutlinedTextField(
                                            value = offsetText,
                                            onValueChange = { offsetText = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text("offset（整数）") },
                                            isError = offset == null,
                                            singleLine = true,
                                        )
                                    }
                                    Text(
                                        "未启用的高级项全部保留 /api/rssToAni 返回的服务端默认值。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                if (previewBundle != null && currentPreview == null) {
                    item {
                        Text(
                            "配置已经变化，请重新生成预览后再添加。",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                currentPreview?.let { bundle ->
                    item {
                        HorizontalDivider()
                        Spacer(Modifier.height(6.dp))
                        Text("订阅预览", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "下载路径：${bundle.preview.downloadPath.ifBlank { "服务端未返回" }}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("命中 ${bundle.preview.items.size} 个资源", style = MaterialTheme.typography.bodySmall)
                        if (bundle.preview.omittedEpisodes.isNotEmpty()) {
                            Text(
                                "缺少集数：${bundle.preview.omittedEpisodes.joinToString("、")}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    items(bundle.preview.items, key = { "${it.episode}:${it.title}:${it.publishedAt}" }) { previewItem ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(previewItem.renamedTitle ?: previewItem.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        previewItem.episode?.let { "E${it.toInt()}" },
                                        previewItem.formatSize,
                                        previewItem.subgroup,
                                        if (previewItem.alreadyDownloaded) "已下载" else null,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                busyLabel?.let { label ->
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
                error?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error) }
                }
                item { Spacer(Modifier.height(74.dp)) }
            }
        }
    }
}

private fun moveAniRssGroup(groups: List<AniRssGroup>, fromIndex: Int, toIndex: Int): List<AniRssGroup> {
    if (fromIndex !in groups.indices || toIndex !in groups.indices || fromIndex == toIndex) return groups
    return groups.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}
