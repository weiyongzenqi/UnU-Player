package io.github.weiyongzenqi.unuplayer.ui.player

/** Windows 使用；`video` 标志只截取解码后的视频帧，不包含 mpv 字幕、OSD 或应用弹幕层。 */
internal fun videoScreenshotCommand(outputPath: String): Array<String> {
    require(outputPath.isNotBlank()) { "截图输出路径不能为空" }
    return arrayOf("screenshot-to-file", outputPath, "video")
}
