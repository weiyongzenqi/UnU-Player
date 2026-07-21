package io.github.weiyongzenqi.unuplayer.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.MediaKeys
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile
import io.github.weiyongzenqi.unuplayer.webdav.isVideoFile

/**
 * 本地文件来源(SAF DocumentFile 实现 MediaSource)。
 *
 * 访问方式: SAF tree URI(ACTION_OPEN_DOCUMENT_TREE), DocumentFile.listFiles() 遍历。
 * 无需 MANAGE_EXTERNAL_STORAGE 权限。
 *
 * path 语义: 用 DocumentFile 的 uri 字符串作为"路径"标识, listFolder(path) 时
 * - path == treeUri: 列根目录
 * - path == 某子目录 uri: 列该子目录
 * 进入子目录时把子目录 uri 作为新 path 传入。
 */
class LocalSource(
    private val context: Context,
    private val treeUri: Uri,
    private val configuredDisplayName: String? = null,
) : MediaSource {

    override val kind: MediaSourceKind = MediaSourceKind.LOCAL
    override val displayName: String =
        configuredDisplayName?.takeIf { it.isNotBlank() }
            ?: treeUri.lastPathSegment?.substringAfterLast('/')
            ?: "本地"

    /** 列目录。path 为 DocumentFile 的 uri 字符串; 空或 treeUri 列根。 */
    override suspend fun listFolder(path: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        listDirectory(path, videosOnly = true)
    }

    /**
     * 解析为可播放媒体。保持稳定的 content:// URI, 由 MpvPlayerEngine 每次 load 时转 fdclose://。
     * HDR reinit 会重新 load 原始 URI, 因而能够重新打开新的文件描述符。
     */
    override suspend fun resolvePlayMedia(entry: MediaEntry): PlayableMedia {
        return PlayableMedia(
            url = entry.path,
            title = entry.name,
            sourceKind = MediaSourceKind.LOCAL,
            // 原始 content:// 同时用于引擎加载与弹幕哈希, 不在来源层打开 fd。
            contentUri = entry.path,
            mediaKey = MediaKeys.local(entry.path),
        )
    }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        DocumentFile.fromTreeUri(context, treeUri)?.canRead() == true
    }

    /** 全量列目录(不过滤视频, 海报墙扫描用)。 */
    override suspend fun listFolderAll(path: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        listDirectory(path, videosOnly = false)
    }

    /** SAF 元数据访问可能触发 provider 查询，只能从 [Dispatchers.IO] 调用。 */
    private fun listDirectory(path: String, videosOnly: Boolean): List<MediaEntry> {
        val dir = if (path.isEmpty()) {
            DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        } else {
            DocumentFile.fromTreeUri(context, Uri.parse(path)) ?: return emptyList()
        }
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            .mapNotNull { child ->
                val childName = child.name
                val childIsDirectory = child.isDirectory
                if (videosOnly && !childIsDirectory && (childName == null || !isVideoFile(childName))) {
                    return@mapNotNull null
                }
                val childIsFile = child.isFile
                val childLength = if (childIsFile) child.length() else 0L
                val childLastModified = child.lastModified()
                val childType = child.type
                MediaEntry(
                    name = childName ?: "",
                    path = child.uri.toString(),
                    isDirectory = childIsDirectory,
                    size = childLength,
                    lastModified = childLastModified,
                    mimeType = childType,
                )
            }
            .sortedWith(compareByDescending<MediaEntry> { it.isDirectory }.thenBy { it.name })
    }

    /** 在 IO 线程流式读取 UTF-8 NFO/INI，硬限制 8 MiB，超限立即拒绝。 */
    override suspend fun readTextFile(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                input.readUtf8TextLimited()
            }
        }.getOrNull()
    }

    /** 在 IO 线程流式下载；失败时删除部分或零字节目标，避免被缓存误判为命中。 */
    override suspend fun downloadToFile(path: String, dest: PlatformFile): Boolean = withContext(Dispatchers.IO) {
        val target = java.io.File(dest.path)
        val succeeded = runCatching {
            target.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(Uri.parse(path))
                ?: return@runCatching false
            input.use {
                target.outputStream().use { output ->
                    it.copyTo(output, bufferSize = IO_BUFFER_BYTES)
                }
            }
            true
        }.getOrDefault(false)
        if (!succeeded) runCatching { target.delete() }
        succeeded
    }

    /** 删除文件/目录(DocumentFile.delete, 目录递归删)。失败(权限/uri 失效)返 false, 调用方走屏蔽兜底。 */
    override suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(path))?.delete() == true
        }.getOrDefault(false)
    }

    override fun close() {
        // LocalSource 无持有资源需要释放(SAF 通过 contentResolver 管理)
    }

    private companion object {
        const val IO_BUFFER_BYTES = 64 * 1024
    }
}
