package io.github.weiyongzenqi.unuplayer.domain

/** Android actual: 对齐原默认值(auto-copy / audiotrack), 不改变 Android 端行为。 */
actual fun defaultHwdec(): String = "auto-copy"

actual fun defaultAudioOutput(): String = "audiotrack"
