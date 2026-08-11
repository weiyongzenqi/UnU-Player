package io.github.weiyongzenqi.unuplayer.library.export

/**
 * 媒体库导出包 zip 读写层(expect/actual)。
 *
 * commonMain 禁 java.io(CR-016); java.util.zip 属 JVM 专有须 expect/actual(同 SyncGzip 先例)。
 * 两端 actual 用 java.util.zip.ZipOutputStream/ZipInputStream, 入参为绝对路径
 * (Android 经 SAF 前先落 app 私有临时文件, 桌面直接用用户选路径)。
 *
 * putFile 按条目字节流受限写入；导入侧按条目类型受限读取或跳过，大图不进内存。
 */

/** 把多个条目写入 zip 文件(条目名唯一)。 */
expect class LibraryZipOutput(path: String) {
    /** 写文本条目(UTF-8)。 */
    fun putText(name: String, text: String)
    /** 流式复制源文件字节为 zip 条目(sourcePath 不存在则跳过)。 */
    fun putFile(name: String, sourcePath: String, maxBytes: Long, maxTotalBytes: Long)
    /** 完成后关闭 zip 与底层流。 */
    fun finish()
}

/** 顺序读取 zip 文件条目。 */
expect class LibraryZipInput(path: String) {
    /** 当前条目名; 无更多条目返回 null。 */
    fun nextEntry(): String?
    /** 读当前条目全部字节(小文件: manifest/data)。 */
    fun readEntryBytes(maxBytes: Long): ByteArray
    /** 流式丢弃当前条目并返回实际字节数，用于校验无需装入内存的条目。 */
    fun skipEntry(maxBytes: Long): Long
    fun close()
}

const val LIBRARY_EXPORT_MAX_IMAGE_BYTES = 32L * 1024L * 1024L
const val LIBRARY_EXPORT_MAX_TOTAL_IMAGE_BYTES = 512L * 1024L * 1024L
