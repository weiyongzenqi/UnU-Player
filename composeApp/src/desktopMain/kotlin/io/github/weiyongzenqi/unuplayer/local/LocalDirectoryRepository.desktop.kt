package io.github.weiyongzenqi.unuplayer.local

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.weiyongzenqi.unuplayer.core.platform.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 桌面实现: 目录绝对路径(java.io.File), 持久化到 Storage。 */
class DesktopLocalDirectoryRepository(
    private val storage: Storage,
) : LocalDirectoryRepository {
    private val key = "local_directories"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(LocalDirectory.serializer())
    private val mutationMutex = Mutex()

    override suspend fun loadAll(): List<LocalDirectory> = withContext(Dispatchers.IO) {
        mutationMutex.withLock { loadAllLocked() }
    }

    private suspend fun loadAllLocked(): List<LocalDirectory> {
        val raw = storage.getString(key) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }
            .getOrDefault(emptyList())
    }

    override suspend fun add(uri: String): List<LocalDirectory> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val dir = File(uri)
            val name = dir.name.ifBlank { uri }
            val current = loadAllLocked().toMutableList()
            if (current.none { it.uri == uri }) {
                current.add(LocalDirectory(uri = uri, name = name))
            }
            val newList = current.toList()
            saveLocked(newList)
            newList
        }
    }

    override suspend fun remove(uri: String): List<LocalDirectory> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val newList = loadAllLocked().filterNot { it.uri == uri }
            saveLocked(newList)
            newList
        }
    }

    private suspend fun saveLocked(list: List<LocalDirectory>) {
        storage.putString(key, json.encodeToString(listSerializer, list))
    }
}
