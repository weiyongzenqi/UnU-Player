package io.github.weiyongzenqi.unuplayer.library

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [EpisodeThumbPosition.toSeconds] 抽帧位置 -> 秒数换算边界测试。
 *
 * 该纯函数由两平台集照生成器(AndroidEpisodeThumbGenerator/DesktopEpisodeThumbGenerator)共用,
 * 换算逻辑原内联于各平台 generate(), 抽到 commonMain 后统一覆盖, 防两平台漂移。
 */
class EpisodeThumbPositionTest {

    @Test
    fun `Percent 正常时长按百分比取点`() {
        assertEquals(120.0, EpisodeThumbPosition.Percent(10).toSeconds(1200.0))
        assertEquals(600.0, EpisodeThumbPosition.Percent(50).toSeconds(1200.0))
        assertEquals(0.0, EpisodeThumbPosition.Percent(0).toSeconds(1200.0))
    }

    @Test
    fun `Percent 越界 coerce 到合法区间`() {
        // 超 100% 钳到 duration; 负值钳到 0
        assertEquals(1200.0, EpisodeThumbPosition.Percent(150).toSeconds(1200.0))
        assertEquals(0.0, EpisodeThumbPosition.Percent(-5).toSeconds(1200.0))
    }

    @Test
    fun `Percent 取不到时长回落 0`() {
        assertEquals(0.0, EpisodeThumbPosition.Percent(10).toSeconds(0.0))
        assertEquals(0.0, EpisodeThumbPosition.Percent(10).toSeconds(-1.0))
    }

    @Test
    fun `Seconds 正常用指定秒数`() {
        assertEquals(30.0, EpisodeThumbPosition.Seconds(30).toSeconds(1200.0))
        assertEquals(0.0, EpisodeThumbPosition.Seconds(0).toSeconds(1200.0))
    }

    @Test
    fun `Seconds 短视频回落 10 百分比`() {
        // duration < value: 回落 duration*0.1; value == duration 同样视为短视频
        assertEquals(2.0, EpisodeThumbPosition.Seconds(30).toSeconds(20.0))
        assertEquals(2.0, EpisodeThumbPosition.Seconds(20).toSeconds(20.0))
    }

    @Test
    fun `Seconds 取不到时长回落 0`() {
        assertEquals(0.0, EpisodeThumbPosition.Seconds(30).toSeconds(0.0))
        assertEquals(0.0, EpisodeThumbPosition.Seconds(30).toSeconds(-3.0))
    }
}
