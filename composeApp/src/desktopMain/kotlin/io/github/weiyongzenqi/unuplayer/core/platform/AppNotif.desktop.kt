package io.github.weiyongzenqi.unuplayer.core.platform

import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.platform.DesktopSettingsStores
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * 桌面 actual: 系统托盘通知 + 应用统一 settings.properties。
 *
 * toast 使用系统托盘气泡；日志仍通过 AppLogger 统一记录，不写 stdout。
 * 旧版 `unu/player/notif` 注册表节点由桌面设置存储一次性迁移。
 */
actual object AppNotif {

    @Volatile private var logger: AppLogger? = null
    private val trayLock = Any()
    private var trayIcon: TrayIcon? = null

    actual fun setLogger(logger: AppLogger?) {
        this.logger = logger
        if (logger == null) synchronized(trayLock) {
            trayIcon?.let { icon -> runCatching { SystemTray.getSystemTray().remove(icon) } }
            trayIcon = null
        }
    }

    actual fun toast(message: String) {
        logger?.appEvent("notification", message, LogLevel.INFO)
        runCatching {
            if (!SystemTray.isSupported()) return@runCatching
            synchronized(trayLock) {
                val icon = trayIcon ?: TrayIcon(
                    BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB),
                    "UnU Player",
                ).also { created ->
                    created.isImageAutoSize = true
                    SystemTray.getSystemTray().add(created)
                    trayIcon = created
                }
                icon.displayMessage("UnU Player", message, TrayIcon.MessageType.INFO)
            }
        }
    }

    actual fun isFlagSet(key: String): Boolean = DesktopSettingsStores.shared.getBoolean(
        DesktopSettingsStores.NOTIFICATION_PREFIX + key,
    )

    actual fun setFlag(key: String) {
        DesktopSettingsStores.shared.putBoolean(DesktopSettingsStores.NOTIFICATION_PREFIX + key, true)
    }
}
