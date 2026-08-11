package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.platform.platformFileExists
import io.github.weiyongzenqi.unuplayer.core.platform.platformFileLength

/**
 * 集照懒加载协调器: 详情页加载剧集后, 对无刮削集照的集并发抽帧生成, 回写 [ScrapedEpisode.local_thumb_path]。
 *
 * - 筛选: thumb_path 为空且(无本地集照或本地文件已失效/过小黑图)的集; 有源端集照(thumb_path 非空)的不动(源端优先)。
 * - 并发: [Semaphore](CONCURRENCY)(mpv 实例占内存 + destroy 阻塞, 2 并发平衡速度与资源)。
 * - 容错: 单集失败(runSuspendCatching)不阻断其余; 协程取消(离页)向上传播; 不抛出(调用方无需 try-catch)。
 * - 位置: 由调用方从设置项构造 [EpisodeThumbPosition] 传入(百分比/秒数, 默认 10%)。
 */
object EpisodeThumbCoordinator {

    /** 跨季度批量生成时的单集目标，允许每个季使用自己的稳定缓存目录。 */
    data class Target(
        val episode: ScrapedEpisode,
        val showKey: String,
        val hasOnlineThumb: Boolean,
    )

    data class Progress(
        val completed: Int,
        val total: Int,
        val generated: Int,
        val episode: ScrapedEpisode,
    )

    data class Result(
        val total: Int,
        val generated: Int,
    )

    /** 抽帧并发数: mpv 实例占内存 + destroy 阻塞, 2 并发平衡速度与资源(一季 ~12 集, LOCAL ~24s / WebDAV ~45s)。 */
    private const val CONCURRENCY = 2

    /**
     * 本地集照有效字节下限(C-02 存量黑图自愈): 纯色 JPEG 理论 1198B, 正常 320×180 q90 ≥4KB,
     * 2048 居中安全 —— 小于此值视为旧版"全黑仍写盘"遗留的黑图固化(或损坏/不可读, length 为 -1), 需重生成。
     */
    private const val MIN_VALID_THUMB_BYTES = 2048L

    /**
     * 对 [episodes] 中无刮削集照的集懒加载生成本地集照。
     *
     * 每集经 [mediaSourceCache.withSource] 租用来源 -> [generator.generate] 抽帧 ->
     * 成功则 [scrapedRepo.updateEpisodeLocalThumb] 回写 + [onUpdated] 回调刷新 UI state。
     *
     * 离页取消时协程取消向上传播(单集 mpv 阻塞操作可能延迟到当前集完成); 单集失败不阻断其余; 不抛出。
     *
     * @param position 抽帧位置(百分比/秒数, 由调用方从设置项构造)
     * @param onUpdated 某集生成成功后回调(主线程安全: 仅函数调用, 调用方在此更新 episodes state 触发重组)
     */
    suspend fun ensureThumbs(
        episodes: List<ScrapedEpisode>,
        onlineThumbEpisodeNumbers: Set<Long> = emptySet(),
        showKey: String,
        library: LibraryConfig,
        mediaSourceCache: MediaSourceCache,
        generator: EpisodeThumbGenerator,
        position: EpisodeThumbPosition,
        scrapedRepo: ScrapedLibraryRepository,
        onProgress: (Progress) -> Unit = {},
        onUpdated: (episodeId: Long, path: String) -> Unit,
    ): Result = ensureThumbs(
        targets = episodes.map { episode ->
            Target(
                episode = episode,
                showKey = showKey,
                hasOnlineThumb = episode.episode_number in onlineThumbEpisodeNumbers,
            )
        },
        library = library,
        mediaSourceCache = mediaSourceCache,
        generator = generator,
        position = position,
        scrapedRepo = scrapedRepo,
        onUpdated = onUpdated,
        onProgress = onProgress,
    )

    /**
     * 生成多个季度的集照。过滤和并发规则与单季入口一致，但每个目标可以使用自己的缓存目录。
     */
    suspend fun ensureThumbs(
        targets: List<Target>,
        library: LibraryConfig,
        mediaSourceCache: MediaSourceCache,
        generator: EpisodeThumbGenerator,
        position: EpisodeThumbPosition,
        scrapedRepo: ScrapedLibraryRepository,
        onProgress: (Progress) -> Unit = {},
        onUpdated: (episodeId: Long, path: String) -> Unit,
    ): Result {
        // 筛选: thumb_path 为空(无源端集照)且(无本地集照或本地文件已失效/过小, 需重新生成)。
        // 过小判定为 C-02 存量黑图自愈: 旧版生成器全黑仍写盘(~1198B 纯色 JPEG), 仅查存在性会把
        // 黑图永远视为有效; 文件过小(<MIN_VALID_THUMB_BYTES, 含不可读 -1)一并视为无效重生成。
        // 逐集磁盘 stat(exists/length)包 IO 执行: 调用方(AnimeDetailScreen LaunchedEffect)默认
        // 主 dispatcher, 一季 ~12 集的 stat 不应阻塞主线程。只包筛选, 下方并发结构不动。
        val pending = withContext(Dispatchers.IO) {
            targets.filter { target ->
                val ep = target.episode
                !target.hasOnlineThumb &&
                    ep.thumb_path.isNullOrEmpty() &&
                    (ep.local_thumb_path.isNullOrEmpty() ||
                        !platformFileExists(ep.local_thumb_path) ||
                        platformFileLength(ep.local_thumb_path) < MIN_VALID_THUMB_BYTES)
            }
        }
        if (pending.isEmpty()) return Result(total = 0, generated = 0)
        onProgress(Progress(completed = 0, total = pending.size, generated = 0, episode = pending.first().episode))
        val semaphore = Semaphore(CONCURRENCY)
        val progressMutex = Mutex()
        var completed = 0
        var generated = 0
        // coroutineScope: 任一子协程被取消(离页) -> 整体取消向上传播; 单集异常被 runSuspendCatching 吞不取消整体
        coroutineScope {
            for (target in pending) {
                launch {
                    semaphore.withPermit {
                        val ep = target.episode
                        val path = runSuspendCatching {
                            mediaSourceCache.withSource(library) { source ->
                                generator.generate(ep, target.showKey, source, position)
                            }
                        }.getOrNull()
                        var generatedThis = false
                        if (path != null) {
                            // CR-080: 仅数据库写成功才回调 onUpdated 刷新 UI state。写失败时若仍回调,
                            // UI 显示本地集照但 DB local_thumb_path 仍空, 下次进详情页重复抽帧且状态不一致。
                            // Coordinator 在 commonMain 无 AppLogger 注入, 失败静默(文件已在 PosterCache,
                            // 下次进详情页因 DB 路径仍空会重新生成覆盖, 代价仅一次抽帧, 不丢数据)。
                            val dbWrite = runSuspendCatching { scrapedRepo.updateEpisodeLocalThumb(ep.id, path) }
                            if (dbWrite.isSuccess) {
                                generatedThis = true
                                onUpdated(ep.id, path)
                            }
                        }
                        progressMutex.withLock {
                            completed++
                            if (generatedThis) generated++
                            onProgress(
                                Progress(
                                    completed = completed,
                                    total = pending.size,
                                    generated = generated,
                                    episode = ep,
                                ),
                            )
                        }
                    }
                }
            }
        }
        return Result(total = pending.size, generated = generated)
    }
}
