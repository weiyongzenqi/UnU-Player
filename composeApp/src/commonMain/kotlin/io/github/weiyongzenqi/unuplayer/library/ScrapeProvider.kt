package io.github.weiyongzenqi.unuplayer.library

/**
 * 在线刮削数据源 provider 抽象: 弹弹(DANDANPLAY)与 Bangumi(BANGUMI)各自实现,
 * 产出统一 [ScrapeCandidate](候选) / [ScrapedScrapeData](详情), 供刮削管线识别与落库。
 *
 * 数据源优先级见 docs/ONLINE_SCRAPING_2026-08-06.md §1: NFO → Bangumi → 弹弹 → TMDB,
 * 各源可降级/合并(Bangumi 补部级元数据, 弹弹补精确季/集结构, TMDB 补身份和图片)。
 */
interface ScrapeProvider {
    /** 数据源身份(落库 scrape_source 用)。 */
    val source: ScrapeSource

    /** 按关键词搜索番剧候选(季级, 一个候选 = 一部季番剧; 含季照/首播/评分)。请求失败向上传递。 */
    suspend fun search(keyword: String): List<ScrapeCandidate>

    /**
     * 拉候选详情(部级元数据 + 季级集标题/放送日 + 季照 URL)。
     * [ScrapeCandidate.identityId] 是源内身份(弹弹 animeId / bgm subjectId)。失败返回 complete=false 的空详情。
     */
    suspend fun fetchDetail(candidate: ScrapeCandidate): ScrapedScrapeData
}
