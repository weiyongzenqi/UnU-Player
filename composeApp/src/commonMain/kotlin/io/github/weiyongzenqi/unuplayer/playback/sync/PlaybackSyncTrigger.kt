package io.github.weiyongzenqi.unuplayer.playback.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.domain.SettingsState
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavClient
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.createHttpClient
import io.ktor.client.HttpClient

/**
 * P2 同步触发协调器: 根据 settings 构造一次性 PlaybackSyncCoordinator 执行 sync/pull/push。
 *
 * Coordinator 绑定当前同步连接(WebDavClient), 连接变更时无需重建本类--每次操作现取连接现造
 * Coordinator。deviceId 经 [deviceIdentityProvider] 持久化(卸载即毁)。
 */
class PlaybackSyncTrigger(
    private val webDavRepository: WebDavConnectionRepository,
    private val playbackRepository: PlaybackRecordRepository,
    private val deviceIdentityProvider: PlaybackSyncDeviceIdentityProvider,
    private val httpClientFactory: () -> HttpClient = ::createHttpClient,
    private val deviceName: String,
    private val logger: AppLogger? = null,
) {
    // 防抖推送: 进程级 scope(不随 Activity/窗口销毁), 退出播放时触发, delay 后 best-effort push。
    private val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // T2-m6: pushJob 的读-取消-写不做原子化——调用方均在主线程(Android PlayerActivity.onDestroy /
    // 桌面 Compose 关窗回调), 实际串行, @Volatile 可见性已够; 不为它引同步锁/新依赖。
    @Volatile private var pushJob: Job? = null
    // 最近一次防抖推送的 settings 缓存: 进程退出时 flushAndClose 用它一次性补推(MAJOR-B)。
    @Volatile private var lastPushSettings: SettingsState? = null
    // T2-m2: 同步操作全局互斥。启动 sync / 退出防抖 push / 手动 sync 三触发源可并发各自 new Coordinator,
    // 串行化消除偶发重复上传(DB 事务化+版本门控幂等本不坏库, 此处仅为去重)。Mutex 不可重入:
    // withLock 体内只调 resolveCoordinator 与 Coordinator 自身方法, 不回调本类其他加锁入口。
    private val syncMutex = Mutex()

    /**
     * 退出播放后防抖推送: 延迟 [delayMs] 后 best-effort push(只 push 不 pull)。
     * 期间新触发取消旧 Job(防抖, 避免快速切集/连点洪泛)。进程级 scope 执行, 不随调用方(Activity/窗口)销毁。
     * settings 未开同步/未选连接/凭据失效 -> resolveCoordinator 返回 null, push 跳过(no-op)。
     */
    fun scheduleDebouncedPush(settings: SettingsState, delayMs: Long = DEFAULT_PUSH_DEBOUNCE_MS) {
        // 自动同步开关: 关闭则退出不自动推送(用户仍可手动按按钮推, 手动按钮走 sync() 不经此方法)。
        if (!settings.playbackAutoSync) return
        // 缓存本次推送设置: 防抖等待期间若进程退出, flushAndClose 用它立即补推(MAJOR-B)。
        lastPushSettings = settings
        pushJob?.cancel()
        pushJob = pushScope.launch {
            delay(delayMs)
            val result = runSuspendCatching { pushOnce(settings) }.getOrElse { error ->
                logger?.appEvent("playback-sync", "退出推送异常: ${error::class.simpleName ?: error.message}", LogLevel.WARN)
                return@launch
            } ?: return@launch // 未开同步/未选连接/凭据失效, resolveCoordinator 返回 null, 跳过
            if (result.success) {
                logger?.appEvent("playback-sync", "退出推送完成: 推送 ${result.pushed} 条", LogLevel.INFO)
            } else {
                logger?.appEvent("playback-sync", "退出推送失败: ${result.error}", LogLevel.WARN)
            }
        }
    }

    /**
     * 退出前一次性补推(MAJOR-B): 取消防抖等待 -> 立即推送当前进度一次 -> 关闭 scope。
     *
     * 场景: 关播放窗口排队的 5s 防抖推送, 若用户在等待期间退出进程, 原 close() 直接 cancel scope
     * 会中断该推送; 本方法先把排队的推送立即兑现再关。commonMain 禁 runBlocking, 故为 suspend,
     * 由平台边界有界阻塞调用(见 DesktopAppGraph.close)。best-effort: 补推失败/超时不阻断退出,
     * 由下次启动 sync 兜底(非永久丢失)。
     */
    suspend fun flushAndClose() {
        pushJob?.cancel() // 停止 5s 防抖等待, 随后立即补推同一份进度
        lastPushSettings?.let { settings ->
            // runSuspendCatching 正确传播 CancellationException: 平台侧 withTimeoutOrNull 超时可中断补推。
            runSuspendCatching { pushOnce(settings) }.onFailure { error ->
                logger?.appEvent("playback-sync", "退出补推异常: ${error::class.simpleName ?: error.message}", LogLevel.WARN)
            }
        }
        pushScope.cancel()
    }

    /** 进程退出时清理推送 scope(对齐 PosterWallScanCoordinator.close)。不能阻塞的调用方用此兜底。 */
    fun close() {
        pushScope.cancel()
    }
    /**
     * 完整同步(先 pull 后 push)。settings 未开同步/未选连接/连接不存在/凭据失效 -> 返回 null(no-op)。
     * 失败(网络/认证)返回 PlaybackSyncResult(success=false, error=...);不抛(调用方 best-effort)。
     */
    suspend fun sync(settings: SettingsState): PlaybackSyncCoordinator.PlaybackSyncResult? = syncMutex.withLock {
        val coordinator = resolveCoordinator(settings) ?: return@withLock null
        val result = runSuspendCatching { coordinator.sync() }.getOrElse { error ->
            logger?.appEvent("playback-sync", "同步异常: ${error::class.simpleName ?: error.message}", LogLevel.WARN)
            PlaybackSyncCoordinator.PlaybackSyncResult(success = false, error = "同步异常: ${error.message ?: error::class.simpleName}")
        }
        if (!result.success) {
            logger?.appEvent("playback-sync", "同步失败: ${result.error}", LogLevel.WARN)
        } else {
            logger?.appEvent("playback-sync", "同步完成: 拉取 ${result.pulled} 文件, 合并记录 ${result.mergedRecords}/进度 ${result.mergedProgress}, 推送 ${result.pushed}", LogLevel.INFO)
        }
        result
    }

    /** 仅拉取(启动异步用)。settings 未开/未选/连接失效 -> null。 */
    suspend fun pull(settings: SettingsState): PlaybackSyncCoordinator.PlaybackSyncResult? = syncMutex.withLock {
        val coordinator = resolveCoordinator(settings) ?: return@withLock null
        runSuspendCatching { coordinator.pull() }.getOrElse { error ->
            logger?.appEvent("playback-sync", "拉取异常: ${error::class.simpleName ?: error.message}", LogLevel.WARN)
            PlaybackSyncCoordinator.PlaybackSyncResult(success = false, error = "拉取异常: ${error.message ?: error::class.simpleName}")
        }
    }

    /**
     * 推送一次(带全局互斥): 防抖推送 launch 体与退出补推 flushAndClose 共用, 保证两者不并发。
     * settings 未开同步/未选连接/凭据失效 -> resolveCoordinator 返回 null -> 返回 null(no-op)。
     */
    private suspend fun pushOnce(settings: SettingsState): PlaybackSyncCoordinator.PlaybackSyncResult? =
        syncMutex.withLock {
            resolveCoordinator(settings)?.push()
        }

    /** 根据 settings 取同步连接构造 Coordinator; 不满足条件返回 null。 */
    private suspend fun resolveCoordinator(settings: SettingsState): PlaybackSyncCoordinator? {
        if (!settings.playbackSyncEnabled) return null
        val connId = settings.playbackSyncConnectionId ?: return null
        val connection = runSuspendCatching { webDavRepository.loadAll() }.getOrNull()
            ?.firstOrNull { it.id == connId } ?: run {
            logger?.appEvent("playback-sync", "同步连接 $connId 未找到, 跳过", LogLevel.WARN)
            return null
        }
        // 凭据失效跳过(平台密钥失效、密文损坏等)
        if (connection.credentialUnavailable) {
            logger?.appEvent("playback-sync", "同步连接 ${connection.name} 凭据失效, 跳过", LogLevel.WARN)
            return null
        }
        return PlaybackSyncCoordinator(
            repository = playbackRepository,
            client = WebDavClient(httpClientFactory(), connection.baseUrl, connection.username, connection.password),
            deviceIdProvider = deviceIdentityProvider::get,
            deviceNameProvider = { deviceName },
            logger = logger,
        )
    }

    companion object {
        /** 默认防抖延迟 5 秒(避免快速切集/连点洪泛) */
        const val DEFAULT_PUSH_DEBOUNCE_MS = 5_000L
    }
}