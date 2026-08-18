package io.github.weiyongzenqi.unuplayer.library.export

/** PosterCache 内一个文件条目(basename + 完整绝对路径)。 */
data class ImageFileEntry(val basename: String, val absolutePath: String)

/** 导入写入结果；[created] 仅表示目标文件由本轮写入首次创建，可用于失败补偿。 */
data class ImageWriteResult(val absolutePath: String, val created: Boolean)

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

    /** 导入: 写 showKey 子目录下的图片。返回路径和本轮所有权; 失败返回 null。 */
    suspend fun writeShowImage(showKey: String, basename: String, bytes: ByteArray): ImageWriteResult?

    /** 导入回滚: 仅删除确属 showKey 缓存目录且路径匹配的图片。 */
    suspend fun deleteShowImage(showKey: String, absolutePath: String): Boolean = false

    /** 导入: 原子写集照 ep<id>.jpg。返回路径和本轮所有权; 失败返回 null。 */
    suspend fun writeEpisodeThumb(showKey: String, episodeId: Long, bytes: ByteArray): ImageWriteResult?

    /** 一次图片恢复结束后按当前配置强制整理容量，不得修改配置上限。 */
    suspend fun finishRestore() = Unit
}
