package io.github.weiyongzenqi.unuplayer.core.player

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.Structure
import java.io.File

/**
 * libmpv client API 的 JNA 绑定(桌面端, 对应 androidMain 的 dev.jdtech.mpv:libmpv AAR)。
 *
 * 加载系统 libmpv:
 * - Linux: libmpv.so(libmpv-dev 提供) 或 libmpv.so.2(libmpv2 运行时, 无 dev symlink 时回退)
 * - Windows: libmpv-2.dll(随包分发, 见打包方案)
 *
 * 签名依据 /usr/include/mpv/client.h(libmpv 0.35)。函数返回 <0 为错误码(mpv_error_string 解析)。
 *
 * 线程模型(见 DESIGN.md §7.6, 与 android 版 MPVLib 对齐):
 * - mpv_wait_event 必须在专用事件线程轮询(阻塞), 不能在主线程
 * - 事件回调/属性变化在事件线程触发, 更新 Compose state 用 StateFlow.update + Dispatchers.Main
 * - mpv_command/set_property 线程安全, 可在任意线程调
 */
object MpvFormat {
    const val MPV_FORMAT_NONE = 0
    const val MPV_FORMAT_STRING = 1
    const val MPV_FORMAT_OSD_STRING = 2
    const val MPV_FORMAT_FLAG = 3
    const val MPV_FORMAT_INT64 = 4
    const val MPV_FORMAT_DOUBLE = 5
    const val MPV_FORMAT_NODE = 6
    const val MPV_FORMAT_NODE_ARRAY = 7
    const val MPV_FORMAT_NODE_MAP = 8
    const val MPV_FORMAT_BYTE_ARRAY = 9
}

/** mpv_event_id 枚举值(见 client.h enum mpv_event_id)。 */
object MpvEventId {
    const val MPV_EVENT_NONE = 0
    const val MPV_EVENT_SHUTDOWN = 1
    const val MPV_EVENT_LOG_MESSAGE = 2
    const val MPV_EVENT_GET_PROPERTY_REPLY = 3
    const val MPV_EVENT_SET_PROPERTY_REPLY = 4
    const val MPV_EVENT_COMMAND_REPLY = 5
    const val MPV_EVENT_START_FILE = 6
    const val MPV_EVENT_END_FILE = 7
    const val MPV_EVENT_FILE_LOADED = 8
    const val MPV_EVENT_IDLE = 11
    const val MPV_EVENT_TICK = 14
    const val MPV_EVENT_CLIENT_MESSAGE = 16
    const val MPV_EVENT_VIDEO_RECONFIG = 17
    const val MPV_EVENT_AUDIO_RECONFIG = 18
    const val MPV_EVENT_SEEK = 20
    const val MPV_EVENT_PLAYBACK_RESTART = 21
    const val MPV_EVENT_PROPERTY_CHANGE = 22
}

/** mpv_end_file_reason 枚举值。 */
object MpvEndFileReason {
    const val MPV_END_FILE_REASON_EOF = 0
    const val MPV_END_FILE_REASON_STOP = 2
    const val MPV_END_FILE_REASON_QUIT = 3
    const val MPV_END_FILE_REASON_ERROR = 4
    const val MPV_END_FILE_REASON_REDIRECT = 5
}

// === 事件结构(JNA Structure, 字段顺序须与 C struct 一致) ===

/** mpv_event: mpv_wait_event 返回的事件(指向 mpv 内部 buffer, 读后即用, 勿跨调用持有)。 */
@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
class MpvEvent : Structure() {
    @JvmField var event_id: Int = 0
    @JvmField var error: Int = 0
    @JvmField var reply_userdata: Long = 0
    @JvmField var data: Pointer? = null
}

