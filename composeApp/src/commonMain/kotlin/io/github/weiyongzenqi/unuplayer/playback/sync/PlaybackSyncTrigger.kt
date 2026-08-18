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
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.util.Crypto
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
    private val sharedHttpClientProvider: () -> HttpClient = ::createHttpClient,
    private val deviceName: String,
    private val logger: AppLogger? = null,
) {
    // 进程级同步 scope：承载一次性启动同步与退出播放防抖同步，不随 Activity/窗口销毁。
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Android Activity 可重建；启动同步必须由进程级触发器至多调度一次，不能跟随每次 onCreate 重跑。
    @Volatile private var startupSyncScheduled = false
    @Volatile private var startupSyncJob: Job? = null
    @Volatile private var startupSyncSettings: SettingsState? = null
    // T2-m6: pushJob 的读-取消-写不做原子化——调用方均在主线程(Android PlayerActivity.onDestroy /
    // 桌面 Compose 关窗回调), 实际串行, @Volatile 可见性已够; 不为它引同步锁/新依赖。
    @Volatile private var pushJob: Job? = null
    // 最近一次防抖推送的 settings 缓存：设置变化时用于判断排队任务是否仍被允许执行。
    @Volatile private var lastPushSettings: SettingsState? = null
    // T2-m2: 同步操作全局互斥。启动 sync / 退出防抖 push / 手动 sync 三触发源可并发各自 new Coordinator,
    // 串行化消除偶发重复上传(DB 事务化+版本门控幂等本不坏库, 此处仅为去重)。Mutex 不可重入:
    // withLock 体内只调 resolveCoordinator 与 Coordinator 自身方法, 不回调本类其他加锁入口。
    private val syncMutex = Mutex()

    /**
     * 每个触发器进程实例至多调度一次启动同步。任务运行在 [syncScope]，Activity 重建或销毁不会中断；
     * 设置在任务完成前关闭同步、关闭自动同步或切换连接时，[reconcileAutoSyncSettings] 会取消旧目标任务。
     * 调用方固定在平台主线程，和 [scheduleDebouncedPush] 的任务状态更新遵守同一串行约束。
     */
    fun scheduleStartupSync(settings: SettingsState) {
        if (startupSyncScheduled) return
        startupSyncScheduled = true
        if (!settings.playbackAutoSync) return
        startupSyncSettings = settings
        startupSyncJob = syncScope.launch {
            try {
                sync(settings)
            } finally {
                startupSyncJob = null
                startupSyncSettings = null
            }
        }
    }

    /**
     * 退出播放后防抖同步: 延迟 [delayMs] 后执行完整 strict sync；远端预检失败时禁止覆盖快照。
     * 期间新触发取消旧 Job(防抖, 避免快速切集/连点洪泛)。进程级 scope 执行, 不随调用方(Activity/窗口)销毁。
     * settings 未开同步/未选连接/凭据失效 -> resolveCoordinator 返回 null, push 跳过(no-op)。
     */
    fun scheduleDebouncedPush(settings: SettingsState, delayMs: Long = DEFAULT_PUSH_DEBOUNCE_MS) {
        // 自动同步开关: 关闭则退出不自动推送(用户仍可手动按按钮推, 手动按钮走 sync() 不经此方法)。
        if (!settings.playbackAutoSync) {
            cancelPendingAutoSync()
            return
        }
        // 缓存本次排队设置，供设置变化时精确撤销已失效的防抖任务。
        lastPushSettings = settings
        pushJob?.cancel()
        pushJob = syncScope.launch {
            delay(delayMs)
            val result = runSuspendCatching { strictSyncOnce(settings) }.getOrElse { error ->
                logger?.appEvent("playback-sync", "退出推送异常: ${error::class.simpleName ?: error.message}", LogLevel.WARN)
                return@launch
            } ?: return@launch // 未开同步/未选连接/凭据失效, resolveCoordinator 返回 null, 跳过
            if (result.success) {
                logger?.appEvent(
                    "playback-sync",
                    "退出同步完成: 拉取 ${result.pulled} 文件, 推送记录 ${result.pushed}/进度 ${result.pushedProgress}",
                    LogLevel.INFO,
                )
            } else {
                logger?.appEvent("playback-sync", "退出推送失败: ${result.error}", LogLevel.WARN)
            }
        }
    }

    /** 设置成功落盘后收敛旧自动任务，避免继续使用已关闭或已切换的同步目标。 */
    fun reconcileAutoSyncSettings(settings: SettingsState) {
        startupSyncSettings?.let { pending ->
            if (!settingsStillAllowPendingSync(settings, pending)) cancelPendingStartupSync()
        }
        lastPushSettings?.let { pending ->
            if (!settingsStillAllowPendingSync(settings, pending)) cancelPendingAutoSync()
        }
    }

    private fun settingsStillAllowPendingSync(current: SettingsState, pending: SettingsState): Boolean =
        current.playbackSyncEnabled &&
            current.playbackAutoSync &&
            current.playbackSyncConnectionId != null &&
            current.playbackSyncConnectionId == pending.playbackSyncConnectionId

    private fun cancelPendingStartupSync() {
        startupSyncJob?.cancel()
        startupSyncJob = null
        startupSyncSettings = null
    }

    private fun cancelPendingAutoSync() {
        pushJob?.cancel()
        pushJob = null
        lastPushSettings = null
    }

    /**
     * 退出前取消防抖等待，并在最终播放记录落库后按最新设置执行一次 strict sync，随后关闭 scope。
     * 自动同步开启时不依赖是否存在防抖任务；失败或平台侧超时不阻断退出，由下次启动同步兜底。
     */
    suspend fun flushAndClose(settings: SettingsState) {
        cancelPendingStartupSync()
        pushJob?.cancel() // 停止 5s 防抖等待, 随后按退出时的最新设置立即补同步
        try {
            if (settings.playbackAutoSync) {
                // runSuspendCatching 正确传播 CancellationException: 平台侧 withTimeoutOrNull 超时可中断补同步。
                runSuspendCatching { strictSyncOnce(settings) }.onFailure { error ->
                    logger?.appEvent("playback-sync", "退出补推异常: ${error::class.simpleName ?: error.message}", LogLevel.WARN)
                }
            }
        } finally {
            pushJob = null
            lastPushSettings = null
            syncScope.cancel()
        }
    }

    /** 进程退出时清理推送 scope(对齐 PosterWallScanCoordinator.close)。不能阻塞的调用方用此兜底。 */
    fun close() {
        syncScope.cancel()
    }
    /**
     * 完整同步(先 pull 后 push)。settings 未开同步/未选连接/连接不存在/凭据失效 -> 返回 null(no-op)。
     * 失败(网络/认证)返回 PlaybackSyncResult(success=false, error=...);不抛(调用方 best-effort)。
     */
    suspend fun sync(settings: SettingsState): PlaybackSyncCoordinator.PlaybackSyncResult? = syncMutex.withLock {
        val result = runSuspendCatching {
            withCoordinator(settings) { coordinator -> coordinator.sync() }
        }.getOrElse { error ->
            logger?.appEvent("playback-sync", "同步异常: ${error::class.simpleName ?: error.message}", LogLevel.WARN)
            PlaybackSyncCoordinator.PlaybackSyncResult(success = false, error = "同步异常: ${error.message ?: error::class.simpleName}")
        } ?: return@withLock null
        if (!result.success) {
            logger?.appEvent("playback-sync", "同步失败: ${result.error}", LogLevel.WARN)
        } else {
            logger?.appEvent(
                "playback-sync",
                "同步完成: 拉取 ${result.pulled} 文件, 合并记录 ${result.mergedRecords}/进度 ${result.mergedProgress}, " +
                    "推送记录 ${result.pushed}/进度 ${result.pushedProgress}",
                LogLevel.INFO,
            )
        }
        result
    }

    /** 仅拉取(启动异步用)。settings 未开/未选/连接失效 -> null。 */
    suspend fun pull(settings: SettingsState): PlaybackSyncCoordinator.PlaybackSyncResult? = syncMutex.withLock {
        runSuspendCatching {
            withCoordinator(settings) { coordinator -> coordinator.pull() }
        }.getOrElse { error ->
            logger?.appEvent("playback-sync", "拉取异常: ${error::class.simpleName ?: error.message}", LogLevel.WARN)
            PlaybackSyncCoordinator.PlaybackSyncResult(success = false, error = "拉取异常: ${error.message ?: error::class.simpleName}")
        }
    }

    /**
     * 一次 strict sync(带全局互斥): 防抖同步 launch 体与退出补同步 flushAndClose 共用, 保证两者不并发。
     * settings 未开同步/未选连接/凭据失效 -> resolveCoordinator 返回 null -> 返回 null(no-op)。
     */
    private suspend fun strictSyncOnce(settings: SettingsState): PlaybackSyncCoordinator.PlaybackSyncResult? =
        syncMutex.withLock {
            withCoordinator(settings) { coordinator -> coordinator.sync() }
        }

    /**
     * 每次操作从平台进程级 HTTP 客户端提供者借用客户端。
     * [createHttpClient] 返回共享单例，所有权不转移给同步器；只能由应用退出边界统一关闭。
     */
    private suspend fun <T> withCoordinator(
        settings: SettingsState,
        block: suspend (PlaybackSyncCoordinator) -> T,
    ): T? {
        val coordinator = resolveCoordinator(settings) ?: return null
        return block(coordinator)
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
        val mediaIdentityResolver = mediaIdentityResolver()
        val localTargetByIdentity = localTargetByIdentityResolver()
        val httpClient = sharedHttpClientProvider()
        return PlaybackSyncCoordinator(
            repository = playbackRepository,
            client = WebDavClient(httpClient, connection.baseUrl, connection.username, connection.password),
            deviceIdProvider = deviceIdentityProvider::get,
            deviceNameProvider = { deviceName },
            logger = logger,
            syncDirPath = PlaybackSyncCoordinator.CURRENT_SYNC_DIR,
            mediaIdentityResolver = mediaIdentityResolver,
            localTargetByIdentity = localTargetByIdentity,
        )
    }

    /**
     * 跨设备稳定媒体身份 resolver: 用全部 WebDAV 连接把 media_key 的 connId 归一化为端点+账号指纹。
     * 拉取/推送共用同一函数, 使同端点不同 connId 的同一文件在两端映射到同一身份, 同步按身份合并。
     */
    private suspend fun mediaIdentityResolver(): (suspend (String) -> String?)? {
        val connections = runSuspendCatching { webDavRepository.loadAll() }.getOrNull() ?: return null
        return { mediaKey -> buildSyncMediaIdentity(connections, mediaKey) }
    }

    /** 身份归属到本地 WebDAV 连接: pull 无本地匹配记录时按身份落到本地连接, 避免 ghost 重复记录。 */
    private suspend fun localTargetByIdentityResolver():
        (suspend (String) -> PlaybackSyncCoordinator.LocalSyncTarget?)? {
        val connections = runSuspendCatching { webDavRepository.loadAll() }.getOrNull() ?: return null
        val v2Candidates = connections.asSequence()
            .filterNot { it.credentialUnavailable }
            .mapNotNull { conn ->
                mediaIdentityPrefix(conn.baseUrl, conn.username)?.let { it to conn }
            }
            .groupBy({ it.first }, { it.second })
        val v2ByPrefix = v2Candidates.mapNotNull { (prefix, candidates) ->
            candidates.singleOrNull()?.let {
                prefix to PlaybackSyncCoordinator.LocalSyncTarget(it.id, it.baseUrl)
            }
        }.toMap()
        // 仅为读取旧 payload 保留强制小写账号的 SHA 前缀；同一旧前缀若对应多个实际凭据
        // 则拒绝猜测归属，避免大小写敏感账号或重复连接被错误合并。
        val legacyCandidates = connections.asSequence()
            .filterNot { it.credentialUnavailable }
            .mapNotNull { conn ->
                legacyMediaIdentityPrefix(conn.baseUrl, conn.username)
                    ?.let { it to conn }
            }
            .groupBy({ it.first }, { it.second })
        val legacyByPrefix = legacyCandidates.mapNotNull { (prefix, candidates) ->
            val distinctCredentials = candidates.distinctBy {
                normalizeSyncEndpoint(it.baseUrl) + "\u0000" + it.username.trim() + "\u0000" + it.password
            }
            distinctCredentials.singleOrNull()?.let {
                prefix to PlaybackSyncCoordinator.LocalSyncTarget(it.id, it.baseUrl)
            }
        }.toMap()
        val byPrefix = v2ByPrefix + legacyByPrefix
        return { identity -> identitySyncPrefix(identity)?.let(byPrefix::get) }
    }

    companion object {
        /** 默认防抖延迟 5 秒(避免快速切集/连点洪泛) */
        const val DEFAULT_PUSH_DEBOUNCE_MS = 5_000L
    }
}

