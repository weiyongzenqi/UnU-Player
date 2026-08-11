package io.github.weiyongzenqi.unuplayer.core.platform

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** 将已完成的图片发布到系统相册；Android 10+ 走 MediaStore，旧版写应用外部图片目录。 */
internal suspend fun publishAndroidImage(
    context: Context,
    displayName: String,
    mimeType: String,
    inputProvider: () -> InputStream,
): String = withContext(Dispatchers.IO) {
    require(displayName.isNotBlank() && '/' !in displayName && '\\' !in displayName) { "图片文件名无效" }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        publishToMediaStore(context, displayName, mimeType, inputProvider)
    } else {
        publishToLegacyPictures(context, displayName, mimeType, inputProvider)
    }
}

private fun publishToMediaStore(
    context: Context,
    displayName: String,
    mimeType: String,
    inputProvider: () -> InputStream,
): String {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/UnU-Player")
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("无法在系统相册中创建图片")
    try {
        resolver.openOutputStream(uri, "w")?.use { output ->
            inputProvider().use { input ->
                check(input.copyTo(output) > 0L) { "图片内容为空" }
            }
        } ?: error("无法写入系统相册")
        check(resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null) > 0) { "无法发布系统相册图片" }
        return "图片/UnU-Player/$displayName"
    } catch (error: Throwable) {
        resolver.delete(uri, null, null)
        throw error
    }
}

private fun publishToLegacyPictures(
    context: Context,
    displayName: String,
    mimeType: String,
    inputProvider: () -> InputStream,
): String {
    val directory = File(
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir,
        "UnU-Player",
    ).apply { check(exists() || mkdirs()) { "无法创建图片目录" } }
    val destination = File(directory, displayName)
    val part = File(directory, ".$displayName.part")
    try {
        inputProvider().use { input ->
            part.outputStream().use { output -> check(input.copyTo(output) > 0L) { "图片内容为空" } }
        }
        check(part.length() > 0L) { "图片内容为空" }
        if (destination.exists() && !destination.delete()) error("无法替换同名图片")
        if (!part.renameTo(destination)) {
            part.copyTo(destination, overwrite = true)
            check(part.delete()) { "无法清理图片临时文件" }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(destination.absolutePath),
            arrayOf(mimeType),
            null,
        )
        return destination.absolutePath
    } finally {
        part.delete()
    }
}