/** mpv_event_property: PROPERTY_CHANGE 事件的 data 指向的结构。 */
@Structure.FieldOrder("namePtr", "format", "data")
class MpvEventProperty : Structure {
    // JNA Structure 的 String 字段按平台 native 编码(-Dfile.encoding)解码, 而 libmpv 的 char* 恒为 UTF-8;
    // 改用 Pointer 字段 + 显式 UTF-8 读取, 避免默认编码非 UTF-8 时属性名乱码。对外 name 签名不变。
    @JvmField var namePtr: Pointer? = null
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null

    /** 属性名(char* 按 UTF-8 读)。 */
    val name: String? get() = namePtr?.getString(0, "UTF-8")

    constructor()
    constructor(p: Pointer) : super(p) { read() }
}

/** mpv_event_log_message: LOG_MESSAGE 事件的 data 指向的结构。 */
@Structure.FieldOrder("prefixPtr", "levelPtr", "textPtr", "log_level")
class MpvEventLogMessage : Structure {
    // 同 MpvEventProperty: String 字段默认平台 native 编码, libmpv 日志字段恒 UTF-8, 改 Pointer + 显式 UTF-8。
    @JvmField var prefixPtr: Pointer? = null
    @JvmField var levelPtr: Pointer? = null
    @JvmField var textPtr: Pointer? = null
    @JvmField var log_level: Int = 0

    val prefix: String? get() = prefixPtr?.getString(0, "UTF-8")
    val level: String? get() = levelPtr?.getString(0, "UTF-8")
    val text: String? get() = textPtr?.getString(0, "UTF-8")

    constructor()
    constructor(p: Pointer) : super(p) { read() }
}

/** mpv_event_end_file: END_FILE 事件的 data 指向的结构。 */
@Structure.FieldOrder("reason", "error", "playlist_entry_id", "playlist_insert_id", "playlist_insert_num_entries")
class MpvEventEndFile : Structure {
    @JvmField var reason: Int = 0
    @JvmField var error: Int = 0
    @JvmField var playlist_entry_id: Long = 0
    @JvmField var playlist_insert_id: Long = 0
    @JvmField var playlist_insert_num_entries: Int = 0

    constructor()
    constructor(p: Pointer) : super(p) { read() }
}

/**
 * libmpv C API JNA 映射。
 *
 * 注意:
 * - mpv_command 的 args 是 NULL 终止的 const char**, JNA 映射 Array<String> 会自动加 NULL 终止
 * - mpv_get_property_string 返回 char* 需调用方 mpv_free, 故映射 Pointer? 由 engine 手动读+free(勿映射 String, 会泄漏)
 * - mpv_wait_event 返回 mpv_event*(mpv 内部 buffer), JNA 映射 MpvEvent? 自动读结构
 */
interface LibMpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_destroy(ctx: Pointer)
    fun mpv_terminate_destroy(ctx: Pointer)

    fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int

    /** args 须 NULL 终止(JNA Array<String> 自动); 如 ["loadfile", url, null] 但 JNA 自动补 null。 */
    fun mpv_command(ctx: Pointer, args: Array<String>): Int
    fun mpv_command_string(ctx: Pointer, args: String): Int

    fun mpv_set_property_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?

    /** 通用 set/get property(format 指定类型, data 指向值)。engine 用 Memory 放 double/int/flag。 */
    fun mpv_set_property(ctx: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: Pointer): Int

    fun mpv_observe_property(ctx: Pointer, reply_userdata: Long, name: String, format: Int): Int
    fun mpv_unobserve_property(ctx: Pointer, reply_userdata: Long): Int

    fun mpv_request_event(ctx: Pointer, event: Int, enable: Int): Int
    fun mpv_request_log_messages(ctx: Pointer, min_level: String): Int

    fun mpv_wait_event(ctx: Pointer, timeout: Double): MpvEvent?
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_set_wakeup_callback(ctx: Pointer, cb: Callback?, d: Pointer?)

    fun mpv_free(data: Pointer)
    fun mpv_error_string(error: Int): String
    fun mpv_client_api_version(): Long

    // === render API (render.h, 须在 GL context current 的线程调) ===
    /** 创建渲染上下文。params 以 MPV_RENDER_PARAM_INVALID 结尾。返回 <0 错误码。 */
    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Array<MpvRenderParam>): Int
    /** 设新帧就绪回调(mpv 内部线程触发, 回调里只请求重绘, 不能直接 render)。 */
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: MpvRenderUpdateCallback?, cbCtx: Pointer?)
    /** 查询该做什么(返回 MPV_RENDER_UPDATE_FRAME 等位掩码)。GL 线程调。 */
    fun mpv_render_context_update(ctx: Pointer): Long
    /** 把当前视频帧渲染到 params 指定的 FBO。GL 线程调。 */
    fun mpv_render_context_render(ctx: Pointer, params: Array<MpvRenderParam>): Int
    /** 通知 mpv 一帧已显示(辅助 A/V sync)。 */
    fun mpv_render_context_report_swap(ctx: Pointer)
    /** 释放渲染上下文(GL context current 线程调, 须在 mpv_terminate_destroy 前)。 */
    fun mpv_render_context_free(ctx: Pointer)
}

