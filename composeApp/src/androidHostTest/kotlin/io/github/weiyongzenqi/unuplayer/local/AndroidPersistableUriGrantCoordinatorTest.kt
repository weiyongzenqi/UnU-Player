package io.github.weiyongzenqi.unuplayer.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidPersistableUriGrantCoordinatorTest {

    @Test
    fun `新增成功只取得一次授权`() = runBlocking {
        val access = FakeAccess()
        val coordinator = PersistableUriGrantCoordinator(access)

        coordinator.addReference("content://media/root", { true }) { 7L }

        assertEquals(1, access.takeCount)
        assertEquals(0, access.releaseCount)
    }

    @Test
    fun `新增写入失败且无其他引用时回滚本次授权`() = runBlocking {
        val access = FakeAccess()
        val coordinator = PersistableUriGrantCoordinator(access)

        assertFailsWith<IllegalStateException> {
            coordinator.addReference("content://media/root", { false }) {
                error("数据库写入失败")
            }
        }

        assertEquals(1, access.takeCount)
        assertEquals(1, access.releaseCount)
    }

    @Test
    fun `新增被取消仍在不可取消收尾中回滚本次授权`() = runBlocking {
        val access = FakeAccess()
        val coordinator = PersistableUriGrantCoordinator(access)
        val mutationStarted = CompletableDeferred<Unit>()
        val job = async {
            coordinator.addReference(
                uri = "content://media/root",
                hasAnyReference = {
                    delay(1)
                    false
                },
                mutation = {
                    mutationStarted.complete(Unit)
                    awaitCancellation()
                },
            )
        }

        mutationStarted.await()
        job.cancelAndJoin()

        assertEquals(1, access.takeCount)
        assertEquals(1, access.releaseCount)
    }

    @Test
    fun `已有授权或已有共享引用时失败不撤销授权`() = runBlocking {
        val existingGrant = FakeAccess(granted = true)
        assertFailsWith<IllegalStateException> {
            PersistableUriGrantCoordinator(existingGrant).addReference("content://media/root", { false }) {
                error("写入失败")
            }
        }
        assertEquals(0, existingGrant.takeCount)
        assertEquals(0, existingGrant.releaseCount)

        val sharedGrant = FakeAccess()
        assertFailsWith<IllegalStateException> {
            PersistableUriGrantCoordinator(sharedGrant).addReference("content://media/root", { true }) {
                error("写入失败")
            }
        }
        assertEquals(1, sharedGrant.takeCount)
        assertEquals(0, sharedGrant.releaseCount)
    }

    @Test
    fun `删除最后引用才释放授权且删除失败不释放`() = runBlocking {
        val access = FakeAccess(granted = true)
        val coordinator = PersistableUriGrantCoordinator(access)
        var references = 2

        coordinator.removeReference("content://media/root", { references > 0 }) {
            references--
        }
        assertEquals(0, access.releaseCount)

        coordinator.removeReference("content://media/root", { references > 0 }) {
            references--
        }
        assertEquals(1, access.releaseCount)

        assertFailsWith<IllegalStateException> {
            coordinator.removeReference("content://media/root", { references > 0 }) {
                error("数据库删除失败")
            }
        }
        assertEquals(1, access.releaseCount)
    }

    @Test
    fun `删除已提交但mutation抛取消仍按真实引用释放授权`() = runBlocking {
        val access = FakeAccess(granted = true)
        val coordinator = PersistableUriGrantCoordinator(access)
        var references = 1

        assertFailsWith<CancellationException> {
            coordinator.removeReference("content://media/root", { references > 0 }) {
                references--
                throw CancellationException("提交完成后调用方取消")
            }
        }

        assertEquals(0, references)
        assertEquals(1, access.releaseCount)
    }

    @Test
    fun `清理阶段无法查询授权时保留授权且不覆盖原始异常`() = runBlocking {
        val access = FakeAccess(granted = true, failGrantQuery = true)
        val coordinator = PersistableUriGrantCoordinator(access)

        val error = assertFailsWith<IllegalStateException> {
            coordinator.removeReference("content://media/root", { false }) {
                error("原始删除失败")
            }
        }

        assertEquals("原始删除失败", error.message)
        assertEquals(0, access.releaseCount)
    }

    @Test
    fun `删除提交后被取消仍完成最后授权释放并传播取消`() = runBlocking {
        val access = FakeAccess(granted = true)
        val coordinator = PersistableUriGrantCoordinator(access)
        val checkStarted = CompletableDeferred<Unit>()
        val finishCheck = CompletableDeferred<Unit>()
        val job = async {
            coordinator.removeReference(
                uri = "content://media/root",
                hasAnyReference = {
                    checkStarted.complete(Unit)
                    finishCheck.await()
                    false
                },
                mutation = {},
            )
        }

        checkStarted.await()
        job.cancel()
        finishCheck.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, access.releaseCount)
    }

    @Test
    fun `并发新增串行化避免重复取得授权`() = runBlocking {
        val access = FakeAccess()
        val coordinator = PersistableUriGrantCoordinator(access)
        var references = 0
        var activeMutations = 0
        var maxActiveMutations = 0

        coroutineScope {
            (0 until 16).map {
                async(Dispatchers.Default) {
                    coordinator.addReference("content://media/root", { references > 0 }) {
                        activeMutations++
                        maxActiveMutations = maxOf(maxActiveMutations, activeMutations)
                        delay(1)
                        references++
                        activeMutations--
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, access.takeCount)
        assertEquals(1, maxActiveMutations)
        assertEquals(16, references)
        assertTrue(access.granted)
    }

    private class FakeAccess(
        var granted: Boolean = false,
        private val failGrantQuery: Boolean = false,
    ) : PersistableUriGrantAccess {
        var takeCount = 0
        var releaseCount = 0

        override fun hasReadGrant(uri: String): Boolean {
            if (failGrantQuery) error("无法查询授权")
            return granted
        }

        override fun takeReadGrant(uri: String) {
            takeCount++
            granted = true
        }

        override fun releaseReadGrant(uri: String) {
            releaseCount++
            granted = false
        }
    }
}
