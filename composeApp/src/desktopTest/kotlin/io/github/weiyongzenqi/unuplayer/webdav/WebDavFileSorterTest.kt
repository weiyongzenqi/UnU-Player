package io.github.weiyongzenqi.unuplayer.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.domain.WebDavSortPreset
import kotlin.test.Test
import kotlin.test.assertEquals

class WebDavFileSorterTest {

    @Test
    fun `默认排序保持目录优先和自然名称顺序`() {
        val entries = listOf(
            entry("S10.mkv"),
            entry("目录10", directory = true),
            entry("S2.mkv"),
            entry("目录2", directory = true),
        )

        val sorted = WebDavFileSorter.sort(entries, WebDavSortPreset.DEFAULT)

        assertEquals(listOf("目录2", "目录10", "S2.mkv", "S10.mkv"), sorted.map { it.name })
    }

    @Test
    fun `后台排序保持修改时间相同时的自然名称语义`() = runBlocking {
        val entries = listOf(entry("第10集.mkv", modified = 100), entry("第2集.mkv", modified = 100))

        val sorted = WebDavFileSorter.sortInBackground(
            entries,
            WebDavSortPreset.MODIFIED_DESC,
            Dispatchers.Default,
        )

        assertEquals(listOf("第2集.mkv", "第10集.mkv"), sorted.map { it.name })
    }

    @Test
    fun `超出整数范围的数字段保持旧字典序语义`() {
        val entries = listOf(entry("S99999999999999999999.mkv"), entry("S10.mkv"))

        val sorted = WebDavFileSorter.sort(entries, WebDavSortPreset.NAME_ASC)

        assertEquals(listOf("S10.mkv", "S99999999999999999999.mkv"), sorted.map { it.name })
    }

    @Test
    fun `数字与符号 token 混排保持真实键顺序`() {
        val entries = listOf(entry("2"), entry("-"))

        val sorted = WebDavFileSorter.sort(entries, WebDavSortPreset.NAME_ASC)

        assertEquals(listOf("-", "2"), sorted.map { it.name })
    }

    private fun entry(name: String, directory: Boolean = false, modified: Long = 0L) = MediaEntry(
        name = name,
        path = "/$name",
        isDirectory = directory,
        lastModified = modified,
    )
}
