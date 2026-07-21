package io.github.weiyongzenqi.unuplayer.core.platform

import java.io.File
import java.io.FileOutputStream

actual fun deletePlatformFile(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)

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
