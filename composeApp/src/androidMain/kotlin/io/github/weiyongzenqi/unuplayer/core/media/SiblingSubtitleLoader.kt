package io.github.weiyongzenqi.unuplayer.core.media

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.webdav.WebDavClient
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.webdav.createHttpClient
import io.github.weiyongzenqi.unuplayer.core.platform.PlatformFile
import java.io.File

/**
 * 同目录外挂字幕加载器(androidMain)。
 *
 * PlayerActivity 是独立 Activity, 浏览页的 source 实例无法跨 Intent 传递。本类从
 * [PlayableMedia.mediaKey](webdav:{connId}:{path}) / [PlayableMedia.contentUri](本地 SAF)
 * 无状态重建 WebDavClient / DocumentFile 能力, 列同目录同名字幕并下载到 cache。
 *
 * 用途:
 * - 无内封字幕时自动加载首个同名字幕([listCandidates], 按语言偏好排序)
 * - 字幕面板"从同目录选择"手动选([listAllSubtitles], 列同目录全部字幕)
 *
 * 同名匹配 + 语言识别(自动加载):
 * - baseName = 视频文件名去后缀
 * - 严格同名: baseName.{ass,ssa,srt,sub,vtt}(无语言段)
 * - 带语言段: baseName.{lang}.{ext}, lang 为中文标记(zc/sc/chs 简中, tc/cht 繁中等)
 * - 偏好 sc: 简中 > 繁中 > 无段; 偏好 tc: 繁中 > 简中 > 无段; 偏好 none: 只严格同名(不识别语言段)
 * - 其他语言(jp/en 等)的 baseName.{lang}.{ext} 不参与自动加载, 用户手动选
 * - 同语言内按后缀优先级: ass > ssa > srt > sub > vtt
 *
 * 外部 Intent 拉起的跨 app contentUri: DocumentFile.parentFile 多半 null, 降级返回空(不崩, SAF 仍可用)。
 */
