package io.github.weiyongzenqi.unuplayer.webdav

import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebDavSourceCredentialBoundaryTest {

    @Test
    fun `凭据失效连接在源构造边界拒绝但显式匿名连接允许`() {
        val httpClient = HttpClient(OkHttp)
        try {
            assertFailsWith<IllegalArgumentException> {
                WebDavSource(connection(credentialUnavailable = true), httpClient)
            }

            val anonymous = WebDavSource(connection(credentialUnavailable = false), httpClient)
            assertEquals("匿名 WebDAV", anonymous.displayName)
            anonymous.close()
        } finally {
            httpClient.close()
        }
    }

    private fun connection(credentialUnavailable: Boolean) = WebDavConnection(
        id = "connection-id",
        name = "匿名 WebDAV",
        baseUrl = "https://example.test/dav",
        username = "",
        password = "",
        credentialUnavailable = credentialUnavailable,
    )
}
