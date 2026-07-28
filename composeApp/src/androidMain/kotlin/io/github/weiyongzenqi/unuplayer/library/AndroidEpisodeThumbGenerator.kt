package io.github.weiyongzenqi.unuplayer.library

import android.content.Context
import android.graphics.Bitmap
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.player.LibMpvAndroid
import io.github.weiyongzenqi.unuplayer.core.player.LibMpvAndroidLoader
import io.github.weiyongzenqi.unuplayer.core.player.MPV_RENDER_UPDATE_FRAME
import io.github.weiyongzenqi.unuplayer.core.player.MpvEventEndFile
import io.github.weiyongzenqi.unuplayer.core.player.MpvEventId
import io.github.weiyongzenqi.unuplayer.core.player.MpvRenderParamType
import io.github.weiyongzenqi.unuplayer.core.player.renderParamArray
import io.github.weiyongzenqi.unuplayer.platform.AndroidAppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import io.github.weiyongzenqi.unuplayer.platform.SystemCaBundle
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "EpisodeThumb"

/** 事件线程与主流程线程共享的状态(局部 var 不能加 @Volatile, 用 holder 类)。 */
private class EventState {
    @Volatile var running = true
    @Volatile var fileLoaded = false
    // seekReceived/playbackRestart 仅事件线程照实记录(诊断用), 不作落位判据:
    // SEEK 事件只表示 seek 命令被受理(入队即发), 此时目标帧尚未解码, 据其 render 必得 seek 前旧帧(首帧 bug 根因)。
    // 落位判据改走 seeking 属性 + time-pos 校验, 见 seekAndWait。
    @Volatile var seekReceived = false
    @Volatile var playbackRestart = false
    @Volatile var endFile = false
    @Volatile var endFileReason = -1
}

/**
 * Android 集照生成器: 用 libmpv software render API(headless, 不绑播放 Surface)从视频抽一帧,
 * 缩放(长边≤320)存 JPEG 到 PosterCache。
 *
 * JNA 直调 AAR 里的 libmpv.so(绕过 MPVLib Java 封装, 见 [LibMpvAndroidLoader]),
 * 复用 [MediaSource.resolvePlayMedia] 取 url+headers + [SystemCaBundle] 取 TLS, 与正式播放路径一致。
 * LOCAL/WebDAV 均可(spike 验证: LOCAL 3.9s / WebDAV 7.6s)。
 *
 * 危险区遵守: setOptionString 在 init 前(#2); 事件回调在 mpv 内部 pthread(#1); destroy 阻塞在 IO 线程(#3)。
 * 单次 [generate] 内 create->destroy 闭环; 多集并发由 [EpisodeThumbCoordinator] Semaphore(2) 限流。
 */
