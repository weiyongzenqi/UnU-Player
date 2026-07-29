package io.github.weiyongzenqi.unuplayer.domain

/** Android actual: 默认 MediaCodec 直出(零拷贝最省电; 个别片源可能花屏, 可在设置切回拷回)。 */
actual fun defaultHwdec(): String = "mediacodec"

actual fun defaultAudioOutput(): String = "audiotrack"
