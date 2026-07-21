package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.runtime.Composable
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.library.PosterWallScanCoordinator
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository

/** 海报墙设置区(commonMain 声明, 平台 actual)。配置项+刮削库管理+数据清理+功能介绍。 */
@Composable
expect fun PosterWallSettingsSlot(
    repository: SettingsRepository,
    scrapedRepo: ScrapedLibraryRepository,
    webDavRepo: WebDavConnectionRepository,
    scanCoordinator: PosterWallScanCoordinator? = null,
)
