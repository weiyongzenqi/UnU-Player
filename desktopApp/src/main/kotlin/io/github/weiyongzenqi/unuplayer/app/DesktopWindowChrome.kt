package io.github.weiyongzenqi.unuplayer.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState

/** Windows 无系统装饰窗口的应用内标题栏。 */
@Composable
internal fun FrameWindowScope.DesktopWindowTitleBar(
    title: String,
    icon: ImageBitmap,
    state: WindowState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(36.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 12.dp, end = 6.dp).size(18.dp),
                )
                WindowDraggableArea(Modifier.weight(1f).fillMaxHeight()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                WindowButton(
                    icon = { Icon(Icons.Filled.Remove, contentDescription = "最小化", modifier = Modifier.size(17.dp)) },
                    onClick = { state.isMinimized = true },
                )
                WindowButton(
                    icon = {
                        Icon(
                            imageVector = if (state.placement == WindowPlacement.Maximized) {
                                Icons.Filled.FilterNone
                            } else {
                                Icons.Filled.CropSquare
                            },
                            contentDescription = if (state.placement == WindowPlacement.Maximized) "还原" else "最大化",
                            modifier = Modifier.size(15.dp),
                        )
                    },
                    onClick = {
                        state.placement = if (state.placement == WindowPlacement.Maximized) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Maximized
                        }
                    },
                )
                WindowButton(
                    icon = { Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(17.dp)) },
                    onClick = onClose,
                )
            }
            HorizontalDivider(
                modifier = Modifier.align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun WindowButton(icon: @Composable () -> Unit, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.width(46.dp).fillMaxHeight(),
    ) {
        icon()
    }
}
