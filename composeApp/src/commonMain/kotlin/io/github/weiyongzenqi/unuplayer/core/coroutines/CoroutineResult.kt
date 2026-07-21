package io.github.weiyongzenqi.unuplayer.core.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 只在确实允许业务失败降级为 [Result] 的 suspend 边界使用。
 *
 * 与标准 [runCatching] 不同：协程取消始终继续向上传播；block 若不协作取消、在取消后仍返回，
 * 返回结果前的 [ensureActive] 也会阻止旧请求继续发布 UI/缓存状态。
 */
suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> = try {
    currentCoroutineContext().ensureActive()
    val value = block()
    currentCoroutineContext().ensureActive()
    Result.success(value)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    // 任务可能在非协作 block 抛出业务异常前已经被取消；此时也不能发布旧错误状态。
    currentCoroutineContext().ensureActive()
    Result.failure(error)
}
