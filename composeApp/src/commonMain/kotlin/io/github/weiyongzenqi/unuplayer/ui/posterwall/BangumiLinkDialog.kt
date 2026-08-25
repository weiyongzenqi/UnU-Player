package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiAssociationService
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiCandidate
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiCandidateSource
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiCatalogApi
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiExtLinkerBridge
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkSource
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkState
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonIdentity
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.bangumi.EffectiveBangumiLink
import io.github.weiyongzenqi.unuplayer.bangumi.EffectiveBangumiLinkSource
import io.github.weiyongzenqi.unuplayer.bangumi.mergeBangumiCandidates
import io.github.weiyongzenqi.unuplayer.bangumi.resolveEffectiveBangumiLink
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiEndpointConfig
import io.github.weiyongzenqi.unuplayer.bangumi.gatewayEndpointOrNull
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.ScrapedSeason
import io.github.weiyongzenqi.unuplayer.library.ScrapedShow
import io.github.weiyongzenqi.unuplayer.library.cleanAnimeSearchKeyword
import io.github.weiyongzenqi.unuplayer.library.getStoredBangumiSeasonLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BangumiLinkDialog(
    show: ScrapedShow,
    season: ScrapedSeason,
    repository: ScrapedLibraryRepository,
    endpoints: BangumiEndpointConfig,
    onDismiss: () -> Unit,
    onChanged: (EffectiveBangumiLink?) -> Unit = {},
) {
    val identityKey = remember(show, season) { BangumiSeasonIdentity.keyFor(show, season) }
    val service = remember(endpoints.identity) {
        BangumiAssociationService(
            catalog = BangumiCatalogApi(
                baseUrl = endpoints.apiBaseUrl,
                gateway = endpoints.gatewayEndpointOrNull(),
            ),
            tmdbBridges = listOf(BangumiExtLinkerBridge()),
        )
    }
    val scope = rememberCoroutineScope()
    var persisted by remember(identityKey) { mutableStateOf<BangumiSeasonLink?>(null) }
    var effective by remember(identityKey) { mutableStateOf<EffectiveBangumiLink?>(null) }
    var candidates by remember(identityKey) { mutableStateOf<List<BangumiCandidate>>(emptyList()) }
    var selectedId by remember(identityKey) { mutableStateOf<Long?>(null) }
    val queryState = remember(identityKey) { TextFieldState(show.title) }
    // Ani-RSS 集数漂移手动修正: 空 = 维持当前值; 保存写 Season 表并迁移关联键。
    val offsetState = remember(identityKey) { TextFieldState("") }
    var savingOffset by remember(identityKey) { mutableStateOf(false) }
    var loading by remember(identityKey) { mutableStateOf(true) }
    var searching by remember(identityKey) { mutableStateOf(false) }
    var saving by remember(identityKey) { mutableStateOf(false) }
    var message by remember(identityKey) { mutableStateOf<String?>(null) }
    var inheritedLegacyIdentityKey by remember(identityKey) { mutableStateOf<String?>(null) }

    val offsetText = offsetState.text.toString().trim()
    val parsedOffset: Long? = offsetText.toLongOrNull()
    val offsetDirty = offsetText.isNotEmpty() && parsedOffset != season.bangumi_offset

    fun saveOffset() {
        val requested = parsedOffset ?: run {
            message = "漂移值无效，请输入整数（如 0、-11）"
            return
        }
        // 范围夹紧后的生效值: 消息与后续判断都以它为准, 避免提示与落库不一致。
        val newOffset = requested.coerceIn(-10_000L, 10_000L)
        if (savingOffset) return
        scope.launch {
            savingOffset = true
            val saved = runSuspendCatching {
                repository.updateSeasonBangumiOffset(
                    libraryId = show.library_id,
                    showPath = show.show_path,
                    tmdbId = show.tmdb_id,
                    seasonId = season.id,
                    seasonNumber = season.season_number,
                    newOffset = newOffset,
                )
            }
            savingOffset = false
            saved.onSuccess { updated ->
                when {
                    !updated -> message = "季度数据已变化（重新扫描），请关闭后重试"
                    newOffset == season.bangumi_offset -> message = "漂移与当前值相同，未修改"
                    else -> {
                        onChanged(effective)
                        // 关闭弹窗: offset 变更会迁移关联 identity 键, 本弹窗持有的仍是旧 offset
                        // 快照, 继续在此确认会把关联写回旧键语义。父级随 onChanged 重载季快照。
                        message = "集数漂移已更新为 $newOffset（本地集号 - 漂移 = 全系列集号）"
                        onDismiss()
                    }
                }
            }.onFailure {
                message = "漂移保存失败，请重试"
            }
        }
    }

    suspend fun clearStoredLinkOverrides() {
        repository.clearBangumiSeasonLink(identityKey)
        inheritedLegacyIdentityKey?.let { legacyKey ->
            repository.clearBangumiSeasonLink(legacyKey)
            inheritedLegacyIdentityKey = null
        }
    }

    suspend fun loadExistingSubject(link: EffectiveBangumiLink) {
        val subject = runSuspendCatching { service.getSubject(link.subjectId) }.getOrNull()
            ?: BangumiCandidate(
                subjectId = link.subjectId,
                title = "Bangumi #${link.subjectId}",
                sources = setOf(BangumiCandidateSource.ID_LOOKUP),
            )
        candidates = mergeBangumiCandidates(candidates + subject)
        selectedId = link.subjectId
    }

    suspend fun runAutomaticDiscovery() {
        loading = true
        message = null
        val discovery = withContext(Dispatchers.Default) {
            runSuspendCatching {
                service.discover(
                    tmdbId = show.tmdb_id,
                    seasonNumber = season.season_number,
                    title = show.title,
                    originalTitle = show.original_title,
                    releaseDate = season.release_date,
                )
            }
        }.getOrElse {
            message = "自动匹配失败，可手动搜索"
            loading = false
            return
        }
        candidates = discovery.candidates
        selectedId = discovery.autoVerified?.subjectId
            ?: discovery.candidates.firstOrNull { it.seasonExact }?.subjectId
            ?: discovery.candidates.firstOrNull()?.subjectId
        when {
            discovery.autoVerified != null -> {
                val now = platformTimeMillis()
                val link = BangumiSeasonLink(
                    identityKey = identityKey,
                    subjectId = discovery.autoVerified.subjectId,
                    state = BangumiLinkState.CONFIRMED,
                    source = BangumiLinkSource.EXT_LINKER,
                    evidence = "BangumiExtLinker;Bangumi API;release-month",
                    updatedAt = now,
                    verifiedAt = now,
                )
                runSuspendCatching { repository.upsertBangumiSeasonLink(link) }
                    .onSuccess {
                        inheritedLegacyIdentityKey?.let { legacyKey ->
                            runSuspendCatching { repository.clearBangumiSeasonLink(legacyKey) }
                            inheritedLegacyIdentityKey = null
                        }
                        persisted = link
                        effective = resolveEffectiveBangumiLink(link, season.bangumi_id)
                        onChanged(effective)
                        message = "已自动关联并完成季度校验"
                    }
                    .onFailure { message = "自动关联结果保存失败，请手动确认后重试" }
            }
            discovery.conflict -> {
                val now = platformTimeMillis()
                val link = BangumiSeasonLink(
                    identityKey = identityKey,
                    subjectId = null,
                    state = BangumiLinkState.CONFLICT,
                    source = BangumiLinkSource.AUTO,
                    evidence = "multiple-season-mappings",
                    updatedAt = now,
                    verifiedAt = null,
                )
                runSuspendCatching { repository.upsertBangumiSeasonLink(link) }
                    .onSuccess {
                        inheritedLegacyIdentityKey?.let { legacyKey ->
                            runSuspendCatching { repository.clearBangumiSeasonLink(legacyKey) }
                            inheritedLegacyIdentityKey = null
                        }
                        persisted = link
                        effective = null
                        message = "检测到多个季度候选，请手动确认"
                    }
                    .onFailure { message = "检测到候选冲突，但状态保存失败，请手动确认" }
            }
            discovery.candidates.isEmpty() && discovery.hadNetworkFailure ->
                message = "自动匹配暂不可用，可手动搜索"
            discovery.candidates.isEmpty() -> message = "没有自动匹配结果，可手动搜索"
            discovery.hadNetworkFailure -> message = "已返回部分候选，部分来源暂不可用"
            else -> message = "已找到候选，请确认后建立关联"
        }
        loading = false
    }

    fun search() {
        val keyword = cleanAnimeSearchKeyword(queryState.text.toString()).trim()
        if (keyword.isEmpty() || searching) return
        scope.launch {
            searching = true
            message = null
            val result = withContext(Dispatchers.Default) {
                runSuspendCatching { service.search(keyword) }
            }
            result.onSuccess { found ->
                candidates = mergeBangumiCandidates(candidates + found)
                selectedId = found.firstOrNull()?.subjectId ?: selectedId
                if (found.isEmpty()) message = "没有找到匹配的动画条目"
            }.onFailure {
                message = "搜索失败，请稍后重试"
            }
            searching = false
        }
    }

    fun retryAutomatic() {
        scope.launch {
            saving = true
            val cleared = runSuspendCatching { clearStoredLinkOverrides() }
            saving = false
            cleared.onSuccess {
                persisted = null
                effective = null
                candidates = emptyList()
                selectedId = null
                runAutomaticDiscovery()
            }.onFailure {
                message = "重新匹配失败，请稍后重试"
            }
        }
    }

    fun confirm() {
        val subjectId = selectedId ?: return
        scope.launch {
            saving = true
            val now = platformTimeMillis()
            val link = BangumiSeasonLink(
                identityKey = identityKey,
                subjectId = subjectId,
                state = BangumiLinkState.CONFIRMED,
                source = BangumiLinkSource.MANUAL,
                evidence = "user-confirmed",
                updatedAt = now,
                verifiedAt = now,
            )
            val saved = runSuspendCatching { repository.upsertBangumiSeasonLink(link) }
            saving = false
            saved.onSuccess {
                inheritedLegacyIdentityKey?.let { legacyKey ->
                    runSuspendCatching { repository.clearBangumiSeasonLink(legacyKey) }
                    inheritedLegacyIdentityKey = null
                }
                onChanged(resolveEffectiveBangumiLink(link, season.bangumi_id))
                onDismiss()
            }.onFailure {
                message = "保存失败，请重试"
            }
        }
    }

    fun disable() {
        scope.launch {
            saving = true
            val now = platformTimeMillis()
            val link = BangumiSeasonLink(
                identityKey = identityKey,
                subjectId = null,
                state = BangumiLinkState.DISABLED,
                source = BangumiLinkSource.MANUAL,
                evidence = "user-disabled",
                updatedAt = now,
                verifiedAt = now,
            )
            val saved = runSuspendCatching { repository.upsertBangumiSeasonLink(link) }
            saving = false
            saved.onSuccess {
                inheritedLegacyIdentityKey?.let { legacyKey ->
                    runSuspendCatching { repository.clearBangumiSeasonLink(legacyKey) }
                    inheritedLegacyIdentityKey = null
                }
                onChanged(null)
                onDismiss()
            }.onFailure {
                message = "保存失败，请重试"
            }
        }
    }

    fun restoreScanned() {
        scope.launch {
            saving = true
            val cleared = runSuspendCatching { clearStoredLinkOverrides() }
            saving = false
            cleared.onSuccess {
                val restored = resolveEffectiveBangumiLink(null, season.bangumi_id)
                onChanged(restored)
                onDismiss()
            }.onFailure {
                message = "恢复失败，请重试"
            }
        }
    }

    LaunchedEffect(identityKey) {
        loading = true
        val existing = runSuspendCatching { repository.getStoredBangumiSeasonLink(show, season) }
        if (existing.isFailure) {
            message = "读取已有关联失败，请关闭后重试"
            loading = false
            return@LaunchedEffect
        }
        val stored = existing.getOrNull()
        persisted = stored?.link
        inheritedLegacyIdentityKey = stored?.inheritedLegacyIdentityKey
        effective = resolveEffectiveBangumiLink(persisted, season.bangumi_id)
        val current = effective
        when {
            current != null -> {
                loadExistingSubject(current)
                loading = false
            }
            persisted?.state == BangumiLinkState.DISABLED -> {
                message = "已停止自动匹配，可搜索后重新关联"
                loading = false
            }
            persisted?.state == BangumiLinkState.CONFLICT -> {
                message = "上次自动匹配存在候选冲突，请手动确认或重新检测"
                loading = false
            }
            else -> runAutomaticDiscovery()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .widthIn(max = 640.dp)
                .heightIn(min = 360.dp, max = 600.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Bangumi 关联 · 第${season.season_number}季",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                effective?.let { link ->
                    Text(
                        text = "当前 #${link.subjectId} · ${link.source.label()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // Ani-RSS 集数漂移手动修正(bangumi.ini 的 offset; 扫描时读入库)。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        state = offsetState,
                        label = { Text("集数漂移（当前 ${season.bangumi_offset}）") },
                        placeholder = { Text("${season.bangumi_offset}") },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(4.dp))
                    TextButton(
                        onClick = { saveOffset() },
                        enabled = offsetDirty && !savingOffset && parsedOffset != null,
                    ) {
                        if (savingOffset) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("保存漂移")
                        }
                    }
                }
                Text(
                    text = "分段番剧用：本地集号 - 漂移 = 全系列集号（如第二季 E1 对应全系列 E12 填 -11）。保存后弹幕与评论按新值匹配；重新扫描会按 bangumi.ini 恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        state = queryState,
                        label = { Text("标题或 Bangumi ID") },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        scrollState = rememberScrollState(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        onKeyboardAction = { search() },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { search() }, enabled = !searching && queryState.text.isNotBlank()) {
                        if (searching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                    }
                }
                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (persisted?.state == BangumiLinkState.DISABLED || persisted?.state == BangumiLinkState.CONFLICT) {
                    TextButton(onClick = { retryAutomatic() }, enabled = !loading && !saving) {
                        Text(if (persisted?.state == BangumiLinkState.CONFLICT) "重新检测候选" else "重新自动匹配")
                    }
                }
                if (season.bangumi_id != null && persisted != null) {
                    TextButton(onClick = { restoreScanned() }, enabled = !saving) {
                        Text("恢复 bangumi.ini 关联")
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(candidates, key = { it.subjectId }) { candidate ->
                                BangumiCandidateRow(
                                    candidate = candidate,
                                    selected = selectedId == candidate.subjectId,
                                    onClick = { selectedId = candidate.subjectId },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row {
                        if (persisted != null || effective != null) {
                            TextButton(onClick = { disable() }, enabled = !saving) {
                                Text("解除并停止自动匹配")
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
                        Spacer(Modifier.size(4.dp))
                        Button(onClick = { confirm() }, enabled = selectedId != null && !saving) {
                            if (saving) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("确认")
                            }
                        }
                    }
                }
                Text(
                    text = "季度映射来源：BangumiExtLinker（CC BY 4.0）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun BangumiCandidateRow(
    candidate: BangumiCandidate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                text = candidate.title.ifBlank { "Bangumi #${candidate.subjectId}" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val metadata = buildList {
                add("#${candidate.subjectId}")
                candidate.date?.let(::add)
                candidate.episodeCount?.takeIf { it > 0 }?.let { add("${it}集") }
                if (candidate.seasonExact) add("季度精确匹配")
                else if (BangumiCandidateSource.EXT_LINKER in candidate.sources) add("系列候选")
                else add("标题候选")
            }
            Text(
                text = metadata.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun EffectiveBangumiLinkSource.label(): String = when (this) {
    EffectiveBangumiLinkSource.MANUAL -> "手动确认"
    EffectiveBangumiLinkSource.SCANNED -> "bangumi.ini"
    EffectiveBangumiLinkSource.AUTO_VERIFIED -> "自动校验"
}
