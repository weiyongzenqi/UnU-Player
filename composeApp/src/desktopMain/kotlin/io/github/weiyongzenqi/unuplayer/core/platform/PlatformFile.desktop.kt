package io.github.weiyongzenqi.unuplayer.core.platform

import java.io.File
import java.io.FileOutputStream

actual fun deletePlatformFile(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)

actual fun platformFileExists(path: String): Boolean = File(path).exists()

// File.length() 对不存在文件返回 0 不抛异常; 无权限等异常经 runCatching 归一为 -1(均视为无效)。
actual fun platformFileLength(path: String): Long = runCatching { File(path).length() }.getOrDefault(-1L)

actual class PlatformFileOutputStream internal constructor(
    private val delegate: FileOutputStream,
) {
    actual fun write(bytes: ByteArray, offset: Int, length: Int) {
        delegate.write(bytes, offset, length)
    }

    actual fun close() {
        delegate.close()
    }
}

actual fun openPlatformFileOutputStream(path: String): PlatformFileOutputStream {
    val file = File(path)
    file.parentFile?.mkdirs()
    return PlatformFileOutputStream(FileOutputStream(file))
}
