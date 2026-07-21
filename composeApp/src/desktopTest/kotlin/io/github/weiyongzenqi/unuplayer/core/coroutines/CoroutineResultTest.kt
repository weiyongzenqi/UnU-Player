package io.github.weiyongzenqi.unuplayer.core.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CoroutineResultTest {

    @Test
    fun `普通成功与业务失败仍可表示为 Result`(): Unit = runBlocking {
        assertEquals("ok", runSuspendCatching { "ok" }.getOrThrow())
        assertIs<IllegalStateException>(runSuspendCatching { error("fault") }.exceptionOrNull())
    }

    @Test
    fun `CancellationException 必须继续传播`(): Unit = runBlocking {
        assertFailsWith<CancellationException> {
            runSuspendCatching<Unit> { throw CancellationException("cancel") }
        }
    }

    @Test
    fun `不协作取消的旧请求返回后也不能发布结果`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        val job = launch {
            runSuspendCatching {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
                "stale"
            }.onSuccess {
                published = true
            }
        }

        entered.await()
        job.cancel()
        release.complete(Unit)
        job.cancelAndJoin()

        assertFalse(published)
    }

    @Test
    fun `不协作取消的旧请求失败后也不能发布错误`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        val job = launch {
            runSuspendCatching<Unit> {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
                error("stale failure")
            }.onFailure {
                published = true
            }
        }

        entered.await()
        job.cancel()
        release.complete(Unit)
        job.cancelAndJoin()

        assertFalse(published)
    }
}
