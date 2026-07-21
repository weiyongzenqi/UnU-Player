package io.github.weiyongzenqi.unuplayer.local

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.weiyongzenqi.unuplayer.core.platform.Storage
import java.io.File

/** 桌面实现: 目录绝对路径(java.io.File), 持久化到 Storage。 */
class DesktopLocalDirectoryRepository(
    private val storage: Storage,
) : LocalDirectoryRepository {
    private val key = "local_directories"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(LocalDirectory.serializer())

    override suspend fun loadAll(): List<LocalDirectory> {
        val raw = storage.getString(key) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }
            .getOrDefault(emptyList())
    }

    override suspend fun add(uri: String): List<LocalDirectory> {
        val dir = File(uri)
        val name = dir.name.ifBlank { uri }
        val current = loadAll().toMutableList()
        if (current.none { it.uri == uri }) {
            current.add(LocalDirectory(uri = uri, name = name))
        }
        val newList = current.toList()
        save(newList)
        return newList
    }

    override suspend fun remove(uri: String): List<LocalDirectory> {
        val newList = loadAll().filterNot { it.uri == uri }
        save(newList)
        return newList
    }

    private suspend fun save(list: List<LocalDirectory>) {
        storage.putString(key, json.encodeToString(listSerializer, list))
    }
}
