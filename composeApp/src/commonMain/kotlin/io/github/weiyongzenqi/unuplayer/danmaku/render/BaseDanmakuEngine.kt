package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode

/**
 * 弹幕渲染引擎基类(commonMain)。封装与渲染方式无关的共享逻辑:
 * 弹幕列表 / 活跃集 / cursor / 轨道分配 / 时间轴推进 / seek / 倍速 / 暂停。
 *
 * 子类只实现两件事:
 * - [activate]: 测量一条弹幕 + 分配轨道 + 构造 [ActiveDanmaku](含渲染载荷)加入 [active]
 * - [draw]: 把 [active] 画到 [DrawScope](用各自载荷: TextLayoutResult / ImageBitmap)
 *
 * 运动模型(增量式墙钟, 关键, 改前务必读):
 * - 滚动用墙钟增量 × rate 推进, 不用 time-pos 增量; 增量式 `d.x -= advanceSpeed * wallDelta`
 *   -> 倍速切换只改 advanceSpeed, x 连续不瞬移; time-pos 只用于激活时机 + seek 检测。
 * - 暂停冻结(wallDelta=0); seek 帧不推进(防跳); 正常 clamp 防帧丢时大跳。
 * - seek 检测: `|posSec - lastPosSec| > SEEK_THRESHOLD` 时清空 + 按新时间重激活。
 * - 进入续播靠 [onFrame] 内 seek 检测清空重激活, 不卡 0(首帧 lastPosSec=NaN 不判 seek)。
 */
abstract class BaseDanmakuEngine : DanmakuEngine {

    protected var entries: List<DanmakuEntry> = emptyList()
    protected val active = ArrayList<ActiveDanmaku>()
    protected var cursor = 0

    protected var configValue = DanmakuConfig()
    protected val config: DanmakuConfig get() = configValue
    protected var pxPerSpValue = 1f          // px/sp, setFontScalePx 走此
    protected val fontScalePx: Float get() = pxPerSpValue
    protected var forceRedraw = true

    protected var scrollAllocator = ScrollLaneAllocator(0)
    protected var topAllocator = FixedLaneAllocator(0)
    protected var bottomAllocator = FixedLaneAllocator(0)

    protected var laneHeight = 0f
    protected var laneCount = 0
    private var lastScreenW = 0f
    private var lastScreenH = 0f
    private var lastFontKey = 0L
    private var lastPosSec = Double.NaN       // seek 检测基准(NaN=首帧不判 seek)
    private var paused = false                // 暂停/缓冲时墙钟冻结
    protected var playbackRate = 1f           // 倍速, setRate 注入(避免与 setRate 合成 setter 签名冲突)

    override fun load(entries: List<DanmakuEntry>) {
        this.entries = entries.sortedBy { it.timeSec }
        clearActive()
        cursor = binarySearchCursor(lastPosSec + config.timeOffsetSec)
        scrollAllocator.reset(); topAllocator.reset(); bottomAllocator.reset()
        lastPosSec = Double.NaN   // 重置 seek 检测, 首帧不判 seek
        onEntriesReplaced()
        forceRedraw = true
    }

    override fun clear() {
        entries = emptyList(); clearActive(); cursor = 0
        scrollAllocator.reset(); topAllocator.reset(); bottomAllocator.reset()
        onEntriesReplaced()
    }

    override fun setConfig(config: DanmakuConfig) {
        if (configValue == config) return
        configValue = config
        clearActive()
        scrollAllocator.reset(); topAllocator.reset(); bottomAllocator.reset()
        cursor = binarySearchCursor(lastPosSec + config.timeOffsetSec)
        lastPosSec = Double.NaN   // 重置 seek 检测, 首帧不判 seek
        forceRedraw = true
    }

    /** 暂停/缓冲时弹幕不动，也不持续重绘。 */
    override fun setPaused(paused: Boolean) {
        if (this.paused != paused) {
            this.paused = paused
            forceRedraw = true   // 状态切换要重绘一帧
        }
    }

    /** 倍速联动: 注入 rate, [onFrame] 用 advanceSpeed = baseSpeed × rate 推进滚动。 */
    override fun setRate(rate: Float) {
        this.playbackRate = rate.coerceAtLeast(0.1f)
    }

