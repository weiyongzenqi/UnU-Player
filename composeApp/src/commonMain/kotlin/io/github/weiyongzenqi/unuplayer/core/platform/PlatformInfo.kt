package io.github.weiyongzenqi.unuplayer.core.platform

/**
 * 平台信息抽象。接口在 commonMain, 实现在 platformMain。
 * Android 实现(AndroidPlatformInfo)提供 HDR 能力检测、SDK 版本等。
 */
interface PlatformInfo {
    val isAndroid: Boolean
    val sdkInt: Int
    /** 设备是否支持 HDR(用于 HdrMode.AUTO 决策)。 */
    val supportsHdr: Boolean
    /** 支持的 HDR 类型("dolby_vision"/"hdr10"/"hlg"...), 空表示不支持。 */
    val hdrTypes: List<String>
}
