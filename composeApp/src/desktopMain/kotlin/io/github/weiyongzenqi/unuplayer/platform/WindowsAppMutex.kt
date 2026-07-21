package io.github.weiyongzenqi.unuplayer.platform

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT.HANDLE

/**
 * Windows 命名互斥量：阻止重复启动，也让安装器能可靠判断播放器是否仍占用安装文件。
 * 互斥量只存在于内核对象命名空间，不写注册表或磁盘。
 */
class WindowsAppMutex private constructor(
    private val handle: HANDLE?,
) : AutoCloseable {

    override fun close() {
        val ownedHandle = handle ?: return
        runCatching { Kernel32.INSTANCE.ReleaseMutex(ownedHandle) }
        runCatching { Kernel32.INSTANCE.CloseHandle(ownedHandle) }
    }

    companion object {
        const val NAME = "UnUPlayerDesktop_A720D50AE10E4C63BDE2B4D37D10E606"

        /** 已有实例运行时返回 null；非 Windows 平台返回无需系统句柄的实例。 */
        fun acquire(): WindowsAppMutex? {
            if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                return WindowsAppMutex(null)
            }

            val kernel32 = Kernel32.INSTANCE
            val mutexHandle = kernel32.CreateMutex(null, true, NAME)
                ?: error("创建 UnU-Player 进程互斥量失败，Windows 错误码=${kernel32.GetLastError()}")
            if (kernel32.GetLastError() == WinError.ERROR_ALREADY_EXISTS) {
                kernel32.CloseHandle(mutexHandle)
                return null
            }
            return WindowsAppMutex(mutexHandle)
        }
    }
}
