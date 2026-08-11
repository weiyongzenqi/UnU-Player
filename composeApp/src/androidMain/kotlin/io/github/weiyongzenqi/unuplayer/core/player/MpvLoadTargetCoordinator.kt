package io.github.weiyongzenqi.unuplayer.core.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor

internal interface MpvDetachedFdAccess {
    fun openReadOnly(contentUrl: String): Int
    fun close(fd: Int)
}

fun interface MpvRemoteFdAccess {
    /** 返回 detached fd；不识别的 scheme 返回 null。 */
    fun openReadOnly(url: String): Int?
}

/** fd/远程定位在交给 mpv 前失败；[playbackMessage] 必须可安全展示且不包含凭据或完整远程地址。 */
internal class MpvLoadTargetException(
    val playbackMessage: String,
    cause: Throwable? = null,
) : RuntimeException(playbackMessage, cause)

internal fun playbackLoadFailureMessage(error: Throwable): String =
    (error as? MpvLoadTargetException)?.playbackMessage ?: "加载失败"

internal class AndroidMpvDetachedFdAccess(
    private val context: Context,
    private val remoteFdAccess: MpvRemoteFdAccess? = null,
) : MpvDetachedFdAccess {
    override fun openReadOnly(contentUrl: String): Int {
        remoteFdAccess?.openReadOnly(contentUrl)?.let { return it }
        val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(contentUrl), "r")
            ?: error("无法打开本地媒体")
        return try {
            descriptor.detachFd()
        } finally {
            // detachFd 成功后 close 是 no-op；detach 异常时关闭仍归应用所有的 fd。
            runCatching { descriptor.close() }
        }
    }

    override fun close(fd: Int) {
        ParcelFileDescriptor.adoptFd(fd).close()
    }
}

/**
 * content:// 每次 load 都打开新 fd，并以 fdclose:// 把所有权交给 mpv。
 * command 抛错表示 mpv 未接管，此时由应用立即关闭 detached fd。
 */
internal class MpvLoadTargetCoordinator(
    private val fdAccess: MpvDetachedFdAccess,
) {
    fun load(url: String, command: (String) -> Unit) {
        val detachedFd = if (url.requiresDetachedFd()) fdAccess.openReadOnly(url) else null
        val targetUrl = detachedFd?.let { "fdclose://$it" } ?: url
        try {
            command(targetUrl)
        } catch (error: Throwable) {
            detachedFd?.let { fd -> runCatching { fdAccess.close(fd) } }
            throw error
        }
    }
}

private fun String.requiresDetachedFd(): Boolean =
    schemeEquals("content") || schemeEquals("smbfd")

private fun String.schemeEquals(expected: String): Boolean {
    val separator = indexOf(':')
    return separator > 0 && substring(0, separator).equals(expected, ignoreCase = true)
}