class AndroidEpisodeThumbGenerator(
    private val context: Context,
    private val allowTlsInsecure: Boolean = false,
) : EpisodeThumbGenerator {

    override suspend fun generate(
        episode: ScrapedEpisode,
        showKey: String,
        source: MediaSource,
        position: EpisodeThumbPosition,
    ): String? = withContext(Dispatchers.IO) {
        val logger = AndroidAppLogger.get(context)
        val t0 = System.currentTimeMillis()
        // 1. resolvePlayMedia 取 url+headers(与正式播放路径一致)
        val playable = runSuspendCatching {
            source.resolvePlayMedia(MediaEntry(name = episode.video_name, path = episode.video_path, isDirectory = false))
        }.getOrNull()
        if (playable == null) {
            logger.appEvent(TAG, "ep${episode.episode_number} resolvePlayMedia 失败, 跳过", LogLevel.WARN)
            return@withContext null
        }
        val url = playable.url
        val headers = playable.headers
        val isHttps = url.startsWith("https://", ignoreCase = true)
        // 2. TLS 与正式播放共用显式授权语义：用户开启降级才关闭验证；默认 CA 失败直接拒绝。
        val tlsCaFile = if (isHttps && !allowTlsInsecure) SystemCaBundle.ensureBundle(context) else null
        val tlsPolicy = resolveEpisodeThumbTlsPolicy(
            isHttps = isHttps,
            allowTlsInsecure = allowTlsInsecure,
            requiresExplicitCaFile = true,
            caFile = tlsCaFile,
        )
        if (tlsPolicy == EpisodeThumbTlsPolicy.Reject) {
            logger.appEvent(TAG, "ep${episode.episode_number} CA bundle 不可用且未授权 TLS 降级, 跳过", LogLevel.ERROR)
            return@withContext null
        }
        // 3. dest(PosterCache 内文件, clearShow 自动清理)
        val dest = PosterCache.get(context).episodeThumbFile(showKey, episode.id)
        logger.appEvent(TAG, "开始 ep${episode.episode_number} https=$isHttps caFile=${tlsCaFile != null} insecure=${tlsPolicy == EpisodeThumbTlsPolicy.Insecure} headerKeys=${headers.keys} dest=${dest.name}")

        val lib = try {
            LibMpvAndroidLoader.load()
        } catch (e: Throwable) {
            logger.appEvent(TAG, "libmpv 加载失败: ${e.javaClass.simpleName}: ${e.message}", LogLevel.ERROR)
            return@withContext null
        }

        var m: Pointer? = null
        var renderCtx: Pointer? = null
        var eventThread: Thread? = null
        val state = EventState()

        try {
            // === create ===
            m = lib.mpv_create()
            if (m == null) {
                logger.appEvent(TAG, "mpv_create 返回 null", LogLevel.ERROR)
                return@withContext null
            }
            val handle: Pointer = m

            // === setOptionString(必须在 initialize 前, 危险区#2) ===
            fun opt(name: String, value: String) {
                val r = lib.mpv_set_option_string(handle, name, value)
                // 失败日志只打选项名不打 value: http-header-fields 的 value 含 Authorization 凭据明文, 落盘即泄漏。
                if (r < 0) logger.appEvent(TAG, "setOption($name) 失败: ${lib.mpv_error_string(r)}", LogLevel.WARN)
            }
            opt("vo", "libmpv")        // headless: libmpv render API, 不绑窗口
            opt("ao", "null")
            opt("vid", "1")
            opt("hwdec", "no")         // 软解最稳(集照不追求性能)
            opt("pause", "yes")        // 加载后暂停, 等 seek 到目标帧
            opt("keep-open", "yes")
            // 精确 seek: 解码推进到目标帧(不停前一关键帧), 推进期间 seeking 属性保持 yes, 落位回 no(见 seekAndWait)
            opt("hr-seek", "yes")
            opt("terminal", "no")
            opt("network-timeout", "30")
            opt("user-agent", "Mozilla/5.0 (Linux; Android) UnUPlayer")
            opt("cache", "yes")
            opt("demuxer-max-bytes", "16MiB")   // 集照只需一帧, 减小预读缓存(原 64MiB 偏多)
            opt("demuxer-seekable-cache", "yes")
            opt("cache-secs", "2")              // 减小预读秒数(原 10s, seek 后渲染一帧即停)
            // WebDAV: http-header-fields(STRING_LIST 逗号分隔, init 前设)。Authorization 走此头,
            // 不用 URL 内嵌 user:pass@(mpv 对 percent-encoding 解码不可靠)。照 MpvPlayerEngine.applyOptions。
            if (headers.isNotEmpty()) {
                val joined = headers.entries.joinToString(",") { "${it.key}: ${it.value}" }
                opt("http-header-fields", joined)
            }
            when (tlsPolicy) {
                is EpisodeThumbTlsPolicy.Verify -> {
                    tlsPolicy.caFile?.let { opt("tls-ca-file", it) }
                    opt("tls-verify", "yes")
                }
                EpisodeThumbTlsPolicy.Insecure -> {
                    opt("tls-verify", "no")
                    logger.appEvent(TAG, "TLS: tls-verify=no(用户已授权降级)", LogLevel.WARN)
                }
                EpisodeThumbTlsPolicy.NotHttps -> Unit
                EpisodeThumbTlsPolicy.Reject -> error("拒绝策略应在创建 mpv 前返回")
            }

            // === initialize ===
            val tInitStart = System.currentTimeMillis()
            val initRc = lib.mpv_initialize(handle)
            if (initRc < 0) {
                logger.appEvent(TAG, "mpv_initialize 失败: ${lib.mpv_error_string(initRc)} ($initRc)", LogLevel.ERROR)
                return@withContext null
            }
            logger.appEvent(TAG, "init OK(${System.currentTimeMillis() - tInitStart}ms)")

            // === 创建 render context(API_TYPE="sw") ===
            val apiTypeMem = Memory(3).also { it.setString(0, "sw") }
            val createParams = renderParamArray(
                MpvRenderParamType.MPV_RENDER_PARAM_API_TYPE to apiTypeMem,
                MpvRenderParamType.MPV_RENDER_PARAM_INVALID to null,
            )
            val result = PointerByReference()
            val ctxRc = lib.mpv_render_context_create(result, handle, createParams)
            if (ctxRc < 0) {
                logger.appEvent(TAG, "render_context_create 失败: ${lib.mpv_error_string(ctxRc)}", LogLevel.ERROR)
                return@withContext null
            }
            renderCtx = result.value
            if (renderCtx == null) {
                logger.appEvent(TAG, "render_context_create 返回 null", LogLevel.ERROR)
                return@withContext null
            }
            val ctx: Pointer = renderCtx

            // === 事件轮询线程(危险区#1: 事件在 mpv 内部 pthread) ===
            eventThread = Thread({
                while (state.running) {
                    try {
                        val ev = lib.mpv_wait_event(handle, 0.1) ?: continue
                        when (ev.event_id) {
                            MpvEventId.MPV_EVENT_FILE_LOADED -> { state.fileLoaded = true }
                            MpvEventId.MPV_EVENT_SEEK -> { state.seekReceived = true }
                            MpvEventId.MPV_EVENT_PLAYBACK_RESTART -> { state.playbackRestart = true }
                            MpvEventId.MPV_EVENT_END_FILE -> {
                                state.endFile = true
                                val ef = ev.data?.let { MpvEventEndFile(it) }
                                state.endFileReason = ef?.reason ?: -1
                            }
                            MpvEventId.MPV_EVENT_SHUTDOWN -> { state.running = false }
                        }
                    } catch (_: Throwable) {
                        break
                    }
                }
            }, "mpv-thumb-event").also { it.isDaemon = true }
            eventThread.start()

            // === loadfile ===
            val tLoadStart = System.currentTimeMillis()
            val loadRc = lib.mpv_command(handle, arrayOf("loadfile", url, "replace"))
            if (loadRc < 0) {
                logger.appEvent(TAG, "loadfile 失败: ${lib.mpv_error_string(loadRc)}", LogLevel.ERROR)
                return@withContext null
            }
            // 等 FILE_LOADED(超时 30s; 远程需连+auth+demux, network-timeout=30s)。
            // 条件含 endFile: LOCAL 损坏文件等即时失败不空转满 30s(也少占 Semaphore 槽位); endFile 由下方统一判定。
            val fileLoadedOk = waitCondition({ state.fileLoaded || state.endFile }, 30000)
            val tFileLoaded = System.currentTimeMillis()
            if (!fileLoadedOk || state.endFile) {
                logger.appEvent(TAG, "文件加载失败(endFile=${state.endFile} reason=${state.endFileReason})", LogLevel.ERROR)
                return@withContext null
            }
            logger.appEvent(TAG, "FILE_LOADED(${tFileLoaded - tLoadStart}ms)")

            // === 取 duration 算 atSeconds(position 转) ===
            val duration = readPropertyString(lib, handle, "duration")?.toDoubleOrNull() ?: 0.0
            val atSeconds = position.toSeconds(duration)   // commonMain 纯函数(两平台共用, commonTest 覆盖边界)
            logger.appEvent(TAG, "duration=${duration}s atSeconds=$atSeconds position=$position")

            // === seek 到目标时间点(pause 态精确落位: seeking 属性 + time-pos 校验, 判据见 seekAndWait) ===
            val tSeekStart = System.currentTimeMillis()
            if (!seekAndWait(lib, handle, atSeconds, state, logger, "first")) {
                return@withContext null
            }
            val tSeekEnd = System.currentTimeMillis()

            // === 取视频尺寸 -> 缩放(长边≤320, 节省内存 + JPEG 体积) ===
            val vw = readPropertyString(lib, handle, "width")?.toIntOrNull() ?: 0
            val vh = readPropertyString(lib, handle, "height")?.toIntOrNull() ?: 0
            val maxEdge = 320
            val outW: Int
            val outH: Int
            if (vw > 0 && vh > 0) {
                val scale = maxEdge.toFloat() / maxOf(vw, vh)
                outW = (vw * scale).toInt().coerceAtLeast(1)
                outH = (vh * scale).toInt().coerceAtLeast(1)
            } else {
                outW = 320
                outH = 180
            }

            // === 分配 rgb0 缓冲 + 构造 SW params(照桌面 renderSoftwareFrame) ===
            val minStride = outW * 4
            val stride = ((minStride + 63) / 64) * 64
            val bufSize = stride * outH
            val sizeMem = Memory(8).also { it.setInt(0, outW); it.setInt(4, outH) }
            val formatMem = Memory(5).also { it.setString(0, "rgb0") }
            val strideMem = Memory(Native.SIZE_T_SIZE.toLong()).also {
                if (Native.SIZE_T_SIZE == 8) it.setLong(0, stride.toLong()) else it.setInt(0, stride)
            }
            val pixelMem = Memory(bufSize.toLong())
            val params = renderParamArray(
                MpvRenderParamType.MPV_RENDER_PARAM_SW_SIZE to sizeMem,
                MpvRenderParamType.MPV_RENDER_PARAM_SW_FORMAT to formatMem,
                MpvRenderParamType.MPV_RENDER_PARAM_SW_STRIDE to strideMem,
                MpvRenderParamType.MPV_RENDER_PARAM_SW_POINTER to pixelMem,
                MpvRenderParamType.MPV_RENDER_PARAM_INVALID to null,
            )

            // === 轮询 render 取帧 + 空白帧检测自动重试(最多 4 次: 原位置 + 3 次 +30s 偏移) ===
            // 抽帧位置可能落黑屏/纯色片头段, 检测空白帧(方差<25)后 +30s 偏移重试, 提升集照质量。
            val raw = ByteArray(bufSize)
            var attempt = 0
            val maxAttempts = 4
            var currentSeek = atSeconds
            var gotNonBlack = false
            val tRenderStart = System.currentTimeMillis()
            // 120s 是轮间软上限(仅 while 顶部检查): 进入某轮后该轮 seekAndWait(≤30s)+render(≤30s)各有独立 deadline,
            // 首次 seek 也不计入; 最坏墙钟 ≈ 首 seek 30s + 120s + 末轮 60s。有界终了, 非死循环。
            while (attempt < maxAttempts && !state.endFile && System.currentTimeMillis() - tRenderStart < 120000) {
                // 重试(attempt>0): seek 到偏移位置(+30s/次, 不超 duration 95%, 避免越界), 同走 seekAndWait 落位判据
                if (attempt > 0) {
                    currentSeek = (atSeconds + attempt * 30.0)
                        .coerceAtMost((duration * 0.95).coerceAtLeast(atSeconds + 1.0))
                    if (!seekAndWait(lib, handle, currentSeek, state, logger, "retry$attempt")) {
                        logger.appEvent(TAG, "ep${episode.episode_number} 偏移 seek 落位失败(@${currentSeek}s), 试下一偏移", LogLevel.WARN)
                        attempt++
                        continue
                    }
                }
                // render 取帧(最多 30s/次; 首次落位后 UPDATE_FRAME 5s 不来则 frame-step 强制产帧)
                var rendered = false
                val renderDeadline = System.currentTimeMillis() + 30000
                val frameStepDeadline = System.currentTimeMillis() + 5000
                var frameStepSent = false
                while (!rendered && !state.endFile && System.currentTimeMillis() < renderDeadline) {
                    currentCoroutineContext().ensureActive()
                    val updateFlags = lib.mpv_render_context_update(ctx)
                    if ((updateFlags and MPV_RENDER_UPDATE_FRAME) != 0L) {
                        val renderRc = lib.mpv_render_context_render(ctx, params)
                        if (renderRc < 0) {
                            logger.appEvent(TAG, "render 失败: ${lib.mpv_error_string(renderRc)}", LogLevel.ERROR)
                            return@withContext null
                        }
                        rendered = true
                    } else {
                        // frame-step 仅首次落位后用(不在重试循环滥用): pause 态 frame-step(默认 play 模式)解码并
                        // 显示一帧后立即暂停(mpv 官方 input.rst: frame-step = play forward then pause), 强制 render
                        // context 产出新帧; 偏移一帧(~40ms)对缩略图无影响。
                        if (attempt == 0 && !frameStepSent && System.currentTimeMillis() >= frameStepDeadline) {
                            logger.appEvent(TAG, "UPDATE_FRAME 5s 未至, frame-step 强制产帧", LogLevel.WARN)
                            lib.mpv_command(handle, arrayOf("frame-step"))
                            frameStepSent = true
                        }
                        delay(50)
                    }
                }
                if (!rendered) {
                    logger.appEvent(TAG, "render 超时(无帧) attempt=$attempt", LogLevel.ERROR)
                    return@withContext null
                }
                logger.appEvent(TAG, "renderFrame attempt=$attempt")
                pixelMem.read(0, raw, 0, bufSize)
                if (!isBlankFrame(raw, stride, outW, outH)) {
                    gotNonBlack = true
                    break
                }
                logger.appEvent(TAG, "ep${episode.episode_number} 第${attempt + 1}次抽帧空白(@${currentSeek}s), +30s 偏移重试", LogLevel.WARN)
                attempt++
            }
            if (!gotNonBlack) {
                // C-02: N 次抽帧全空白(黑屏/纯色片头) -> 放弃写盘返回 null, 防黑图固化。
                // 纯色帧压 JPEG 仅约 1198B, 一旦写盘, Coordinator 会视本地集照有效(路径非空+文件存在)
                // 永不重生成, 黑图永久留在详情页。宁缺勿黑: 本地集照留空, 下次进详情页重试抽帧。
                logger.appEvent(TAG, "ep${episode.episode_number} $maxAttempts 次均空白, 放弃写盘(防黑图固化)", LogLevel.WARN)
                return@withContext null
            }

            // === rgb0 -> Bitmap -> JPEG ===
            // rgb0 每像素 R,G,B,X(4字节); Bitmap ARGB_8888 的 int 为 0xAARRGGBB, 逐像素组装。
            val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            // CR-081 原子写: 同目录 part 临时文件 + 完整写完 + 原子替换到 dest。
            // 为何原子: 写中断留半截 JPEG, Coordinator "文件存在"判断把它当有效缓存永不重生成;
            //   C-02 的 <2048B 筛选只兜小文件, >2KB 的半截 JPEG 会漏, 坏图永久显示。
            // 为何同目录临时文件: 跨文件系统 rename 不原子(降级 copy+delete); 同目录保证同文件系统。
            // 随机后缀防并发冲突(Semaphore(2) 可并发处理不同集, 同 PosterCache 目录)与上次残留。
            val part = File(dest.parentFile, ".${dest.name}.${Random.nextInt(0x7fffffff)}.part")
            var committed = false
            try {
                val pixels = IntArray(outW * outH)
                for (row in 0 until outH) {
                    for (col in 0 until outW) {
                        val idx = row * stride + col * 4
                        val r = raw[idx].toInt() and 0xFF
                        val g = raw[idx + 1].toInt() and 0xFF
                        val b = raw[idx + 2].toInt() and 0xFF
                        pixels[row * outW + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                bitmap.setPixels(pixels, 0, outW, 0, 0, outW, outH)
                // 先写完整 JPEG 到 part: compress=false 即编码失败, 视为未写(绝不留半截)。
                FileOutputStream(part).use { fos ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)) {
                        logger.appEvent(TAG, "ep${episode.episode_number} JPEG 编码失败(compress=false), 放弃写盘", LogLevel.ERROR)
                        return@withContext null
                    }
                    fos.flush()
                    fos.fd.sync()
                }
                // 只接受同目录 ATOMIC_MOVE + REPLACE_EXISTING。不能原子替换时保留旧有效文件，
                // 绝不先删目标再制造缺失窗口。
                if (!publishAtomicEpisodeThumbPart(part, dest)) {
                    logger.appEvent(TAG, "ep${episode.episode_number} part->dest 原子替换失败, 保留旧集照", LogLevel.ERROR)
                    return@withContext null
                }
                committed = true
            } finally {
                // recycle 移到 finally: setPixels/compress 异常也回收 native 像素内存, 防泄漏
                bitmap.recycle()
                // 任何失败/取消路径(含 CancellationException)清理 part; 成功 rename 后 part 已不存在(committed=true)
                if (!committed) runCatching { part.delete() }
            }
            val tEnd = System.currentTimeMillis()
            logger.appEvent(TAG, "完成 ep${episode.episode_number} size=${dest.length()}B load=${tFileLoaded - tLoadStart}ms seek=${tSeekEnd - tSeekStart}ms render=${tEnd - tRenderStart}ms 总=${tEnd - t0}ms")
            return@withContext dest.absolutePath
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            logger.appEvent(TAG, "抽帧异常 ep${episode.episode_number}: ${e.javaClass.simpleName}: ${e.message}", LogLevel.ERROR)
            return@withContext null
        } finally {
            // === 清理(危险区#3: destroy 阻塞 pthread_join, IO 线程调) ===
            state.running = false
            m?.let { runCatching { lib.mpv_wakeup(it) } }
            eventThread?.join(2000)
            renderCtx?.let { rc ->
                runCatching { lib.mpv_render_context_set_update_callback(rc, null, null) }
                runCatching { lib.mpv_render_context_free(rc) }
            }
            m?.let { runCatching { lib.mpv_terminate_destroy(it) } }
        }
    }
}

/**
 * 轮询等待条件满足, 超时返回 false。
 *
 * 可协作取消: 循环用 [delay] 而非 Thread.sleep, 每轮 [ensureActive] 检查; 离页协程取消时
 * delay 立即抛 CancellationException -> 调用方 finally 清理 native 实例(无需等满超时)。
 * 仅可在协程内调(事件线程是普通 Java Thread, 不调此函数)。
 */
private suspend fun waitCondition(condition: () -> Boolean, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition() && System.currentTimeMillis() < deadline) {
        currentCoroutineContext().ensureActive()
        delay(30)
    }
    return condition()
}

