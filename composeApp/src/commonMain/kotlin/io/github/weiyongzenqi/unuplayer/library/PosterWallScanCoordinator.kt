package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.domain.SettingsState

/**
 * 海报墙扫描协调器(commonMain, 进程级单例语义)。
 *
 * **为何独立出协调器**: 海报墙页(AnimeScreen)在底部 tab 的 `SaveableStateHolder` 内,
 * 切走 tab 时整个 AnimeScreen 被 dispose, `rememberCoroutineScope` 的 scope 取消 ->
 * 扫描 job 被连带取消(扫描中断, 非仅状态丢失)。故把扫描 job + 状态提到进程级 scope,
 * 与 composable 生命周期解耦: 切 tab / 进详情 / Activity 不重建都不影响进行中的扫描。
 *
 * **阻塞重复触发**: [startScan]/[rescanCurrent] 开头检查 `scanJob?.isActive`, 进行中直接返回,
 * 满足"扫描中不能再次触发"的需求。
 *
 * **source 生命周期**: 协调器内部用 [mediaSourceFactory] 自建扫描用 [MediaSource],
 * 扫描结束 finally close。与 AnimeScreen/AnimeDetailScreen 图片加载用的 source 互不干扰
 * (一个 LibraryConfig 可对应多个 MediaSource 实例, WebDAV/本地 source 无连接池冲突)。
 *
 * **流式加载**: [ScanState.foundShows] 随扫描进度递增, UI 监听其变化调 listShows 刷新,
 * 实现扫描中番剧陆续出现(配合 LazyVerticalGrid 的 animateItem 丝滑滑入)。
 *
 * **单番剧刷新**: 详情页刷新不走本协调器(详情页自包含 source, 单番剧扫描快, 用户在场无需跨页面保持),
 * 直接 new [ScrapedLibraryScanner].scanOneShow。
 */
class PosterWallScanCoordinator(
    private val scrapedRepo: ScrapedLibraryRepository,
    private val mediaSourceFactory: MediaSourceFactory,
) {
    /** 扫描运行时状态(可观察)。foundShows/foundEpisodes 用于 UI 渐进刷新。 */
    data class ScanState(
        val isScanning: Boolean = false,
        val status: String = "",
        val libraryId: Long? = null,
        val foundShows: Int = 0,
        val foundEpisodes: Int = 0,
    )

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    /** 进程级 scope: 切 tab/进详情不取消。SupervisorJob 保证单次扫描失败不波及 scope。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // @Volatile: scanJob 在调用线程写(startScan/rescanCurrent)、跨线程读(stopScan/close 的 isActive/cancel), 保证可见性(P3⑰)
    @Volatile private var scanJob: Job? = null
    @Volatile private var stopRequested = false

    /** 当前是否正在扫描指定库(切 tab 回来据此显示"扫描中" + 禁用按钮)。 */
    fun isScanningLibrary(libraryId: Long): Boolean =
        _state.value.isScanning && _state.value.libraryId == libraryId

    /** 全盘扫描(从 library.rootPath 递归)。force=true 强制刷新已记录番剧。进行中则忽略。 */
    fun startScan(library: LibraryConfig, settings: SettingsState, force: Boolean) {
        if (scanJob?.isActive == true) return  // 阻塞重复触发
        scanJob = scope.launch { runScan(library, settings, force = force, rescanCurrent = false) }
    }

    /** 增量重扫当前目录(只扫 rootPath 下未记录子目录)。进行中则忽略。 */
    fun rescanCurrent(library: LibraryConfig, settings: SettingsState) {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch { runScan(library, settings, force = false, rescanCurrent = true) }
    }

    /** 停止扫描: 置停止标志 + cancel job。 */
    fun stopScan() {
        stopRequested = true
        scanJob?.cancel()
        _state.update { it.copy(isScanning = false, status = "已停止") }
    }

    /** 平台应用退出时调用，停止后台扫描并释放进程级 scope。 */
    fun close() {
        stopRequested = true
        scanJob?.cancel()
        scope.cancel()
        _state.update { it.copy(isScanning = false, status = "已停止") }
    }

    private suspend fun runScan(
        library: LibraryConfig, settings: SettingsState, force: Boolean, rescanCurrent: Boolean,
    ) {
        val sourceResult = runSuspendCatching { mediaSourceFactory.create(library) }
        val sourceError = sourceResult.exceptionOrNull()
        val src = sourceResult.getOrNull()
        if (src == null) {
            val detail = sourceError?.message
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(180)
                ?.takeIf { it.isNotEmpty() }
            _state.value = ScanState(
                false,
                if (detail == null) "无法创建数据源：连接或目录已不存在" else "无法创建数据源：$detail",
                library.id,
                0,
                0,
            )
            return
        }
        try {
            _state.value = ScanState(
                isScanning = true,
                status = if (rescanCurrent) "重扫当前目录..." else if (force) "强制重扫中..." else "扫描中...",
                libraryId = library.id,
            )
            stopRequested = false
            val config = ScanConfig(
                requestIntervalMs = settings.posterWallScanRequestIntervalMs,
                concurrency = settings.posterWallScanConcurrency,
                depth = settings.posterWallScanDepth,
                timeoutSeconds = settings.posterWallScanTimeoutSeconds,
            )
            val scanner = ScrapedLibraryScanner(src, library, scrapedRepo, config)
            val result = if (rescanCurrent) {
                scanner.rescanDir(
                    dirPath = library.rootPath,
                    onProgress = ::onProgress,
                    onStopRequested = { stopRequested },
                )
            } else {
                scanner.scan(
                    force = force,
                    onProgress = ::onProgress,
                    onStopRequested = { stopRequested },
                )
            }
            val interrupted = result.stopped || result.timedOut
            val hasErrors = result.errors > 0
            val outcome = when {
                result.timedOut -> "扫描超时"
                result.stopped -> "已停止"
                hasErrors && result.foundShows > 0 -> "部分完成"
                hasErrors -> "扫描失败"
                else -> "完成"
            }
            _state.value = ScanState(
                isScanning = false,
                status = outcome + ": 番剧 ${result.foundShows}, 剧集 ${result.foundEpisodes}" +
                    (if (result.skippedShows > 0) ", 跳过 ${result.skippedShows}" else "") +
                    (if (hasErrors) ", 错误 ${result.errors}" else "") +
                    (result.firstErrorMessage?.let { "；$it" } ?: ""),
                libraryId = library.id,
                foundShows = result.foundShows,
                foundEpisodes = result.foundEpisodes,
            )
            if (!interrupted && !hasErrors) {
                runSuspendCatching { scrapedRepo.setLibraryScanned(library.id, platformTimeMillis()) }
            }
            if (settings.posterWallWalAutoCheckpoint) {
                runSuspendCatching { scrapedRepo.checkpointTruncate() }
            }
        } finally {
            runCatching { src.close() }
        }
    }

    private fun onProgress(scanned: Int, foundShows: Int, foundEpisodes: Int) {
        _state.update {
            it.copy(
                status = "已扫 $scanned 目录, 番剧 $foundShows, 剧集 $foundEpisodes",
                foundShows = foundShows,
                foundEpisodes = foundEpisodes,
            )
        }
    }
}