/**
 * 把 media_key 归一化为跨设备稳定媒体身份(端点+账号指纹+path)。media_key 含本机 connectionId,
 * 跨设备重装/新建连接后同文件 key 不同, 同步按 key 精确匹配会产生重复记录; 身份用
 * baseUrl(归一化)+username(仅去首尾空白、保留大小写)的版本化 SHA-256 假名加 path，
 * 表达"同一端点同一账号的同一文件"；密码轮换不改变身份，也不会把同步 payload 变成口令校验器。
 *
 * 隐私边界: 摘要只是假名而非加密，知道候选端点/账号的人仍可离线枚举；它的目标是避免
 * payload 直接落明文和旧 64 位哈希碰撞，不提供端点/账号机密性。找不到唯一连接时回落 media_key 匹配。
 */
internal fun buildSyncMediaIdentity(connections: List<WebDavConnection>, mediaKey: String): String? {
    if (!mediaKey.startsWith("webdav:")) return null
    val payload = mediaKey.removePrefix("webdav:")
    val separator = payload.indexOf(':')
    if (separator <= 0 || separator == payload.lastIndex) return null
    val connId = payload.substring(0, separator)
    val path = payload.substring(separator + 1)
    if (path.isEmpty()) return null
    val connection = connections.firstOrNull { it.id == connId } ?: return null
    val prefix = mediaIdentityPrefix(connection.baseUrl, connection.username) ?: return null
    return "$prefix:$path"
}

