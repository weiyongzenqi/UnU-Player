package io.github.weiyongzenqi.unuplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** 桌面 actual: 无动态取色, 返回 null(回退静态配色)。 */
@Composable
actual fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

/** 桌面 actual: no-op(窗口 chrome 由 Compose Window 管理; 后续可设标题栏明暗)。 */
@Composable
actual fun setupPlatformChrome(darkTheme: Boolean) {
    // no-op
}
