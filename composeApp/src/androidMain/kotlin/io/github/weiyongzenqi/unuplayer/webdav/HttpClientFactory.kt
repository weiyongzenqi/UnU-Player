package io.github.weiyongzenqi.unuplayer.webdav

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Android 端 HttpClient 工厂: 用 OkHttp 引擎, 返回**进程级共享单例**。
 *
 * OkHttp 引擎支持自定义 HTTP 方法(如 WebDAV PROPFIND)。HttpClient(OkHttp) 内部持有
 * 连接池 + Dispatcher 线程池, 重复创建会泄漏线程池(旧 RemoteDanmakuHash 每次哈希 new
 * 不 close 即泄漏)。共享单例是 OkHttp 推荐用法, 连接池随单例常驻复用, 非泄漏。
 *
 * 请求级配置(auth/header/Range)在请求时注入, 不依赖 HttpClient 级配置, 故 WebDAV/
 * 弹弹play/远程哈希可共享同一实例。调用方**不应 close()** 返回的实例(会影响所有调用方);
 * [WebDavSource.close] 已空化为 no-op。
 * 注意: 未来若需 per-account 状态(cookie 持久化/auth token 缓存等带连接级状态的插件),
 * 共享单例会跨账号串状态, 届时须改回 per-source client。
 *
 * 引擎配置(单例创建时设; TLS 开关值动态读, 非创建时快照):
 * - 超时(P3⑲): connectTimeout=15s / readTimeout=60s / writeTimeout=60s。OkHttp 默认
 *   各 10s 且长连无上限; read/write 为 WebDAV 大目录 PROPFIND 与慢链路留足读取余量;
 *   **不设 callTimeout**——流式下载/视频传输不能整体限时。
 * - TLS(B12): [delegatingTrustManager] + 动态 hostnameVerifier 委托系统信任链
 *   (标志 [SharedHttpTlsPolicy.allowInsecure]=false 时行为与现状逐字节一致); 仅当
 *   标志为 true 才放行全部证书与主机名。标志经 [setSharedHttpClientTlsInsecure] 由
 *   Activity 的设置收集(settingsRepo.state.collect)联动, 覆盖 WebDAV 列目录/弹弹play
 *   匹配/字幕下载等应用内 HTTP——自签/私有 CA 的 NAS 开开关后即可列目录。
 *
 * 日志/UA 等插件后续按需加(避免引入不必要的依赖)。
 */

/**
 * 进程级共享 HTTP 客户端的 TLS 验证降级开关(B12)。
 *
 * 默认 false = 安全底线(始终走系统信任链, 任何改动不得翻成 true);
 * true = 跳过全部证书链/主机名校验(自签/私有 CA 等特殊服务器, 用户知情同意中间人风险)。
 * 因共享客户端是 lazy 单例(可能在设置加载前已被 DandanplayApi 等默认参对象创建),
 * 所有消费方在握手时经委托动态读取本标志——不在创建时快照, 更不因设置变更重建客户端
 * (重建会泄漏连接池/Dispatcher 线程池)。
 */
private object SharedHttpTlsPolicy {
    @Volatile
    var allowInsecure: Boolean = false
        private set

    fun setAllowInsecure(allow: Boolean) {
        allowInsecure = allow
    }
}

/** 系统默认 X509TrustManager(系统 CA 信任链)。TrustManagerFactory 是平台 JVM API, 在 actual 内合法。 */
private val systemTrustManager: X509TrustManager by lazy {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    // null KeyStore = 加载平台默认信任材料(系统 CA, Android 上含网络安全配置), 等同 OkHttp 默认信任路径。
    factory.init(null as KeyStore?)
    factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        ?: error("无法获取系统默认 X509TrustManager")
}

/**
 * 委托 TrustManager: 每次握手动态读 [SharedHttpTlsPolicy.allowInsecure]。
 * - false(默认): 原样委托 [systemTrustManager](系统信任链), 与现状行为一致;
 * - true: 放行所有客户端/服务端证书(不校验, 用户已知情同意)。
 */
private val delegatingTrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        if (SharedHttpTlsPolicy.allowInsecure) return
        systemTrustManager.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (SharedHttpTlsPolicy.allowInsecure) return
        systemTrustManager.checkServerTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrustManager.acceptedIssuers
}

/** 使用委托 TrustManager 的 SSLContext, 供 OkHttp 握手校验(随客户端单例创建惰性初始化)。 */
private val delegatingSslContext: SSLContext by lazy {
    SSLContext.getInstance("TLS").apply { init(null, arrayOf(delegatingTrustManager), null) }
}

/**
 * JVM 默认 hostname verifier(平台公共 API [HttpsURLConnection.getDefaultHostnameVerifier])。
 * 标志 false 时 hostname 校验委托它(RFC 2818, 与 OkHttp 默认验证等价); true 时直接放行。
 */
private val systemHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

private val sharedHttpClientDelegate = lazy {
    HttpClient(OkHttp) {
        engine {
            config {
                // P3⑲ 显式超时: OkHttp 默认各 10s 且长连无上限, 此处统一调整。
                // connect 15s: 弱 Wi-Fi/TLS 握手比默认略宽松;
                // read 60s: WebDAV 大目录 PROPFIND 与慢链路留足读取余量;
                // write 60s: 与 read 对齐, 慢上行留余量;
                // 不设 callTimeout: 流式下载/视频传输不能整体限时。
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(60, TimeUnit.SECONDS)
                writeTimeout(60, TimeUnit.SECONDS)
                // B12 TLS 降级挂接: 握手校验经委托动态读 SharedHttpTlsPolicy.allowInsecure。
                // 必须 sslSocketFactory + trustManager 双参版一起提供, 否则 OkHttp 退回平台反射
                // 取信任管理器, 委托失效; 单参版本已废弃, 同样原因不用。
                sslSocketFactory(delegatingSslContext.socketFactory, delegatingTrustManager)
                hostnameVerifier { host, session ->
                    if (SharedHttpTlsPolicy.allowInsecure) true else systemHostnameVerifier.verify(host, session)
                }
            }
        }
        // 后续按需: install(Logging) / UserAgent / ContentNegotiation
    }
}

private val sharedHttpClient: HttpClient get() = sharedHttpClientDelegate.value

actual fun createHttpClient(): HttpClient = sharedHttpClient

actual fun closeSharedHttpClient() {
    if (sharedHttpClientDelegate.isInitialized()) sharedHttpClientDelegate.value.close()
}

/** B12: 设置共享 HTTP 客户端 TLS 降级开关; 动态生效, 下次握手即按新值走。见 [SharedHttpTlsPolicy]。 */
actual fun setSharedHttpClientTlsInsecure(allow: Boolean) {
    SharedHttpTlsPolicy.setAllowInsecure(allow)
}
