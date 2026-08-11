package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis

internal data class SavedImageFormat(
    val extension: String,
    val mimeType: String,
)

internal fun detectSavedImageFormat(header: ByteArray): SavedImageFormat {
    fun has(offset: Int, vararg bytes: Int): Boolean = bytes.indices.all { index ->
        header.getOrNull(offset + index)?.toInt()?.and(0xFF) == bytes[index]
    }
    fun hasAscii(offset: Int, value: String): Boolean = value.indices.all { index ->
        header.getOrNull(offset + index)?.toInt()?.and(0xFF) == value[index].code
    }
    return when {
        has(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ->
            SavedImageFormat("png", "image/png")
        has(0, 0xFF, 0xD8, 0xFF) -> SavedImageFormat("jpg", "image/jpeg")
        has(0, 0x52, 0x49, 0x46, 0x46) && has(8, 0x57, 0x45, 0x42, 0x50) ->
            SavedImageFormat("webp", "image/webp")
        has(0, 0x47, 0x49, 0x46, 0x38) -> SavedImageFormat("gif", "image/gif")
        has(0, 0x42, 0x4D) -> SavedImageFormat("bmp", "image/bmp")
        has(4, 0x66, 0x74, 0x79, 0x70) && (
            has(8, 0x61, 0x76, 0x69, 0x66) || has(8, 0x61, 0x76, 0x69, 0x73)
            ) -> SavedImageFormat("avif", "image/avif")
        has(4, 0x66, 0x74, 0x79, 0x70) && listOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")
            .any { brand -> hasAscii(8, brand) } ->
            SavedImageFormat("heic", "image/heif")
        else -> SavedImageFormat("jpg", "image/jpeg")
    }
}

internal fun savedImageDisplayName(
    fileStem: String,
    format: SavedImageFormat,
    timestamp: Long = platformTimeMillis(),
): String {
    val safeStem = sanitizeFileName(fileStem).trimEnd(' ', '.').ifBlank { "UnU_Player_Image" }
    return "${safeStem.take(90)}_$timestamp.${format.extension}"
}

/** 将 Coil 当前成功显示的本地模型复制到用户图片目录。 */
internal expect suspend fun saveScrapedImageModel(
    platformContext: Any,
    model: Any,
    fileStem: String,
): String
