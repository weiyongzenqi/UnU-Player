package io.github.weiyongzenqi.unuplayer.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerLaunchLocatorTest {

    @Test
    fun `直接媒体与完整媒体服务器定位可启动`() {
        assertTrue(isPlayerLaunchLocatorValid("content://video/1", null, null))
        assertTrue(isPlayerLaunchLocatorValid(null, "connection", "item"))
    }

    @Test
    fun `空定位或残缺媒体服务器定位必须拒绝`() {
        assertFalse(isPlayerLaunchLocatorValid(null, null, null))
        assertFalse(isPlayerLaunchLocatorValid(null, "connection", null))
        assertFalse(isPlayerLaunchLocatorValid(null, null, "item"))
        assertFalse(isPlayerLaunchLocatorValid("content://video/1", "connection", null))
    }
}