    override fun setFontScalePx(px: Float) {
        if (px > 0f && px != pxPerSpValue) {
            pxPerSpValue = px
            clearActive()
            scrollAllocator.reset(); topAllocator.reset(); bottomAllocator.reset()
            lastPosSec = Double.NaN   // 重置 seek 检测, 首帧不判 seek
            forceRedraw = true
        }
    }

    override fun onSeek(positionMs: Long) {
        clearActive()
        scrollAllocator.reset(); topAllocator.reset(); bottomAllocator.reset()
        lastPosSec = positionMs / 1000.0 + config.timeOffsetSec   // 非 NaN, 下一帧 rawDelta 从此基准算
        cursor = binarySearchCursor(lastPosSec)
        forceRedraw = true
    }

    override fun onFrame(positionMs: Long, screenW: Float, screenH: Float, deltaSec: Float): Boolean {
        if (screenW <= 0 || screenH <= 0) return false
        val posSec = positionMs / 1000.0 + config.timeOffsetSec

        val fontKey = fontKey()
        if (screenW != lastScreenW || screenH != lastScreenH || fontKey != lastFontKey) {
            lastScreenW = screenW; lastScreenH = screenH; lastFontKey = fontKey
            clearActive()
            recomputeLanes(screenH)
            cursor = binarySearchCursor(posSec)
            forceRedraw = true   // 尺寸/字号变了要重绘(暂停时也不会漏)
        }

        // seek 检测: 视频时间跳变(拖进度/续播 seek 完成时 positionFlow 跳)。墙钟运动不依赖 time-pos
        // 增量(它突发上报会抖), 只用 time-pos 判断"是否 seek 了"--seek 时清空 + 按新时间重激活;
        // 非 seek 平滑。首帧(lastPosSec=NaN)rawDelta=0, 不判 seek。
        val rawDelta = if (lastPosSec.isNaN()) 0.0 else posSec - lastPosSec
        lastPosSec = posSec
        val seekDetected = rawDelta > SEEK_THRESHOLD || rawDelta < -SEEK_THRESHOLD
        if (seekDetected) {
            clearActive()
            scrollAllocator.reset(); topAllocator.reset(); bottomAllocator.reset()
            cursor = binarySearchCursor(posSec)
            forceRedraw = true
        }

        // 墙钟运动增量: 暂停冻结; seek 帧不推进(防跳); 正常 clamp 防帧丢时大跳
        val wallDelta = if (paused || seekDetected) 0f else deltaSec.coerceIn(0f, MAX_WALL_DELTA)
        val baseSpeed = scrollSpeed(screenW)            // px/视频秒, 不含 rate
        val advanceSpeed = baseSpeed * playbackRate         // px/墙钟秒 = baseSpeed × rate(倍速时快)
        val scrollDur = BASE_SCROLL_DURATION / config.speedMultiplier.coerceAtLeast(0.01f)  // 视频秒, 不含 rate
        var activated = false

        // 激活(按视频时间 posSec; cursor 单调, 已过期/已屏蔽跳过)
        while (cursor < entries.size) {
            val e = entries[cursor]
            if (e.timeSec > posSec) break
            val age = posSec - e.timeSec
            val dur = if (e.mode == DanmakuMode.SCROLL) scrollDur else FIXED_DURATION
            if (age >= dur) { cursor++; continue }
            if (isHidden(e.mode)) { cursor++; continue }
            // 同屏上限: 超出即丢弃(防高密度卡顿/遮挡); 0 映射到硬上限。cursor++ 单调前进, 被跳过的弹幕
            // 永久丢弃(名额空出也不补激活), 仅时间更晚的新弹幕会正常进入。与 B 站行为一致。
            if (active.size >= effectiveMaxOnScreen()) { cursor++; continue }
            if (activate(e, posSec, screenW, baseSpeed)) activated = true
            cursor++
        }

        // 推进(墙钟 × advanceSpeed)+ 回收。增量式: 倍速切换只改 advanceSpeed, x 连续不瞬移。
        // onActiveRemoved 必须调(多内核 payload 引用计数: Bitmap 回收位图/Atlas 释放 region)。
        val it = active.iterator()
        while (it.hasNext()) {
            val d = it.next()
            when (d.entry.mode) {
                DanmakuMode.SCROLL -> {
                    d.x -= advanceSpeed * wallDelta
                    if (d.x < -d.width) {
                        it.remove()
                        onActiveRemoved(d)
                    }
                }
                DanmakuMode.TOP, DanmakuMode.BOTTOM -> {
                    if (posSec > d.entry.timeSec + FIXED_DURATION) {
                        it.remove()
                        onActiveRemoved(d)
                    }
                }
                else -> {
                    it.remove()
                    onActiveRemoved(d)
                }
            }
        }

        // 播放中且有活跃弹幕 -> 每帧重绘(运动); 暂停/无弹幕/seek 帧 -> 不重绘(省 GPU)
        val dirty = forceRedraw || activated || (!paused && wallDelta > 0f && active.isNotEmpty())
        forceRedraw = false
        return dirty
    }

