package io.github.weiyongzenqi.unuplayer.ui.player

import io.github.weiyongzenqi.unuplayer.core.player.PlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DESKTOP_SCREENSHOT_TIMEOUT_MS = 15_000L

internal suspend fun captureDesktopVideoScreenshot(engine: PlayerEngine): String = withContext(Dispatchers.IO) {
    val directory = File(System.getProperty("user.home"), "Pictures/UnU-Player").apply {
        check(exists() || mkdirs()) { "无法创建截图目录" }
    }
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val destination = File(directory, "UnU_Player_$timestamp.png")
    if (destination.exists() && !destination.delete()) error("无法清理同名截图")

    engine.command(videoScreenshotCommand(destination.absolutePath))
    if (!waitForDesktopScreenshot(destination)) {
        destination.delete()
        error("视频画面截图超时或内核未返回图像")
    }
    destination.absolutePath
}

private suspend fun waitForDesktopScreenshot(file: File): Boolean {
    val deadline = System.nanoTime() + DESKTOP_SCREENSHOT_TIMEOUT_MS * 1_000_000L
    var previousLength = -1L
    var stableChecks = 0
    while (System.nanoTime() < deadline) {
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length > 0L && length == previousLength) {
            stableChecks++
            if (stableChecks >= 2) return true
        } else {
            stableChecks = 0
        }
        previousLength = length
        delay(100)
    }
    return false
}