/** 读 mpv 字符串属性(mpv_get_property_string 返回 char* 需 mpv_free, 照 getPropertyDouble 的 finally 模式)。不可用/读失败返回 null。 */
private fun readPropertyString(lib: LibMpvAndroid, handle: Pointer, name: String): String? {
    val ptr = lib.mpv_get_property_string(handle, name) ?: return null
    return try { ptr.getString(0, "UTF-8").trim() } finally { lib.mpv_free(ptr) }
}

/**
 * seek 到 [seconds] 并等待落位(pause 态精确落位判据, 修"集照只生成首帧"根因)。
 *
 * 旧实现以 MPV_EVENT_SEEK(EventState.seekReceived)为完成信号, 该事件仅表示 seek 命令被受理
 * (入队即发), 目标帧尚未解码, 立刻 render 拿到 seek 前旧帧 -> 集照永远是首帧; pause 态
 * PLAYBACK_RESTART 可能迟到, OR 条件被 seekReceived 短路, 黑屏 1198B 小文件同源。
 *
 * 新判据依据(exa 查证 mpv 源码 player/command.c mp_property_seeking + player/core.h 注释, 2026-07 核实):
 * - `seeking` 属性实现即 `!restart_complete`: 从 seek 入队到核心完成 playback restart(hr-seek 精确
 *   解码推进到目标帧)期间为 yes, 落位后回 no;
 * - core.h 明确: "While paused, playback restart is still active, because you want seeking to work
 *   even if paused" —— pause 态同样完整走 restart 流程, seeking 正常回 no(不依赖 PLAYBACK_RESTART 事件);
 * - seek 命令 absolute 模式默认带 exact 标志(mpv 官方 input.rst: "exact is used for absolute seeks"),
 *   叠加 init 选项 hr-seek=yes 确保精确 seek 推进到目标帧而非停前一关键帧。
 *
 * 流程: 发 seek 命令 -> delay 80ms 让核心受理(防 seeking 尚未翻 yes 就读到 no 误判) -> 轮询
 * seeking==no(超时 30s; endFile 立即失败) -> 读 time-pos 校验与目标差 < 2.0s 才算落位
 * (流未出帧时 time-pos 输出 "N/A" 读不到, 允许重试 10 次×50ms)。落位失败/位置偏离过大
 * 记日志返回 false, 调用方不取帧(宁缺勿错)。[label] 区分首次/重试, 日志可读。
 */
