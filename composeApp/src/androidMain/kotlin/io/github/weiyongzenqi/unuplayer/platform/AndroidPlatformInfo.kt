package io.github.weiyongzenqi.unuplayer.platform

import android.content.Context
import android.os.Build
import android.view.WindowManager
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformInfo

/**
 * Android 平台信息实现。提供 HDR 能力检测(用于 HdrMode.AUTO 决策, 见 DESIGN.md §11.3.3)。
 */
class AndroidPlatformInfo(private val context: Context) : PlatformInfo {

    override val isAndroid: Boolean = true

    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    @Suppress("DEPRECATION")  // WindowManager.defaultDisplay API30 deprecated, minSdk 26 需兼容
    override val supportsHdr: Boolean
        get() {
            // Display.isHdr() API26+ 可用(minSdk=26), 返回 display 是否支持任意 HDR 类型。
            // 旧 hdrCapabilities.supportedHdrTypes 在 API34 废弃, isHdr 是官方替代。
            if (Build.VERSION.SDK_INT < 26) return false // 防御性, minSdk=26 实际不触发
            return runCatching {
                val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay
                display?.isHdr == true
            }.getOrDefault(false)
        }

    @Suppress("DEPRECATION")  // WindowManager.defaultDisplay API30 deprecated, minSdk 26 需兼容
    override val hdrTypes: List<String>
        get() {
            if (Build.VERSION.SDK_INT < 26) return emptyList()
            return runCatching {
                val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay
                // API34+: Display.Mode.getSupportedHdrTypes() 是官方替代;
                // 低版本回退到废弃的 HdrCapabilities.supportedHdrTypes(getter 级 @Suppress 已覆盖)。
                val types = if (Build.VERSION.SDK_INT >= 34) {
                    display?.mode?.supportedHdrTypes
                } else {
                    display?.hdrCapabilities?.supportedHdrTypes
                }
                types?.map { type ->
                    when (type) {
                        1 -> "dolby_vision"
                        2 -> "hdr10"
                        3 -> "hdr10_plus"
                        4 -> "hlg"
                        else -> "unknown($type)"
                    }
                } ?: emptyList()
            }.getOrDefault(emptyList())
        }
}
