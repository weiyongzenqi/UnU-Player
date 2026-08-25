package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.github.weiyongzenqi.unuplayer.bangumi.bangumiImageModel
import io.github.weiyongzenqi.unuplayer.bangumi.resolveImageUrl
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.domain.SettingsState
import io.github.weiyongzenqi.unuplayer.domain.bangumiEndpoints
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleEntry
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleRepository
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleStatus
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

@Stable
internal class OnlineAnimeSearchState {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<ScheduleEntry>>(emptyList())
    var searching by mutableStateOf(false)
    var searched by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
internal fun rememberOnlineAnimeSearchState(): OnlineAnimeSearchState = remember { OnlineAnimeSearchState() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnlineAnimeSearchDialog(
    repository: ScheduleRepository,
    settings: SettingsState,
    state: OnlineAnimeSearchState,
    history: List<String>,
    onHistoryChange: (List<String>) -> Unit,
    selectionBusy: Boolean,
    selectionError: String?,
    onDismiss: () -> Unit,
    onSelect: (ScheduleEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val endpoints = settings.bangumiEndpoints()

    fun search(requestedQuery: String = state.query) {
        val keyword = requestedQuery.trim()
        if (keyword.isEmpty() || state.searching || selectionBusy) return
        state.query = keyword
        state.results = emptyList()
        state.searched = true
        state.error = null
        state.searching = true
        focusManager.clearFocus()
        keyboardController?.hide()
        onHistoryChange(io.github.weiyongzenqi.unuplayer.schedule.updateScheduleSearchHistory(history, keyword))
        scope.launch {
            try {
                runSuspendCatching { repository.searchAnime(keyword) }
                    .onSuccess { state.results = it }
                    .onFailure { state.error = it.message ?: "番剧搜索失败，请稍后重试" }
            } finally {
                state.searching = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!state.searching && !selectionBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = !state.searching && !selectionBusy) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭搜索")
                        }
                    },
                    title = { Text("搜索番剧") },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pointerInput(focusManager, keyboardController) {
                        awaitEachGesture {
                            val down = awaitFirstDown(pass = PointerEventPass.Final)
                            val consumedByChild = down.isConsumed
                            val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
                            if (!consumedByChild && up != null && !up.isConsumed) {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }
                        }
                    },
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { state.query = it.take(120) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    singleLine = true,
                    enabled = !selectionBusy,
                    label = { Text("名称或 Bangumi ID") },
                    placeholder = { Text("搜索不局限于本周时间表") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { search() }, enabled = state.query.isNotBlank() && !state.searching && !selectionBusy) {
                            if (state.searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Filled.Search, contentDescription = "开始搜索")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
                Text(
                    "结果来自 Bangumi；选择后会继续核对 TMDB、媒体库关联和观看状态。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (selectionError ?: state.error)?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                when {
                    selectionBusy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator()
                            Text("正在核对番剧详情…")
                        }
                    }
                    state.searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator()
                            Text("正在搜索番剧…")
                        }
                    }
                    state.searched && !state.searching && state.error == null && state.results.isEmpty() ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("没有找到匹配的动画条目", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (!state.searched && history.isNotEmpty()) {
                            item(key = "search-history-header") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "最近搜索",
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        TextButton(onClick = { onHistoryChange(emptyList()) }) { Text("清空") }
                                    }
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        history.take(12).forEach { keyword ->
                                            InputChip(
                                                selected = false,
                                                enabled = !state.searching && !selectionBusy,
                                                onClick = { search(keyword) },
                                                label = {
                                                    Text(
                                                        keyword,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Filled.Search,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                },
                                                trailingIcon = {
                                                    Icon(
                                                        Icons.Filled.Clear,
                                                        contentDescription = "删除搜索记录",
                                                        modifier = Modifier
                                                            .size(18.dp)
                                                            .clickable {
                                                                onHistoryChange(history.filterNot { it == keyword })
                                                            },
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (state.results.isNotEmpty()) {
                            item(key = "search-results-header") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("搜索结果", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${state.results.size} 条",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        items(state.results, key = { it.subjectId }) { entry ->
                            val posterUrl = endpoints.resolveImageUrl(entry.posterUrl)
                            val posterModel = remember(posterUrl) {
                                posterUrl?.let { url -> bangumiImageModel(context, url) }
                            }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.searching && !selectionBusy) { onSelect(entry) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier
                                            .size(68.dp, 96.dp)
                                            .clip(MaterialTheme.shapes.medium)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(entry.title.firstOrNull()?.toString().orEmpty())
                                        posterModel?.let {
                                            AsyncImage(
                                                model = it,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        entry.originalTitle?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            listOfNotNull(
                                                "Bangumi #${entry.subjectId}",
                                                entry.airDate?.takeIf(String::isNotBlank),
                                                entry.rating?.let { "%.1f 分".format(it) },
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (entry.status != ScheduleStatus.NONE) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                shape = MaterialTheme.shapes.small,
                                            ) {
                                                Text(
                                                    entry.status.label,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
