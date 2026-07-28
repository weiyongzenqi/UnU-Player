package io.github.weiyongzenqi.unuplayer.core.platform

/** 跨平台文件目标标识；具体文件系统操作由平台 actual 提供。 */
data class PlatformFile(val path: String)

expect fun deletePlatformFile(path: String): Boolean

/** 检查路径所指文件是否存在(供集照本地路径有效性校验等跨平台场景)。 */
expect fun platformFileExists(path: String): Boolean

/**
 * 取路径所指文件的字节长度; 不可读(不存在/无权限/IO 错误)返回 -1。
 * 供集照存量黑图自愈筛选(C-02: 过小文件视为黑图固化需重生成)。
 */
expect fun platformFileLength(path: String): Long

expect class PlatformFileOutputStream {
    fun write(bytes: ByteArray, offset: Int, length: Int)
    fun close()
}

expect fun openPlatformFileOutputStream(path: String): PlatformFileOutputStream
