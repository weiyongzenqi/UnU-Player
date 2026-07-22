package io.github.weiyongzenqi.unuplayer.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.weiyongzenqi.unuplayer.domain.DesktopLayout

/**
 * Android actual: 底部 NavigationBar(4 tab)。
 *
 * Android 固定底部导航, 忽略 [desktopLayout](该设置仅桌面端生效)。
 */
@Composable
actual fun HomeNavShell(
    selectedTab: UnUTab,
    onSelectTab: (UnUTab) -> Unit,
    desktopLayout: DesktopLayout,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == UnUTab.MEDIA_SOURCE,
                    onClick = { onSelectTab(UnUTab.MEDIA_SOURCE) },
                    icon = { Icon(Icons.Filled.Movie, contentDescription = "影视源") },
                    label = { Text("影视源") },
                )
                NavigationBarItem(
                    selected = selectedTab == UnUTab.ANIME,
                    onClick = { onSelectTab(UnUTab.ANIME) },
                    icon = { Icon(Icons.Filled.VideoLibrary, contentDescription = "番剧") },
                    label = { Text("番剧") },
                )
                NavigationBarItem(
                    selected = selectedTab == UnUTab.RECENT,
                    onClick = { onSelectTab(UnUTab.RECENT) },
                    icon = { Icon(Icons.Filled.History, contentDescription = "最近播放") },
                    label = { Text("最近播放") },
                )
                NavigationBarItem(
                    selected = selectedTab == UnUTab.SETTINGS,
                    onClick = { onSelectTab(UnUTab.SETTINGS) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "设置") },
                    label = { Text("设置") },
                )
            }
        },
    ) { padding ->
        content(padding)
    }
}
