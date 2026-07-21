package io.github.weiyongzenqi.unuplayer.danmaku.render

import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry

/**
 * 活跃弹幕(运行时可变状态)。数据/绘制分离: [entry] 不可变, [x] 随帧推进。
 *
 * @param entry 原始弹幕数据
 * @param lane 分配的轨道号
 * @param width 文本测量宽度(px)
 * @param x 当前左边缘 x 坐标(px); 滚动: 从 screenW 滑到 -width; 顶/底: 居中左边缘
 * @param payload 渲染载荷(引擎相关): Compose 内核=TextLayoutResult, 位图内核=ImageBitmap。
 *   激活时测一次存入, draw 直接用, 避免每帧重测/重查缓存。
 */
class ActiveDanmaku(
    val entry: DanmakuEntry,
    val lane: Int,
    val width: Float,
    var x: Float,
    val payload: Any? = null,
)
