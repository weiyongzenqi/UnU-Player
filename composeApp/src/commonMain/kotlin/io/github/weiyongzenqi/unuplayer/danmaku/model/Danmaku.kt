package io.github.weiyongzenqi.unuplayer.danmaku.model

/**
 * 弹幕来源。用于多源聚合与按源过滤(渲染层 hide* / 屏蔽规则用)。
 */
enum class DanmakuSource { DANDANPLAY, BILI, LOCAL, CUSTOM }

/**
 * 弹幕运动模式。code 对齐弹弹play / B站 mode 字段。
 *
 * - [SCROLL]   code=1  从右向左滚动
 * - [BOTTOM]   code=4  底部固定
 * - [TOP]      code=5  顶部固定
 * - [REVERSE]  code=6  从左向右逆向滚动
 * - [SPECIAL]  code=7  高级(特效/定位)
 *
 * 未知 code 兜底为 [SCROLL](最常见)。
 */
enum class DanmakuMode(val code: Int) {
    SCROLL(1),
    BOTTOM(4),
    TOP(5),
    REVERSE(6),
    SPECIAL(7);

    companion object {
        fun fromCode(code: Int): DanmakuMode =
            entries.firstOrNull { it.code == code } ?: SCROLL
    }
}

/**
 * 单条弹幕(归一化后的不可变数据)。渲染层只认此模型。
 *
 * 两种数据源归一化到此结构:
 * - 弹弹play comment: `p="time,mode,color,uid"`(4 段, 无字号)
 * - B站 XML:          `p="time,mode,fontsize,color,sendtime,pool,userhash,id"`(8 段, 有字号)
 *
 * @param timeSec  出现时间(秒, 相对视频开头)
 * @param mode     运动模式
 * @param color    RGB 十进制(白色 = 16777215 = 0xFFFFFF)
 * @param text     弹幕文本
 * @param source   来源
 * @param fontSize 字号(B站 XML 有, 弹弹play 无; null = 用播放器默认)
 */
data class DanmakuEntry(
    val timeSec: Double,
    val mode: DanmakuMode,
    val color: Int,
    val text: String,
    val source: DanmakuSource,
    val fontSize: Int? = null,
)