/** v2 身份前缀: 端点归一化、账号只 trim 且保留大小写，密码不参与也不落 payload。 */
internal fun mediaIdentityPrefix(baseUrl: String, username: String): String? {
    val normalizedUser = username.trim()
    if (normalizedUser.isEmpty()) return null
    val value = "unu-player:media-identity:v2\u0000" +
        normalizeSyncEndpoint(baseUrl) + "\u0000" + normalizedUser
    return "webdav:h2-" + Crypto.sha256Hex(value)
}

/** v1 兼容前缀：只允许读取历史 payload，不再生成新的身份。 */
internal fun legacyMediaIdentityPrefix(baseUrl: String, username: String): String? {
    val normalizedUser = username.trim().lowercase()
    if (normalizedUser.isEmpty()) return null
    return "webdav:" + Crypto.sha256Hex(normalizeSyncEndpoint(baseUrl) + "|" + normalizedUser)
}

/** 从身份提取 "webdav:<端点+账号哈希>" 前缀(身份 = "<前缀>:<path>", 哈希无冒号)。 */
internal fun identitySyncPrefix(identity: String): String? {
    if (!identity.startsWith("webdav:")) return null
    val rest = identity.removePrefix("webdav:")
    val separator = rest.indexOf(':')
    if (separator <= 0) return null
    return "webdav:" + rest.substring(0, separator)
}

/** 端点归一化(去尾斜杠 + scheme/host 小写, path 保留), 保证两端对同端点生成一致身份。 */
private fun normalizeSyncEndpoint(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return trimmed
    val authorityStart = schemeEnd + 3
    val authorityEnd = sequenceOf(
        trimmed.indexOf('/', authorityStart),
        trimmed.indexOf('?', authorityStart),
        trimmed.indexOf('#', authorityStart),
    ).filter { it >= 0 }.minOrNull() ?: trimmed.length
    return trimmed.substring(0, schemeEnd).lowercase() + "://" +
        trimmed.substring(authorityStart, authorityEnd).lowercase() +
        trimmed.substring(authorityEnd)
}
