package io.github.weiyongzenqi.unuplayer.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.weiyongzenqi.unuplayer.core.platform.Storage

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

    override suspend fun loadAll(): List<LocalDirectory> {
        val raw = storage.getString(key) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }
            .getOrDefault(emptyList())
    }

    /** 添加 SAF tree URI 字符串并 takePersistableUriPermission(保证重启后仍可访问)。 */
    override suspend fun add(uri: String): List<LocalDirectory> = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(uri)
        runCatching {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
        val name = DocumentFile.fromTreeUri(context, treeUri)?.name
            ?: treeUri.lastPathSegment?.substringAfterLast('/') ?: treeUri.toString()
        val current = loadAll().toMutableList()
        if (current.none { it.uri == uri }) {
            current.add(LocalDirectory(uri = uri, name = name))
        }
        val newList = current.toList()
        save(newList)
        return@withContext newList
    }

    override suspend fun remove(uri: String): List<LocalDirectory> = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = Uri.parse(uri)
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(parsed, flags)
        }
        val newList = loadAll().filterNot { it.uri == uri }
        save(newList)
        return@withContext newList
    }

    private suspend fun save(list: List<LocalDirectory>) {
        storage.putString(key, json.encodeToString(listSerializer, list))
    }
}
