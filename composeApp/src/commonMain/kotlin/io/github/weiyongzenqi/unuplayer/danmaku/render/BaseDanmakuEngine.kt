package io.github.weiyongzenqi.unuplayer.danmaku.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode
import kotlin.math.ceil

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
 * - 时间偏移不变量: 弹幕钟 = 视频时间 − timeOffsetSec(正=推迟, 弹幕比画面晚出现)。onSeek/onFrame
 *   一律把视频时间换算成弹幕钟再与弹幕时间轴(entries.timeSec)比较; frameSchedule 做反向换算。
 */
abstract class BaseDanmakuEngine : DanmakuEngine {

    private var disposed = false

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
    private var activationDeferred = false    // 子内核主动把缓存 miss 延后到下一帧，防单帧光栅化尖峰
    protected var playbackRate = 1f           // 倍速, setRate 注入(避免与 setRate 合成 setter 签名冲突)
    internal var lastActivationCandidateCount = 0
        private set
    internal val activeDanmakuCount: Int get() = active.size

    override fun load(entries: List<DanmakuEntry>) {
        this.entries = entries
        clearActive()
        cursor = binarySearchCursor(lastPosSec)
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

    final override fun dispose() {
        if (disposed) return
        disposed = true
        try {
            clear()
        } finally {
            onDispose()
        }
    }

    override fun setConfig(config: DanmakuConfig) {
        val old = configValue
        if (old == config) return
        configValue = config
        // B-06: 按字段 diff 决定清屏。只有影响已渲染弹幕几何/内容的字段变化才 clearActive;
        // opacity(graphicsLayer alpha)/maxOnScreen(只约束后续激活)等变化不清屏, 拖滑条不再全屏闪断。
        if (!needsActiveRebuild(old, config)) return
        clearActive()
        scrollAllocator.reset(); topAllocator.reset(); bottomAllocator.reset()
        // C-P1-1: 几何字段(displayArea/fontSize)变化必须重算 laneCount/laneHeight——
        // 否则清屏重建后轨道数/行高仍是旧值, 播放中拖"显示区域"滑条本次播放内完全无效。
        // 首帧前 lastScreenH==0 时跳过, 由 onFrame 尺寸分支照常重算。
        if (lastScreenH > 0f) recomputeLanes(lastScreenH)
        // cursor 基准偏移换算: lastPosSec 是按旧偏移算的弹幕钟(弹幕钟 = 视频时间 − timeOffsetSec, 正=推迟),
        // 偏移变化时先还原视频时间(= lastPosSec + 旧偏移)再减新偏移, 否则 cursor 陈旧一个偏移差,
        // 清屏重建会少补几条临场弹幕(误差不累积, onFrame 重算即自愈)。偏移未变/NaN(首帧)保持原行为。
        val cursorBase = if (old.timeOffsetSec != config.timeOffsetSec && !lastPosSec.isNaN()) {
            lastPosSec + old.timeOffsetSec - config.timeOffsetSec
        } else {
            lastPosSec
        }
        cursor = binarySearchCursor(cursorBase)
        lastPosSec = Double.NaN   // 重置 seek 检测, 首帧不判 seek
        forceRedraw = true
    }

    /**
     * B-06 字段级清屏判据: 新旧 config 哪些字段变化需要清空已渲染弹幕重建。
     *
     * 清屏(影响已渲染弹幕的几何/内容/可见性):
     * - fontSize:        轨道高度(laneHeight)与文本宽度都变, 存量弹幕尺寸/位置全错
     * - displayArea:     轨道数(laneCount)变, 存量轨道号映射的区域错位
     * - speedMultiplier: 滚动速度变 + 回看窗口(binarySearchCursor)变, 存量 x 推进速率失配
     * - strokeWidth:     描边参与光栅化(Atlas/Bitmap 载荷 cache key 含 strokeBits), 存量载荷是旧描边
     * - hideScroll/hideTop/hideBottom: 存量同类弹幕应变不可见(测试依赖 hideScroll 立即清空)
     * - timeOffsetSec:   时间轴平移, 激活时机与 cursor 基准全变
     * - engineType:      同实例内核类型不变(DanmakuLayer 按 engineType remember 引擎, 实际不可达), 新字段安全默认归类清屏
     *
     * 不清屏:
     * - opacity:      由 DanmakuCanvas 的 graphicsLayer alpha 应用, 不参与引擎绘制
     * - maxOnScreen:  只在激活时约束新弹幕(active.size >= effectiveMaxOnScreen), 不影响存量
     * - enabled:      关闭时 DanmakuLayer 整体退出组合, setConfig 收不到此变化(实际不可达)
     */
    private fun needsActiveRebuild(old: DanmakuConfig, new: DanmakuConfig): Boolean =
        old.fontSize != new.fontSize ||
            old.displayArea != new.displayArea ||
            old.speedMultiplier != new.speedMultiplier ||
            old.strokeWidth != new.strokeWidth ||
            old.hideScroll != new.hideScroll ||
            old.hideTop != new.hideTop ||
            old.hideBottom != new.hideBottom ||
            old.timeOffsetSec != new.timeOffsetSec ||
            old.engineType != new.engineType

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
        lastPosSec = positionMs / 1000.0 - config.timeOffsetSec   // 弹幕钟 = 视频时间 − 偏移(正=推迟); 非 NaN, 下一帧 rawDelta 从此基准算
        cursor = binarySearchCursor(lastPosSec)
        forceRedraw = true
    }