// === render API 数据类型(render.h / render_gl.h) ===

/**
 * mpv_render_param_type 枚举值(render.h)。
 * 注: X11_DISPLAY=8(用于 Linux 硬解 interop, 传 XOpenDisplay 的 Display*); Windows 不传。
 */
object MpvRenderParamType {
    const val MPV_RENDER_PARAM_INVALID = 0
    const val MPV_RENDER_PARAM_API_TYPE = 1
    const val MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2
    const val MPV_RENDER_PARAM_OPENGL_FBO = 3
    const val MPV_RENDER_PARAM_FLIP_Y = 4
    const val MPV_RENDER_PARAM_X11_DISPLAY = 8
    const val MPV_RENDER_PARAM_WL_DISPLAY = 9
    const val MPV_RENDER_PARAM_ADVANCED_CONTROL = 10
    const val MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12
    /** 软件 render API: data 指向 int[2]，依次为输出宽、高。 */
    const val MPV_RENDER_PARAM_SW_SIZE = 17
    /** 软件 render API: data 指向以 NUL 结尾的 mpv 像素格式字符串。 */
    const val MPV_RENDER_PARAM_SW_FORMAT = 18
    /** 软件 render API: data 指向 size_t，表示每行字节数。 */
    const val MPV_RENDER_PARAM_SW_STRIDE = 19
    /** 软件 render API: data 直接指向调用方提供的输出像素缓冲。 */
    const val MPV_RENDER_PARAM_SW_POINTER = 20
}

/** mpv_render_update_flag: MPV_RENDER_UPDATE_FRAME = 1<<0, 表示有新帧需 render。 */
const val MPV_RENDER_UPDATE_FRAME = 1L

/** mpv_render_param: { type; data; } 数组, INVALID 结尾。data 指向各类型参数(结构体/char-ptr/int-ptr)。 */
@Structure.FieldOrder("type", "data")
class MpvRenderParam : Structure {
    @JvmField var type: Int = 0
    @JvmField var data: Pointer? = null
    constructor()
    constructor(t: Int, d: Pointer?) { type = t; data = d }
}

/** mpv_render_update_fn: void (*)(void *cb_ctx)。mpv 内部线程触发, 只请求重绘。 */
interface MpvRenderUpdateCallback : Callback {
    fun invoke(cbCtx: Pointer?)
}

/**
 * libmpv 加载器: 跨平台查找 libmpv。
 *
 * Linux: libmpv.so(libmpv-dev) -> libmpv.so.2(libmpv2 运行时, 无 dev symlink 时)
 * Windows: libmpv-2.dll(随包) -> mpv.dll/libmpv.dll(兜底)
 *
 * 加载失败抛 UnsatisfiedLinkError, engine 捕获后给用户友好提示(请装 libmpv2)。
 */
