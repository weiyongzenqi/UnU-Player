package io.github.weiyongzenqi.unuplayer.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiblingSubtitleMatcherTest {
    private val entries = listOf(
        entry("Anime S01E04.ASS"),
        entry("Anime S01E04.SRT"),
        entry("Anime S01E04.sc.srt"),
        entry("Anime S01E04.tc.ass"),
        entry("Anime S01E04.zh-Hans.VTT"),
        entry("Anime S01E04.en.ass"),
        entry("Other.srt"),
        entry("README.txt"),
        entry("Folder.ass", directory = true),
    )

    @Test
    fun `简中偏好按语言再按扩展名排序`() {
        assertEquals(
            listOf(
                "Anime S01E04.sc.srt",
                "Anime S01E04.zh-Hans.VTT",
                "Anime S01E04.tc.ass",
                "Anime S01E04.ASS",
                "Anime S01E04.SRT",
            ),
            SiblingSubtitleMatcher.automaticCandidates(entries, "Anime S01E04.mkv", "sc").map { it.name },
        )
    }

    @Test
    fun `繁中偏好与严格同名顺序正确`() {
        assertEquals(
            listOf(
                "Anime S01E04.tc.ass",
                "Anime S01E04.sc.srt",
                "Anime S01E04.zh-Hans.VTT",
                "Anime S01E04.ASS",
                "Anime S01E04.SRT",
            ),
            SiblingSubtitleMatcher.automaticCandidates(entries, "Anime S01E04.mkv", "tc").map { it.name },
        )
    }

    @Test
    fun `none 与未知偏好只接受严格同名`() {
        val expected = listOf("Anime S01E04.ASS", "Anime S01E04.SRT")
        assertEquals(expected, SiblingSubtitleMatcher.automaticCandidates(entries, "Anime S01E04.mkv", "none").map { it.name })
        assertEquals(expected, SiblingSubtitleMatcher.automaticCandidates(entries, "Anime S01E04.mkv", "unknown").map { it.name })
    }

    @Test
    fun `手动列表包含其他语言和不同名字幕但排除目录与非字幕`() {
        val all = SiblingSubtitleMatcher.allSubtitles(entries).map { it.name }
        assertTrue("Anime S01E04.en.ass" in all)
        assertTrue("Other.srt" in all)
        assertFalse("README.txt" in all)
        assertFalse("Folder.ass" in all)
        assertEquals(all.sortedBy { it.lowercase() }, all)
    }

    private fun entry(name: String, directory: Boolean = false) = MediaEntry(
        name = name,
        path = "/fixture/$name",
        isDirectory = directory,
    )
}
