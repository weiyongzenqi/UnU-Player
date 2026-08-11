package io.github.weiyongzenqi.unuplayer.library.export

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Android/JVM zip 写入: java.util.zip 流式。 */
actual class LibraryZipOutput actual constructor(path: String) {
    private val file = File(path)
    private var zipOut: ZipOutputStream? = null
    private var copiedFileBytes = 0L

    init {
        file.parentFile?.mkdirs()
        zipOut = ZipOutputStream(FileOutputStream(file))
    }

    actual fun putText(name: String, text: String) {
        val out = requireNotNull(zipOut)
        out.putNextEntry(ZipEntry(name))
        out.write(text.encodeToByteArray())
        out.closeEntry()
    }

    actual fun putFile(name: String, sourcePath: String, maxBytes: Long, maxTotalBytes: Long) {
        require(maxBytes > 0L && maxTotalBytes > 0L) { "ZIP 文件条目大小上限无效" }
        val out = requireNotNull(zipOut)
        val source = File(sourcePath)
        if (!source.isFile) return
        out.putNextEntry(ZipEntry(name))
        FileInputStream(source).use { input ->
            val buffer = ByteArray(64 * 1024)
            var entryBytes = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                entryBytes += count
                require(entryBytes <= maxBytes) { "ZIP 文件条目超过大小上限" }
                require(copiedFileBytes + count <= maxTotalBytes) { "ZIP 文件条目总量超过大小上限" }
                out.write(buffer, 0, count)
                copiedFileBytes += count
            }
        }
        out.closeEntry()
    }

    actual fun finish() {
        zipOut?.close()
        zipOut = null
    }
}

/** Android/JVM zip 读取: java.util.zip 流式。 */
actual class LibraryZipInput actual constructor(path: String) {
    private val file = File(path)
    private var zipIn: ZipInputStream? = null
    private var current: ZipEntry? = null

    init {
        zipIn = ZipInputStream(FileInputStream(file))
    }

    actual fun nextEntry(): String? {
        val inStream = requireNotNull(zipIn)
        current = inStream.nextEntry
        return current?.name
    }

    actual fun readEntryBytes(maxBytes: Long): ByteArray {
        require(maxBytes > 0L) { "ZIP 条目大小上限无效" }
        val input = requireNotNull(zipIn)
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "ZIP 条目超过大小上限" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    actual fun skipEntry(maxBytes: Long): Long {
        require(maxBytes > 0L) { "ZIP 条目大小上限无效" }
        val input = requireNotNull(zipIn)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            require(total <= maxBytes) { "ZIP 条目超过大小上限" }
        }
    }

    actual fun close() {
        zipIn?.close()
        zipIn = null
    }
}
