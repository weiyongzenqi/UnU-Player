package io.github.weiyongzenqi.unuplayer.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MpvInitializationStepsTest {

    @Test
    fun `字幕样式必须在 Surface 和 READY 发布前应用`() {
        val steps = mutableListOf<String>()

        runMpvInitializationSteps(
            applyOptions = { steps += "options" },
            initializeNative = { steps += "native" },
            registerObservers = { steps += "observers" },
            applyInitialSubtitleStyle = { steps += "subtitle" },
            attachSurfaceAndPublish = { steps += "surface-ready" },
        )

        assertEquals(
            listOf("options", "native", "observers", "subtitle", "surface-ready"),
            steps,
        )
    }

    @Test
    fun `字幕样式失败时不得绑定 Surface 或发布 READY`() {
        val steps = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            runMpvInitializationSteps(
                applyOptions = { steps += "options" },
                initializeNative = { steps += "native" },
                registerObservers = { steps += "observers" },
                applyInitialSubtitleStyle = {
                    steps += "subtitle"
                    error("subtitle failed")
                },
                attachSurfaceAndPublish = { steps += "surface-ready" },
            )
        }

        assertEquals(listOf("options", "native", "observers", "subtitle"), steps)
    }

    @Test
    fun `退出信号会在下一初始化阶段前中止事务`() {
        val steps = mutableListOf<String>()
        var checks = 0

        assertFailsWith<IllegalStateException> {
            runMpvInitializationSteps(
                applyOptions = { steps += "options" },
                initializeNative = { steps += "native" },
                registerObservers = { steps += "observers" },
                applyInitialSubtitleStyle = { steps += "subtitle" },
                attachSurfaceAndPublish = { steps += "surface-ready" },
                checkActive = {
                    checks++
                    if (checks == 3) error("released")
                },
            )
        }

        assertEquals(listOf("options", "native"), steps)
    }
}
