package io.github.weiyongzenqi.unuplayer.smb

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import io.github.weiyongzenqi.unuplayer.core.player.MpvLoadTargetException
import io.github.weiyongzenqi.unuplayer.core.player.MpvRemoteFdAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 将一次 SMB 随机访问文件映射成可 seek 的 Android proxy fd。
 *
 * callback 的 onRead 在专用 HandlerThread 运行；每个 fd 独占 SMB 会话和句柄，onRelease 恰好关闭一次。
 * mpv 接管 detached fd 后通过 fdclose:// 负责关闭数字 fd，应用只负责 callback 资源。
 */
internal class SmbProxyFileDescriptorAccess(
    context: Context,
    private val repository: SmbConnectionRepository,
) : MpvRemoteFdAccess, AutoCloseable {
    private val storageManager = context.getSystemService(StorageManager::class.java)
        ?: error("设备不支持 StorageManager")
    private val callbackThread = HandlerThread("smb-proxy-fd").also { it.start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val callbackLifetime = SmbCallbackLifetime {
        callbackThread.quitSafely()
    }

    override fun openReadOnly(url: String): Int? {
        val locator = SmbPlaybackLocator.parse(url) ?: return null
        return try {
            openLocator(locator)
        } catch (error: MpvLoadTargetException) {
            throw error
        } catch (error: Exception) {
            throw MpvLoadTargetException(
                "SMB 连接失败，请检查网络、服务器、共享和凭据",
                error,
            )
        }
    }

    private fun openLocator(locator: SmbPlaybackLocator): Int {
        callbackLifetime.beginOpen()
        var openingFinished = false
        try {
            val connection = runBlocking(Dispatchers.IO) {
                repository.loadAll().firstOrNull { it.id == locator.connectionId }
            } ?: throw MpvLoadTargetException("SMB 连接不存在，请返回影视源重新选择")
            if (connection.credentialUnavailable) {
                throw MpvLoadTargetException("SMB 凭据已失效，请重新输入密码")
            }

            val client = AndroidSmbClient(connection)
            val file = try {
                client.openRead(locator.path)
            } catch (error: Throwable) {
                runCatching { client.close() }
                throw error
            }
            val callback = SmbProxyCallback(file, callbackLifetime::release)
            val accepted = callbackLifetime.finishOpen(callback)
            openingFinished = true
            if (!accepted) {
                runCatching { callback.onRelease() }
                throw MpvLoadTargetException("SMB 播放会话已关闭，请重新进入播放器")
            }
            return try {
                storageManager.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY,
                    callback,
                    callbackHandler,
                ).use { it.detachFd() }
            } catch (error: Throwable) {
                runCatching { callback.onRelease() }
                throw error
            }
        } finally {
            if (!openingFinished) callbackLifetime.abortOpen()
        }
    }

    override fun close() {
        callbackLifetime.close()
    }

    private class SmbProxyCallback(
        private val file: AndroidSmbFileHandle,
        private val onReleased: (SmbProxyCallback) -> Unit,
    ) : ProxyFileDescriptorCallback() {
        private val released = AtomicBoolean(false)

        override fun onGetSize(): Long = file.size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            if (released.get()) return 0
            if (offset >= file.size || size <= 0) return 0
            val count = minOf(size, data.size, (file.size - offset).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            return file.read(offset, data, count)
        }

        override fun onWrite(offset: Long, size: Int, data: ByteArray): Int =
            throw UnsupportedOperationException("SMB 播放 fd 只读")

        override fun onFsync() = Unit

        override fun onRelease() {
            if (!released.compareAndSet(false, true)) return
            try {
                file.close()
            } finally {
                onReleased(this)
            }
        }
    }
}

/** close 后不再接受新 fd，并等待所有打开操作和 callback 释放后再退出线程。 */
internal class SmbCallbackLifetime(
    private val onClosed: () -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val activeCallbacks = mutableSetOf<Any>()
    private var openings = 0
    private var closeRequested = false
    private var closed = false

    fun beginOpen() {
        synchronized(lock) {
            check(!closeRequested) { "SMB fd 访问器已关闭" }
            openings += 1
        }
    }

    fun finishOpen(callback: Any): Boolean {
        val accepted: Boolean
        val notifyClosed: Boolean
        synchronized(lock) {
            check(openings > 0) { "没有待完成的 SMB fd 打开操作" }
            openings -= 1
            accepted = !closeRequested
            if (accepted) activeCallbacks += callback
            notifyClosed = markClosedIfReady()
        }
        if (notifyClosed) onClosed()
        return accepted
    }

    fun abortOpen() {
        val notifyClosed = synchronized(lock) {
            check(openings > 0) { "没有待取消的 SMB fd 打开操作" }
            openings -= 1
            markClosedIfReady()
        }
        if (notifyClosed) onClosed()
    }

    fun release(callback: Any) {
        val notifyClosed = synchronized(lock) {
            activeCallbacks.remove(callback)
            markClosedIfReady()
        }
        if (notifyClosed) onClosed()
    }

    override fun close() {
        val notifyClosed = synchronized(lock) {
            closeRequested = true
            markClosedIfReady()
        }
        if (notifyClosed) onClosed()
    }

    private fun markClosedIfReady(): Boolean {
        if (closed || !closeRequested || openings != 0 || activeCallbacks.isNotEmpty()) return false
        closed = true
        return true
    }
}
