package io.github.weiyongzenqi.unuplayer.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.weiyongzenqi.unuplayer.core.platform.Storage
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabaseProvider

/**
 * Android 实现: SAF tree URI(content://), 配合 takePersistableUriPermission 跨重启访问。
 * 无需 MANAGE_EXTERNAL_STORAGE 敏感权限。
 */
class AndroidLocalDirectoryRepository(
    private val storage: Storage,
    private val context: Context,
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

    /** 添加 SAF tree URI；授权与本地目录持久化由进程级协调器串行提交。 */
    override suspend fun add(uri: String): List<LocalDirectory> = withContext(Dispatchers.IO) {
        AndroidPersistableUriGrantCoordinator.addReference(
            context = context,
            uri = uri,
            hasAnyReference = { hasAnyReference(uri) },
        ) {
            mutationMutex.withLock {
                val treeUri = Uri.parse(uri)
                val name = DocumentFile.fromTreeUri(context, treeUri)?.name
                    ?: treeUri.lastPathSegment?.substringAfterLast('/') ?: treeUri.toString()
                val current = loadAllLocked().toMutableList()
                if (current.none { it.uri == uri }) {
                    current.add(LocalDirectory(uri = uri, name = name))
                }
                val newList = current.toList()
                saveLocked(newList)
                newList
            }
        }
    }

    override suspend fun remove(uri: String): List<LocalDirectory> = withContext(Dispatchers.IO) {
        AndroidPersistableUriGrantCoordinator.removeReference(
            context = context,
            uri = uri,
            hasAnyReference = { hasAnyReference(uri) },
        ) {
            mutationMutex.withLock {
                val newList = loadAllLocked().filterNot { it.uri == uri }
                saveLocked(newList)
                newList
            }
        }
    }

    private suspend fun hasAnyReference(uri: String): Boolean {
        if (loadAllLocked().any { it.uri == uri }) return true
        return UnuDatabaseProvider.get(context).scrapedQueries.listLibraries().executeAsList()
            .any { it.local_uri == uri }
    }

    private suspend fun saveLocked(list: List<LocalDirectory>) {
        storage.putString(key, json.encodeToString(listSerializer, list))
    }
}
