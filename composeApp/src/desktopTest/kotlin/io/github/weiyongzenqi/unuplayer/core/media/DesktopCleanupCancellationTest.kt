package io.github.weiyongzenqi.unuplayer.core.media

import io.github.weiyongzenqi.unuplayer.ui.settings.DesktopCleanupAction
import io.github.weiyongzenqi.unuplayer.ui.settings.collectDesktopCleanupFailures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopCleanupCancellationTest {

    @Test
    fun `全部清理遇取消后不执行后续副作用`() = runBlocking {
        var laterRuns = 0

        assertFailsWith<CancellationException> {
            collectDesktopCleanupFailures(
                listOf(
                    DesktopCleanupAction("已取消") { throw CancellationException("cancel") },
                    DesktopCleanupAction("不应执行") { laterRuns++ },
                ),
            )
        }
        assertEquals(0, laterRuns)
    }

    @Test
    fun `首次字幕会话清理继续传播取消`() = runBlocking {
        assertFailsWith<CancellationException> {
            runBestEffortStaleSubtitleCleanup {
                throw CancellationException("cancel")
            }
        }
        Unit
    }

    @Test
    fun `全部清理仍汇总普通失败并继续其它步骤`() = runBlocking {
        var laterRuns = 0
        val failures = collectDesktopCleanupFailures(
            listOf(
                DesktopCleanupAction("失败项") { error("fault") },
                DesktopCleanupAction("成功项") { laterRuns++ },
            ),
        )

        assertEquals(listOf("失败项"), failures)
        assertEquals(1, laterRuns)
    }
}
