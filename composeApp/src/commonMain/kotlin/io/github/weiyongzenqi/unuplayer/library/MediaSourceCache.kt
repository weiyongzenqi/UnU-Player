package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 页面级 [MediaSource] 所有者。
 *
 * 同一份来源配置(含凭据指纹)只创建一个实例；调用方必须通过 [withSource] 租用，不能长期保存裸实例。
 * 配置失效或缓存关闭时先禁止新租用，已有操作结束后再恰好关闭一次，避免 use-after-close。
 *
 * 缓存身份包含凭据指纹([MediaSourceFactory.credentialsToken]): WebDAV 连接密码被编辑后指纹变化,
 * 命中失败改走工厂新建, 避免缓存源继续用旧密码 401。指纹仅在 [credentialsTokenTtl] 过期后重验,
 * 防止海报墙逐图片租用时每次都全量重读并解密连接。
 */
class MediaSourceCache(
    private val factory: MediaSourceFactory,
    private val credentialsTokenTtl: Duration = 5.seconds,
) {
    private data class SourceIdentity(
        val libraryId: Long,
        val name: String,
        val sourceKind: MediaSourceKind,
        val connectionId: String?,
        val localUri: String?,
        val rootPath: String,
        /** 凭据指纹(WEBDAV 源 = 连接凭据哈希; LOCAL 等无凭据源 = null)。指纹变化即判定缓存源凭据过期。 */
        val credentialsToken: String?,
    ) {
        companion object {
            fun from(library: LibraryConfig, credentialsToken: String?) = SourceIdentity(
                libraryId = library.id,
                name = library.name,
                sourceKind = library.sourceKind,
                connectionId = library.connectionId,
                localUri = library.localUri,
                rootPath = library.rootPath,
                credentialsToken = credentialsToken,
            )
        }

        /** 不含凭据指纹的配置字段比较, 供快命中路径使用(配置未变时避免每次租用它都重读凭据)。 */
        fun matchesConfig(library: LibraryConfig): Boolean =
            libraryId == library.id &&
                name == library.name &&
                sourceKind == library.sourceKind &&
                connectionId == library.connectionId &&
                localUri == library.localUri &&
                rootPath == library.rootPath
    }

    private class Entry(
        val identity: SourceIdentity,
        val source: MediaSource,
    ) {
        var users: Int = 0
        var retired: Boolean = false
        var closeScheduled: Boolean = false

        /** 凭据指纹上次验证时点；[credentialsTokenTtl] 窗口内信任缓存指纹, 过期后最多重验一次(有界过期)。 */
        var credentialsTokenVerifiedAt: TimeMark = TimeSource.Monotonic.markNow()
    }

    private val mutex = Mutex()
    private val entries = mutableMapOf<Long, Entry>()
    private var closed = false

    /**
     * 在 source 有效期间执行 [block]。缓存关闭后返回 null；block 的异常和协程取消原样传播。
     */
    suspend fun <T> withSource(
        library: LibraryConfig,
        block: suspend (MediaSource) -> T,
    ): T? {
        val entry = acquire(library) ?: return null
        try {
            currentCoroutineContext().ensureActive()
            val value = block(entry.source)
            currentCoroutineContext().ensureActive()
            return value
        } finally {
            withContext(NonCancellable) {
                release(entry)
            }
        }
    }

    /** 预热并验证该配置能创建 source，不把实例所有权暴露给调用方。 */
    suspend fun prepare(library: LibraryConfig): Boolean =
        withSource(library) { true } ?: false

    /** 删除或明确失效某个库时停止新租用；活跃操作结束后关闭旧实例。 */
    suspend fun invalidate(libraryId: Long) {
        val toClose = mutex.withLock {
            val entry = entries.remove(libraryId) ?: return@withLock null
            retireLocked(entry)
        }
        closeSource(toClose)
    }

    /**
     * 关闭缓存。方法幂等；活跃租用不会被中途关闭，会在最后一个使用者退出时释放。
     */
    suspend fun close() {
        val toClose = mutex.withLock {
            if (closed) return@withLock emptyList()
            closed = true
            val current = entries.values.toList()
            entries.clear()
            current.mapNotNull(::retireLocked)
        }
        toClose.forEach { closeSource(it) }
    }

    private suspend fun acquire(library: LibraryConfig): Entry? {
        val toClose = mutableListOf<MediaSource>()
        try {
            return mutex.withLock {
                if (closed) return@withLock null

                entries[library.id]?.let { existing ->
                    if (!existing.retired && existing.identity.matchesConfig(library)) {
                        // 配置未变: TTL 窗口内信任缓存的凭据指纹, 避免海报墙逐图片租用都全量重读
                        // 并解密 WebDAV 连接; 过期后每窗口最多重验一次, 密码编辑的失效延迟有界。
                        val token = if (existing.credentialsTokenVerifiedAt.elapsedNow() < credentialsTokenTtl) {
                            existing.identity.credentialsToken
                        } else {
                            factory.credentialsToken(library).also {
                                existing.credentialsTokenVerifiedAt = TimeSource.Monotonic.markNow()
                            }
                        }
                        if (existing.identity.credentialsToken == token) {
                            existing.users++
                            return@withLock existing
                        }
                        // 凭据指纹变化(如密码被编辑): 退役旧实例, 下面走工厂重建。
                    }
                    entries.remove(library.id)
                    retireLocked(existing)?.let(toClose::add)
                }

                val identity = SourceIdentity.from(library, factory.credentialsToken(library))
                val created = factory.create(library) ?: return@withLock null
                Entry(identity, created).also { entry ->
                    entry.users = 1
                    entries[library.id] = entry
                }
            }
        } finally {
            toClose.forEach { closeSource(it) }
        }
    }

    private suspend fun release(entry: Entry) {
        val toClose = mutex.withLock {
            check(entry.users > 0) { "MediaSource 租用计数失衡" }
            entry.users--
            if (entry.retired) retireLocked(entry) else null
        }
        closeSource(toClose)
    }

    /** 必须在 [mutex] 内调用；返回值表示本次调用取得唯一关闭权。 */
    private fun retireLocked(entry: Entry): MediaSource? {
        entry.retired = true
        if (entry.users != 0 || entry.closeScheduled) return null
        entry.closeScheduled = true
        return entry.source
    }

    private suspend fun closeSource(source: MediaSource?) {
        if (source == null) return
        withContext(Dispatchers.IO) {
            runCatching { source.close() }
        }
    }
}
