package io.github.weiyongzenqi.unuplayer.core.gl

import com.sun.jna.Library
import com.sun.jna.Native

/** Compose Desktop 创建任何 Skiko 对象前确定本次进程使用的渲染后端。 */
object DesktopRenderBackend {
    data class Configuration(
        val requestedApi: String?,
        val reason: String,
        val remoteSession: Boolean?,
    )

    /**
     * 本地 Windows 使用 Direct3D 合成 Compose、视频最终帧和弹幕。
     * libmpv 的视频输出后端与 Skiko 后端独立，始终使用稳定的 sw render API。
     */
    fun configureBeforeCompose(): Configuration {
        val environmentApi = System.getenv("SKIKO_RENDER_API")
        val propertyApi = System.getProperty("skiko.renderApi")
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        selectConfiguration(environmentApi, propertyApi, isWindows, remoteSession = null).let { explicit ->
            if (!environmentApi.isNullOrBlank() || !propertyApi.isNullOrBlank() || !isWindows) return explicit
        }

        val remoteSessionProbe = runCatching {
            Native.load("user32", User32RenderProbe::class.java)
                .GetSystemMetrics(SM_REMOTESESSION) != 0
        }
        val selected = selectConfiguration(
            environmentApi = null,
            propertyApi = null,
            isWindows = true,
            remoteSession = remoteSessionProbe.getOrNull(),
        ).let { configuration ->
            remoteSessionProbe.exceptionOrNull()?.let { error ->
                configuration.copy(
                    reason = "Windows 远程会话探测失败(${error.javaClass.simpleName})，使用软件兼容后端",
                )
            } ?: configuration
        }
        selected.requestedApi?.let { System.setProperty("skiko.renderApi", it) }
        return selected
    }

    /** 纯决策入口，供启动逻辑和桌面测试共享。remoteSession=null 表示探测失败。 */
    internal fun selectConfiguration(
        environmentApi: String?,
        propertyApi: String?,
        isWindows: Boolean,
        remoteSession: Boolean?,
    ): Configuration {
        if (!environmentApi.isNullOrBlank()) {
            return Configuration(environmentApi, "尊重 SKIKO_RENDER_API 环境变量", null)
        }
        if (!propertyApi.isNullOrBlank()) {
            return Configuration(propertyApi, "尊重 skiko.renderApi 系统属性", null)
        }
        if (!isWindows) {
            return Configuration(null, "非 Windows 平台，不覆盖 Skiko 默认渲染后端", null)
        }
        return when (remoteSession) {
            false -> Configuration(
                requestedApi = "DIRECT3D",
                reason = "本地 Windows 会话使用 Direct3D 合成",
                remoteSession = false,
            )
            true -> Configuration(
                requestedApi = "SOFTWARE",
                reason = "检测到 Windows 远程会话，使用软件兼容后端",
                remoteSession = true,
            )
            null -> Configuration(
                requestedApi = "SOFTWARE",
                reason = "Windows 远程会话探测失败，使用软件兼容后端",
                remoteSession = null,
            )
        }
    }

    /** 返回 Skiko 本进程实际请求的后端，环境变量优先级与 Skiko 保持一致。 */
    fun requestedApi(): String? =
        System.getenv("SKIKO_RENDER_API")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("skiko.renderApi")?.takeIf { it.isNotBlank() }

    private interface User32RenderProbe : Library {
        fun GetSystemMetrics(index: Int): Int
    }

    private const val SM_REMOTESESSION = 0x1000
}
