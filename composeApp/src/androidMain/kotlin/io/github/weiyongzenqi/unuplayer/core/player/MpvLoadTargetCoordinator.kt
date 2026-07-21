package io.github.weiyongzenqi.unuplayer.core.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor

internal interface MpvDetachedFdAccess {
    fun openReadOnly(contentUrl: String): Int
    fun close(fd: Int)
}

internal class AndroidMpvDetachedFdAccess(
    private val context: Context,
) : MpvDetachedFdAccess {
    override fun openReadOnly(contentUrl: String): Int {
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
        val detachedFd = if (url.schemeEquals("content")) fdAccess.openReadOnly(url) else null
        val targetUrl = detachedFd?.let { "fdclose://$it" } ?: url
        try {
            command(targetUrl)
        } catch (error: Throwable) {
            detachedFd?.let { fd -> runCatching { fdAccess.close(fd) } }
            throw error
        }
    }
}

private fun String.schemeEquals(expected: String): Boolean {
    val separator = indexOf(':')
    return separator > 0 && substring(0, separator).equals(expected, ignoreCase = true)
}
