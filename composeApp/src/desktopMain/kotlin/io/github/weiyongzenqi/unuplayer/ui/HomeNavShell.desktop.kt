package io.github.weiyongzenqi.unuplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.weiyongzenqi.unuplayer.domain.DesktopLayout

/**
 * 桌面 actual: 据 [desktopLayout] 选侧边栏或顶部 tab。
 *
 * - [DesktopLayout.SIDEBAR](默认): 左侧自定义导航栏(品牌头 + 圆角选中块导航项, 220dp),
 *   视觉对齐 Material3 NavigationDrawer。桌面媒体应用主流形态: 宽屏下海报墙/文件列表横向舒展,
 *   切换不占垂直空间。比移动端心智的顶部 tab 更适合桌面。
 * - [DesktopLayout.TOP_TABS]: TopAppBar(标题 UnU Player) + PrimaryTabRow(4 tab), 内容区在下。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun HomeNavShell(
    selectedTab: UnUTab,
    onSelectTab: (UnUTab) -> Unit,
    desktopLayout: DesktopLayout,
    content: @Composable (PaddingValues) -> Unit,
) {
    when (desktopLayout) {
        DesktopLayout.SIDEBAR -> SidebarShell(selectedTab, onSelectTab, content)
        DesktopLayout.TOP_TABS -> TopTabsShell(selectedTab, onSelectTab, content)
    }
}

/** 侧边栏壳: 品牌头 + 导航项 + 内容区。 */
@Composable
private fun SidebarShell(
    selectedTab: UnUTab,
    onSelectTab: (UnUTab) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxHeight().width(220.dp),
            // surfaceContainerLow 比 surface 稍深, 与内容区分层
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.fillMaxHeight().padding(vertical = 16.dp)) {
                // 品牌头
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.PlayCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "UnU Player",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "桌面版",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 导航项
                NavItem(selectedTab == UnUTab.MEDIA_SOURCE, onSelectTab, UnUTab.MEDIA_SOURCE, Icons.Filled.Movie, "影视源")
                NavItem(selectedTab == UnUTab.ANIME, onSelectTab, UnUTab.ANIME, Icons.Filled.VideoLibrary, "番剧")
                NavItem(selectedTab == UnUTab.RECENT, onSelectTab, UnUTab.RECENT, Icons.Filled.History, "最近播放")
                NavItem(selectedTab == UnUTab.SETTINGS, onSelectTab, UnUTab.SETTINGS, Icons.Filled.Settings, "设置")
            }
        }
        // 内容区占满剩余宽度
        Box(Modifier.weight(1f).fillMaxHeight()) {
            content(PaddingValues(0.dp))
        }
    }
}

/** 侧边栏导航项: 选中态圆角高亮块(primaryContainer), 图标+文字, 选中加粗。 */
@Composable
private fun NavItem(
    selected: Boolean,
    onSelect: (UnUTab) -> Unit,
    tab: UnUTab,
    icon: ImageVector,
    label: String,
) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val onContainer = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .clickable { onSelect(tab) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = onContainer, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            color = onContainer,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** 顶部 tab 壳: TopAppBar + PrimaryTabRow + 内容区。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopTabsShell(
    selectedTab: UnUTab,
    onSelectTab: (UnUTab) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("UnU Player") })
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    TabRowItem(selectedTab == UnUTab.MEDIA_SOURCE, onSelectTab, UnUTab.MEDIA_SOURCE, "影视源")
                    TabRowItem(selectedTab == UnUTab.ANIME, onSelectTab, UnUTab.ANIME, "番剧")
                    TabRowItem(selectedTab == UnUTab.RECENT, onSelectTab, UnUTab.RECENT, "最近播放")
                    TabRowItem(selectedTab == UnUTab.SETTINGS, onSelectTab, UnUTab.SETTINGS, "设置")
                }
            }
        },
    ) { padding ->
        content(padding)
    }
}

@Composable
private fun TabRowItem(
    selected: Boolean,
    onSelect: (UnUTab) -> Unit,
    tab: UnUTab,
    label: String,
) {
    Tab(
        selected = selected,
        onClick = { onSelect(tab) },
        text = { Text(label) },
    )
}