    // === 子类实现 ===

    /** 测量+分配轨道+构造 [ActiveDanmaku] 加入 [active]; 返回 true=已加入。 */
    protected abstract fun activate(e: DanmakuEntry, posSec: Double, screenW: Float, baseSpeed: Float): Boolean

    /** 内核名(预留标识)。 */
    protected abstract fun engineName(): String

    /** 换集/清空时子类可清理自身缓存(如位图缓存)。默认空。 */
    protected open fun onEntriesReplaced() {}

    /** 子引擎在活跃项离场时释放与其共享的 native 载荷。 */
    protected open fun onActiveRemoved(item: ActiveDanmaku) = Unit

    private fun clearActive() {
        active.forEach(::onActiveRemoved)
        active.clear()
    }

    // === 共享辅助 ===

    protected fun effectiveFontSp(): Float =
        if (config.fontSize > 0f) config.fontSize else DEFAULT_FONT_SP

    protected fun effectiveMaxOnScreen(): Int =
        if (config.maxOnScreen <= 0) MAX_ON_SCREEN_HARD_LIMIT
        else config.maxOnScreen.coerceAtMost(MAX_ON_SCREEN_HARD_LIMIT)

    /** 滚动速度(px/视频秒) = screenW / 基准时长 × 速度倍率。**不含 rate**。 */
    protected fun scrollSpeed(screenW: Float): Float =
        (screenW * config.speedMultiplier.coerceAtLeast(0.01f) / BASE_SCROLL_DURATION).toFloat()

    protected fun recomputeLanes(screenH: Float) {
        laneHeight = effectiveFontSp() * fontScalePx * LINE_HEIGHT_FACTOR
        val usable = screenH * config.displayArea
        laneCount = ((usable / laneHeight).toInt()).coerceAtLeast(1)
        scrollAllocator = ScrollLaneAllocator(laneCount)
        topAllocator = FixedLaneAllocator(laneCount)
        bottomAllocator = FixedLaneAllocator(laneCount)
    }

    protected fun isHidden(mode: DanmakuMode): Boolean = when (mode) {
        DanmakuMode.SCROLL -> config.hideScroll
        DanmakuMode.TOP -> config.hideTop
        DanmakuMode.BOTTOM -> config.hideBottom
        else -> true  // REVERSE/SPECIAL 暂不渲染
    }

    protected fun laneY(mode: DanmakuMode, lane: Int, screenH: Float): Float = when (mode) {
        DanmakuMode.BOTTOM -> screenH - (lane + 1) * laneHeight
        else -> lane * laneHeight
    }

    protected fun rgbToColor(rgb: Int): Color {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return Color(r / 255f, g / 255f, b / 255f)
    }

    private fun fontKey(): Long =
        (effectiveFontSp().toBits().toLong() shl 32) or fontScalePx.toRawBits().toLong()

    private fun binarySearchCursor(posSec: Double): Int {
        val speed = config.speedMultiplier.coerceAtLeast(0.01f)
        val target = posSec - BASE_SCROLL_DURATION / speed
        var lo = 0; var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].timeSec < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val BASE_SCROLL_DURATION = 8.0   // 基准滚动时长(视频秒, 1x 一屏)
        const val FIXED_DURATION = 5.0          // 顶/底弹幕显示时长(视频秒)
        const val DEFAULT_FONT_SP = 16f         // 默认字号 sp(config.fontSize=0 时)
        const val LINE_HEIGHT_FACTOR = 1.5f     // 行高 = 字号px × 此系数
        const val MAX_ON_SCREEN_HARD_LIMIT = 5_000
        const val SEEK_THRESHOLD = 1.0          // 视频秒, |rawDelta|超此判 seek
        const val MAX_WALL_DELTA = 0.1f         // 墙钟秒, 单帧推进上限防帧丢大跳
    }
}
