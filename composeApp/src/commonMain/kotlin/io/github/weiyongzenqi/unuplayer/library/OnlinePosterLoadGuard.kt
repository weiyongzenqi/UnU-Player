package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 海报墙在线封面一次性加载守卫(批次C, 进程/会话级): 同一 key(「libraryId|showPath」)在本次应用
 * 启动期间只放行第一次尝试, 之后(无论成败)一律拒绝 —— 用户硬性约束「每次启动一部番只尝试一次,
 * 绝不无限重试」。先标记后尝试: 即使尝试被取消/失败, 该番本会话也不会再次触发。
 *
 * 协程 Mutex 保护(commonMain 禁 java.util.concurrent); 不落库、不持久, 进程结束即清零,
 * 下次启动天然获得新一轮「每番一次」额度。
 */
object OnlinePosterLoadGuard {
    private val mutex = Mutex()
    private val attempted = mutableSetOf<String>()

    /** 只读查询: 该 key 本会话是否已尝试过(不消耗额度)。 */
    suspend fun isAttempted(key: String): Boolean = mutex.withLock { key in attempted }

    /** 首次对该 key 发起尝试返回 true; 本会话已尝试过(含失败)返回 false。 */
    suspend fun markAttempted(key: String): Boolean = mutex.withLock {
        if (attempted.contains(key)) {
            false
        } else {
            attempted.add(key)
            true
        }
    }
}
