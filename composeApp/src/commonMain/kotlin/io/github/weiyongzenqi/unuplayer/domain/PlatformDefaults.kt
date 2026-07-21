package io.github.weiyongzenqi.unuplayer.domain

/**
 * 平台相关播放默认值(各平台 actual 提供各自合理默认)。
 *
 * 桌面与 Android 的 mpv 默认值不同:
 * - hwdec: Android 用 auto-copy(android_embedded vo 需拷回); 桌面用 auto(零拷贝更高效)
 * - audioOutput: Android 用 audiotrack; 桌面用空(让 mpv autoprobe: Linux pipewire>pulse>alsa, Windows wasapi)
 *
 * SettingsState 默认值与 SettingsRepositoryImpl.loadSettings 的 storage 默认都调此处的 actual,
 * 确保各平台首次启动(无存储值)时拿到平台合理默认, 而非 Android 值。
 */

/** 硬件解码默认值。 */
expect fun defaultHwdec(): String

/** 音频后端默认值。空串=autoprobe(桌面)。 */
expect fun defaultAudioOutput(): String
