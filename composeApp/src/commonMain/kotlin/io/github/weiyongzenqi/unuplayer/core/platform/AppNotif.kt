package io.github.weiyongzenqi.unuplayer.core.platform

import io.github.weiyongzenqi.unuplayer.platform.AppLogger

/**
 * 应用级轻提示 + 布尔标记(跨平台抽象)。
 *
 * - Android: Toast + SharedPreferences
 * - 桌面: 系统托盘通知 + 应用数据目录设置文件
 *
 * commonMain 各页面统一用 [AppNotif] 替代 Android 专属的 Toast / SharedPreferences,
 * 使 UI 代码不直接依赖 Android Context。
 */
expect object AppNotif {
    /** 注入进程级日志器；桌面占位通知只能经此接口记录，不能写标准输出。 */
    fun setLogger(logger: AppLogger?)
    /** 弹轻提示(短消息)。 */
    fun toast(message: String)
    /** 布尔标记是否已设(true)。 */
    fun isFlagSet(key: String): Boolean
    /** 设置布尔标记为 true。 */
    fun setFlag(key: String)
}
