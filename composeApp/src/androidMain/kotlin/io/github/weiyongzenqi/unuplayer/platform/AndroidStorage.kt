package io.github.weiyongzenqi.unuplayer.platform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import io.github.weiyongzenqi.unuplayer.core.platform.Storage
import io.github.weiyongzenqi.unuplayer.core.platform.StorageBatch
import io.github.weiyongzenqi.unuplayer.core.platform.StorageBatchValue
import io.github.weiyongzenqi.unuplayer.core.platform.StorageSnapshot

/**
 * 顶层扩展属性, 通过 preferencesDataStore 委托创建 DataStore<Preferences> 实例。
 *
 * preferencesDataStore 是线程安全的, 内部使用单例模式确保每个 name
 * 只创建一个 DataStore 实例, 避免重复打开文件导致的竞态。
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "unu_settings")

/**
 * Android 平台 Storage 实现, 基于 AndroidX DataStore Preferences。
 *
 * DataStore 使用协程 + 事务性更新保证线程安全:
 * - data 属性返回 Flow<Preferences>, 每次读取通过 map + first 拿到快照值。
 * - edit 块内的修改是原子事务, 在协程调度器上串行执行, 天然避免并发写冲突。
 */
class AndroidStorage(private val context: Context) : Storage {

    override suspend fun readSnapshot(): StorageSnapshot {
        val values = context.dataStore.data.first().asMap()
            .mapKeys { (key, _) -> key.name }
        return StorageSnapshot(values)
    }

    override suspend fun getString(key: String, default: String?): String? {
        // dataStore.data 返回 Flow<Preferences>, first() 挂起直到拿到第一个值
        // stringPreferencesKey(key) 创建类型安全的 Preferences.Key<String>
        return context.dataStore.data
            .map { preferences -> preferences[stringPreferencesKey(key)] }
            .first() ?: default
    }

    override suspend fun putString(key: String, value: String) {
        // edit 是 suspend 且原子操作: 内部通过协程加锁, 保证读写一致性
        context.dataStore.edit { preferences ->
            preferences[stringPreferencesKey(key)] = value
        }
    }

    override suspend fun getBoolean(key: String, default: Boolean): Boolean {
        return context.dataStore.data
            .map { preferences -> preferences[booleanPreferencesKey(key)] }
            .first() ?: default
    }

    override suspend fun putBoolean(key: String, value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[booleanPreferencesKey(key)] = value
        }
    }

    override suspend fun getInt(key: String, default: Int): Int {
        return context.dataStore.data
            .map { preferences -> preferences[intPreferencesKey(key)] }
            .first() ?: default
    }

    override suspend fun putInt(key: String, value: Int) {
        context.dataStore.edit { preferences ->
            preferences[intPreferencesKey(key)] = value
        }
    }

    override suspend fun remove(key: String) {
        // DataStore 的 key 是类型安全的, 运行时无法得知存储时使用的 key 类型。
        // 因此对 string/boolean/int 三种 key 都执行 remove,
        // 不存在的 key 调用 remove 是安全无操作的 (no-op)。
        context.dataStore.edit { preferences ->
            preferences.remove(stringPreferencesKey(key))
            preferences.remove(booleanPreferencesKey(key))
            preferences.remove(intPreferencesKey(key))
        }
    }

    override suspend fun edit(block: StorageBatch.() -> Unit) {
        val batch = StorageBatch().apply(block)
        context.dataStore.edit { preferences ->
            batch.changes.forEach { (key, value) ->
                when (value) {
                    is StorageBatchValue.StringValue -> preferences[stringPreferencesKey(key)] = value.value
                    is StorageBatchValue.BooleanValue -> preferences[booleanPreferencesKey(key)] = value.value
                    is StorageBatchValue.IntValue -> preferences[intPreferencesKey(key)] = value.value
                    StorageBatchValue.Removed -> {
                        preferences.remove(stringPreferencesKey(key))
                        preferences.remove(booleanPreferencesKey(key))
                        preferences.remove(intPreferencesKey(key))
                    }
                }
            }
        }
    }
}
