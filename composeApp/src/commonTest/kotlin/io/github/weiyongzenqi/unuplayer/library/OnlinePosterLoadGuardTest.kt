package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 海报墙在线封面一次性守卫纯逻辑测试(批次C, commonTest)。
 * 守卫是进程级单例且故意不提供重置接口(会话语义), 各用例用互不重叠的 key 防串扰。
 */
class OnlinePosterLoadGuardTest {

    @Test
    fun `同一key首次放行之后一律拒绝`() = runBlocking {
        // 修复前失败点: 守卫不存在/不做去重时第二次仍返回 true → 同一次启动内对同一番剧可反复重试,
        // 违反用户硬性约束「每次启动一部番只尝试一次」。
        val key = "guard-case1|show-a"
        assertFalse(OnlinePosterLoadGuard.isAttempted(key), "未尝试前 isAttempted 应为 false")
        assertTrue(OnlinePosterLoadGuard.markAttempted(key))
        assertTrue(OnlinePosterLoadGuard.isAttempted(key), "尝试后只读查询应可见")
        assertFalse(OnlinePosterLoadGuard.markAttempted(key))
        assertFalse(OnlinePosterLoadGuard.markAttempted(key))
    }

    @Test
    fun `不同key互不影响`() = runBlocking {
        // 修复前失败点: 若错误地以单一全局布尔实现(只记"已尝试过"), 第二个番剧会被第一个的记录挡掉。
        val keyA = "guard-case2|show-a"
        val keyB = "guard-case2|show-b"
        assertTrue(OnlinePosterLoadGuard.markAttempted(keyA))
        assertTrue(OnlinePosterLoadGuard.markAttempted(keyB))
        assertFalse(OnlinePosterLoadGuard.markAttempted(keyA))
        assertFalse(OnlinePosterLoadGuard.markAttempted(keyB))
    }

    @Test
    fun `同一番剧不同季各占一次会话配额`() = runBlocking {
        // 修复前失败点: 守卫 key 不含季号 → 多季番补完最高季刷新后, 低缺封季被整番锁死本会话不再试。
        // 契约: key 含季号(libraryId|showPath|seasonNumber), 每缺封季独立获得一次唯一额度。
        val season2 = "guard-case-season|show-multi|2"
        val season1 = "guard-case-season|show-multi|1"
        assertTrue(OnlinePosterLoadGuard.markAttempted(season2))
        assertTrue(OnlinePosterLoadGuard.markAttempted(season1), "季2 已试不阻塞季1 的额度")
        assertFalse(OnlinePosterLoadGuard.markAttempted(season2))
        assertFalse(OnlinePosterLoadGuard.markAttempted(season1))
    }

    @Test
    fun `先标记后尝试失败也算已尝试`() = runBlocking {
        // 修复前失败点: 若语义是"成功后才标记", 失败后的下一次仍放行 → 失败番剧被无限重试。
        // 契约: markAttempted 返回 true 即视为已消耗本次启动的唯一额度(调用方随后尝试, 无论成败)。
        val key = "guard-case3|show-c"
        assertTrue(OnlinePosterLoadGuard.markAttempted(key)) // 调用方拿 true 后发起尝试并失败
        assertFalse(OnlinePosterLoadGuard.markAttempted(key)) // 失败后的再次尝试也被拒绝
    }
}
