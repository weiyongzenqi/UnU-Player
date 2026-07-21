package io.github.weiyongzenqi.unuplayer.platform

import io.github.weiyongzenqi.unuplayer.core.platform.PlatformInfo

/**
 * 桌面(JVM/Linux) PlatformInfo 实现, 对应 androidMain 的 AndroidPlatformInfo。
 *
 * 桌面端 HDR 检测留给播放器阶段(libmpv 桌面集成后再做),
 * 当前 isAndroid=false / supportsHdr=false / hdrTypes=空。
 */
class DesktopPlatformInfo : PlatformInfo {

    override val isAndroid: Boolean = false

    override val sdkInt: Int = 0

    override val supportsHdr: Boolean = false

    override val hdrTypes: List<String> = emptyList()
}
