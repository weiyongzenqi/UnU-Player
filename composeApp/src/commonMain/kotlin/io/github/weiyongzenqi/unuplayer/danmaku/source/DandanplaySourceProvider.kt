package io.github.weiyongzenqi.unuplayer.danmaku.source

import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuSource

/**
 * 弹弹play 弹幕源。实现 [DanmakuSourceProvider]。
 *
 * 网络异常 / 解析失败 / 未匹配时静默回退(match 返回 null, fetch 返回空),
 * 由调用方走回退链或纯播放。日志走 AppLogger(后续接入)。
 *
 * @param api 已注入凭证的 [DandanplayApi]。为空凭证时上层不应构造本类。
 */
class DandanplaySourceProvider(
    private val api: DandanplayApi,
) : DanmakuSourceProvider {

    override suspend fun match(fileName: String, fileHash: String, fileSize: Long): DanmakuMatchResult? =
        runSuspendCatching {
            val resp = api.match(fileName, fileHash, fileSize)
            if (!resp.isMatched) return@runSuspendCatching null
            resp.matches.firstOrNull()?.let {
                DanmakuMatchResult(
                    episodeId = it.episodeId,
                    animeId = it.animeId,
                    animeTitle = it.animeTitle,
                    episodeTitle = it.episodeTitle,
                    shift = it.shift,
                    matchMethod = DanmakuMatchMethod.HASH,
                )
            }
        }.getOrNull()

    override suspend fun fetch(episodeId: Long): List<DanmakuEntry> =
        runSuspendCatching {
            val resp = api.comment(episodeId, withRelated = true)
            resp.comments.mapNotNull { parseComment(it) }.sortedBy { it.timeSec }
        }.getOrDefault(emptyList())

    /**
     * 弹弹play p 字段 "time,mode,color,uid"(4 段) -> [DanmakuEntry]。
     * 弹弹play 无字号字段, fontSize=null(用播放器默认)。
     */
    private fun parseComment(c: DandanplayComment): DanmakuEntry? {
        val p = c.p.split(",")
        if (p.size < 3) return null
        val time = p[0].toDoubleOrNull() ?: return null
        val mode = DanmakuMode.fromCode(p[1].toIntOrNull() ?: 1)
        val color = p[2].toIntOrNull() ?: 16777215   // 默认白
        return DanmakuEntry(
            timeSec = time,
            mode = mode,
            color = color,
            text = c.m,
            source = DanmakuSource.DANDANPLAY,
            fontSize = null,
        )
    }
}
