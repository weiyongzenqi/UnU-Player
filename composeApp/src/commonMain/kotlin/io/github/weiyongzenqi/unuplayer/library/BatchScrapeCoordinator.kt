package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 可由批量协调器驱动的在线刮削器。 */
interface BatchScraper {
    suspend fun scrapePendingInCoordinator(
        library: LibraryConfig,
        anchorOnly: Boolean,
        concurrency: Int,
        hashProvider: (suspend (String) -> Pair<Long, String>?)?,
        onProgress: suspend (done: Int, total: Int, currentTitle: String) -> Unit,
    ): Int
}

/** 批量补刮触发来源，用于完成提示区分用户操作和扫描后自动任务。 */
enum class BatchScrapeReason { MANUAL, AFTER_SCAN }

/**
 * 进程级批量补刮协调器。
 *
 * 批量任务、进度和媒体源所有权独立于海报墙页面；切换 tab、进入详情页或 Activity 重建都不会
 * 取消任务。内部 [MediaSourceCache] 只服务批量 hash，页面销毁关闭自己的缓存时不会中断后台任务。
 */
class BatchScrapeCoordinator(
    mediaSourceFactory: MediaSourceFactory,
) {
    data class State(
        val isRunning: Boolean = false,
        val isStopping: Boolean = false,
        val libraryId: Long? = null,
        val libraryName: String = "",
        val completed: Int = 0,
        val total: Int = 0,
        val currentTitle: String = "",
        val successful: Int = 0,
        val status: String = "",
        val reason: BatchScrapeReason = BatchScrapeReason.MANUAL,
        val runId: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mediaSourceCache = MediaSourceCache(mediaSourceFactory)

    @Volatile private var scrapeJob: Job? = null
    @Volatile private var stopRequested = false

    fun start(
        library: LibraryConfig,
        scraper: BatchScraper,
        anchorOnly: Boolean,
        concurrency: Int,
        reason: BatchScrapeReason,
    ): Boolean {
        if (scrapeJob?.isActive == true) return false
        stopRequested = false
        val runId = _state.value.runId + 1
        _state.value = State(
            isRunning = true,
            libraryId = library.id,
            libraryName = library.name,
            status = "正在准备批量补刮...",
            reason = reason,
            runId = runId,
        )
        scrapeJob = scope.launch {
            runBatch(library, scraper, anchorOnly, concurrency, runId)
        }
        return true
    }

    fun stop() {
        if (scrapeJob?.isActive != true && !_state.value.isRunning) return
        stopRequested = true
        _state.update { current ->
            if (current.isRunning) {
                current.copy(isStopping = true, status = "正在停止批量补刮...")
            } else {
                current
            }
        }
        scrapeJob?.cancel()
    }

    suspend fun close() {
        stopRequested = true
        scrapeJob?.cancelAndJoin()
        mediaSourceCache.close()
        scope.cancel()
    }

    private suspend fun runBatch(
        library: LibraryConfig,
        scraper: BatchScraper,
        anchorOnly: Boolean,
        concurrency: Int,
        runId: Long,
    ) {
        var cancelledByCaller = false
        try {
            val successful = scraper.scrapePendingInCoordinator(
                library = library,
                anchorOnly = anchorOnly,
                concurrency = concurrency,
                hashProvider = ScrapeFactory.buildHashProvider(library, mediaSourceCache),
                onProgress = { completed, total, currentTitle ->
                    _state.update { current ->
                        if (current.runId != runId) current else current.copy(
                            completed = completed,
                            total = total,
                            currentTitle = currentTitle,
                            status = if (total > 0) "正在批量补刮" else "正在检查缺失元数据...",
                        )
                    }
                },
            )
            _state.update { current ->
                if (current.runId != runId) current else current.copy(
                    successful = successful,
                    status = if (current.total == 0) {
                        "没有需要补刮的番剧"
                    } else {
                        "批量补刮完成：成功 $successful/${current.total} 部"
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            cancelledByCaller = true
            throw cancelled
        } catch (error: Throwable) {
            val detail = error.message
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(180)
                ?.takeIf(String::isNotEmpty)
            _state.update { current ->
                if (current.runId != runId) current else current.copy(
                    status = detail?.let { "批量补刮失败：$it" } ?: "批量补刮失败",
                )
            }
        } finally {
            if (scrapeJob == currentCoroutineContext()[Job]) {
                scrapeJob = null
            }
            _state.update { current ->
                if (current.runId != runId) {
                    current
                } else {
                    current.copy(
                        isRunning = false,
                        isStopping = false,
                        status = if (stopRequested || cancelledByCaller) {
                            "已停止批量补刮：完成 ${current.completed}/${current.total}"
                        } else {
                            current.status
                        },
                    )
                }
            }
        }
    }
}
