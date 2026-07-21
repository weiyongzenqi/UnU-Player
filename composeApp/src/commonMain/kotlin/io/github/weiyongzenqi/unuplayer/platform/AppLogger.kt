package io.github.weiyongzenqi.unuplayer.platform

/** App 事件日志等级(ordinal 越小级别越低, 用于阈值过滤)。 */
enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR, FATAL }

/**
 * 应用日志器抽象(跨平台)。
 *
 * 两个独立文件: app 事件([appEvent]) -> `unu-app-YYYY-MM-DD.txt`; mpv 日志([log]) -> `unu-mpv-YYYY-MM-DD.txt`。
 * app 入口按 [setAppLogLevel] 阈值过滤; mpv 入口不过滤(信任 mpv log-level 上游控制)。
 *
 * 平台实现: Android 用 SAF DocumentFile([AndroidAppLogger]); 桌面用平台文件 API([DesktopAppLogger])。
 * 进程级单例(平台壳注入), 避免多实例并发写同一文件交叉。
 *
 * 保留期清理: 实现须在 writer 协程内(不阻塞主线程/EDT)于启动与每日轮转时各扫一次日志目录,
 * 按文件名日期删除超过 [LOG_RETENTION_DAYS] 天的文件, 非法命名文件跳过, 删除失败只记日志不抛错。
 */
interface AppLogger {
    /** 设置日志目录(Android: SAF URI 字符串; 桌面: 目录绝对路径)。null=关闭(不写, 关闭已有流)。 */
    fun setDirectory(path: String?)
    /** 设置 app 日志最低级别(运行时可改, 立即生效)。 */
    fun setAppLogLevel(level: LogLevel)
    /** 写一条 mpv 日志(level/prefix 来自 mpv LogObserver)。不过滤, 路由到 mpv 文件。 */
    fun log(level: String, prefix: String, text: String)
    /** 写一条 App 自身事件(按 appLogLevel 阈值过滤; level 默认 INFO)。 */
    fun appEvent(tag: String, message: String, level: LogLevel = LogLevel.INFO)
    /**
     * 删除日志目录下所有 unu-*.txt(返回删除字节数)；实现须与已接收日志形成串行屏障。
     * suspend: 经队列屏障挂起等待 writer, 不阻塞调用线程(禁止在实现内 runBlocking)。
     */
    suspend fun clearLogs(): Long
    /** 日志文件总大小(字节)；返回前须 flush 调用前已接收的日志。suspend 语义同 [clearLogs]。 */
    suspend fun logsSize(): Long
    /** 停止接收新日志，drain 已接收队列并 flush/关闭资源；重复调用须安全。 */
    fun shutdown() {}

    companion object {
        /** 日志保留天数: 文件名日期早于(今天 - 该天数)的日志在启动/每日轮转扫描时自动删除。 */
        const val LOG_RETENTION_DAYS = 14
    }
}
