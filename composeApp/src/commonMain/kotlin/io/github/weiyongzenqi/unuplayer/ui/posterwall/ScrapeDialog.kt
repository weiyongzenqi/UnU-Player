package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.weiyongzenqi.unuplayer.library.AnimeScraper
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.ScrapeCandidate
import io.github.weiyongzenqi.unuplayer.library.ScrapeSource
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private data class SourceSearchState(
    val candidates: List<ScrapeCandidate> = emptyList(),
    val searching: Boolean = false,
    val hasSearched: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

data class ScrapeSeasonTarget(
    val seasonId: Long,
    val seasonNumber: Int,
    val showPath: String,
    val label: String,
)

internal val defaultScrapeDialogSource = ScrapeSource.BANGUMI

/**
 * 在线刮削手动弹窗: 选择数据源(Bangumi / 弹弹 / TMDB) -> 搜索候选(预填番剧名) -> 确认应用。
 *
 * 弹弹/Bangumi 应用写 MANUAL_* 覆盖语义；TMDB 只指定身份并补头图/集照，不覆盖已有文本。
 * 详情页"刮削/纠正"入口使用; 候选展示含 标题/原名/年份/类型/季数, 不含缩略图(避免弹窗内并发图片下载)。
 */
@Composable
fun ScrapeDialog(
    showTitle: String,
    showPath: String,
    library: LibraryConfig,
    scraper: AnimeScraper,
    seasonTargets: List<ScrapeSeasonTarget> = emptyList(),
    initialSeasonId: Long? = null,
    initialSource: ScrapeSource = defaultScrapeDialogSource,
    autoSearchOnOpen: Boolean = false,
    isAutoTmdbFailurePrompt: Boolean = false,
    applicationScope: CoroutineScope,
    onPermanentlyDismissAutoTmdbPrompt: (suspend () -> Unit)? = null,
    onDismiss: () -> Unit,
    onSearchBusyChange: (Boolean) -> Unit = {},
    onApplyBusyChange: (Boolean) -> Unit = {},
    onApplicationProgress: (String?) -> Unit = {},
    onApplied: suspend (appliedShowPath: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val resolvedInitialSource = resolveScrapeDialogInitialSource(initialSource, scraper.hasTmdb)
    var source by remember(resolvedInitialSource) { mutableStateOf(resolvedInitialSource) }
    var searchStates by remember(showTitle) {
        mutableStateOf(
            ScrapeSource.entries.associateWith { SourceSearchState() },
        )
    }
    val keywordStates = remember(showTitle) {
        ScrapeSource.entries.associateWith { TextFieldState(showTitle) }
    }
    var applying by remember { mutableStateOf(false) }
    var tmdbIdentityApplied by remember { mutableStateOf(false) }
    var updatingPromptPreference by remember { mutableStateOf(false) }
    var selectedSeasonId by remember(seasonTargets, initialSeasonId) {
        mutableStateOf(resolveScrapeDialogInitialSeasonId(seasonTargets, initialSeasonId))
    }
    val selectedSeasonTarget = seasonTargets.firstOrNull { it.seasonId == selectedSeasonId }

    val sourceState = searchStates[source] ?: SourceSearchState()
    val searching = sourceState.searching
    val candidates = sourceState.candidates
    val message = sourceState.message
    val messageIsError = sourceState.messageIsError
    val anySearching = searchStates.values.any { it.searching }
    val busy = anySearching || applying || updatingPromptPreference
    val dismissBlocked = isScrapeDialogDismissBlocked(
        anySearching = anySearching,
        updatingPromptPreference = updatingPromptPreference,
        applying = applying,
        tmdbIdentityApplied = tmdbIdentityApplied,
    )

    fun updateSearchState(targetSource: ScrapeSource, transform: (SourceSearchState) -> SourceSearchState) {
        searchStates = searchStates.toMutableMap().apply {
            this[targetSource] = transform(this[targetSource] ?: SourceSearchState())
        }
    }

    suspend fun search(targetSource: ScrapeSource = source) {
        val targetState = searchStates[targetSource] ?: SourceSearchState()
        val keyword = keywordStates.getValue(targetSource).text.toString()
        if (applying || targetState.searching || keyword.isBlank()) return
        tmdbIdentityApplied = false
        updateSearchState(targetSource) {
            it.copy(searching = true, hasSearched = true, message = null, messageIsError = false)
        }
        try {
            val result = runSuspendCatching { scraper.searchCandidates(keyword, targetSource) }
            val resultCandidates = result.getOrDefault(emptyList())
            updateSearchState(targetSource) {
                it.copy(
                    candidates = resultCandidates,
                    message = when {
                        result.isFailure -> "搜索失败，请稍后重试"
                        resultCandidates.isEmpty() -> "未找到候选，请调整关键词或数据源"
                        else -> null
                    },
                    messageIsError = result.isFailure || resultCandidates.isEmpty(),
                )
            }
        } finally {
            updateSearchState(targetSource) { it.copy(searching = false) }
        }
    }

    LaunchedEffect(anySearching, updatingPromptPreference) {
        onSearchBusyChange(anySearching || updatingPromptPreference)
    }

    DisposableEffect(Unit) {
        onDispose { onSearchBusyChange(false) }
    }

    LaunchedEffect(autoSearchOnOpen, isAutoTmdbFailurePrompt, showTitle, scraper.hasTmdb) {
        if (autoSearchOnOpen) {
            val searchSources = if (isAutoTmdbFailurePrompt) {
                listOfNotNull(ScrapeSource.TMDB.takeIf { scraper.hasTmdb })
            } else {
                ScrapeSource.entries.filter { it != ScrapeSource.TMDB || scraper.hasTmdb }
            }
            searchSources
                .forEach { targetSource ->
                    if (!(searchStates[targetSource]?.hasSearched ?: false)) {
                        launch { search(targetSource) }
                    }
                }
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!dismissBlocked) onDismiss() },
        title = { Text("在线刮削 · ${showTitle.take(20)}") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    scrapeDialogSourceOrder(scraper.hasTmdb).forEach { scrapeSource ->
                        FilterChip(
                            selected = source == scrapeSource,
                            enabled = !applying,
                            onClick = {
                                source = scrapeSource
                                tmdbIdentityApplied = false
                            },
                            label = { Text(scrapeDialogSourceLabel(scrapeSource)) },
                        )
                    }
                }
                if (seasonTargets.size > 1 && source != ScrapeSource.TMDB) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        seasonTargets.forEach { target ->
                            FilterChip(
                                selected = selectedSeasonId == target.seasonId,
                                enabled = !applying,
                                onClick = { selectedSeasonId = target.seasonId },
                                label = { Text(target.label) },
                            )
                        }
                    }
                }
                key(source) {
                    val keywordState = keywordStates.getValue(source)
                    val keywordScrollState = rememberScrollState()
                    LaunchedEffect(keywordState) {
                        var previous = keywordState.text.toString()
                        snapshotFlow { keywordState.text.toString() }.collect { value ->
                            if (value != previous) {
                                previous = value
                                updateSearchState(source) {
                                    it.copy(hasSearched = false, message = null, messageIsError = false)
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        state = keywordState,
                        modifier = Modifier.fillMaxWidth(),
                        lineLimits = TextFieldLineLimits.SingleLine,
                        scrollState = keywordScrollState,
                        label = { Text("搜索关键词") },
                    )
                    Button(
                        onClick = { scope.launch { search() } },
                        enabled = !sourceState.searching && !applying && keywordState.text.isNotBlank(),
                    ) { Text(if (searching) "搜索中…" else "搜索") }
                }

                if (candidates.isNotEmpty()) {
                    Text(
                        if (source == ScrapeSource.TMDB) "请选择正确的 TMDB 作品" else "候选(应用后覆盖在线刮削结果)",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        itemsIndexed(
                            items = candidates,
                            key = { index, candidate ->
                                "${candidate.source}-${candidate.identityId}-${candidate.title}-${candidate.year}-$index"
                            },
                        ) { _, c ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    c.originalTitle?.takeIf { it.isNotBlank() }?.let { originalTitle ->
                                        Text(originalTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    }
                                    val meta = buildList {
                                        c.year?.let { add("$it 年") }
                                        c.typeDescription?.let { add(it) }
                                        c.episodeCount?.let { add("${it} 集") }
                                    }
                                    if (meta.isNotEmpty()) {
                                        Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        val applyingSource = source
                                        val applyingSeasonNumber = selectedSeasonTarget?.seasonNumber.takeUnless {
                                            applyingSource == ScrapeSource.TMDB
                                        }
                                        val applyingShowPath = selectedSeasonTarget?.showPath ?: showPath
                                        if (applying) return@TextButton
                                        applying = true
                                        tmdbIdentityApplied = false
                                        onApplyBusyChange(true)
                                        onApplicationProgress("正在应用在线刮削...")
                                        applicationScope.launch {
                                            updateSearchState(applyingSource) {
                                                it.copy(message = null, messageIsError = false)
                                            }
                                            try {
                                                val result = runSuspendCatching {
                                                    scraper.applyCandidate(
                                                        library = library,
                                                        showPath = applyingShowPath,
                                                        seasonNumber = applyingSeasonNumber,
                                                        candidate = c,
                                                        manual = true,
                                                        onProgress = { progress ->
                                                            val visibleProgress = if (tmdbIdentityApplied) {
                                                                "TMDB 已匹配，$progress，可关闭此窗口"
                                                            } else {
                                                                progress
                                                            }
                                                            onApplicationProgress(visibleProgress)
                                                            updateSearchState(applyingSource) {
                                                                it.copy(
                                                                    message = visibleProgress,
                                                                    messageIsError = false,
                                                                )
                                                            }
                                                        },
                                                        onTmdbIdentityApplied = {
                                                            tmdbIdentityApplied = true
                                                            onApplicationProgress("TMDB 已匹配，正在后台补全图片...")
                                                            updateSearchState(applyingSource) {
                                                                it.copy(
                                                                    message = "TMDB 已匹配，正在后台补全图片，可关闭此窗口",
                                                                    messageIsError = false,
                                                                )
                                                            }
                                                        },
                                                    )
                                                }
                                                if (result.getOrDefault(false)) {
                                                    updateSearchState(applyingSource) {
                                                        it.copy(
                                                            message = if (applyingSource == ScrapeSource.TMDB) {
                                                                "已指定 TMDB: ${c.title}"
                                                            } else {
                                                                "已应用: ${c.title}"
                                                            },
                                                            messageIsError = false,
                                                        )
                                                    }
                                                    onApplied(applyingShowPath)
                                                } else {
                                                    updateSearchState(applyingSource) {
                                                        it.copy(
                                                            message = if (result.isFailure) {
                                                                "应用失败，请稍后重试"
                                                            } else if (
                                                                selectedSeasonTarget == null &&
                                                                seasonTargets.size > 1 &&
                                                                applyingSource == ScrapeSource.BANGUMI
                                                            ) {
                                                                "Bangumi 多季番请先选择具体季度"
                                                            } else {
                                                                "应用失败(候选无法可靠映射到本地季度)"
                                                            },
                                                            messageIsError = true,
                                                        )
                                                    }
                                                }
                                            } finally {
                                                applying = false
                                                onApplicationProgress(null)
                                                onApplyBusyChange(false)
                                            }
                                        }
                                    },
                                    enabled = !searching && !applying,
                                ) { Text(if (applying) "应用中…" else "应用") }
                            }
                        }
                    }
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                if (searching) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !dismissBlocked) {
                Text(
                    when {
                        applying && tmdbIdentityApplied -> "关闭（后台继续）"
                        isAutoTmdbFailurePrompt -> "关闭一次"
                        else -> "关闭"
                    },
                )
            }
        },
        dismissButton = {
            if (isAutoTmdbFailurePrompt && onPermanentlyDismissAutoTmdbPrompt != null) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            updatingPromptPreference = true
                            val result = runSuspendCatching { onPermanentlyDismissAutoTmdbPrompt() }
                            updatingPromptPreference = false
                            if (result.isSuccess) {
                                onDismiss()
                            } else {
                                updateSearchState(source) {
                                    it.copy(message = "永久关闭失败，请稍后重试", messageIsError = true)
                                }
                            }
                        }
                    },
                ) { Text(if (updatingPromptPreference) "处理中…" else "永久关闭") }
            }
        },
    )
}

