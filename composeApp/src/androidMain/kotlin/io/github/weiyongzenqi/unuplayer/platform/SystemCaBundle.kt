package io.github.weiyongzenqi.unuplayer.platform

import android.content.Context
import java.io.File
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.X509Certificate

/**
 * 导出 Android 系统 CA 证书为 PEM bundle 文件, 供 mpv 的 tls-ca-file 使用。
 *
 * 背景: libmpv-android 的 ffmpeg 用 OpenSSL 做 TLS。OpenSSL 在 Android 上找不到系统 CA
 * 文件(SSL_CTX_set_default_verify_paths 指向的 /etc/ssl/certs/ 等路径不存在), 默认无受信
 * 证书 → 与 HTTPS WebDAV 握手时无法验证证书 → 失败(列目录用 OkHttp 走系统 Conscrypt 正常)。
 *
 * 解法: 把 Android 系统/用户 CA(KeyStore)导出为单个 PEM 文件, 设给 mpv
 * tls-ca-file(ffmpeg 的 ca_file → SSL_CTX_load_verify_locations), 让 OpenSSL 据此验证服务端证书。
 *
 * 文件写到 cacheDir(私有, 重启可重建), 内容按证书指纹去重, 跨调用复用。
 */
object SystemCaBundle {

    @Volatile private var bundlePath: String? = null

    /** 返回 CA bundle 文件路径; 已生成且未过期则复用。失败返回 null(调用方回退 tls-verify=no)。 */
    fun ensureBundle(context: Context): String? {
        // CR-074: mtime 过期检查, 避免运行中安装新系统 CA 后 bundle 永不刷新
        // (超过 BUNDLE_MAX_AGE_MS 重建; invalidate() 可主动失效; 重启 app 亦重建)。
        bundlePath?.let { path ->
            val f = File(path)
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < BUNDLE_MAX_AGE_MS) return path
        }
        return runCatching {
            val certs = collectSystemCAs()
            if (certs.isEmpty()) return@runCatching null
            val file = File(context.cacheDir, "system-ca-bundle.pem")
            file.bufferedWriter().use { w ->
                certs.values.forEach { cert -> w.write(certToPem(cert)) }
            }
            bundlePath = file.absolutePath
            file.absolutePath
        }.getOrNull()
    }

    /** 主动失效缓存; 下次 ensureBundle 重建(含最新系统 CA)。 */
    fun invalidate() { bundlePath = null }

    /** bundle 过期阈值; 系统 CA 变更罕见, 7 天重建保证最终一致。 */
    private const val BUNDLE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    /** 收集系统 + 用户 CA 证书(Android 14+ 系统 CA 在 apex, KeyStore 统一暴露)。 */
    private fun collectSystemCAs(): Map<String, Certificate> {
        val out = LinkedHashMap<String, Certificate>()
        val ks = KeyStore.getInstance("AndroidCAStore")
        ks.load(null)
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
            // 用公钥指纹去重(系统/用户可能重复)
            val key = cert.subjectX500Principal.name + "/" + cert.serialNumber
            out.putIfAbsent(key, cert)
        }
        return out
    }

    private fun certToPem(cert: Certificate): String {
        val b64 = android.util.Base64.encodeToString(cert.encoded, android.util.Base64.NO_WRAP)
        val sb = StringBuilder()
        sb.append("-----BEGIN CERTIFICATE-----\n")
        b64.chunked(64).forEach { sb.append(it).append('\n') }
        sb.append("-----END CERTIFICATE-----\n")
        return sb.toString()
    }
}
