package io.github.weiyongzenqi.unuplayer.webdav

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.domain.PinyinSorter
import io.github.weiyongzenqi.unuplayer.domain.WebDavSortPreset

/**
 * WebDAV 文件排序器(移植自 NipaPlay webdav_file_sorter.dart)。
 * 自然排序(数字感知): "S2" < "S10"(按数值 2<10)。
 */
object WebDavFileSorter {

    private val tokenRegex = Regex("(\\d+)|(\\D+)")

    private data class PreparedToken(
        val raw: String,
        val number: Int?,
        val key: String,
    )

    private data class PreparedName(val tokens: List<PreparedToken>)

    private data class PreparedEntry(val entry: MediaEntry, val name: PreparedName)

    fun sort(entries: List<MediaEntry>, preset: WebDavSortPreset): List<MediaEntry> {
        if (entries.size < 2) return entries
        val prepared = entries.map { PreparedEntry(it, prepareName(it.name)) }
        return prepared.sortedWith(comparator(preset)).map { it.entry }
    }

    /** UI 调用的后台排序契约；回调和状态发布仍在调用者上下文。 */
    internal suspend fun sortInBackground(
        entries: List<MediaEntry>,
        preset: WebDavSortPreset,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): List<MediaEntry> = withContext(dispatcher) { sort(entries, preset) }

    private fun comparator(preset: WebDavSortPreset): Comparator<PreparedEntry> = when (preset) {
        WebDavSortPreset.DEFAULT -> Comparator { a, b ->
            when {
                a.entry.isDirectory && !b.entry.isDirectory -> -1
                !a.entry.isDirectory && b.entry.isDirectory -> 1
                else -> comparePrepared(a.name, b.name)
            }
        }
        WebDavSortPreset.NAME_ASC -> Comparator { a, b -> comparePrepared(a.name, b.name) }
        WebDavSortPreset.NAME_DESC -> Comparator { a, b -> comparePrepared(b.name, a.name) }
        WebDavSortPreset.MODIFIED_DESC -> Comparator { a, b -> compareThenName(b.entry.lastModified, a.entry.lastModified, a.name, b.name) }
        WebDavSortPreset.MODIFIED_ASC -> Comparator { a, b -> compareThenName(a.entry.lastModified, b.entry.lastModified, a.name, b.name) }
        WebDavSortPreset.SIZE_DESC -> Comparator { a, b -> compareThenName(b.entry.size, a.entry.size, a.name, b.name) }
        WebDavSortPreset.SIZE_ASC -> Comparator { a, b -> compareThenName(a.entry.size, b.entry.size, a.name, b.name) }
    }

    /** 数值比较, 相等则名称自然升序 tiebreak。 */
    private fun compareThenName(x: Long, y: Long, a: PreparedName, b: PreparedName): Int {
        val c = x.compareTo(y)
        return if (c != 0) c else comparePrepared(a, b)
    }

    /**
     * 自然排序: 数字段按整数值比(相等再比原串长度), 非数字段按拼音首字母键字典序。
     * 中文走 [PinyinSorter.sortKey](首字母大写), 非中文转小写, 实现"刀"<"剑"<"神" 的拼音字母序。
     */
    fun naturalCompare(a: String, b: String): Int {
        return comparePrepared(prepareName(a), prepareName(b))
    }

    private fun comparePrepared(a: PreparedName, b: PreparedName): Int {
        val ta = a.tokens
        val tb = b.tokens
        val n = minOf(ta.size, tb.size)
        for (i in 0 until n) {
            val pa = ta[i]
            val pb = tb[i]
            val c = if (pa.number != null && pb.number != null) {
                val v = pa.number.compareTo(pb.number)
                if (v != 0) v else pa.raw.length.compareTo(pb.raw.length)
            } else {
                pa.key.compareTo(pb.key)
            }
            if (c != 0) return c
        }
        return ta.size.compareTo(tb.size)
    }

    private fun prepareName(s: String): PreparedName = PreparedName(
        tokenRegex.findAll(s).map { token ->
            val raw = token.value
            val number = raw.toIntOrNull()
            PreparedToken(raw, number, if (number == null) PinyinSorter.sortKey(raw) else raw)
        }.toList(),
    )
}
