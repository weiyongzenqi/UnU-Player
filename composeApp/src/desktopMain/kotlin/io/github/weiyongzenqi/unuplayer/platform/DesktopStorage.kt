package io.github.weiyongzenqi.unuplayer.platform

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.platform.Storage
import io.github.weiyongzenqi.unuplayer.core.platform.StorageBatch
import io.github.weiyongzenqi.unuplayer.core.platform.StorageBatchValue
import io.github.weiyongzenqi.unuplayer.core.platform.StorageSnapshot

/**
 * 桌面 Storage 实现，对应 androidMain 的 AndroidStorage。
 *
 * 设置统一写到 `%LOCALAPPDATA%/UnU-Player/data/settings.properties`，不使用注册表。
 *
 * 各 suspend 方法内部一律以 [withContext] 切到 [Dispatchers.IO] 再做文件 IO, 避免 UI 侧
 * `rememberCoroutineScope().launch { settingsRepository.update{} }` 在 EDT 上做阻塞写入
 * (Windows Defender 扫描临时文件时会造成 10-100ms 卡顿)。底层 [DesktopSettingsFileStore] 以其
 * JVM 监视器锁保证读-改-写原子性与快照语义(并发 update 不丢键, 见 CR-002); 该锁随调用进入 IO
 * 线程照常工作, 语义不变。
 */
class DesktopStorage : Storage {

    private val store: DesktopSettingsFileStore

    constructor() {
        store = DesktopSettingsStores.shared
    }

    internal constructor(store: DesktopSettingsFileStore) {
        this.store = store
    }

    override suspend fun readSnapshot(): StorageSnapshot =
        withContext(Dispatchers.IO) { StorageSnapshot(store.snapshot()) }

    override suspend fun getString(key: String, default: String?): String? =
        withContext(Dispatchers.IO) { store.getString(key, default) }

    override suspend fun putString(key: String, value: String) {
        withContext(Dispatchers.IO) { store.putString(key, value) }
    }

    override suspend fun getBoolean(key: String, default: Boolean): Boolean =
        withContext(Dispatchers.IO) { store.getBoolean(key, default) }

    override suspend fun putBoolean(key: String, value: Boolean) {
        withContext(Dispatchers.IO) { store.putBoolean(key, value) }
    }

    override suspend fun getInt(key: String, default: Int): Int =
        withContext(Dispatchers.IO) { store.getInt(key, default) }

    override suspend fun putInt(key: String, value: Int) {
        withContext(Dispatchers.IO) { store.putInt(key, value) }
    }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) { store.remove(key) }
    }

    override suspend fun edit(block: StorageBatch.() -> Unit) {
        // 批量内容构建是纯内存操作, 仅最终 putAll 落盘; 只把落盘切到 IO, 读-改-写原子性与
        // 快照语义由 DesktopSettingsFileStore 的锁保证, 并发 update 不丢键(CR-002)。
        val batch = StorageBatch().apply(block)
        val updates = batch.changes.mapValues { (_, value) ->
            when (value) {
                is StorageBatchValue.StringValue -> value.value
                is StorageBatchValue.BooleanValue -> value.value.toString()
                is StorageBatchValue.IntValue -> value.value.toString()
                StorageBatchValue.Removed -> null
            }
        }
        withContext(Dispatchers.IO) { store.putAll(updates) }
    }
}

internal object DesktopSettingsStores {
    const val NOTIFICATION_PREFIX = "notification."

    val shared: DesktopSettingsFileStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DesktopSettingsFileStore(DesktopAppDirectories.settingsFile)
    }
}

/** 小型、线程安全、原子替换的 properties 存储。 */
internal class DesktopSettingsFileStore(settingsFile: Path) {
    private val file = settingsFile.toAbsolutePath().normalize()
    private val lock = Any()
    private val values = Properties()

    init {
        synchronized(lock) {
            loadLocked()
        }
    }

    fun getString(key: String, default: String? = null): String? = synchronized(lock) {
        values.getProperty(key) ?: default
    }

    fun snapshot(): Map<String, String> = synchronized(lock) {
        values.stringPropertyNames().associateWith(values::getProperty)
    }

    fun putString(key: String, value: String) = synchronized(lock) {
        mutateLocked(key, value)
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: default

    fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())

    fun getInt(key: String, default: Int = 0): Int = getString(key)?.toIntOrNull() ?: default

    fun putInt(key: String, value: Int) = putString(key, value.toString())

    /**
     * 一次原子写入一组设置；null 表示删除。
     *
     * 窗口位置包含 x/y/宽/高/最大化五个彼此关联的值，逐项落盘会产生中间态，
     * 也会把一次拖动结束放大为多次临时文件替换，因此提供批量入口。
     */
    fun putAll(updates: Map<String, String?>) = synchronized(lock) {
        if (updates.isEmpty()) return@synchronized
        val previous = Properties().apply { putAll(this@DesktopSettingsFileStore.values) }
        updates.forEach { (key, value) ->
            if (value == null) values.remove(key) else values.setProperty(key, value)
        }
        try {
            saveLocked()
        } catch (error: Throwable) {
            values.clear()
            values.putAll(previous)
            throw error
        }
    }

    fun remove(key: String) = synchronized(lock) {
        val previous = values.remove(key) ?: return@synchronized
        try {
            saveLocked()
        } catch (error: Throwable) {
            values[key] = previous
            throw error
        }
    }

    private fun loadLocked() {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) {
            "桌面设置路径不是安全的普通文件: $file"
        }
        Files.newInputStream(file, StandardOpenOption.READ).buffered().use(values::load)
    }

    private fun mutateLocked(key: String, value: String) {
        val hadPrevious = values.containsKey(key)
        val previous = values.setProperty(key, value)
        try {
            saveLocked()
        } catch (error: Throwable) {
            if (hadPrevious && previous != null) values[key] = previous else values.remove(key)
            throw error
        }
    }

    private fun saveLocked() {
        val parent = requireNotNull(file.parent) { "桌面设置文件缺少父目录: $file" }
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".settings-", ".tmp")
        try {
            Files.newOutputStream(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).buffered().use { output ->
                values.store(output, "UnU-Player desktop settings")
            }
            try {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }
}