    override fun onFrame(positionMs: Long, screenW: Float, screenH: Float, deltaSec: Float): Boolean {
        if (screenW <= 0 || screenH <= 0) return false
        activationDeferred = false
        lastActivationCandidateCount = 0
        onFrameStarted()
        val posSec = positionMs / 1000.0 - config.timeOffsetSec   // 弹幕钟 = 视频时间 − 偏移(正=推迟: 弹幕比画面晚出现)

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
        val candidateBudget = activationCandidateBudgetPerFrame().coerceAtLeast(1)
        while (cursor < entries.size) {
            val e = entries[cursor]
            if (e.timeSec > posSec) break
            if (lastActivationCandidateCount >= candidateBudget) {
                activationDeferred = true
                break
            }
            lastActivationCandidateCount++
            val age = posSec - e.timeSec
            val dur = if (e.mode == DanmakuMode.SCROLL) scrollDur else FIXED_DURATION
            if (age >= dur) { cursor++; continue }
            if (isHidden(e.mode)) { cursor++; continue }
            // 同屏上限: 超出即丢弃(防高密度卡顿/遮挡); 0 映射到硬上限。cursor++ 单调前进, 被跳过的弹幕
            // 永久丢弃(名额空出也不补激活), 仅时间更晚的新弹幕会正常进入。与 B 站行为一致。
            if (active.size >= effectiveMaxOnScreen()) { cursor++; continue }
            // Atlas 等需要在主线程生成 native 载荷的内核可给 miss 设置单帧预算。延后时不推进 cursor，
            // 下一帧从同一条继续；已有缓存命中不受限，因而不会把高密度瞬时光栅化变成永久丢幕。
            if (shouldDeferActivation(e)) {
                activationDeferred = true
                break
            }
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

    override fun frameSchedule(): DanmakuFrameSchedule {
        if (activationDeferred) return DanmakuFrameSchedule.Continuous
        if (active.isNotEmpty()) return DanmakuFrameSchedule.Continuous
        val nextEntry = entries.getOrNull(cursor)
        val wakePositionMs = nextEntry?.let {
            // 反向换算: 弹幕在 timeSec(弹幕钟)激活, 对应视频时间 = 弹幕时刻 + 偏移(正=推迟 -> 更晚唤醒)
            ceil((it.timeSec + config.timeOffsetSec) * 1_000.0).toLong().coerceAtLeast(0L)
        }
        return DanmakuFrameSchedule.Suspend(wakePositionMs)
    }

    // === 子类实现 ===

    /** 测量+分配轨道+构造 [ActiveDanmaku] 加入 [active]; 返回 true=已加入。 */
    protected abstract fun activate(e: DanmakuEntry, posSec: Double, screenW: Float, baseSpeed: Float): Boolean

    /** 内核名(预留标识)。 */
    protected abstract fun engineName(): String

    /** 换集/清空时子类可清理自身缓存(如位图缓存)。默认空。 */
    protected open fun onEntriesReplaced() {}

    /** 引擎终态释放时关闭不可复用资源；由 [dispose] 保证只调用一次。 */
    protected open fun onDispose() {}

    /** 子引擎在活跃项离场时释放与其共享的 native 载荷。 */
    protected open fun onActiveRemoved(item: ActiveDanmaku) = Unit

    /** 每个有效 viewport 帧开始时调用；子内核可在这里重置单帧工作预算。 */
    protected open fun onFrameStarted() = Unit

    /** 返回 true 时保留当前 cursor 到下一帧，不激活也不丢弃该条目。 */
    protected open fun shouldDeferActivation(entry: DanmakuEntry): Boolean = false

    /** 单帧最多检查多少条已到时候选；默认不限，重载内核可削平突发主线程工作。 */
    protected open fun activationCandidateBudgetPerFrame(): Int = Int.MAX_VALUE

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
        // 高速滚动时滚动窗口可能短于固定弹幕的 5 秒；回看两者最大值，避免 seek/viewport 重建后
        // 漏补仍在生命周期内的顶部/底部弹幕。
        val lookBackDuration = maxOf(BASE_SCROLL_DURATION / speed, FIXED_DURATION)
        val target = posSec - lookBackDuration
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
