package io.github.weiyongzenqi.unuplayer.core.platform

/** 跨平台文件目标标识；具体文件系统操作由平台 actual 提供。 */
data class PlatformFile(val path: String)

expect fun deletePlatformFile(path: String): Boolean

expect class PlatformFileOutputStream {
    fun write(bytes: ByteArray, offset: Int, length: Int)
    fun close()
}

expect fun openPlatformFileOutputStream(path: String): PlatformFileOutputStream