/**
 * 最小 libc 绑定: 仅 [setlocale], 修 mpv 的 "Non-C locale" 检查。
 *
 * JVM 启动后 locale=系统默认(zh_CN.UTF-8 等), mpv 初始化检测 LC_NUMERIC 非 C 时 mpv_create 直接返回 NULL
 * (报 "Non-C locale detected. Call 'setlocale(LC_NUMERIC, \"C\")' in your code.")。
 * C 程序默认 locale=C 故不受影响, 纯 C 测试 mpv_create 成功, JNA/JVM 下失败即此因。
 * 故 Unix 上加载 libmpv 前先 setlocale(LC_NUMERIC, "C") 设回 C。
 */
private interface LibC : Library {
    fun setlocale(category: Int, locale: String): Pointer?
}

object LibMpvLoader {
    val INSTANCE: LibMpv by lazy { load() }
    /** 当前 zhongfly 构建静态导入 vulkan-1.dll；强引用防 JNA 提前卸载。 */
    private var vulkanLoader: NativeLibrary? = null
    /** libmpv 的所有 `char*` 均约定 UTF-8；Windows 默认代码页不能用于中文路径。 */
    private val utf8Options = mapOf(Library.OPTION_STRING_ENCODING to Charsets.UTF_8.name())

    private fun load(): LibMpv {
        val osName = System.getProperty("os.name", "").lowercase()
        val isWindows = osName.startsWith("windows")
        // mpv 检查 LC_NUMERIC 必须=C, 否则 mpv_create 返回 null(JVM 默认系统 locale 非 C)。
        // 仅 Unix: glibc LC_NUMERIC=1, BSD/macOS=4; Windows locale 模型不同, 跳过(待验证)。
        if (!isWindows) {
            runCatching {
                val lcNumeric = if (osName.contains("mac")) 4 else 1
                Native.load("c", LibC::class.java).setlocale(lcNumeric, "C")
            }
        }
        if (isWindows) return loadWindows()

        val names =
            // Linux: 优先 libmpv.so(有 dev), 回退 libmpv.so.2(仅运行时)
            listOf("mpv", "libmpv.so.2", "libmpv")
        var lastError: UnsatisfiedLinkError? = null
        for (name in names) {
            try {
                return Native.load(name, LibMpv::class.java, utf8Options)
            } catch (e: UnsatisfiedLinkError) {
                lastError = e
            }
        }
        throw lastError ?: UnsatisfiedLinkError("libmpv 未找到(Linux 请 apt install libmpv2; Windows 请随包带 libmpv-2.dll)")
    }

    private fun loadWindows(): LibMpv {
        check(System.getProperty("os.arch", "").contains("64")) {
            "当前 libmpv-2.dll 仅支持 Windows x64"
        }
        val configuredDir = System.getProperty("unu.libmpv.dir")?.takeIf { it.isNotBlank() }
        val resourcesDir = System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }
        val directory = listOfNotNull(configuredDir, resourcesDir)
            .map(::File)
            .firstOrNull { File(it, "libmpv-2.dll").isFile }
            ?: throw UnsatisfiedLinkError(
                "找不到 libmpv-2.dll；已检查 unu.libmpv.dir=$configuredDir, " +
                    "compose.application.resources.dir=$resourcesDir",
            )

        val systemVulkan = System.getenv("WINDIR")
            ?.let { File(it, "System32/vulkan-1.dll") }
            ?.takeIf(File::isFile)
        val bundledVulkan = File(directory, "vulkan-1.dll").takeIf(File::isFile)
        val vulkan = systemVulkan ?: bundledVulkan
            ?: throw UnsatisfiedLinkError("libmpv-2.dll 依赖 vulkan-1.dll，但系统和 ${directory.absolutePath} 均未找到")
        vulkanLoader = NativeLibrary.getInstance(vulkan.absolutePath)

        val mpvFile = File(directory, "libmpv-2.dll")
        return Native.load(mpvFile.absolutePath, LibMpv::class.java, utf8Options)
    }
}
