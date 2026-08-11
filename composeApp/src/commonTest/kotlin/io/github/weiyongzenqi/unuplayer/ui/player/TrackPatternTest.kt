package io.github.weiyongzenqi.unuplayer.ui.player

import io.github.weiyongzenqi.unuplayer.core.player.TrackInfo
import io.github.weiyongzenqi.unuplayer.core.player.TrackType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackPatternTest {

    @Test
    fun `合法轨道正则保持忽略大小写语义`() {
        val track = TrackInfo(id = 1, type = TrackType.AUDIO, title = "Japanese Audio", lang = "JPN")

        assertTrue(track.matchesTrackPattern("japanese|jpn"))
        assertFalse(track.matchesTrackPattern("english|eng"))
    }

    @Test
    fun `非法正则回退为忽略大小写的字面包含`() {
        val track = TrackInfo(id = 2, type = TrackType.SUBTITLE, title = "Chinese [Simplified]", lang = "ZHO")

        assertTrue(track.matchesTrackPattern("["))
    }

    @Test
    fun `超长模式不进入正则编译`() {
        val literalPrefix = "a".repeat(256)
        val track = TrackInfo(id = 3, type = TrackType.AUDIO, title = "${literalPrefix}x", lang = null)

        assertFalse(track.matchesTrackPattern("${literalPrefix}."))
    }
}
