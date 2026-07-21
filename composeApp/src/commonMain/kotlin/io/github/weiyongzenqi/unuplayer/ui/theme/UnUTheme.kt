package io.github.weiyongzenqi.unuplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** UnU-Player 基础配色(动漫向, 后续 P2 精调)。 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    secondary = Color(0xFFE2B5C4),
    background = Color(0xFF101014),
    surface = Color(0xFF1A1A20),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF4285F4),
    secondary = Color(0xFFB95E7A),
    background = Color(0xFFF7F7FA),
    surface = Color(0xFFFFFFFF),
)

/**
 * UnU-Player 主题(跨平台)。
 *
 * @param dynamicColor Android 12+ 动态取色(桌面端无, 自动回退静态配色)
 * @param darkTheme 深色主题
 */
@Composable
fun UnUTheme(
    dynamicColor: Boolean = true,
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dynamic = if (dynamicColor) platformDynamicColorScheme(darkTheme) else null
    val colorScheme = dynamic ?: if (darkTheme) DarkColors else LightColors
    setupPlatformChrome(darkTheme)
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** 平台动态取色(Android 12+); 不支持返回 null(回退静态配色)。 */
@Composable
expect fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme?

/** 平台 UI chrome 设置: Android 调状态栏跟随主题; 桌面 no-op(后续可设窗口标题栏)。 */
@Composable
expect fun setupPlatformChrome(darkTheme: Boolean)
