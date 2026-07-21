package io.github.weiyongzenqi.unuplayer.core.platform

// 桌面(JVM/Linux)实现, 对应 androidMain 的 PlatformTime.kt
// JVM 同样用 System.currentTimeMillis(), 与 android 版一致
actual fun platformTimeMillis(): Long = System.currentTimeMillis()