private suspend fun seekAndWait(
    lib: LibMpvAndroid,
    handle: Pointer,
    seconds: Double,
    state: EventState,
    logger: AndroidAppLogger,
    label: String,
): Boolean {
    val t0 = System.currentTimeMillis()
    val seekRc = lib.mpv_command(handle, arrayOf("seek", seconds.toString(), "absolute"))
    if (seekRc < 0) {
        logger.appEvent(TAG, "seek($label) 命令失败 @${seconds}s: ${lib.mpv_error_string(seekRc)}", LogLevel.ERROR)
        return false
    }
    delay(80)   // 受理窗口: seek 命令入队到 seeking 翻 yes 有一轮事件往返延迟, 先等再轮询防误判
    val deadline = System.currentTimeMillis() + 30000
    while (readPropertyString(lib, handle, "seeking") != "no") {
        if (state.endFile) {
            logger.appEvent(TAG, "seek($label) @${seconds}s 被文件结束打断(reason=${state.endFileReason})", LogLevel.ERROR)
            return false
        }
        currentCoroutineContext().ensureActive()
        if (System.currentTimeMillis() >= deadline) {
            logger.appEvent(TAG, "seek($label) @${seconds}s 等 seeking=no 超时", LogLevel.ERROR)
            return false
        }
        delay(30)
    }
    val seekingWait = System.currentTimeMillis() - t0
    // 落位校验: time-pos 落在目标 ±2.0s 内(hr-seek 落位即精确解码到目标帧; 防 seeking 瞬变 no 的竞态误判)
    var timePos: Double? = null
    var retries = 0
    while (retries < 10) {
        currentCoroutineContext().ensureActive()
        val parsed = readPropertyString(lib, handle, "time-pos")?.toDoubleOrNull()
        if (parsed != null) { timePos = parsed; break }
        retries++
        delay(50)
    }
    val pos = timePos
    if (pos == null) {
        logger.appEvent(TAG, "seek($label) @${seconds}s time-pos 重试 $retries 次仍不可读, 判未落位", LogLevel.ERROR)
        return false
    }
    if (abs(pos - seconds) >= 2.0) {
        logger.appEvent(TAG, "seek($label) 落位偏差过大 seekTo=${seconds}s timePos=${pos}s, 不取帧", LogLevel.ERROR)
        return false
    }
    logger.appEvent(TAG, "seek($label) seekTo=${seconds}s seekingWait=${seekingWait}ms timePos=${pos}s")
    return true
}

/**
 * 检测空白帧(纯黑/纯色片头/过渡): 采样像素亮度方差 < 25(标准差 < 5)判定。
 * 纯色帧(黑/白/单色)方差~0, 正常画面方差远大; 比均值法更鲁棒(能识别纯色非黑片头)。
 * 每 4 像素采一个(提速), 320×180 仅采样 1440 点。
 *
 * internal: 供 androidHostTest 单测(逻辑纯函数, 不依赖 Android 环境)。
 */
internal fun isBlankFrame(raw: ByteArray, stride: Int, outW: Int, outH: Int): Boolean {
    var sum = 0L
    var sumSq = 0L
    var count = 0
    for (row in 0 until outH step 4) {
        for (col in 0 until outW step 4) {
            val idx = row * stride + col * 4
            if (idx + 2 < raw.size) {
                val lum = ((raw[idx].toInt() and 0xFF) + (raw[idx + 1].toInt() and 0xFF) + (raw[idx + 2].toInt() and 0xFF)) / 3
                sum += lum
                sumSq += lum.toLong() * lum
                count++
            }
        }
    }
    if (count == 0) return false
    val mean = sum.toDouble() / count
    val variance = sumSq.toDouble() / count - mean * mean
    return variance < 25.0
}
