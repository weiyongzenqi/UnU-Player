package io.github.weiyongzenqi.unuplayer.library

import android.content.Context
import android.net.Uri
import io.github.weiyongzenqi.unuplayer.core.platform.publishAndroidImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

internal actual suspend fun saveScrapedImageModel(
    platformContext: Any,
    model: Any,
    fileStem: String,
): String = withContext(Dispatchers.IO) {
    val context = (platformContext as? Context)?.applicationContext
        ?: error("Android 图片上下文无效")
    val header = openImageInput(context, model).use { input ->
        ByteArray(16).also { bytes -> check(input.read(bytes) > 0) { "图片内容为空" } }
    }
    val format = detectSavedImageFormat(header)
    val displayName = savedImageDisplayName(fileStem, format)
    publishAndroidImage(context, displayName, format.mimeType) {
        openImageInput(context, model)
    }
}

private fun openImageInput(context: Context, model: Any): InputStream = when (model) {
    is File -> model.inputStream()
    is Uri -> context.contentResolver.openInputStream(model) ?: error("无法读取图片")
    is String -> {
        val uri = Uri.parse(model)
        when (uri.scheme?.lowercase()) {
            "content", "android.resource" ->
                context.contentResolver.openInputStream(uri) ?: error("无法读取图片")
            "file" -> File(requireNotNull(uri.path) { "图片路径无效" }).inputStream()
            else -> File(model).inputStream()
        }
    }
    else -> error("不支持的图片来源")
}
