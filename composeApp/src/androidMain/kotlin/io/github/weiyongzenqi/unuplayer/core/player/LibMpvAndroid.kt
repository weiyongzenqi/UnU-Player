package io.github.weiyongzenqi.unuplayer.core.player

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference

/**
 * Android 端 JNA 直调 AAR 里 libmpv.so 的绑定(绕过 MPVLib Java 封装)。
 *
 * 供集照生成([io.github.weiyongzenqi.unuplayer.library.AndroidEpisodeThumbGenerator])用:
 * mpv software render API headless 抽帧。照桌面 LibMpv.kt 的 interface + 常量, 加载方式改 Android
 * (System.loadLibrary + Native.load)。
 */

/**
 * mpv_render_param_type 枚举值(render.h)。
 * 软件 render API 用 SW_SIZE/SW_FORMAT/SW_STRIDE/SW_POINTER 四件套。
 */
object MpvRenderParamType {
    const val MPV_RENDER_PARAM_INVALID = 0
    const val MPV_RENDER_PARAM_API_TYPE = 1
    const val MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2
    const val MPV_RENDER_PARAM_OPENGL_FBO = 3
    const val MPV_RENDER_PARAM_FLIP_Y = 4
    const val MPV_RENDER_PARAM_ADVANCED_CONTROL = 10
    const val MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12
    /** 软件 render API: data 指向 int[2], 依次为输出宽、高。 */
    const val MPV_RENDER_PARAM_SW_SIZE = 17
    /** 软件 render API: data 指向以 NUL 结尾的 mpv 像素格式字符串(如 "rgb0")。 */
    const val MPV_RENDER_PARAM_SW_FORMAT = 18
    /** 软件 render API: data 指向 size_t, 表示每行字节数(stride)。 */
    const val MPV_RENDER_PARAM_SW_STRIDE = 19
    /** 软件 render API: data 直接指向调用方提供的输出像素缓冲。 */
    const val MPV_RENDER_PARAM_SW_POINTER = 20
}

/** mpv_format 枚举值(client.h)。 */
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

/** mpv_event_id 枚举值(client.h)。 */
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

/** mpv_render_update_flag: MPV_RENDER_UPDATE_FRAME = 1<<0, 表示有新帧需 render。 */
const val MPV_RENDER_UPDATE_FRAME = 1L

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
    // libmpv 的 char* 恒为 UTF-8, 改用 Pointer + 显式 UTF-8 读取(同桌面版)。
    @JvmField var namePtr: Pointer? = null
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null

    val name: String? get() = namePtr?.getString(0, "UTF-8")

    constructor()
    constructor(p: Pointer) : super(p) { read() }
}

/** mpv_event_log_message: LOG_MESSAGE 事件的 data 指向的结构。 */
@Structure.FieldOrder("prefixPtr", "levelPtr", "textPtr", "log_level")
class MpvEventLogMessage : Structure {
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

/** mpv_render_param: { type; data; } 数组, INVALID 结尾。data 指向各类型参数。 */
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
 * libmpv client API JNA 映射(Android 版, 照抄桌面 LibMpv)。
 *
 * - mpv_command 的 args 是 NULL 终止的 const char**, JNA 映射 Array<String> 自动补 NULL
 * - mpv_get_property_string 返回 char* 需调用方 mpv_free, 映射 Pointer? 手动读+free
 * - mpv_wait_event 返回 mpv_event*(mpv 内部 buffer), 映射 MpvEvent? 自动读结构
 */
interface LibMpvAndroid : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_destroy(ctx: Pointer)
    fun mpv_terminate_destroy(ctx: Pointer)

    fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int

    fun mpv_command(ctx: Pointer, args: Array<String>): Int
    fun mpv_command_string(ctx: Pointer, args: String): Int

    fun mpv_set_property_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?

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

    // === render API (render.h) ===
    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Array<MpvRenderParam>): Int
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: MpvRenderUpdateCallback?, cbCtx: Pointer?)
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Array<MpvRenderParam>): Int
    fun mpv_render_context_report_swap(ctx: Pointer)
    fun mpv_render_context_free(ctx: Pointer)
}

/**
 * 构造 render param 数组(INVALID 结尾), 供 [LibMpvAndroid.mpv_render_context_create] /
 * [LibMpvAndroid.mpv_render_context_render] 使用。
 *
 * 用法: `renderParamArray(SW_SIZE to mem, SW_FORMAT to fmt, ..., INVALID to null)`
 *
 * 注: Android JNA 严格要求 Structure 数组元素在连续内存中(否则抛
 * "Structure array elements must use contiguous memory")。桌面版用 Array{} 碰巧能过,
 * Android 必须用 [Structure.toArray] 分配连续内存。
 */
fun renderParamArray(vararg pairs: Pair<Int, Pointer?>): Array<MpvRenderParam> {
    val prototype = MpvRenderParam()
    @Suppress("UNCHECKED_CAST")
    val array = prototype.toArray(pairs.size) as Array<MpvRenderParam>
    for (i in pairs.indices) {
        array[i].type = pairs[i].first
        array[i].data = pairs[i].second
        array[i].write()
    }
    return array
}

/**
 * libmpv Android 加载器。
 *
 * 加载策略:
 * 1. 先 System.loadLibrary("mpv") 让 Android linker 加载 libmpv.so 并自动解析 DT_NEEDED
 *    (libavcodec/avformat/... 同在 APK lib/arm64-v8a/, linker namespace 内可解析)。
 * 2. 再 Native.load("mpv", ...) 获取 JNA 绑定(此时库已在 namespace, dlopen 能找到)。
 *
 * JNA 5.x 在 Android 上首次访问 [Native] 会从 jna.jar 提取 libjnidispatch.so 到临时目录加载,
 * 无需手动配 jniLibs。若 libjnidispatch 加载失败会抛 UnsatisfiedLinkError。
 */
object LibMpvAndroidLoader {
    /** libmpv 的 char* 均约定 UTF-8; 显式指定避免平台默认编码问题。 */
    private val utf8Options = mapOf(Library.OPTION_STRING_ENCODING to Charsets.UTF_8.name())

    @Volatile private var instance: LibMpvAndroid? = null

    /** 加载 libmpv.so 并返回 JNA 绑定。失败抛 UnsatisfiedLinkError(含完整错误信息)。 */
    fun load(): LibMpvAndroid {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            // 先让 Android linker 加载 libmpv.so(解析 libav*.so 依赖); 失败不阻断, 交给 Native.load 报错。
            val loadLibError = runCatching { System.loadLibrary("mpv") }.exceptionOrNull()
            try {
                val lib = Native.load("mpv", LibMpvAndroid::class.java, utf8Options)
                instance = lib
                return lib
            } catch (e: UnsatisfiedLinkError) {
                // Native.load 失败: 合并 System.loadLibrary 的错误信息便于排查
                if (loadLibError != null) {
                    throw UnsatisfiedLinkError(
                        "Native.load(\"mpv\") 失败: ${e.message}; " +
                            "System.loadLibrary(\"mpv\") 亦失败: ${loadLibError.message}",
                    )
                }
                throw e
            }
        }
    }
}
