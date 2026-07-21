package io.github.weiyongzenqi.unuplayer.core.platform

internal sealed interface StorageBatchValue {
    data class StringValue(val value: String) : StorageBatchValue
    data class BooleanValue(val value: Boolean) : StorageBatchValue
    data class IntValue(val value: Int) : StorageBatchValue
    data object Removed : StorageBatchValue
}

class StorageBatch internal constructor() {
    internal val changes = LinkedHashMap<String, StorageBatchValue>()

    fun putString(key: String, value: String) {
        changes[key] = StorageBatchValue.StringValue(value)
    }

    fun putBoolean(key: String, value: Boolean) {
        changes[key] = StorageBatchValue.BooleanValue(value)
    }

    fun putInt(key: String, value: Int) {
        changes[key] = StorageBatchValue.IntValue(value)
    }

    fun remove(key: String) {
        changes[key] = StorageBatchValue.Removed
    }
}

/** 一次持久化读取产生的不可变快照，避免按字段重复建立平台存储读取流。 */
class StorageSnapshot internal constructor(values: Map<String, Any?>) {
    private val values = values.toMap()

    fun getString(key: String, default: String? = null): String? = values[key] as? String ?: default

    fun getBoolean(key: String, default: Boolean = false): Boolean = when (val value = values[key]) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull() ?: default
        else -> default
    }

    fun getInt(key: String, default: Int = 0): Int = when (val value = values[key]) {
        is Int -> value
        is String -> value.toIntOrNull() ?: default
        else -> default
    }
}

/**
 * 持久化抽象。接口在 commonMain, 实现在 platformMain。
 * Android 用 DataStore Preferences 实现(替代 SharedPreferences, 协程友好)。
 *
 * 用于: WebDAV 连接列表、播放设置、番剧识别开关等。
 */
interface Storage {
    /** 平台可提供单次一致快照；旧的轻量实现可返回 null 并沿用逐字段读取。 */
    suspend fun readSnapshot(): StorageSnapshot? = null

    suspend fun getString(key: String, default: String? = null): String?
    suspend fun putString(key: String, value: String)
    suspend fun getBoolean(key: String, default: Boolean = false): Boolean
    suspend fun putBoolean(key: String, value: Boolean)
    suspend fun getInt(key: String, default: Int = 0): Int
    suspend fun putInt(key: String, value: Int)
    suspend fun remove(key: String)

    suspend fun edit(block: StorageBatch.() -> Unit) {
        val batch = StorageBatch().apply(block)
        batch.changes.forEach { (key, value) ->
            when (value) {
                is StorageBatchValue.StringValue -> putString(key, value.value)
                is StorageBatchValue.BooleanValue -> putBoolean(key, value.value)
                is StorageBatchValue.IntValue -> putInt(key, value.value)
                StorageBatchValue.Removed -> remove(key)
            }
        }
    }
}
