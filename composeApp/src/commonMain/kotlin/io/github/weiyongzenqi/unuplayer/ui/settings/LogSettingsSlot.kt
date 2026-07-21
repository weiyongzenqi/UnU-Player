package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.runtime.Composable
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository

/**
 * 日志设置区槽位(commonMain 声明, androidMain actual)。
 *
 * 日志功能依赖 SAF(平台 API), 实现在 androidMain。commonMain 这里提供空实现,
 * 避免无平台实现时编译失败; androidMain 覆盖为真实 UI(开关 + 级别 + 选目录)。
 */
@Composable
expect fun LogSettingsSlot(repository: SettingsRepository)
