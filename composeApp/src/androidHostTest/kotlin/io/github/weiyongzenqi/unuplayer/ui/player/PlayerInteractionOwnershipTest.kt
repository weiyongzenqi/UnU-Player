package io.github.weiyongzenqi.unuplayer.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerInteractionOwnershipTest {

    @Test
    fun `旧控制层 timeout 不能关闭续期后的控制层`() {
        assertFalse(
            shouldHidePlayerControls(
                timeoutGeneration = 4L,
                currentGeneration = 5L,
                gestureActive = false,
            ),
        )
        assertTrue(
            shouldHidePlayerControls(
                timeoutGeneration = 5L,
                currentGeneration = 5L,
                gestureActive = false,
            ),
        )
    }

    @Test
    fun `拖动跨过 timeout 时不能隐藏且结束后由新代次重新计时`() {
        assertFalse(
            shouldHidePlayerControls(
                timeoutGeneration = 8L,
                currentGeneration = 8L,
                gestureActive = true,
            ),
        )
        assertFalse(
            shouldHidePlayerControls(
                timeoutGeneration = 8L,
                currentGeneration = 9L,
                gestureActive = false,
            ),
        )
    }

    @Test
    fun `第二指接管后直到本轮结束都不退回单指所有权`() {
        var owner = resolvePlayerGestureOwner(PlayerGestureOwner.SINGLE, pressedPointerCount = 1)
        assertEquals(PlayerGestureOwner.SINGLE, owner)

        owner = resolvePlayerGestureOwner(owner, pressedPointerCount = 2)
        assertEquals(PlayerGestureOwner.TRANSFORM, owner)

        owner = resolvePlayerGestureOwner(owner, pressedPointerCount = 1)
        assertEquals(PlayerGestureOwner.TRANSFORM, owner)
        owner = resolvePlayerGestureOwner(owner, pressedPointerCount = 0)
        assertEquals(PlayerGestureOwner.TRANSFORM, owner)
    }
}
