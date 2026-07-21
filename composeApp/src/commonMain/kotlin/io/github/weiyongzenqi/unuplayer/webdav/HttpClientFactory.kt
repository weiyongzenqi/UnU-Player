package io.github.weiyongzenqi.unuplayer.webdav

import io.ktor.client.HttpClient

/**
 * 平台 HTTP 客户端工厂。commonMain 声明, 各平台 actual 提供引擎。
 *
 * Android 用 OkHttp 引擎(androidMain actual), 桌面端未来用其他引擎。
 * WebDAV PROPFIND 需要自定义 HTTP 方法, 引擎必须支持(Ktor OkHttp 支持)。
 */
expect fun createHttpClient(): HttpClient

/** 应用进程退出时释放平台共享 HTTP 客户端；未初始化时不得为关闭而创建。 */
expect fun closeSharedHttpClient()

/**
 * 设置进程级共享 HTTP 客户端的 TLS 验证降级开关(B12)。
 *
 * allow = true: 共享 OkHttp 客户端不再验证服务端证书链与主机名——覆盖 WebDAV 列目录、
 * 弹弹play 匹配/哈希、字幕下载等全部应用内 HTTP; false(默认, 绝不翻转): 走系统信任链,
 * 行为与现状逐字节一致。
 *
 * 动态生效: 共享客户端是 lazy 单例, 设置可能在其创建之后才变更; 平台侧 TrustManager /
 * HostnameVerifier 以委托模式在握手时动态读取该标志, 不重建客户端(重建有连接池/线程池泄漏风险)。
 *
 * 挂接后 allowTlsInsecure 设置的语义范围 = "播放内核(mpv TLS)+ 应用内 HTTP 通信"全链路,
 * 两端共用同一开关、语义一致。由各端设置收集(settingsRepo.state.collect)调用本函数联动。
 */
expect fun setSharedHttpClientTlsInsecure(allow: Boolean)
