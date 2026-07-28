package io.github.weiyongzenqi.unuplayer.platform

/**
 * 桌面进程级 [AppLogger] 持有器(对齐 android 的 `AndroidAppLogger.get(context)` 单例模式)。
 *
 * 背景: 桌面端 [DesktopAppLogger] 由 [io.github.weiyongzenqi.unuplayer.app.DesktopAppGraph] 构造持有,
 * 不像 android 有 `AndroidAppLogger.get(context)` 单例入口。但某些组件(如集照生成器
 * [io.github.weiyongzenqi.unuplayer.library.DesktopEpisodeThumbGenerator])在 commonMain expect
 * 签名的 composable 内构造, 接入处拿不到 [AppLogger] 引用, 需要一个进程级入口获取。
 *
 * 用法: [DesktopAppGraph] 初始化时 [set], 组件内 [get] 取用。null=未初始化/已关闭(调用方安全忽略日志)。
 */
object DesktopAppLoggerHolder {

    @Volatile
    private var instance: AppLogger? = null

    /** 由依赖图在启动时注入; 重复 set 覆盖旧值(支持测试替换)。 */
    fun set(logger: AppLogger?) {
        instance = logger
    }

    /** 取当前注入的 logger; 未注入返回 null(调用方用安全调用 `?.appEvent` 忽略日志)。 */
    fun get(): AppLogger? = instance
}