class SiblingSubtitleLoader(
    private val context: Context,
    private val webDavConnRepo: WebDavConnectionRepository,
) {
    /** 字幕后缀 + 优先级(索引越小越优先)。 */
    val subExtensions = listOf("ass", "ssa", "srt", "sub", "vtt")

    /** 中文语言标记(文件名 baseName.{lang}.{ext} 的 lang 段, 小写)。 */
    private val scLangs = setOf("zc", "sc", "chs", "zh-hans", "zh-cn", "gb")
    private val tcLangs = setOf("tc", "cht", "zh-hant", "zh-tw", "big5", "zh")  // zh 中文总称兜底归繁中集(简中没匹配时兜底)

    /** 候选字幕: displayName + fetch(写到目标文件, 成功 true)。 */
    data class Candidate(val displayName: String, val fetch: suspend (dest: File) -> Boolean)

    /**
     * 列同目录同名字幕候选(严格同名 + 带中文语言段), 按语言偏好 + 后缀优先级排序。
     * 空=无同名字幕或无法解析来源。用于自动加载(取首个)。
     *
     * @param mediaKey webdav:{connId}:{path}(WebDAV); 非 webdav: 前缀走本地分支
     * @param contentUri 本地 SAF content://(本地); mediaKey 非 webdav 时用此
     * @param videoTitle 视频文件名(含扩展名); baseName = 去后缀
     * @param preference sc=简中优先 / tc=繁中优先 / none=不限(只严格同名, 不识别语言段)
     */
    suspend fun listCandidates(
        mediaKey: String?,
        contentUri: String?,
        videoTitle: String,
        preference: String = "sc",
    ): List<Candidate> = withContext(Dispatchers.IO) {
        val baseName = videoTitle.substringBeforeLast('.').trim()
        if (baseName.isEmpty()) return@withContext emptyList()

        when {
            mediaKey != null && mediaKey.startsWith("webdav:") ->
                findWebDav(mediaKey, baseName, preference)
            contentUri != null ->
                findLocal(contentUri, baseName, preference)
            else -> emptyList()
        }
    }

    /** 下载到调用方拥有的会话临时文件；失败删除部分文件并返回 null。 */
    suspend fun download(candidate: Candidate, destination: File): File? = withContext(Dispatchers.IO) {
        var completed = false
        try {
            val parent = destination.parentFile
            check(parent == null || parent.isDirectory || parent.mkdirs()) { "无法创建字幕临时目录" }
            if (candidate.fetch(destination)) {
                completed = true
                destination
            } else {
                null
            }
        } finally {
            if (!completed) runCatching { destination.delete() }
        }
    }

    /**
     * 列同目录所有字幕文件(不限同名), 按文件名排序。用于手动选择器(需求3)。
     * 与 [listCandidates] 的区别: 后者只列同名字幕(按语言偏好排序, 供自动加载); 本方法列同目录全部字幕后缀文件。
     */
    suspend fun listAllSubtitles(mediaKey: String?, contentUri: String?): List<Candidate> = withContext(Dispatchers.IO) {
        when {
            mediaKey != null && mediaKey.startsWith("webdav:") -> findAllWebDav(mediaKey)
            contentUri != null -> findAllLocal(contentUri)
            else -> emptyList()
        }
    }

    private suspend fun findWebDav(mediaKey: String, baseName: String, preference: String): List<Candidate> {
        val rest = mediaKey.removePrefix("webdav:")
        val connId = rest.substringBefore(':')
        val filePath = rest.substringAfter(':')
        if (connId.isEmpty() || filePath.isEmpty()) return emptyList()
        val conn = runSuspendCatching { webDavConnRepo.loadAll() }.getOrDefault(emptyList())
            .firstOrNull { it.id == connId } ?: return emptyList()
        val client = WebDavClient(createHttpClient(), conn.baseUrl, conn.username, conn.password)
        val dirPath = filePath.substringBeforeLast('/').ifEmpty { "/" }
        val entries = runSuspendCatching { client.listDirectoryAll(dirPath) }.getOrDefault(emptyList())
        return entries
            .filter { !it.isDirectory && matchLang(it.name, baseName, preference) != null }
            .sortedBy { langOrder(matchLang(it.name, baseName, preference)!!, preference) * 10 + subExtensions.indexOf(it.name.substringAfterLast('.').lowercase()) }
            .map { entry ->
                Candidate(entry.name) { dest -> client.downloadTo(entry.path, PlatformFile(dest.path)) }
            }
    }

    private suspend fun findLocal(contentUri: String, baseName: String, preference: String): List<Candidate> {
        val doc = DocumentFile.fromSingleUri(context, Uri.parse(contentUri)) ?: return emptyList()
        val parent = doc.parentFile ?: return emptyList()
        return parent.listFiles()
            .filter { it.isFile && it.name != null && matchLang(it.name!!, baseName, preference) != null }
            .sortedBy { langOrder(matchLang(it.name!!, baseName, preference)!!, preference) * 10 + subExtensions.indexOf(it.name!!.substringAfterLast('.').lowercase()) }
            .map {
                Candidate(it.name!!) { dest ->
                    runSuspendCatching {
                        context.contentResolver.openInputStream(it.uri)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        } != null
                    }.getOrDefault(false)
                }
            }
    }

    /** 列同目录所有字幕(WebDAV, 不限同名), 按文件名排序。 */
    private suspend fun findAllWebDav(mediaKey: String): List<Candidate> {
        val rest = mediaKey.removePrefix("webdav:")
        val connId = rest.substringBefore(':')
        val filePath = rest.substringAfter(':')
        if (connId.isEmpty() || filePath.isEmpty()) return emptyList()
        val conn = runSuspendCatching { webDavConnRepo.loadAll() }.getOrDefault(emptyList())
            .firstOrNull { it.id == connId } ?: return emptyList()
        val client = WebDavClient(createHttpClient(), conn.baseUrl, conn.username, conn.password)
        val dirPath = filePath.substringBeforeLast('/').ifEmpty { "/" }
        val entries = runSuspendCatching { client.listDirectoryAll(dirPath) }.getOrDefault(emptyList())
        return entries
            .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in subExtensions }
            .sortedBy { it.name }
            .map { entry -> Candidate(entry.name) { dest -> client.downloadTo(entry.path, PlatformFile(dest.path)) } }
    }

    /** 列同目录所有字幕(本地 SAF, 不限同名), 按文件名排序。 */
    private suspend fun findAllLocal(contentUri: String): List<Candidate> {
        val doc = DocumentFile.fromSingleUri(context, Uri.parse(contentUri)) ?: return emptyList()
        val parent = doc.parentFile ?: return emptyList()
        return parent.listFiles()
            .filter { it.isFile && it.name != null && it.name!!.substringAfterLast('.', "").lowercase() in subExtensions }
            .sortedBy { it.name!! }
            .map {
                Candidate(it.name!!) { dest ->
                    runSuspendCatching {
                        context.contentResolver.openInputStream(it.uri)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        } != null
                    }.getOrDefault(false)
                }
            }
    }

    /**
     * 匹配同名字幕并返回语言类别:
     * - "none": 严格同名 baseName.ext(无语言段)
     * - "sc"/"tc": baseName.{lang}.ext, lang 是简中/繁中标记
     * - null: 不匹配(后缀非字幕 / 非同名 / 语言段非中文标记 / none 模式下带语言段)
     *
     * @param preference sc/tc=识别中文语言段; none=只严格同名(不识别语言段, 带段同名也不匹配)
     */
    private fun matchLang(name: String, baseName: String, preference: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in subExtensions) return null
        val stem = name.substringBeforeLast('.').trim()
        if (stem.equals(baseName, ignoreCase = true)) return "none"  // 严格同名, 无语言段
        if (preference == "none") return null  // 不限模式: 不识别语言段
        val langSeg = stem.substringAfterLast('.').lowercase()
        val basePart = stem.substringBeforeLast('.')
        if (!basePart.equals(baseName, ignoreCase = true)) return null  // 非同名
        return when (langSeg) {
            in scLangs -> "sc"
            in tcLangs -> "tc"
            else -> null  // 其他语言(jp/en 等)不参与自动加载, 用户手动选
        }
    }

    /** 语言类别排序权重: 偏好语言 0, 另一中文 1, 无语言段 2。配合 subExtensions 做二级排序。 */
    private fun langOrder(lang: String, preference: String): Int = when (lang) {
        "none" -> 2
        preference -> 0
        else -> 1
    }
}
