package io.github.weiyongzenqi.unuplayer.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import io.github.weiyongzenqi.unuplayer.domain.DesktopLayout

/**
 * 主导航壳(跨平台)。
 *
 * - Android: 底部 NavigationBar(4 tab, 固定布局, 忽略 [desktopLayout])
 * - 桌面: 据 [desktopLayout] 选侧边栏([DesktopLayout.SIDEBAR], NavigationRail 风)
 *   或顶部 tab([DesktopLayout.TOP_TABS], TopAppBar + PrimaryTabRow)
 *
 * 内容区 [content] 共用, 接收导航壳的内边距([PaddingValues])。
 */
@Composable
expect fun HomeNavShell(
    selectedTab: UnUTab,
    onSelectTab: (UnUTab) -> Unit,
    desktopLayout: DesktopLayout,
    content: @Composable (PaddingValues) -> Unit,
)
