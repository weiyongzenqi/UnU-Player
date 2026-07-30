package io.github.weiyongzenqi.unuplayer.danmaku.render

/**
 * 弹幕轨道分配算法(纯算法, 平台无关, 可单测)。
 *
 * 分两类:
 * - [ScrollLaneAllocator] 滚动弹幕: 同轨道新弹幕不追上前一个(前一个已完全进入屏幕)
 * - [FixedLaneAllocator]  顶部/底部弹幕: 同轨道显示固定时长, 找已释放轨道
 *
 * 轨道号 0=最上, laneCount-1=最下。laneHeight 由调用方算(字号 + 行距)。
 */

/**
 * 滚动弹幕轨道分配器。
 *
 * 不重叠条件: 新弹幕 B(timeB 进入, 左边缘=screenW)不追上前弹幕 A(timeA 进入):
 * B 左边缘(screenW) >= A 右边缘(screenW - (timeB-timeA)*speed + widthA)
 * 化简: (timeB - timeA) * speed >= widthA(A 已左移至少 widthA, 即 A 完全进入屏幕)。
 */
class ScrollLaneAllocator(private val laneCount: Int) {
    // 每轨道最后一个弹幕的进入时间(秒) + 宽度
    private val enterTime = DoubleArray(laneCount) { Double.NEGATIVE_INFINITY }
    private val lastWidth = FloatArray(laneCount)

    /**
     * 分配轨道。
     *
     * @param timeSec 弹幕出现时间(秒)
     * @param width 弹幕宽度(px)
     * @param speed 滚动速度(px/秒) = screenW / durationSec
     * @return 轨道号; -1 = 全占满(跳过此弹幕)
     */
    fun allocate(timeSec: Double, width: Float, speed: Float): Int {
        val lane = findAvailableLane(timeSec, speed)
        if (lane >= 0) occupy(lane, timeSec, width)
        return lane
    }

    /** 只查询可用轨道，不修改状态；供需要先准备可能失败载荷的内核做事务化预留。 */
    fun findAvailableLane(timeSec: Double, speed: Float): Int {
        for (lane in 0 until laneCount) {
            val prev = enterTime[lane]
            if (prev.isInfinite() || (timeSec - prev) * speed >= lastWidth[lane]) {
                return lane
            }
        }
        return -1
    }

    /** 提交由 [findAvailableLane] 返回的轨道；调用方必须保证查询与提交之间没有其他分配。 */
    fun occupy(lane: Int, timeSec: Double, width: Float) {
        require(lane in 0 until laneCount) { "滚动弹幕轨道越界: $lane" }
        enterTime[lane] = timeSec
        lastWidth[lane] = width
    }

    fun reset() {
        enterTime.fill(Double.NEGATIVE_INFINITY)
        lastWidth.fill(0f)
    }
}

/**
 * 顶部/底部固定弹幕轨道分配器。
 *
 * 同轨道弹幕显示 [durationSec] 秒, 期间占用; 新弹幕找 occupiedUntil <= time 的轨道。
 */
class FixedLaneAllocator(private val laneCount: Int) {
    private val occupiedUntil = DoubleArray(laneCount) { Double.NEGATIVE_INFINITY }

    /** @return 轨道号; -1 = 全占满 */
    fun allocate(timeSec: Double, durationSec: Double): Int {
        val lane = findAvailableLane(timeSec)
        if (lane >= 0) occupy(lane, timeSec, durationSec)
        return lane
    }

    /** 只查询可用轨道，不修改状态；载荷准备成功后再用 [occupy] 提交。 */
    fun findAvailableLane(timeSec: Double): Int {
        for (lane in 0 until laneCount) {
            if (occupiedUntil[lane] <= timeSec) {
                return lane
            }
        }
        return -1
    }

    /** 提交由 [findAvailableLane] 返回的固定弹幕轨道。 */
    fun occupy(lane: Int, timeSec: Double, durationSec: Double) {
        require(lane in 0 until laneCount) { "固定弹幕轨道越界: $lane" }
        occupiedUntil[lane] = timeSec + durationSec
    }

    fun reset() {
        occupiedUntil.fill(Double.NEGATIVE_INFINITY)
    }
}
