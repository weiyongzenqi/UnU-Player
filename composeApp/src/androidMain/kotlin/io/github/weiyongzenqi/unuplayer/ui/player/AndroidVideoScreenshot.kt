package io.github.weiyongzenqi.unuplayer.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import io.github.weiyongzenqi.unuplayer.core.platform.publishAndroidImage
import io.github.weiyongzenqi.unuplayer.core.player.MpvPlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

internal suspend fun captureAndroidVideoScreenshot(
    context: Context,
    engine: MpvPlayerEngine,
    surfaceView: SurfaceView,
): String =
    withContext(Dispatchers.IO) {
        val cacheDirectory = File(context.cacheDir, "video-screenshots").apply {
            check(exists() || mkdirs()) { "无法创建截图缓存目录" }
        }
        val displayName = screenshotDisplayName()
        val temporary = File(cacheDirectory, ".part_$displayName")
        if (temporary.exists() && !temporary.delete()) error("无法清理旧截图缓存")

        try {
            val overlayState = engine.prepareCleanVideoScreenshot()
                ?: error("播放器尚未准备好视频画面")
            val bitmap = try {
                // 属性变化需要至少一次视频重绘；暂停状态下 mpv 也会因属性变化刷新当前帧。
                delay(80)
                copyVideoSurface(surfaceView)
            } finally {
                engine.restoreAfterCleanVideoScreenshot(overlayState)
            }
            try {
                temporary.outputStream().buffered().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法编码视频截图" }
                }
            } finally {
                bitmap.recycle()
            }
            check(temporary.length() > 0L) { "视频截图内容为空" }
            publishAndroidImage(context, displayName, "image/png") { temporary.inputStream() }
        } finally {
            temporary.delete()
        }
    }

private suspend fun copyVideoSurface(surfaceView: SurfaceView): Bitmap {
    val dimensions = withContext(Dispatchers.Main.immediate) {
        check(surfaceView.isAttachedToWindow && surfaceView.holder.surface.isValid) { "视频画面尚未连接" }
        val width = surfaceView.width
        val height = surfaceView.height
        check(width > 0 && height > 0) { "视频画面尺寸无效" }
        width to height
    }
    val bitmap = withContext(Dispatchers.IO) {
        Bitmap.createBitmap(dimensions.first, dimensions.second, Bitmap.Config.ARGB_8888)
    }
    var lastResult = PixelCopy.ERROR_UNKNOWN
    var copied = false
    try {
        for (attempt in 0 until 3) {
            // PixelCopy 没有取消 API；请求提交后必须等回调，避免退出播放器时提前回收目标 Bitmap。
            lastResult = withContext(NonCancellable) {
                requestPixelCopy(surfaceView, bitmap)
            }
            if (lastResult == PixelCopy.SUCCESS) {
                copied = true
                return bitmap
            }
            if (lastResult != PixelCopy.ERROR_SOURCE_NO_DATA || attempt == 2) break
            delay(80)
        }
        error(pixelCopyFailureMessage(lastResult))
    } finally {
        if (!copied) bitmap.recycle()
    }
}

private suspend fun requestPixelCopy(surfaceView: SurfaceView, bitmap: Bitmap): Int =
    suspendCancellableCoroutine { continuation ->
        PixelCopy.request(
            surfaceView,
            bitmap,
            { result -> if (continuation.isActive) continuation.resume(result) },
            Handler(Looper.getMainLooper()),
        )
    }

private fun pixelCopyFailureMessage(result: Int): String = when (result) {
    PixelCopy.ERROR_SOURCE_NO_DATA -> "视频画面暂时没有可截取的帧"
    PixelCopy.ERROR_SOURCE_INVALID -> "视频画面已失效"
    PixelCopy.ERROR_DESTINATION_INVALID -> "截图缓冲区无效"
    PixelCopy.ERROR_TIMEOUT -> "读取视频画面超时"
    else -> "读取视频画面失败（错误码 $result）"
}

private fun screenshotDisplayName(): String {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    return "UnU_Player_$timestamp.png"
}
