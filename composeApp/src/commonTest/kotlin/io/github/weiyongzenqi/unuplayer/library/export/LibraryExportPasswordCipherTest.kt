package io.github.weiyongzenqi.unuplayer.library.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LibraryExportPasswordCipherTest {
    @Test
    fun `迁移口令可往返且错误口令失败`() {
        val protected = protectLibraryExportPassword("migration-pass", "真实连接密码")

        assertTrue(protected.startsWith(LIBRARY_EXPORT_PASSWORD_PREFIX))
        assertNotEquals("真实连接密码", protected)
        assertEquals("真实连接密码", unprotectLibraryExportPassword("migration-pass", protected))
        assertFailsWith<IllegalArgumentException> {
            unprotectLibraryExportPassword("wrong-pass", protected)
        }
    }

    @Test
    fun `固定密文向量可在两端解密`() {
        val protected =
            "unu-export-sec:v1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGyWP7lWc9yaeGrh7PxTEidfsKHILWxViySgbC4tluAC53hA="

        assertEquals("真实连接密码", unprotectLibraryExportPassword("migration-pass", protected))
    }
}
