package io.github.weiyongzenqi.unuplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayAnimeSummary
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayApi
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayEpisode
import io.github.weiyongzenqi.unuplayer.ui.AppBackHandler

/**
 * 手动匹配弹幕的选择结果(对话框 -> 调用方)。
 *
 * 调用方拿到后用 [episodeId] 拉 comment 弹幕(复用 [io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplaySourceProvider.fetch])。
 */
data class ManualMatchSelection(
    val episodeId: Long,
    val animeId: Long,
    val animeTitle: String,
    val episodeTitle: String,
)

/**
 * 手动匹配弹幕对话框(两步状态机: 搜番 -> 选集)。
 *
 * 参考 NipaPlay `ManualDanmakuMatchDialog`: 单对话框内 [step] 切换搜索/选集视图,
 * 预填 [initialKeyword](文件名清洗后), 默认第一集兜底, BackHandler 选集页回搜索。
 * 接口只走 [DandanplayApi] 一处(searchAnime + bangumi), 不重复 HTTP 调用(NipaPlay 三处重复的改进)。
 *
 * @param api 已注入凭证的弹弹play API
 * @param initialKeyword 预填搜索词(文件名清洗后的番剧标题主体)
 * @param onConfirm 用户确认选集后回调(选了番但没选集 -> 默认第一集)
 * @param onDismiss 取消(返回键 / 点外部 / 关闭)
 */
@Composable
fun ManualMatchDialog(
    api: DandanplayApi,
    initialKeyword: String,
    onConfirm: (ManualMatchSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 1 = 搜索番剧, 2 = 选集
    var step by remember { mutableStateOf(1) }
    var keyword by remember { mutableStateOf(initialKeyword) }
    var searchResults by remember { mutableStateOf<List<DandanplayAnimeSummary>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var selectedAnime by remember { mutableStateOf<DandanplayAnimeSummary?>(null) }
    var episodes by remember { mutableStateOf<List<DandanplayEpisode>>(emptyList()) }
    var selectedEpisode by remember { mutableStateOf<DandanplayEpisode?>(null) }
    var loadingEps by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    /** 搜索番剧: keyword -> searchAnime。 */
    fun doSearch() {
        val kw = keyword.trim()
        if (kw.isBlank()) return
        scope.launch {
            searching = true
            error = null
            val result = withContext(Dispatchers.IO) {
                runSuspendCatching { api.searchAnime(kw).animes }
            }
            result.onSuccess {
                searchResults = it
                if (it.isEmpty()) error = "未找到匹配的番剧"
            }.onFailure {
                error = "搜索失败: ${it.message ?: "未知错误"}"
            }
            searching = false
        }
    }

    /** 选中番剧 -> bangumi 拿剧集列表 -> 进选集页。 */
    fun selectAnime(anime: DandanplayAnimeSummary) {
        scope.launch {
            loadingEps = true
            error = null
            val result = withContext(Dispatchers.IO) {
                runSuspendCatching { api.bangumi(anime.animeId).bangumi?.episodes.orEmpty() }
            }
            result.onSuccess { eps ->
                episodes = eps
                selectedAnime = anime
                selectedEpisode = null
                step = 2
                if (eps.isEmpty()) error = "该剧集列表为空"
            }.onFailure {
                error = "获取剧集失败: ${it.message ?: "未知错误"}"
            }
            loadingEps = false
        }
    }

    /** 确认: 选了集用选中集, 否则默认第一集。 */
    fun confirm() {
        val anime = selectedAnime ?: return
        val ep = selectedEpisode ?: episodes.firstOrNull() ?: return
        onConfirm(ManualMatchSelection(ep.episodeId, anime.animeId, anime.animeTitle, ep.episodeTitle))
    }

    /** 返回搜索页(清剧集状态)。 */
    fun backToSearch() {
        step = 1
        selectedAnime = null
        episodes = emptyList()
        selectedEpisode = null
        error = null
    }

    // 首次打开: initialKeyword 非空时自动搜一次, 给即时结果(省一步)
    LaunchedEffect(Unit) {
        if (initialKeyword.isNotBlank()) doSearch()
    }

    // 选集页返回键 -> 回搜索; 搜索页返回键 -> onDismiss(Dialog 的 onDismissRequest 兜底)
    AppBackHandler(enabled = step == 2) { backToSearch() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = 320.dp, max = 520.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    if (step == 1) "手动匹配弹幕" else "选择剧集",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.size(12.dp))

                if (step == 1) {
                    // === 搜索页 ===
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            label = { Text("番剧名称") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = { doSearch() }, enabled = !searching) {
                            Text("搜索")
                        }
                    }
                    error?.let {
                        Spacer(Modifier.size(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.size(8.dp))
                    if (searching) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(searchResults) { anime ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectAnime(anime) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            anime.animeTitle,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        anime.typeDescription?.takeIf { it.isNotBlank() }?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // === 选集页 ===
                    selectedAnime?.let {
                        Text(
                            "已选: ${it.animeTitle}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.size(8.dp))
                    }
                    if (loadingEps) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(episodes) { ep ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedEpisode = ep }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = selectedEpisode?.episodeId == ep.episodeId,
                                        onClick = { selectedEpisode = ep },
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(episodeLabel(ep))
                                }
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(onClick = { backToSearch() }) { Text("返回搜索") }
                            Button(onClick = { confirm() }) {
                                Text(
                                    when {
                                        selectedEpisode != null -> "确认匹配"
                                        episodes.isNotEmpty() -> "使用第一集"
                                        else -> "确认"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 剧集标签: "第N话 标题"(无 episodeNumber 则只显示标题)。episodeNumber 是字符串(弹弹 bangumi)。 */
private fun episodeLabel(ep: DandanplayEpisode): String {
    val num = ep.episodeNumber?.takeIf { it.isNotBlank() }
    val title = ep.episodeTitle
    return when {
        num != null && title.isNotBlank() -> "第${num}话  $title"
        num != null -> "第${num}话"
        title.isNotBlank() -> title
        else -> "episodeId=${ep.episodeId}"
    }
}
