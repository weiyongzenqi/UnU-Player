package io.github.weiyongzenqi.unuplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** Android actual: 委托 androidx.activity.compose.BackHandler(系统返回键 / 预测返回手势)。 */
@Composable
actual fun AppBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
