package io.github.weiyongzenqi.unuplayer.domain

/**
 * 桌面 actual: 对齐 mpv 桌面推荐默认(见桌面 libmpv 调研报告 调研2)。
 *
 * - hwdec=auto: 桌面零拷贝硬解(Linux vaapi/nvdec, Windows d3d11va/nvdec 自动选), 比 auto-copy 高效
 * - audioOutput="": 空串让 mpv autoprobe(Linux pipewire>pulse>alsa, Windows wasapi),
 *   不硬绑特定后端; audiotrack/opensles 是 Android 专属, 桌面不存在
 */
actual fun defaultHwdec(): String = "auto-copy"

actual fun defaultAudioOutput(): String = ""
