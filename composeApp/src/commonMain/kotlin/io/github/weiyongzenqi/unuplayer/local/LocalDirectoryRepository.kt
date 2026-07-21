package io.github.weiyongzenqi.unuplayer.local

import kotlinx.serialization.Serializable

/** 已添加的本地目录。uri: Android 为 SAF content:// tree URI; 桌面为目录绝对路径。 */
@Serializable
data class LocalDirectory(
    val uri: String,
    val name: String,
)

/**
 * 本地目录仓库: 持久化已添加的本地目录列表(跨平台)。
 *
 * Android 用 SAF tree URI(content://); 桌面用目录绝对路径。
 * key = "local_directories", JSON 持久化(平台实现注入 Storage)。
 */
interface LocalDirectoryRepository {
    suspend fun loadAll(): List<LocalDirectory>
    /** 添加目录(Android: SAF tree URI 字符串; 桌面: 绝对路径)。返回新列表。 */
    suspend fun add(uri: String): List<LocalDirectory>
    suspend fun remove(uri: String): List<LocalDirectory>
}