internal fun scrapeDialogSourceOrder(hasTmdb: Boolean): List<ScrapeSource> = buildList {
    add(ScrapeSource.BANGUMI)
    add(ScrapeSource.DANDANPLAY)
    if (hasTmdb) add(ScrapeSource.TMDB)
}

internal fun resolveScrapeDialogInitialSource(initialSource: ScrapeSource, hasTmdb: Boolean): ScrapeSource =
    initialSource.takeUnless { it == ScrapeSource.TMDB && !hasTmdb } ?: defaultScrapeDialogSource

internal fun resolveScrapeDialogInitialSeasonId(
    seasonTargets: List<ScrapeSeasonTarget>,
    initialSeasonId: Long?,
): Long? = initialSeasonId
    ?.takeIf { id -> seasonTargets.any { it.seasonId == id } }
    ?: seasonTargets.firstOrNull()?.seasonId

internal fun isScrapeDialogDismissBlocked(
    anySearching: Boolean,
    updatingPromptPreference: Boolean,
    applying: Boolean,
    tmdbIdentityApplied: Boolean,
): Boolean = updatingPromptPreference || (!tmdbIdentityApplied && (anySearching || applying))

private fun scrapeDialogSourceLabel(source: ScrapeSource): String = when (source) {
    ScrapeSource.BANGUMI -> "Bangumi"
    ScrapeSource.DANDANPLAY -> "弹弹play"
    ScrapeSource.TMDB -> "TMDB"
    else -> error("不支持的在线刮削来源：$source")
}
