package io.github.weiyongzenqi.unuplayer.platform

/**
 * 可跨进程恢复的桌面窗口状态。坐标和尺寸使用 Compose Desktop 的逻辑像素(dp)整数值。
 * [x]/[y] 同时为空表示首次启动交给平台决定位置；负坐标用于主屏左侧/上方的副显示器。
 */
data class DesktopWindowBounds(
    val x: Int? = null,
    val y: Int? = null,
    val width: Int,
    val height: Int,
    val maximized: Boolean = false,
)

/**
 * 窗口状态与普通设置共用 `%LOCALAPPDATA%/UnU-Player/data/settings.properties`，
 * 但不走 [io.github.weiyongzenqi.unuplayer.domain.SettingsRepository] 的全量保存，避免拖动/缩放窗口时重写全部设置键。
 */
class DesktopWindowPreferences internal constructor(
    private val store: DesktopSettingsFileStore,
) {
    constructor() : this(DesktopSettingsStores.shared)

    fun loadMain(): DesktopWindowBounds = load(MAIN_PREFIX, MAIN_DEFAULT_WIDTH, MAIN_DEFAULT_HEIGHT)

    fun loadPlayer(): DesktopWindowBounds = load(PLAYER_PREFIX, PLAYER_DEFAULT_WIDTH, PLAYER_DEFAULT_HEIGHT)

    fun saveMain(bounds: DesktopWindowBounds) = save(MAIN_PREFIX, bounds, MAIN_DEFAULT_WIDTH, MAIN_DEFAULT_HEIGHT)

    fun savePlayer(bounds: DesktopWindowBounds) =
        save(PLAYER_PREFIX, bounds, PLAYER_DEFAULT_WIDTH, PLAYER_DEFAULT_HEIGHT)

    private fun load(prefix: String, defaultWidth: Int, defaultHeight: Int): DesktopWindowBounds {
        val width = store.getInt("$prefix.width", defaultWidth).takeIf(::isValidDimension) ?: defaultWidth
        val height = store.getInt("$prefix.height", defaultHeight).takeIf(::isValidDimension) ?: defaultHeight
        val x = store.getString("$prefix.x")?.toIntOrNull()?.takeIf(::isValidCoordinate)
        val y = store.getString("$prefix.y")?.toIntOrNull()?.takeIf(::isValidCoordinate)
        val hasCompletePosition = x != null && y != null
        return DesktopWindowBounds(
            x = x.takeIf { hasCompletePosition },
            y = y.takeIf { hasCompletePosition },
            width = width,
            height = height,
            maximized = store.getBoolean("$prefix.maximized", false),
        )
    }

    private fun save(
        prefix: String,
        bounds: DesktopWindowBounds,
        defaultWidth: Int,
        defaultHeight: Int,
    ) {
        val width = bounds.width.takeIf(::isValidDimension) ?: defaultWidth
        val height = bounds.height.takeIf(::isValidDimension) ?: defaultHeight
        val x = bounds.x?.takeIf(::isValidCoordinate)
        val y = bounds.y?.takeIf(::isValidCoordinate)
        val hasCompletePosition = x != null && y != null
        store.putAll(
            mapOf(
                "$prefix.x" to x?.toString().takeIf { hasCompletePosition },
                "$prefix.y" to y?.toString().takeIf { hasCompletePosition },
                "$prefix.width" to width.toString(),
                "$prefix.height" to height.toString(),
                "$prefix.maximized" to bounds.maximized.toString(),
            ),
        )
    }

    private fun isValidDimension(value: Int): Boolean = value in MIN_DIMENSION..MAX_DIMENSION

    private fun isValidCoordinate(value: Int): Boolean = value in MIN_COORDINATE..MAX_COORDINATE

    private companion object {
        const val MAIN_PREFIX = "desktop.mainWindow"
        const val PLAYER_PREFIX = "desktop.playerWindow"
        const val MAIN_DEFAULT_WIDTH = 1280
        const val MAIN_DEFAULT_HEIGHT = 800
        const val PLAYER_DEFAULT_WIDTH = 1280
        const val PLAYER_DEFAULT_HEIGHT = 800
        const val MIN_DIMENSION = 320
        const val MAX_DIMENSION = 16_384
        const val MIN_COORDINATE = -65_536
        const val MAX_COORDINATE = 65_536
    }
}
