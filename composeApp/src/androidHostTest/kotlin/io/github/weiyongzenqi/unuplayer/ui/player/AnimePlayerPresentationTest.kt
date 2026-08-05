package io.github.weiyongzenqi.unuplayer.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnimePlayerPresentationTest {
    @Test
    fun `海报墙剧集在竖向且未请求全屏时显示播放详情`() {
        assertEquals(
            AnimePlayerPresentation.PORTRAIT_DETAIL,
            resolveAnimePlayerPresentation(
                hasAnimeDetail = true,
                fullscreenRequested = false,
                isPortraitOrientation = true,
            ),
        )
    }

    @Test
    fun `用户请求全屏后立即切为全屏呈现`() {
        assertEquals(
            AnimePlayerPresentation.FULLSCREEN,
            resolveAnimePlayerPresentation(
                hasAnimeDetail = true,
                fullscreenRequested = true,
                isPortraitOrientation = true,
            ),
        )
    }

    @Test
    fun `返回竖屏的旋转过渡阶段维持全屏直到方向完成`() {
        assertEquals(
            AnimePlayerPresentation.FULLSCREEN,
            resolveAnimePlayerPresentation(
                hasAnimeDetail = true,
                fullscreenRequested = false,
                isPortraitOrientation = false,
            ),
        )
    }

    @Test
    fun `非海报墙播放始终沿用全屏呈现`() {
        assertEquals(
            AnimePlayerPresentation.FULLSCREEN,
            resolveAnimePlayerPresentation(
                hasAnimeDetail = false,
                fullscreenRequested = false,
                isPortraitOrientation = true,
            ),
        )
    }

    @Test
    fun `横屏上下边缘只屏蔽纵向亮度音量手势`() {
        val unblockedIntents = PlayerGestureIntent.entries - PlayerGestureIntent.VERTICAL_DRAG
        unblockedIntents.forEach { intent ->
            assertFalse(blockedAt(intent = intent, presentation = AnimePlayerPresentation.FULLSCREEN, downY = 10f))
            assertFalse(blockedAt(intent = intent, presentation = AnimePlayerPresentation.FULLSCREEN, downY = 970f))
        }
        assertTrue(
            blockedAt(
                intent = PlayerGestureIntent.VERTICAL_DRAG,
                presentation = AnimePlayerPresentation.FULLSCREEN,
                downY = 10f,
            ),
        )
        assertTrue(
            blockedAt(
                intent = PlayerGestureIntent.VERTICAL_DRAG,
                presentation = AnimePlayerPresentation.FULLSCREEN,
                downY = 970f,
            ),
        )
        assertFalse(
            blockedAt(
                intent = PlayerGestureIntent.VERTICAL_DRAG,
                presentation = AnimePlayerPresentation.FULLSCREEN,
                downY = 500f,
            ),
        )
    }

    @Test
    fun `竖屏播放详情在上下边缘也没有手势死区`() {
        assertFalse(
            blockedAt(
                intent = PlayerGestureIntent.VERTICAL_DRAG,
                presentation = AnimePlayerPresentation.PORTRAIT_DETAIL,
                downY = 10f,
            ),
        )
        assertFalse(
            blockedAt(
                intent = PlayerGestureIntent.VERTICAL_DRAG,
                presentation = AnimePlayerPresentation.PORTRAIT_DETAIL,
                downY = 970f,
            ),
        )
        assertFalse(
            blockedAt(
                intent = PlayerGestureIntent.VERTICAL_DRAG,
                presentation = AnimePlayerPresentation.FULLSCREEN,
                downY = 10f,
                isPortraitOrientation = true,
            ),
            "全屏请求后的旋转过渡仍是物理竖屏，不应提前启用保护区",
        )
    }

    private fun blockedAt(
        intent: PlayerGestureIntent,
        presentation: AnimePlayerPresentation,
        downY: Float,
        isPortraitOrientation: Boolean = false,
    ) = shouldBlockPlayerGesture(
        presentation = presentation,
        intent = intent,
        isPortraitOrientation = isPortraitOrientation,
        downY = downY,
        height = 1_000f,
        topDeadZone = 28f,
        bottomDeadZone = 48f,
    )
}
