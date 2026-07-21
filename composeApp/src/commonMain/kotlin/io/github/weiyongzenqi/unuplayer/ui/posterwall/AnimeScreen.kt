package io.github.weiyongzenqi.unuplayer.ui.posterwall

import androidx.compose.runtime.Composable
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.library.MediaSourceFactory
import io.github.weiyongzenqi.unuplayer.library.PosterWallScanCoordinator
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.local.LocalDirectoryRepository
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository

/**
 * 番剧海报墙 tab(跨平台 expect)。
 *
 * Android 与桌面 actual 均实现海报墙主页；详情页和大部分业务逻辑由 commonMain 共享。
 */
@Composable
expect fun AnimeScreen(
    onPlay: (PlayableMedia) -> Unit,
    scrapedRepo: ScrapedLibraryRepository,
    mediaSourceFactory: MediaSourceFactory,
    scanCoordinator: PosterWallScanCoordinator,
    webDavRepo: WebDavConnectionRepository,
    localDirRepo: LocalDirectoryRepository,
    settingsRepo: SettingsRepository,
    playbackRepo: PlaybackRecordRepository?,
)
