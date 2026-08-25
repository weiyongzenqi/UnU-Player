package io.github.weiyongzenqi.unuplayer.library

/**
 * 海报墙逻辑合并：只合并同一库、同一 TMDB、同一本地季号的单季物理目录。
 *
 * 这允许「第一季 / 第一季第 2 部分」共用一张卡并在详情页显示分段页签，同时保证第二、第三季
 * 各自保留独立卡片。包含多个本地季度的物理目录及无 TMDB 身份目录不参与跨目录合并，避免
 * 只有 TMDB 剧集身份、没有可执行物理路径身份的粗粒度折叠。
 *
 * 详情页的季页签与卡片口径独立：进入任一卡片可翻看同 TMDB 的全部季度(按各季物理目录的
 * owner 跟随执行刮削/评论/播放)，此处合并只决定海报墙外层卡片的形态。
 */
fun mergeLogicalShowCards(shows: List<ListShowsByLibrary>): List<ListShowsByLibrary> {
    if (shows.size < 2) return shows
    val groups = linkedMapOf<String, MutableList<ListShowsByLibrary>>()
    shows.forEach { show ->
        val key = if (show.tmdb_id != null && show.card_season_number != null) {
            "tmdb:${show.library_id}:${show.tmdb_id}:season:${show.card_season_number}"
        } else {
            "show:${show.library_id}:${show.id}"
        }
        groups.getOrPut(key) { mutableListOf() } += show
    }
    return groups.values.map { group -> mergeLogicalShowGroup(group) }
}

private fun mergeLogicalShowGroup(group: List<ListShowsByLibrary>): ListShowsByLibrary {
    if (group.size == 1) return group.single()
    val ordered = group.sortedWith(
        compareBy<ListShowsByLibrary> { it.min_release_date == null }
            .thenBy { it.min_release_date }
            .thenBy { it.id },
    )
    val representative = ordered.first()
    val posterOwner = ordered.firstOrNull { !it.card_poster_path.isNullOrBlank() }
    val fanartOwner = ordered.firstOrNull { !it.card_online_fanart_path.isNullOrBlank() }
    val seasonBadges = group.mapNotNull { it.card_season_number }.distinct()
    return representative.copy(
        is_favorite = group.maxOf { it.is_favorite },
        favorited_at = group.mapNotNull { it.favorited_at }.maxOrNull(),
        favorite_sort_order = group.maxOf { it.favorite_sort_order },
        scanned_at = group.maxOf { it.scanned_at },
        min_release_date = group.mapNotNull { it.min_release_date }.minOrNull(),
        card_poster_path = posterOwner?.card_poster_path,
        card_online_poster_path = posterOwner?.card_online_poster_path,
        card_online_fanart_path = fanartOwner?.card_online_fanart_path,
        // 远程缺图补全必须写回代表目录，不能借用另一物理目录的 URL/季号。
        card_remote_poster_url = representative.card_remote_poster_url,
        card_remote_poster_season = representative.card_remote_poster_season,
        card_poster_path_kind = posterOwner?.card_poster_path_kind ?: representative.card_poster_path_kind,
        card_season_number = seasonBadges.singleOrNull(),
    )
}
