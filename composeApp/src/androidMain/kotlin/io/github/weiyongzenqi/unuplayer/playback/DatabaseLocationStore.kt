package io.github.weiyongzenqi.unuplayer.playback

import android.content.Context

/**
 * 数据库位置偏好(internal / external), 用 SharedPreferences 同步存。
 *
 * 用 SharedPreferences(非 DataStore) 以便 [UnuDatabaseProvider.get] 同步读取选路径
 * (DataStore 异步, 单例 get 同步场景不便)。
 *
 * - [INTERNAL]: /data/data/<pkg>/databases/ (app 私有, 默认, 隐私高)
 * - [EXTERNAL]: /sdcard/Android/data/<pkg>/files/databases/ (用户可见可管理, 隐私下降)
 *
 * WebDAV 密码等敏感信息存 DataStore(独立于数据库, 始终在 /data), 不随库迁移。
 */
object DatabaseLocationStore {
    const val INTERNAL = "internal"
    const val EXTERNAL = "external"
    private const val PREFS = "unu_db"
    private const val KEY = "location"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, INTERNAL) ?: INTERNAL

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }
}
