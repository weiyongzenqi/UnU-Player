package io.github.weiyongzenqi.unuplayer.library.export

/** 跨 Android/Windows 的迁移口令保护；解开后仍由各平台凭据仓库用 unu-sec:v1: 保存。 */
const val LIBRARY_EXPORT_PASSWORD_PREFIX = "unu-export-sec:v1:"

/** PBKDF2 迭代次数；迁移包不频繁生成，使用较高成本抵抗离线猜测。 */
const val LIBRARY_EXPORT_PASSWORD_ITERATIONS = 600_000

const val LIBRARY_EXPORT_MIN_PASSWORD_LENGTH = 8

expect fun protectLibraryExportPassword(exportPassword: String, plaintext: String): String

expect fun unprotectLibraryExportPassword(exportPassword: String, protectedValue: String): String
