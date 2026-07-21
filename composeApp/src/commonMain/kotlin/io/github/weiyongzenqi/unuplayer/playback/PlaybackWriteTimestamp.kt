package io.github.weiyongzenqi.unuplayer.playback

/** 返回严格大于 [after] 的进程内播放记录写入时间戳，仍保持 epoch 毫秒兼容。 */
expect fun nextPlaybackWriteTimestamp(after: Long = Long.MIN_VALUE): Long
