package io.github.weiyongzenqi.unuplayer.local

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
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
 * 本地文件来源(SAF 实现 MediaSource)。
 *
 * 访问方式: SAF tree URI(ACTION_OPEN_DOCUMENT_TREE)。列目录经 DocumentsContract 单 cursor
 * 一次取全部列(C-04: 原 DocumentFile.listFiles() 逐条 getter 每字段一次 Binder 往返, N+1 查询);
 * 删除/探测仍用 DocumentFile(低频路径)。无需 MANAGE_EXTERNAL_STORAGE 权限。
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
            tmdbId = entry.tmdbId,
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
        )
    }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        DocumentFile.fromTreeUri(context, treeUri)?.canRead() == true
    }

    /** 全量列目录(不过滤视频, 海报墙扫描用)。 */
    override suspend fun listFolderAll(path: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        listDirectory(path, videosOnly = false)
    }

    /**
     * SAF 元数据访问可能触发 provider 查询，只能从 [Dispatchers.IO] 调用。
     *
     * C-04: 用 [DocumentsContract.buildChildDocumentsUriUsingTree] + 单 cursor 一次取全部列,
     * 取代 DocumentFile.listFiles() 逐 child 读 getter(name/isDirectory/length/lastModified/type
     * 每个 getter 各一次 ContentResolver Binder 往返, 100 条目约 600+ 次查询)。
     * 字段语义与原 DocumentFile 版逐一对齐:
     * - name        <- COLUMN_DISPLAY_NAME(缺失记空串)
     * - path        <- buildDocumentUriUsingTree(treeUri, COLUMN_DOCUMENT_ID)(即原 child.uri)
     * - isDirectory <- COLUMN_MIME_TYPE == MIME_TYPE_DIR
     * - size        <- COLUMN_SIZE(目录恒 0, 与原 isFile 才取 length 一致; 缺列/null 记 0)
     * - lastModified<- COLUMN_LAST_MODIFIED(缺列/null 记 0)
     * - mimeType    <- COLUMN_MIME_TYPE
     * 排序/过滤保持原行为(目录在前, 组内按名; videosOnly 只保留目录与视频文件)。
     */
    private fun listDirectory(path: String, videosOnly: Boolean): List<MediaEntry> {
        val parentUri = if (path.isEmpty()) treeUri else Uri.parse(path)
        // 子目录 path 是 document URI, 取其 docId; path 为 tree URI 时回退 treeDocId(调用方可能直传 treeUri)。
        val parentDocId = runCatching { DocumentsContract.getDocumentId(parentUri) }
            .recoverCatching { DocumentsContract.getTreeDocumentId(parentUri) }
            .getOrNull() ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val resolver = context.contentResolver
        // query 对非目录父节点返回 null(原实现 isDirectory 预检的等价行为 -> 空列表)。
        val cursor = runCatching {
            resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
        }.getOrNull() ?: return emptyList()
        return cursor.use { c ->
            val idIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idIndex < 0) return@use emptyList()
            buildList {
                while (c.moveToNext()) {
                    val docId = c.getString(idIndex) ?: continue
                    val childName = if (nameIndex >= 0) c.getString(nameIndex) else null
                    val childType = if (mimeIndex >= 0) c.getString(mimeIndex) else null
                    val childIsDirectory = childType == DocumentsContract.Document.MIME_TYPE_DIR
                    if (videosOnly && !childIsDirectory && (childName == null || !isVideoFile(childName))) {
                        continue
                    }
                    add(
                        MediaEntry(
                            name = childName ?: "",
                            path = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString(),
                            isDirectory = childIsDirectory,
                            size = if (childIsDirectory) 0L else c.longValueOrZero(sizeIndex),
                            lastModified = c.longValueOrZero(modifiedIndex),
                            mimeType = childType,
                        )
                    )
                }
            }
        }.sortedWith(compareByDescending<MediaEntry> { it.isDirectory }.thenBy { it.name })
    }

    /** 部分 SAF 实现不返回 SIZE/LAST_MODIFIED 列: 列缺失或值为 null 一律记 0(与原 DocumentFile getter 缺省一致)。 */
    private fun Cursor.longValueOrZero(index: Int): Long =
        if (index < 0 || isNull(index)) 0L else getLong(index)

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

        /** 列目录单 cursor 投影: 一次查询取全部所需字段(C-04)。 */
        val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
