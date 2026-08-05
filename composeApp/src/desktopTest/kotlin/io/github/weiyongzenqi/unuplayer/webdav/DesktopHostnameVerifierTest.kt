package io.github.weiyongzenqi.unuplayer.webdav

import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopHostnameVerifierTest {

    @Test
    fun `OkHttp 严格校验接受匹配的 IP SAN 并拒绝其他 IP`() {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(TEST_IP_SAN_CERTIFICATE.trimIndent().encodeToByteArray()))
        val session = Proxy.newProxyInstance(
            SSLSession::class.java.classLoader,
            arrayOf(SSLSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPeerCertificates" -> arrayOf(certificate)
                "isValid" -> true
                else -> defaultValue(method.returnType)
            }
        } as SSLSession

        assertTrue(verifyDesktopHostname("127.0.0.1", session))
        assertFalse(verifyDesktopHostname("127.0.0.2", session))
        assertFalse(verifyDesktopHostname("unused", session), "IP SAN 存在时不得回退到 Subject CN")
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private companion object {
        /** 本地生成的自签测试证书：Subject CN 故意不匹配，SAN 仅含 127.0.0.1(type 7)。 */
        const val TEST_IP_SAN_CERTIFICATE = """
            -----BEGIN CERTIFICATE-----
            MIIBSTCB8aADAgECAgkArgdZn3OSyF0wCgYIKoZIzj0EAwMwETEPMA0GA1UEAxMG
            dW51c2VkMB4XDTI2MDgwNTE0MTkzN1oXDTM2MDgwMjE0MTkzN1owETEPMA0GA1UE
            AxMGdW51c2VkMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEcUmFo7rdBOCJ+ua/
            FmOqceM70a2kwYTN4M8euYka6zBKy7M/JC9ca73SNH80SWW8EA5UIgkqyDNWgrtb
            NpRBt6MyMDAwHQYDVR0OBBYEFIzoB6fV6Gmfv/LflNr+2xf3PtaPMA8GA1UdEQQI
            MAaHBH8AAAEwCgYIKoZIzj0EAwMDRwAwRAIgFZU0bIpc6u+Xm1jlST+pls/quDaf
            Q2dYrqDgUO3I6isCIDr0LVCP4V3UaUiEZfhX2upa+8dB1fVc3fWHxFBZhuC/
            -----END CERTIFICATE-----
        """
    }
}
