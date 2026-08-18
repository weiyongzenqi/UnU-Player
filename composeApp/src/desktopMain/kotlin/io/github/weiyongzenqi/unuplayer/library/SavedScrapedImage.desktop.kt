package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal actual suspend fun saveScrapedImageModel(
    platformContext: Any,
    model: Any,
    fileStem: String,
): String = withContext(Dispatchers.IO) {
    val source = when (model) {
        is File -> model
        is String -> if (model.startsWith("file:", ignoreCase = true)) File(URI(model)) else File(model)
        is ByteArray -> model
        else -> error("不支持的图片来源")
    }
    fun openSourceStream(): InputStream = when (source) {
        is File -> source.inputStream()
        is ByteArray -> ByteArrayInputStream(source)
        else -> error("不支持的图片来源")
    }
    check(
        (source is File && source.isFile && source.length() > 0L) ||
            (source is ByteArray && source.isNotEmpty()),
    ) { "图片文件不可读" }
    val header = openSourceStream().use { input -> ByteArray(16).also { input.read(it) } }
    val format = detectSavedImageFormat(header)
    val directory = File(System.getProperty("user.home"), "Pictures/UnU-Player").apply {
        check(exists() || mkdirs()) { "无法创建图片目录" }
    }
    val destination = File(directory, savedImageDisplayName(fileStem, format))
    val part = File(directory, ".${destination.name}.part")
    try {
        openSourceStream().use { input -> part.outputStream().use { output -> input.copyTo(output) } }
        check(part.length() > 0L) { "图片内容为空" }
        try {
            Files.move(
                part.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        destination.absolutePath
    } finally {
        part.delete()
    }
}
