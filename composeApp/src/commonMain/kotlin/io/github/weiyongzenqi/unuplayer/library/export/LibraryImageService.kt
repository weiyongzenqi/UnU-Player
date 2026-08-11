package io.github.weiyongzenqi.unuplayer.library.export

/** PosterCache 内一个文件条目(basename + 完整绝对路径)。 */
data class ImageFileEntry(val basename: String, val absolutePath: String)

/**
 * 媒体库导出/导入的图片服务(平台注入)。
 *
 * 平台实现(androidMain/desktopMain)各自持有 PosterCache 单例:
 * - 导出: 列某 showKey 子目录下的文件(集照 ep<id>.jpg 收集用)。
 * - 导入: 写 showKey 子目录图片(经 PosterCache 安全目录/原子发布), 集照走 episodeThumbFile。
 * 返回写入后绝对路径, 供导入流程回写 DB(local_poster_path/local_fanart_path/local_thumb_path)。
 */
interface LibraryImageService {
    /** 导出: 列某 showKey 子目录下的文件(完整绝对路径); 目录不存在返回空。 */
    suspend fun listShowFiles(showKey: String): List<ImageFileEntry>

    /** 导入: 写 showKey 子目录下的图片。返回写入后绝对路径; 失败返回 null。 */
    suspend fun writeShowImage(showKey: String, basename: String, bytes: ByteArray): String?

    /** 导入: 写集照 ep<id>.jpg。返回写入后绝对路径; 失败返回 null。 */
    suspend fun writeEpisodeThumb(showKey: String, episodeId: Long, bytes: ByteArray): String?
}