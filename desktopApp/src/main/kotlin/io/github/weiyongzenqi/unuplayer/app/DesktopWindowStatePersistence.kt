package io.github.weiyongzenqi.unuplayer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.platform.DesktopWindowBounds

internal data class WindowObservation(
    val placement: WindowPlacement,
    val minimized: Boolean,
    val position: WindowPosition,
    val width: Int,
    val height: Int,
)

/** 只把最后有效的 Floating bounds 持久化；最大化只记录标志，全屏/最小化不污染恢复尺寸。 */
internal class DesktopWindowStateTracker(initial: DesktopWindowBounds) {
    private var last = initial

    fun capture(observation: WindowObservation): DesktopWindowBounds {
        if (observation.minimized || observation.placement == WindowPlacement.Fullscreen) return last
        if (observation.placement == WindowPlacement.Maximized) {
            last = last.copy(maximized = true)
            return last
        }

        val absolute = observation.position as? WindowPosition.Absolute
        val validSize = observation.width >= MIN_WINDOW_DIMENSION && observation.height >= MIN_WINDOW_DIMENSION
        if (validSize) {
            last = DesktopWindowBounds(
                x = absolute?.x?.value?.roundToInt() ?: last.x,
                y = absolute?.y?.value?.roundToInt() ?: last.y,
                width = observation.width,
                height = observation.height,
                maximized = false,
            )
        } else {
            last = last.copy(maximized = false)
        }
        return last
    }
}

@Composable
internal fun PersistWindowState(
    state: WindowState,
    tracker: DesktopWindowStateTracker,
    save: (DesktopWindowBounds) -> Unit,
) {
    LaunchedEffect(state, tracker) {
        snapshotFlow { state.toObservation() }
            .map(tracker::capture)
            .distinctUntilChanged()
            .collectLatest { bounds ->
                // 连续拖动/缩放只在停止一小段时间后落一次盘。
                delay(WINDOW_SAVE_DEBOUNCE_MS)
                withContext(Dispatchers.IO) { runCatching { save(bounds) } }
            }
    }
    DisposableEffect(state, tracker) {
        onDispose {
            runCatching { save(tracker.capture(state.toObservation())) }
        }
    }
}

internal fun WindowState.toObservation(): WindowObservation = WindowObservation(
    placement = placement,
    minimized = isMinimized,
    position = position,
    width = size.width.value.takeIf { it.isFinite() }?.roundToInt() ?: 0,
    height = size.height.value.takeIf { it.isFinite() }?.roundToInt() ?: 0,
)

internal fun DesktopWindowBounds.toWindowPosition(): WindowPosition {
    val savedX = x
    val savedY = y
    return if (savedX != null && savedY != null) {
        WindowPosition.Absolute(savedX.dp, savedY.dp)
    } else {
        WindowPosition.PlatformDefault
    }
}

/** 拔掉副屏或显示布局变化后，至少让窗口回到平台默认的可见位置。 */
internal fun DesktopWindowBounds.ensureVisibleOnCurrentScreens(): DesktopWindowBounds {
    val savedX = x ?: return this
    val savedY = y ?: return this
    val isVisible = runCatching {
        if (GraphicsEnvironment.isHeadless()) return@runCatching true
        val saved = Rectangle(savedX, savedY, width, height)
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device ->
            val intersection = saved.intersection(device.defaultConfiguration.bounds)
            intersection.width >= MIN_VISIBLE_WINDOW_EDGE && intersection.height >= MIN_VISIBLE_WINDOW_EDGE
        }
    }.getOrDefault(true)
    return if (isVisible) this else copy(x = null, y = null)
}

private const val MIN_WINDOW_DIMENSION = 320
private const val MIN_VISIBLE_WINDOW_EDGE = 64
private const val WINDOW_SAVE_DEBOUNCE_MS = 500L
