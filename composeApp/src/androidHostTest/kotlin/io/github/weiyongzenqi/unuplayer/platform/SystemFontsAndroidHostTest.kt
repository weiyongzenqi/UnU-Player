package io.github.weiyongzenqi.unuplayer.platform

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemFontsAndroidHostTest {

    @Test
    fun `字体流恰好达到上限可以复制`() {
        val output = ByteArrayOutputStream()

        val copied = SystemFonts.copyFontStreamLimited(
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
            output,
            maxBytes = 4L,
        )

        assertEquals(4L, copied)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), output.toByteArray())
    }

    @Test
    fun `字体流超出上限一个字节会拒绝且主体不越界`() {
        val output = ByteArrayOutputStream()

        assertFailsWith<IllegalArgumentException> {
            SystemFonts.copyFontStreamLimited(
                SizedInputStream(5L),
                output,
                maxBytes = 4L,
            )
        }

        assertEquals(4, output.size())
    }

    @Test
    fun `TTF导入解析内部family并按内容哈希幂等发布`() {
        val root = Files.createTempDirectory("unu-android-font-").toFile()
        try {
            root.resolve(".font-import-orphan.part").writeText("orphan")
            val bytes = buildSfnt(
                family = "Legacy Family",
                typographicFamily = "Internal Family",
                fullName = "Internal Family Regular",
            )

            val first = SystemFonts.importFontStream(
                root,
                "Friendly Font.ttf",
                ByteArrayInputStream(bytes),
            )
            val second = SystemFonts.importFontStream(
                root,
                "Friendly Font.ttf",
                ByteArrayInputStream(bytes),
            )

            assertEquals("Internal Family", first.faces.single().family)
            assertEquals("Internal Family Regular", first.faces.single().fullName)
            assertEquals(first.faces.single().fileName, second.faces.single().fileName)
            assertTrue(first.faces.single().fileName.matches(Regex("Friendly Font-[0-9a-f]{12}\\.ttf")))
            assertContentEquals(bytes, root.resolve(first.faces.single().fileName).readBytes())
            assertEquals(1, root.listFiles().orEmpty().count { it.extension == "ttf" })
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `OTF与TTC都按内部name表列出family`() {
        val root = Files.createTempDirectory("unu-android-font-formats-").toFile()
        try {
            val otf = root.resolve("font.otf").apply {
                writeBytes(buildSfnt("OpenType Family", signature = "OTTO".encodeToByteArray()))
            }
            val ttc = root.resolve("font.ttc").apply {
                writeBytes(buildTtc("Collection One", "Collection Two"))
            }

            assertEquals("OpenType Family", SystemFonts.parseAndroidFontFaces(otf).single().family)
            assertEquals(
                listOf("Collection One", "Collection Two"),
                SystemFonts.parseAndroidFontFaces(ttc).map { it.family },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `非法扩展和伪字体不会发布最终文件`() {
        val root = Files.createTempDirectory("unu-android-font-invalid-").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                SystemFonts.importFontStream(root, "font.zip", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            }
            assertFailsWith<IllegalArgumentException> {
                SystemFonts.importFontStream(root, "font.ttf", ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
            }

            assertFalse(root.listFiles().orEmpty().any { it.isFile })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `旧文件名设置值仍可匹配解析后的family`() {
        val font = AndroidImportedFont(
            family = "Noto Sans CJK SC",
            fullName = "Noto Sans CJK SC Regular",
            fileName = "____.otf",
            legacyName = "____",
        )

        assertTrue(font.matchesStoredSetting("Noto Sans CJK SC"))
        assertTrue(font.matchesStoredSetting("思源黑体"))
        assertFalse(font.matchesStoredSetting("Other Font"))
    }

    private fun buildTtc(firstFamily: String, secondFamily: String): ByteArray {
        val headerSize = 20
        val first = buildSfnt(firstFamily, baseOffset = headerSize)
        val secondOffset = headerSize + first.size
        val second = buildSfnt(secondFamily, baseOffset = secondOffset)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeBytes("ttcf")
                output.writeInt(0x00010000)
                output.writeInt(2)
                output.writeInt(headerSize)
                output.writeInt(secondOffset)
                output.write(first)
                output.write(second)
            }
        }.toByteArray()
    }

    private fun buildSfnt(
        family: String,
        typographicFamily: String = family,
        fullName: String = "$typographicFamily Regular",
        signature: ByteArray = byteArrayOf(0, 1, 0, 0),
        baseOffset: Int = 0,
    ): ByteArray {
        val names = listOf(
            1 to family.toByteArray(StandardCharsets.UTF_16BE),
            16 to typographicFamily.toByteArray(StandardCharsets.UTF_16BE),
            4 to fullName.toByteArray(StandardCharsets.UTF_16BE),
        )
        val stringOffset = 6 + names.size * 12
        val nameTableLength = stringOffset + names.sumOf { it.second.size }
        val nameTableOffset = baseOffset + 28
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(signature)
                output.writeShort(1)
                output.writeShort(0)
                output.writeShort(0)
                output.writeShort(0)
                output.writeBytes("name")
                output.writeInt(0)
                output.writeInt(nameTableOffset)
                output.writeInt(nameTableLength)
                output.writeShort(0)
                output.writeShort(names.size)
                output.writeShort(stringOffset)
                var relativeOffset = 0
                names.forEach { (nameId, nameBytes) ->
                    output.writeShort(3)
                    output.writeShort(1)
                    output.writeShort(0x0409)
                    output.writeShort(nameId)
                    output.writeShort(nameBytes.size)
                    output.writeShort(relativeOffset)
                    relativeOffset += nameBytes.size
                }
                names.forEach { (_, nameBytes) -> output.write(nameBytes) }
            }
        }.toByteArray()
    }

    private class SizedInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            remaining--
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val count = minOf(remaining, length.toLong()).toInt()
            buffer.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }
}
