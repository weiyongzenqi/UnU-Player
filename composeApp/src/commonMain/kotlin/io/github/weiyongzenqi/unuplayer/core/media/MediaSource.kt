package io.github.weiyongzenqi.unuplayer.core.media

import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile

/**
 * 媒体来源抽象。统一 WebDAV/SMB/Emby/本地/外部拉起, 播放器只认 PlayableMedia。
 *
 * P0 只实现 WebDavSource, 其他来源 P2 补 actual。
 * 继承 AutoCloseable 用于释放底层资源(HttpClient 等)。
 */
interface MediaSource : AutoCloseable {
    val kind: MediaSourceKind
    val displayName: String

    /** 列目录。返回子项(文件+目录)。 */
    suspend fun listFolder(path: String): List<MediaEntry>

    /** 把目录项解析为可播放媒体(含认证 URL/headers)。 */
    suspend fun resolvePlayMedia(entry: MediaEntry): PlayableMedia

    /** 测试连通性。 */
    suspend fun testConnection(): Boolean

    /** 全量列目录(不过滤视频/nfo/图片, 海报墙扫描用)。默认回退 listFolder。 */
    suspend fun listFolderAll(path: String): List<MediaEntry> = listFolder(path)

    /** 读小文件文本(nfo/ini 解析用)。不存在/失败/不支持返回 null。 */
    suspend fun readTextFile(path: String): String? = null

    /** 下载文件到本地(图片缓存用)。成功 true。默认 false(不支持)。 */
    suspend fun downloadToFile(path: String, dest: PlatformFile): Boolean = false

    /** 删除文件/目录(删番剧源文件用, WebDAV DELETE / 本地 DocumentFile.delete)。成功 true; 不支持/失败 false。默认 false。 */
    suspend fun deleteFile(path: String): Boolean = false
}
