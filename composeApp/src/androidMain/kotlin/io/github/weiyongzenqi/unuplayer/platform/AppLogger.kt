package io.github.weiyongzenqi.unuplayer.platform

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import io.github.weiyongzenqi.unuplayer.core.security.redactSensitiveText

/**
 * 应用日志器 Android 实现(SAF DocumentFile)。
 *
 * 两个独立文件(unu-app/unu-mpv)，有界队列隔离主线程与 mpv pthread；唯一 IO 消费协程拥有 writer。
 * 目录切换使用 generation，clear/size 使用 suspend 队列屏障(不阻塞调用线程)，shutdown 完整 drain。
 * 高频日志按批 flush；保留期清理([pruneExpiredLogsLocked])在同一 writer 协程内于启动与每日轮转时扫描。
 * Android 进程被系统强杀时无法执行 drain，仍可能损失有界 Channel 中的待消费条目与未 flush 批次。
 */
class AndroidAppLogger private constructor(
    private val context: Context,
) : AppLogger {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<QueueItem>(capacity = DEFAULT_QUEUE_CAPACITY)
    private val accepting = AtomicBoolean(true)
    private val droppedApp = AtomicLong(0)
    private val droppedMpv = AtomicLong(0)
    private val configLock = Any()
    private val writerLock = Any()

    @Volatile private var directoryConfig = DirectoryConfig(generation = 0, path = null)
    @Volatile private var appLogLevel: LogLevel = LogLevel.INFO

    /** 以下状态只能在 [writerLock] 内访问。 */
    private var appliedConfig = DirectoryConfig(generation = 0, path = null)
    private var currentDir: DocumentFile? = null
    private val appSlot = StreamSlot()
    private val mpvSlot = StreamSlot()

    /** 最近一次保留期清理的扫描日期(yyyy-MM-dd)，只在 [writerLock] 内访问，同一天最多扫描一次。 */
    private var lastRetentionScanDate: String? = null

    private val consumerJob: Job = scope.launch { consume() }

    init {
        consumerJob.invokeOnCompletion {
            accepting.set(false)
            queue.close()
        }
    }

    companion object {
        @Volatile private var instance: AndroidAppLogger? = null

        /** 获取进程级单例(首次用 applicationContext 初始化，后续忽略 context)。 */
        fun get(context: Context): AndroidAppLogger =
            instance ?: synchronized(this) {
                instance ?: AndroidAppLogger(context.applicationContext).also { instance = it }
            }

        private const val DEFAULT_QUEUE_CAPACITY = 4096
        private const val DEFAULT_BATCH_SIZE = 128
        private const val DEFAULT_FLUSH_INTERVAL_MS = 50L
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        private val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /** 设置 SAF tree URI；只更新轻量配置并异步交给 IO writer，调用主线程时不关闭流。 */
    override fun setDirectory(path: String?) {
        if (!accepting.get()) return
        val normalized = path?.trim()?.takeIf { it.isNotEmpty() }
        val next = synchronized(configLock) {
            val current = directoryConfig
            if (current.path == normalized) return
            DirectoryConfig(current.generation + 1, normalized).also { directoryConfig = it }
        }
        enqueueControl(QueueItem.SetDirectory(next))
    }

    override fun setAppLogLevel(level: LogLevel) {
        appLogLevel = level
    }

    override fun log(level: String, prefix: String, text: String) {
        enqueue(Source.MPV, level, "[$prefix]", text)
    }

    override fun appEvent(tag: String, message: String, level: LogLevel) {
        if (level.ordinal < appLogLevel.ordinal) return
        enqueue(Source.APP, level.name.lowercase(Locale.US), "[$tag]", message)
    }

    private fun enqueue(source: Source, level: String, tagField: String, text: String) {
        if (!accepting.get()) return
        val config = directoryConfig
        if (config.path == null) return
        val item = QueueItem.Entry(
            config = config,
            timestampMillis = System.currentTimeMillis(),
            source = source,
            level = level,
            tagField = tagField,
            text = redactSensitiveText(text.trimEnd('\n')),
        )
        if (queue.trySend(item).isFailure && accepting.get()) {
            droppedCounter(source).incrementAndGet()
        }
    }

    override suspend fun clearLogs(): Long = requestLong(
        itemFactory = { config, response -> QueueItem.Clear(config, response) },
        fallback = { config -> synchronized(writerLock) { clearLogsLocked(config) } },
    )

    override suspend fun logsSize(): Long = requestLong(
        itemFactory = { config, response -> QueueItem.Size(config, response) },
        fallback = { config -> synchronized(writerLock) { logsSizeLocked(config) } },
    )

    /** Android 正常不会主动关闭进程单例；显式调用时仍保证 drain，重复调用安全。 */
    override fun shutdown() {
        if (!accepting.compareAndSet(true, false)) return
        queue.close()
        runBlocking { consumerJob.join() }
        scope.cancel()
    }

    private fun enqueueControl(item: QueueItem) {
        if (queue.trySend(item).isSuccess || !accepting.get()) return
        scope.launch {
            runCatching { queue.send(item) }
        }
    }

    /**
     * clear/size 的 suspend 队列屏障: 直接 send + await 挂起等待 writer 处理, 不阻塞调用线程。
     * 队列已关闭/shutdown 后走 [fallback](此时 writer 已停, 同步加 [writerLock] 安全);
     * 协程取消按结构化并发语义向上抛, 不吞掉也不执行同步 fallback。
     */
    private suspend fun requestLong(
        itemFactory: (DirectoryConfig, CompletableDeferred<Long>) -> QueueItem,
        fallback: (DirectoryConfig) -> Long,
    ): Long {
        val config = directoryConfig
        if (!accepting.get()) return fallback(config)
        val response = CompletableDeferred<Long>()
        return try {
            queue.send(itemFactory(config, response))
            response.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            fallback(config)
        }
    }

    private suspend fun consume() {
        var pendingWrites = 0
        try {
            while (true) {
                val received = if (pendingWrites == 0) {
                    queue.receiveCatching()
                } else {
                    withTimeoutOrNull(DEFAULT_FLUSH_INTERVAL_MS) { queue.receiveCatching() }
                }
                if (received == null) {
                    flushBatch()
                    pendingWrites = 0
                    continue
                }
                val item = received.getOrNull() ?: break
                when (item) {
                    is QueueItem.Entry -> {
                        synchronized(writerLock) { writeEntryLocked(item) }
                        pendingWrites++
                        if (pendingWrites >= DEFAULT_BATCH_SIZE) {
                            flushBatch()
                            pendingWrites = 0
                        }
                    }
                    is QueueItem.SetDirectory -> {
                        flushBatch()
                        pendingWrites = 0
                        synchronized(writerLock) {
                            applyDirectoryLocked(item.config)
                            // 启动时(app 入口 setDirectory 生成首个 SetDirectory)与目录切换时扫描一次, 同日去重
                            pruneExpiredLogsLocked(System.currentTimeMillis())
                        }
                    }
                    is QueueItem.Clear -> {
                        flushBatch()
                        pendingWrites = 0
                        val result = runCatching {
                            synchronized(writerLock) { clearLogsLocked(item.config) }
                        }.getOrDefault(0L)
                        item.response.complete(result)
                    }
                    is QueueItem.Size -> {
                        flushBatch()
                        pendingWrites = 0
                        val result = runCatching {
                            synchronized(writerLock) { logsSizeLocked(item.config) }
                        }.getOrDefault(0L)
                        item.response.complete(result)
                    }
                }
            }
        } finally {
            flushBatch()
            synchronized(writerLock) {
                appSlot.close()
                mpvSlot.close()
            }
        }
    }

    private fun flushBatch() {
        synchronized(writerLock) {
            writeDroppedSummaryLocked()
            appSlot.flushOrReset()
            mpvSlot.flushOrReset()
        }
    }

    private fun writeDroppedSummaryLocked() {
        val app = droppedApp.getAndSet(0)
        val mpv = droppedMpv.getAndSet(0)
        if (app == 0L && mpv == 0L) return
        val config = directoryConfig
        if (config.path == null) {
            droppedApp.addAndGet(app)
            droppedMpv.addAndGet(mpv)
            return
        }
        writeEntryLocked(
            QueueItem.Entry(
                config = config,
                timestampMillis = System.currentTimeMillis(),
                source = Source.APP,
                level = "warn",
                tagField = "[logger]",
                text = "日志队列已满，丢弃 app=$app mpv=$mpv 条（保留旧项、丢弃新项）",
            ),
        )
    }

    private fun writeEntryLocked(entry: QueueItem.Entry) {
        if (entry.config.generation < appliedConfig.generation) return
        applyDirectoryLocked(entry.config)
        val dir = resolveCurrentDirectoryLocked() ?: return
        val date = formatDate(entry.timestampMillis)
        val fileName = (if (entry.source == Source.APP) "unu-app-" else "unu-mpv-") + "$date.txt"
        val slot = if (entry.source == Source.APP) appSlot else mpvSlot
        try {
            if (fileName != slot.fileName) {
                // slot 已有旧文件名且与新名不同 = 跨日轮转(首写 slot.fileName==null 不算); 先关旧流再清理过期文件
                val rotated = slot.fileName != null
                slot.close()
                slot.fileName = fileName
                if (rotated) pruneExpiredLogsLocked(entry.timestampMillis)
            }
            if (slot.stream == null) {
                val file = dir.findFile(fileName) ?: dir.createFile("text/plain", fileName)
                if (file != null && file.canWrite()) {
                    context.contentResolver.openOutputStream(file.uri, "wa")?.let { output ->
                        slot.stream = BufferedOutputStream(output, STREAM_BUFFER_BYTES)
                    }
                }
            }
            val line = "[${formatTimestamp(entry.timestampMillis)}] [${entry.level}] ${entry.tagField} ${entry.text}\n"
            slot.stream?.write(line.toByteArray(Charsets.UTF_8))
        } catch (_: Throwable) {
            slot.close()
        }
    }

    private fun applyDirectoryLocked(config: DirectoryConfig) {
        if (config.generation <= appliedConfig.generation) return
        appSlot.close()
        mpvSlot.close()
        currentDir = null
        appliedConfig = config
    }

    private fun resolveCurrentDirectoryLocked(): DocumentFile? {
        currentDir?.let { return it }
        val uri = appliedConfig.path?.let(Uri::parse) ?: return null
        return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.isDirectory }?.also { currentDir = it }
    }

    private fun clearLogsLocked(config: DirectoryConfig): Long {
        val path = config.path ?: return 0L
        val active = appliedConfig.path == path
        if (active) {
            appSlot.close()
            mpvSlot.close()
        }
        val dir = if (active) {
            resolveCurrentDirectoryLocked()
        } else {
            DocumentFile.fromTreeUri(context, Uri.parse(path))?.takeIf { it.isDirectory }
        } ?: return 0L
        var deleted = 0L
        dir.listFiles().forEach { file ->
            val name = file.name ?: return@forEach
            if (isOwnedLogName(name)) {
                val size = file.length()
                if (file.delete()) deleted += size
            }
        }
        return deleted
    }

    private fun logsSizeLocked(config: DirectoryConfig): Long {
        val path = config.path ?: return 0L
        val active = appliedConfig.path == path
        if (active) {
            appSlot.flushOrReset()
            mpvSlot.flushOrReset()
        }
        val dir = if (active) {
            resolveCurrentDirectoryLocked()
        } else {
            DocumentFile.fromTreeUri(context, Uri.parse(path))?.takeIf { it.isDirectory }
        } ?: return 0L
        return dir.listFiles().sumOf { file ->
            val name = file.name
            if (name != null && isOwnedLogName(name)) file.length() else 0L
        }
    }

    /**
     * 保留期清理(P3⑳): 扫描当前日志目录, 按文件名日期删除超过 [AppLogger.LOG_RETENTION_DAYS] 天的文件。
     * 只在 writer 协程/IO 上下文执行(经 [writerLock] 与写入串行), 由 [lastRetentionScanDate] 同日去重;
     * 目录不可读/删除失败只记日志不抛错, 绝不影响日志写入主路径; 非法命名文件跳过不删。
     */
    private fun pruneExpiredLogsLocked(nowMillis: Long) {
        val today = formatDate(nowMillis)
        if (today == lastRetentionScanDate) return
        val dir = runCatching { resolveCurrentDirectoryLocked() }.getOrNull() ?: return
        lastRetentionScanDate = today
        val cutoff = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
            .toLocalDate().minusDays(AppLogger.LOG_RETENTION_DAYS.toLong())
        val files = runCatching { dir.listFiles() }.getOrElse { error ->
            appEvent("logger", "保留期清理枚举日志目录失败: ${error.message}", LogLevel.WARN)
            return
        }
        files.forEach { file ->
            val name = file.name ?: return@forEach
            val fileDate = parseLogNameDate(name) ?: return@forEach
            if (!fileDate.isBefore(cutoff)) return@forEach
            val deleted = runCatching { file.delete() }.getOrDefault(false)
            if (!deleted) {
                appEvent("logger", "删除过期日志失败: $name", LogLevel.WARN)
            }
        }
    }

    /** 解析 "unu-app-YYYY-MM-DD.txt"/"unu-mpv-YYYY-MM-DD.txt" 文件名的日期; 非法命名返回 null(不参与保留期清理)。 */
    private fun parseLogNameDate(name: String): LocalDate? {
        val withoutPrefix = when {
            name.startsWith("unu-app-") -> name.removePrefix("unu-app-")
            name.startsWith("unu-mpv-") -> name.removePrefix("unu-mpv-")
            else -> return null
        }
        if (!withoutPrefix.endsWith(".txt")) return null
        return runCatching { LocalDate.parse(withoutPrefix.removeSuffix(".txt"), DATE_FORMAT) }.getOrNull()
    }

    private fun droppedCounter(source: Source): AtomicLong =
        if (source == Source.APP) droppedApp else droppedMpv

    private fun formatDate(timestampMillis: Long): String =
        DATE_FORMAT.format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()))

    private fun formatTimestamp(timestampMillis: Long): String =
        TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()))

    private fun isOwnedLogName(name: String): Boolean =
        name.startsWith("unu-app-") || name.startsWith("unu-mpv-")

    private class StreamSlot {
        var stream: OutputStream? = null
        var fileName: String? = null

        fun flushOrReset() {
            try {
                stream?.flush()
            } catch (_: Throwable) {
                close()
            }
        }

        fun close() {
            runCatching { stream?.close() }
            stream = null
            fileName = null
        }
    }

    private data class DirectoryConfig(val generation: Long, val path: String?)

    private sealed interface QueueItem {
        data class Entry(
            val config: DirectoryConfig,
            val timestampMillis: Long,
            val source: Source,
            val level: String,
            val tagField: String,
            val text: String,
        ) : QueueItem
        data class SetDirectory(val config: DirectoryConfig) : QueueItem
        data class Clear(val config: DirectoryConfig, val response: CompletableDeferred<Long>) : QueueItem
        data class Size(val config: DirectoryConfig, val response: CompletableDeferred<Long>) : QueueItem
    }

    private enum class Source { APP, MPV }
}
