package io.github.weiyongzenqi.unuplayer.core.platform

/**
 * 跨平台当前时间(毫秒), 供 commonMain 做墙钟比较(如 WebDAV 搜索超时)。
 * commonMain 不能直接用 java.lang.System, 故 expect/actual。
 */
expect fun platformTimeMillis(): Long
