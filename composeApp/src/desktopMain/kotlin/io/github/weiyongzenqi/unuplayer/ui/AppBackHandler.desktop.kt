package io.github.weiyongzenqi.unuplayer.ui

import androidx.compose.runtime.Composable

/**
 * 桌面 actual: no-op。
 *
 * 桌面端无系统返回键手势, 返回由顶栏 navigationIcon 按钮承担。
 * 后续可在此接 Esc 键 / 鼠标侧键监听实现桌面级返回。
 */
@Composable
actual fun AppBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // no-op: 桌面无系统返回手势
}
