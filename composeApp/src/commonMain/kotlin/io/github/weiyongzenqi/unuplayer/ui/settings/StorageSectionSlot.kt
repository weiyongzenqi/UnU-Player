package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.runtime.Composable
import io.github.weiyongzenqi.unuplayer.platform.AppLogger

/**
 * 存储清理区槽位(commonMain 声明, 平台 actual)。
 *
 * 平台实现只展示并清理自身明确拥有的缓存；[appLogger] 由进程级依赖图注入，
 * 避免设置页自行创建日志器或猜测用户选择的日志目录。
 */
@Composable
expect fun StorageSectionSlot(appLogger: AppLogger?)
