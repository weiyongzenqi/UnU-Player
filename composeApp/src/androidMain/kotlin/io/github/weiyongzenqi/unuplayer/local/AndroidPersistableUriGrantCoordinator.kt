package io.github.weiyongzenqi.unuplayer.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** SAF grant 的最小可测试适配面。生产实现只使用 read grant。 */
internal interface PersistableUriGrantAccess {
    fun hasReadGrant(uri: String): Boolean
    fun takeReadGrant(uri: String)
    fun releaseReadGrant(uri: String)
}

private class ContentResolverPersistableUriGrantAccess(
    context: Context,
) : PersistableUriGrantAccess {
    private val resolver = context.contentResolver

    override fun hasReadGrant(uri: String): Boolean = resolver.persistedUriPermissions.any {
        it.uri == Uri.parse(uri) && it.isReadPermission
    }

    override fun takeReadGrant(uri: String) {
        resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun releaseReadGrant(uri: String) {
        resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/**
 * 串行化所有本地 SAF 引用变更，避免刮削库和本地目录仓库互相撤销仍在使用的 grant。
 * 失败补偿只释放本次取得且已经确认没有业务引用的授权；清理失败不覆盖原始业务异常。
 */
internal class PersistableUriGrantCoordinator(
    private val access: PersistableUriGrantAccess,
) {
    private val mutex = Mutex()

    suspend fun <T> addReference(
        uri: String,
        hasAnyReference: suspend () -> Boolean,
        mutation: suspend () -> T,
    ): T = mutex.withLock {
        val normalizedUri = validateUri(uri)
        val alreadyGranted = access.hasReadGrant(normalizedUri)
        var grantedByThisCall = false
        if (!alreadyGranted) {
            access.takeReadGrant(normalizedUri)
            grantedByThisCall = true
        }
        try {
            mutation()
        } catch (error: Throwable) {
            if (grantedByThisCall) {
                withContext(NonCancellable) {
                    if (!hasReferenceSafely(hasAnyReference)) releaseSafely(normalizedUri)
                }
            }
            throw error
        }
    }

    suspend fun <T> removeReference(
        uri: String,
        hasAnyReference: suspend () -> Boolean,
        mutation: suspend () -> T,
    ): T = mutex.withLock {
        val normalizedUri = validateUri(uri)
        val result = try {
            mutation()
        } catch (error: Throwable) {
            releaseIfUnused(normalizedUri, hasAnyReference)
            throw error
        }
        releaseIfUnused(normalizedUri, hasAnyReference)
        currentCoroutineContext().ensureActive()
        result
    }

    private suspend fun releaseIfUnused(
        uri: String,
        hasAnyReference: suspend () -> Boolean,
    ) {
        withContext(NonCancellable) {
            val grantExists = try {
                access.hasReadGrant(uri)
            } catch (_: Exception) {
                false
            }
            if (
                grantExists &&
                !hasReferenceSafely(hasAnyReference)
            ) {
                releaseSafely(uri)
            }
        }
    }

    private suspend fun hasReferenceSafely(check: suspend () -> Boolean): Boolean = try {
        check()
    } catch (_: Exception) {
        // 无法证明没有引用时保留 grant，宁可留下可回收授权也不破坏仍可用的本地库。
        true
    }

    private fun releaseSafely(uri: String) {
        try {
            access.releaseReadGrant(uri)
        } catch (_: Exception) {
            // release 是提交后的 best-effort 清理；失败不回滚已经提交的业务数据。
        }
    }

    private fun validateUri(uri: String): String {
        require(uri.isNotBlank()) { "LOCAL 媒体库必须提供非空 SAF URI" }
        return uri
    }
}

/** 进程级共享协调器；MainActivity/PlayerActivity 获取的仓库都经过同一把锁。 */
internal object AndroidPersistableUriGrantCoordinator {
    private val mutex = Mutex()

    suspend fun <T> addReference(
        context: Context,
        uri: String,
        hasAnyReference: suspend () -> Boolean,
        mutation: suspend () -> T,
    ): T = mutex.withLock {
        PersistableUriGrantCoordinator(ContentResolverPersistableUriGrantAccess(context.applicationContext))
            .addReference(uri, hasAnyReference, mutation)
    }

    suspend fun <T> removeReference(
        context: Context,
        uri: String,
        hasAnyReference: suspend () -> Boolean,
        mutation: suspend () -> T,
    ): T = mutex.withLock {
        PersistableUriGrantCoordinator(ContentResolverPersistableUriGrantAccess(context.applicationContext))
            .removeReference(uri, hasAnyReference, mutation)
    }
}
