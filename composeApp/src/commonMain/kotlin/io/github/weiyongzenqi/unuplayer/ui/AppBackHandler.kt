package io.github.weiyongzenqi.unuplayer.ui

import androidx.compose.runtime.Composable

/**
 * 系统返回拦截(跨平台抽象)。
 *
 * - Android: 委托 androidx.activity.compose.BackHandler(系统返回键 / 预测返回手势)
 * - 桌面: 桌面无系统返回键手势, actual 为 no-op; 返回由顶栏 navigationIcon 按钮承担。
 *   后续可在此接 Esc 键监听等桌面级返回。
 *
 * commonMain 各页面统一用 [AppBackHandler] 替代 Android 专属的 BackHandler。
 */
@Composable
expect fun AppBackHandler(enabled: Boolean = true, onBack: () -> Unit)
